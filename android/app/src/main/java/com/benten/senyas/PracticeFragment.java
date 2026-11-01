package com.benten.senyas;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.common.util.concurrent.ListenableFuture;
import com.senyas.cv.FeatureEncoder;
import com.senyas.cv.HandFrame;
import com.senyas.cv.HandOverlayView;
import com.senyas.cv.HandTracker;
import com.senyas.cv.SampleStore;
import com.senyas.cv.SignClassifier;
import com.senyas.cv.SignRecognizer;
import com.senyas.cv.SignReferences;
import com.senyas.cv.SignSample;
import com.senyas.cv.TemplateSignClassifier;
import com.senyas.cv.TfliteSignClassifier;
import com.senyas.ml.Feedback;
import com.senyas.ml.ReferenceClip;
import com.senyas.ml.SignScorer;
import com.senyas.ml.Tip;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI Practice Room. The front camera feeds MediaPipe hand tracking; the learner's signing is
 * scored against the sign's built-in references (both hands, tracked from the FSL-105
 * demonstration clips the lesson shows), and the guide pill shows the most useful correction:
 * hand shape, palm direction, movement or the second hand. Signs without built-in references fall
 * back to Sign Lab recordings, then to a trained model in assets. Learners never record anything.
 * Long-press the title to preview the "camera unavailable" screen.
 *
 * Only the hands of one signer are scored (see HandGate): with no face in view, or more than one
 * person or two hands, the pills say so and the score waits.
 *
 * Threads: CameraX analyses frames on {@code cameraExecutor}; MediaPipe calls back on its own
 * thread; recognition and scoring run on {@code cvExecutor} (the newest frame only, when it falls
 * behind); views update on main.
 */
public class PracticeFragment extends Fragment implements DarkScreen, HandTracker.Listener {

    /** The room's pass mark: the white marker on the score bar and the copy below it. */
    private static final int PASS = SignScorer.PASS;
    private static final String STATE_ASKED = "askedForCamera", STATE_ATTEMPT = "attempt";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor, cvExecutor;
    private Context appContext;
    private SampleStore store;

    private Scenario sc;
    private int idx, shownKey = -1, attemptId, shownBest = -1;
    private boolean matched, askedForCamera, paused;
    /** The attempt on screen when the phone turned: its sign, best score and match, shown again. */
    @Nullable private Bundle resume;

    private TextView tvProgress, tvScenario, tvWord, tvTranslation, tvGuide, tvHand, tvScoreTitle, tvPct, tvScoreNote;
    private LinearProgressIndicator progress;
    private PreviewView preview;
    private HandOverlayView overlay;
    private ScoreBarView scoreBar;
    private MaterialButton btnSkip;
    private HandTracker tracker; // one per view, created on main
    private AtomicBoolean cameraStopped; // set when the view goes, so late frames skip the closed tracker
    private boolean mirrored = true; // front camera: the preview (and the scorer's left/right) is a mirror
    private ImageAnalysis analysis; // main thread
    /** The part of the camera image inside the on-screen guide (see HandOverlayView#guideInImage). */
    private volatile float[] visibleGuide;
    /** The newest frame waiting for the cv thread; older ones are skipped when scoring falls behind. */
    private final AtomicReference<Runnable> queued = new AtomicReference<>();

    /**
     * Turning the phone straight from one landscape side to the other does not recreate the
     * screen, so camera frames would arrive upside down (no face found, hands flipped).
     */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display d = preview == null ? null : preview.getDisplay();
            if (d != null && d.getDisplayId() == displayId && analysis != null) analysis.setTargetRotation(d.getRotation());
        }
    };

    // Touched only on cvExecutor.
    private boolean modelChecked;
    private SignClassifier trained;
    private SignRecognizer recognizer;
    private SignScorer scorer = new SignScorer(null);
    private String target;
    private float targetProbability = -1;
    private int attemptBest = -1, cvAttemptId;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (getView() == null) return;
                if (granted) {
                    startCamera();
                } else {
                    showCameraOff();
                    ((MainActivity) requireActivity()).open(new CameraUnavailableFragment());
                }
            });

    @Override
    public void onCreate(@Nullable Bundle b) {
        super.onCreate(b);
        cameraExecutor = Executors.newSingleThreadExecutor();
        cvExecutor = Executors.newSingleThreadExecutor();
        appContext = requireContext().getApplicationContext();
        store = new SampleStore(appContext);
        if (b != null) {
            askedForCamera = b.getBoolean(STATE_ASKED);
            resume = b.getBundle(STATE_ATTEMPT);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_ASKED, askedForCamera);
        if (getView() == null) {
            out.putBundle(STATE_ATTEMPT, resume); // in the back stack: keep what is waiting to be shown
            return;
        }
        Bundle attempt = new Bundle();
        attempt.putInt("sign", shownKey);
        attempt.putInt("best", shownBest);
        attempt.putBoolean("matched", matched);
        out.putBundle(STATE_ATTEMPT, attempt);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_practice, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        tvProgress = v.findViewById(R.id.tv_progress);
        tvScenario = v.findViewById(R.id.tv_scenario);
        tvWord = v.findViewById(R.id.tv_word);
        tvTranslation = v.findViewById(R.id.tv_translation);
        tvGuide = v.findViewById(R.id.tv_guide);
        tvHand = v.findViewById(R.id.tv_hand);
        tvScoreTitle = v.findViewById(R.id.tv_score_title);
        tvPct = v.findViewById(R.id.tv_pct);
        tvScoreNote = v.findViewById(R.id.tv_score_note);
        progress = v.findViewById(R.id.progress);
        preview = v.findViewById(R.id.preview);
        overlay = v.findViewById(R.id.hand);
        scoreBar = v.findViewById(R.id.score_bar);
        btnSkip = v.findViewById(R.id.btn_skip);

        // Keep the dashed guide where the placeholder drew it: below the pills, above the guide pill.
        overlay.setGuideInsets(Ui.dp(requireContext(), 56), Ui.dp(requireContext(), 64));
        displays().registerDisplayListener(displayListener, main);

        v.findViewById(R.id.btn_back).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_learn));
        v.findViewById(R.id.tv_room_title).setOnLongClickListener(x -> {
            ((MainActivity) requireActivity()).open(new CameraUnavailableFragment());
            return true;
        });
        // The signed demonstration of the sign on screen; Back returns here.
        v.findViewById(R.id.btn_watch).setOnClickListener(x ->
                ((MainActivity) requireActivity()).open(SignLessonFragment.newInstance(sc.id, idx)));
        v.findViewById(R.id.btn_try).setOnClickListener(x -> {
            if (!hasCamera()) cameraPermission.launch(Manifest.permission.CAMERA);
            else startAttempt();
        });
        btnSkip.setOnClickListener(x -> {
            if (!matched && Repo.practiceBest[idx] < 0) Repo.practiceBest[idx] = -2;
            advance();
        });

        bindSign();
        if (resume != null && resume.getInt("sign") == shownKey) {
            resumeAttempt(resume.getInt("best"), resume.getBoolean("matched"));
        }
        resume = null;
        if (hasCamera()) {
            startCamera();
        } else if (!askedForCamera) {
            askedForCamera = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            showCameraOff();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sc == null) return;
        // Returning from the summary after "Practice again", or from a lesson, may change the sign.
        if (shownKey != Repo.practiceScenario * 100 + Repo.practiceIndex) bindSign();
        else if (paused) loadReferences(SignSample.normalizeLabel(sc.signs[idx][0])); // Sign Lab may have new recordings
        paused = false;
        if (tracker == null && hasCamera()) startCamera();
    }

    @Override
    public void onPause() {
        paused = true;
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        displays().unregisterDisplayListener(displayListener);
        HandTracker t = tracker;
        tracker = null;
        overlay = null;
        analysis = null;
        if (t != null) {
            cameraStopped.set(true);
            // Frames are analysed on cameraExecutor, so closing there waits for one in flight.
            try {
                cameraExecutor.execute(t::close);
            } catch (RejectedExecutionException ignored) {
                t.close();
            }
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        runOnCv(() -> {
            if (trained != null) trained.close();
        });
        cameraExecutor.shutdown();
        cvExecutor.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ sign + attempt (main)

    private void bindSign() {
        sc = Repo.SCENARIOS.get(Repo.practiceScenario);
        idx = Repo.practiceIndex;
        shownKey = Repo.practiceScenario * 100 + idx;
        String[] sign = sc.signs[idx];
        int total = sc.signs.length;
        tvScenario.setText(sc.label(requireContext()));
        tvWord.setText(sign[0]);
        tvTranslation.setText(sign[1]);
        tvProgress.setText(getString(R.string.sign_x_of_y, idx + 1, total));
        progress.setProgress((idx + 1) * 100 / total);
        loadReferences(SignSample.normalizeLabel(sign[0]));
        startAttempt();
    }

    private void startAttempt() {
        int id = ++attemptId;
        matched = false;
        shownBest = -1;
        btnSkip.setText(R.string.skip_sign);
        tvGuide.setText(R.string.score_waiting);
        showScore(-1, true, null);
        runOnCv(() -> {
            cvAttemptId = id;
            attemptBest = -1;
            targetProbability = -1;
            scorer.reset();
            if (recognizer != null) recognizer.reset();
        });
    }

    /** Shows the attempt again after the phone turned; the camera restarts, the best score stays. */
    private void resumeAttempt(int best, boolean wasMatched) {
        if (wasMatched) {
            matched = true;
            btnSkip.setText(R.string.next_sign);
        }
        if (best < 0) return;
        shownBest = best;
        showScore(best, true, null);
        int id = attemptId;
        runOnCv(() -> {
            if (cvAttemptId == id && best > attemptBest) attemptBest = best;
        });
    }

    private void advance() {
        int total = sc.signs.length;
        if (idx + 1 >= total) {
            ((MainActivity) requireActivity()).open(new PracticeSummaryFragment());
            return;
        }
        Repo.practiceIndex = idx + 1;
        bindSign();
    }

    // ------------------------------------------------------------------ camera (main)

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (tracker != null) return;
        try {
            tracker = new HandTracker(requireContext(), this);
        } catch (RuntimeException e) {
            tvGuide.setText(getString(R.string.tracking_failed, String.valueOf(e.getMessage())));
            return;
        }
        Context context = requireContext();
        LifecycleOwner owner = getViewLifecycleOwner();
        HandTracker analyzer = tracker;
        AtomicBoolean stopped = new AtomicBoolean(false);
        cameraStopped = stopped;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (getView() == null || tracker != analyzer) return;
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA;
                overlay.setMirror(mirrored);
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                Preview p = new Preview.Builder().setResolutionSelector(resolution).build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis.Builder frames = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888);
                Display display = preview.getDisplay();
                if (display != null) frames.setTargetRotation(display.getRotation());
                analysis = frames.build();
                analysis.setAnalyzer(cameraExecutor, image -> {
                    if (stopped.get()) image.close();
                    else analyzer.analyze(image);
                });
                provider.unbindAll();
                provider.bindToLifecycle(owner, selector, p, analysis);
                tvGuide.setText(R.string.detect_show_face);
            } catch (Exception e) {
                tvGuide.setText(getString(R.string.camera_failed, String.valueOf(e.getMessage())));
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void showCameraOff() {
        setHandPill(false, R.string.camera_off);
        tvGuide.setText(R.string.camera_access_is_off);
        scoreBar.setScore(0);
        tvPct.setText(R.string.no_score);
        tvScoreTitle.setText(R.string.camera_access_is_off);
        tvScoreNote.setText(R.string.enable_camera_access_in_your);
    }

    @Override
    public void onHands(HandFrame frame, int imageWidth, int imageHeight) {
        main.post(() -> {
            if (overlay == null) return;
            overlay.setHands(frame, imageWidth, imageHeight);
            visibleGuide = overlay.guideInImage();
        });
        float aspect = imageHeight / (float) Math.max(1, imageWidth);
        // One job in the cv queue at a time: a slow phone scores the newest frame instead of
        // falling further and further behind the camera.
        if (queued.getAndSet(() -> process(frame, aspect)) == null) {
            runOnCv(() -> {
                Runnable next = queued.getAndSet(null);
                if (next != null) next.run();
            });
        }
    }

    @Override
    public void onTrackerError(String message) {
        main.post(() -> {
            if (getView() != null) tvGuide.setText(getString(R.string.tracking_failed, message));
        });
    }

    private DisplayManager displays() {
        return (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
    }

    private void runOnCv(Runnable r) {
        try {
            cvExecutor.execute(r);
        } catch (RejectedExecutionException ignored) {
            // Closing down.
        }
    }

    // ------------------------------------------------------------------ scoring (cv thread)

    /**
     * The sign's built-in references (FSL-105), else Sign Lab recordings of it, plus a trained
     * model if assets have one.
     */
    private void loadReferences(String label) {
        runOnCv(() -> {
            if (!modelChecked) {
                modelChecked = true;
                try {
                    trained = TfliteSignClassifier.loadFromAssets(appContext);
                } catch (Exception e) {
                    trained = null; // a model that does not fit this app version; references still work
                }
            }
            TemplateSignClassifier mine = new TemplateSignClassifier();
            List<ReferenceClip> recordings = new ArrayList<>();
            for (SignSample s : store.loadAll()) {
                mine.add(s.label, FeatureEncoder.encodeSequence(s.frames, FeatureEncoder.SEQUENCE_LENGTH));
                if (s.label.equals(label)) recordings.add(s.toReference());
            }
            List<ReferenceClip> builtIn = SignReferences.load(appContext, label);
            if (!label.equals(target)) attemptBest = -1; // same sign reloaded: keep this attempt's best
            target = label;
            recognizer = new SignRecognizer(trained != null ? trained : mine);
            scorer = new SignScorer(builtIn.isEmpty() ? recordings : builtIn);
            targetProbability = -1;
        });
    }

    private void process(HandFrame frame, float aspect) {
        if (recognizer == null) return;
        int id = cvAttemptId;
        HandFrame.Status status = frame.status;
        if (frame.crowded()) {
            // Not one signer's hands: the score waits until only the learner is in view.
            main.post(() -> {
                if (overlay != null && id == attemptId) showCrowded(status);
            });
            return;
        }
        // References are matched as whole movements; a trained model is the fallback.
        if (!scorer.hasReference()) {
            SignRecognizer.State state = recognizer.onFrame(frame);
            if (state != null) {
                targetProbability = recognizer.classifier().labels().contains(target)
                        ? state.probabilityOf(target) : -1;
            }
        }
        scorer.setGuide(visibleGuide);
        Feedback fb = scorer.onFrame(frame.timestampMs, frame.landmarksByConfidence(), aspect);
        // The whole sign is matched a moment after it ends, often once the hands are already out
        // of view, so the attempt keeps the scorer's best rather than the latest frame's score.
        int score = scorer.hasReference() ? scorer.bestScore()
                : fb.handVisible && targetProbability >= 0 ? Math.round(targetProbability * 100) : -1;
        if (score > attemptBest) attemptBest = score;
        boolean reference = targetProbability >= 0 || scorer.hasReference();
        int best = attemptBest, faces = frame.faces;
        main.post(() -> {
            if (overlay != null && id == attemptId) show(fb, best, reference, status, faces);
        });
    }

    // ------------------------------------------------------------------ views (main)

    private void show(Feedback fb, int best, boolean hasReference, HandFrame.Status status, int faces) {
        overlay.setFeedback(fb);
        if (best >= PASS && !matched) {
            matched = true;
            btnSkip.setText(R.string.next_sign);
        }
        if (best >= 0 && best > Repo.practiceBest[idx]) Repo.practiceBest[idx] = best;
        shownBest = best;
        showScore(best, hasReference, fb);
        if (showRefused(status)) return;
        setHandPill(fb.handVisible, fb.handVisible ? R.string.hand_detected : R.string.hand_not_detected);
        tvGuide.setText(matched ? getString(R.string.guide_great) : guideText(fb, faces));
    }

    /** More than one signer or two hands in view: say so, and leave the score as it was. */
    private void showCrowded(HandFrame.Status status) {
        overlay.setFeedback(null);
        showRefused(status);
    }

    /** Pills for hands the signer check refused; false when the frame's hands are used. */
    private boolean showRefused(HandFrame.Status status) {
        int pill, guide;
        switch (status) {
            case NO_FACE:
                pill = R.string.detect_pill_no_face;
                guide = R.string.detect_show_face;
                break;
            case TOO_MANY_HANDS:
                pill = R.string.detect_pill_too_many_hands;
                guide = R.string.detect_too_many_hands;
                break;
            case TOO_MANY_PEOPLE:
                pill = R.string.detect_pill_too_many_people;
                guide = R.string.detect_too_many_people;
                break;
            default:
                return false;
        }
        Context c = requireContext();
        tvHand.setText(pill);
        tvHand.setBackgroundTintList(ColorStateList.valueOf(c.getColor(R.color.warning_container)));
        tvHand.setTextColor(c.getColor(R.color.warning_text));
        tvGuide.setText(guide);
        return true;
    }

    private void showScore(int best, boolean hasReference, @Nullable Feedback fb) {
        scoreBar.setScore(Math.max(best, 0));
        tvPct.setText(best >= 0 ? getString(R.string.match_pct, best) : getString(R.string.no_score));
        if (best >= 0) {
            tvScoreTitle.setText(best >= PASS ? R.string.score_matched : best >= 60 ? R.string.score_almost : R.string.score_keep);
            String right = best >= PASS || fb == null ? null : partsRight(fb);
            tvScoreNote.setText(right != null ? getString(R.string.score_note_right, right)
                    : getString(R.string.reach_85_to_mark_this));
        } else if (!hasReference) {
            tvScoreTitle.setText(R.string.score_no_reference);
            tvScoreNote.setText(R.string.score_no_reference_note);
        } else {
            tvScoreTitle.setText(R.string.score_waiting);
            tvScoreNote.setText(R.string.reach_85_to_mark_this);
        }
    }

    private void setHandPill(boolean found, int text) {
        Context c = requireContext();
        tvHand.setText(text);
        tvHand.setBackgroundTintList(ColorStateList.valueOf(c.getColor(found ? R.color.success_container : R.color.dark_surface)));
        tvHand.setTextColor(c.getColor(found ? R.color.success_text : R.color.text_on_dark));
    }

    /** The scorer's most useful correction, in the app's language. */
    private String guideText(Feedback fb, int faces) {
        if (!fb.handVisible) return getString(faces == 0 ? R.string.detect_show_face : R.string.guide_show_hand);
        for (Tip t : fb.tips) {
            if (t.kind != Tip.Kind.PARTS_RIGHT) return tipText(t);
        }
        return getString(R.string.score_waiting);
    }

    /** "hand shape and movement" when the scorer says those parts are already right. */
    @Nullable
    private String partsRight(Feedback fb) {
        for (Tip t : fb.tips) {
            if (t.kind != Tip.Kind.PARTS_RIGHT || t.parts.isEmpty()) continue;
            String first = partText(t.parts.get(0));
            return t.parts.size() == 1 ? first : getString(R.string.parts_and, first, partText(t.parts.get(1)));
        }
        return null;
    }

    private String tipText(Tip t) {
        switch (t.kind) {
            case GOOD: return getString(R.string.tip_good);
            case KEEP_IN_VIEW: return getString(R.string.tip_keep_in_view);
            case EDGE:
                switch (onScreen(t.dir)) {
                    case UP: return getString(R.string.guide_up);
                    case DOWN: return getString(R.string.guide_lower);
                    case LEFT: return getString(R.string.guide_left);
                    default: return getString(R.string.guide_right);
                }
            case STRAIGHTEN_FINGER:
            case BEND_FINGER: {
                String finger = getResources().getStringArray(R.array.finger_names)[t.finger];
                return getString(t.kind == Tip.Kind.STRAIGHTEN_FINGER ? R.string.tip_straighten : R.string.tip_bend,
                        whenText(t.when), finger);
            }
            case PALM_FACE: return getString(R.string.tip_palm, whenText(t.when), dirText(t.dir));
            case POINT_FINGERS: return getString(R.string.tip_point, whenText(t.when), dirText(t.dir));
            case MOVE: return getString(R.string.tip_move, whenText(t.when), dirText(t.dir));
            case MOVE_TOWARD: return getString(R.string.tip_move_toward, whenText(t.when), dirText(t.dir));
            case HOLD_STILL: return getString(R.string.tip_hold_still, whenText(t.when));
            case BIGGER: return getString(R.string.tip_bigger);
            case SMALLER: return getString(R.string.tip_smaller);
            case BOTH_HANDS: return getString(R.string.tip_both_hands);
            case SECOND_HAND_MOVE: return getString(R.string.tip_second_hand_move, dirText(t.dir));
            case SECOND_HAND_COPY: return getString(R.string.tip_second_hand_copy);
            case ONE_HAND: return getString(R.string.tip_one_hand);
            default: return getString(R.string.tip_almost);
        }
    }

    private String whenText(Tip.When w) {
        switch (w) {
            case START: return getString(R.string.when_start);
            case MIDDLE: return getString(R.string.when_middle);
            default: return getString(R.string.when_end);
        }
    }

    private String dirText(Tip.Dir d) {
        switch (onScreen(d)) {
            case LEFT: return getString(R.string.dir_left);
            case RIGHT: return getString(R.string.dir_right);
            case UP: return getString(R.string.dir_up);
            case DOWN: return getString(R.string.dir_down);
            case TOWARD_CAMERA: return getString(R.string.dir_toward_camera);
            default: return getString(R.string.dir_away);
        }
    }

    /** The scorer speaks as in a mirror; the back camera's preview is not one. */
    private Tip.Dir onScreen(Tip.Dir d) {
        if (mirrored || d == null) return d;
        return d == Tip.Dir.LEFT ? Tip.Dir.RIGHT : d == Tip.Dir.RIGHT ? Tip.Dir.LEFT : d;
    }

    private String partText(Tip.Part p) {
        switch (p) {
            case HANDSHAPE: return getString(R.string.part_handshape);
            case ORIENTATION: return getString(R.string.part_orientation);
            case MOVEMENT: return getString(R.string.part_movement);
            default: return getString(R.string.part_second_hand);
        }
    }
}
