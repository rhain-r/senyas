package com.benten.senyas;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Size;
import android.view.Display;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.common.util.concurrent.ListenableFuture;
import com.senyas.cv.FeatureEncoder;
import com.senyas.cv.HandFrame;
import com.senyas.cv.HandOverlayView;
import com.senyas.cv.HandTracker;
import com.senyas.cv.Prediction;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sign Lab, ported from the Sign Lab project (../app) without its Firebase score saving.
 *
 *  Record     save reference takes of a sign; the Practice Room scores that sign against them
 *  Recognize  camera -> MediaPipe hand landmarks -> classifier -> "which sign is this?"
 *  Practice   pick any recorded sign; score (0-100) plus green/red fingers and a correction arrow
 *
 * Only the hands of one signer with their face in view are recorded, recognized or scored (see
 * HandGate); otherwise the open panel says why.
 *
 * Threads: CameraX analyses frames on {@code cameraExecutor}; MediaPipe calls back on its own
 * thread; recognition, scoring and recording run on {@code cvExecutor}; views update on main.
 */
public class SignLabActivity extends AppCompatActivity implements HandTracker.Listener {
    private enum Mode { RECOGNIZE, PRACTICE, RECORD }

    private static final String STATE_MODE = "mode", STATE_TARGET = "target";

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cvExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private PreviewView preview;
    private HandOverlayView overlay;
    private TextView modelName, status, sign, top3, practiceScore, practiceVerdict, guidance, recordCounts, countdown;
    private LinearProgressIndicator confidence, practiceBar;
    private MaterialButton modeRecognize, modePractice, modeRecord, recordButton;
    private MaterialAutoCompleteTextView target, recordLabel;
    private View recognizePanel, practicePanel, recordPanel, permissionPanel;

    private HandTracker tracker;
    private SampleStore store;

    // Touched only on cvExecutor.
    private SignRecognizer recognizer;
    private SignClassifier trained;
    private final TemplateSignClassifier mine = new TemplateSignClassifier();
    private SignScorer scorer = new SignScorer(null);
    private final SignSample.Recorder recorder = new SignSample.Recorder();
    private boolean recording;
    private String recordingLabel;
    private String practiceTarget;
    private float targetProbability = -1;
    /** Last frame time with a hand, and the previous attempt's best (shown until the next one scores). */
    private long lastHandMs = Long.MIN_VALUE;
    private int previousBest = -1;
    /** Whether the chosen practice sign can be scored at all (references, recordings or the model). */
    private volatile boolean practiceScorable;
    /** What the practice score is compared with, shown until there is advice. */
    private volatile String practiceNote;

    private volatile Mode mode = Mode.RECOGNIZE;
    /** The sign picked in Practice mode (main thread), kept when the screen turns. */
    private String chosenTarget;
    private ImageAnalysis analysis; // main thread

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
            Display d = preview.getDisplay();
            if (d != null && d.getDisplayId() == displayId && analysis != null) analysis.setTargetRotation(d.getRotation());
        }
    };

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else permissionPanel.setVisibility(View.VISIBLE);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this, SystemBarStyle.dark(0), SystemBarStyle.dark(0));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_lab);
        store = new SampleStore(this);
        bindViews();
        applyInsets();
        // Turning the phone recreates the screen: keep the open mode and the sign being practised.
        String saved = savedInstanceState == null ? null : savedInstanceState.getString(STATE_MODE);
        setMode(saved != null ? Mode.valueOf(saved) : Mode.RECORD);
        chosenTarget = savedInstanceState == null ? null : savedInstanceState.getString(STATE_TARGET);

        getSystemService(DisplayManager.class).registerDisplayListener(displayListener, null);
        cvExecutor.execute(this::loadClassifiers);
        if (chosenTarget != null) {
            String label = chosenTarget;
            cvExecutor.execute(() -> choosePracticeTarget(label));
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (savedInstanceState != null) {
            // Already asked before the screen turned; an open request still answers the callback.
            permissionPanel.setVisibility(View.VISIBLE);
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the phone's settings with access turned on.
        if (tracker == null && permissionPanel.getVisibility() == View.VISIBLE
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_MODE, mode.name());
        out.putString(STATE_TARGET, chosenTarget);
    }

    private void bindViews() {
        preview = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        modelName = findViewById(R.id.model_name);
        status = findViewById(R.id.status);
        sign = findViewById(R.id.sign);
        top3 = findViewById(R.id.top3);
        confidence = findViewById(R.id.confidence);
        practiceScore = findViewById(R.id.practice_score);
        practiceVerdict = findViewById(R.id.practice_verdict);
        practiceBar = findViewById(R.id.practice_bar);
        guidance = findViewById(R.id.guidance);
        recordCounts = findViewById(R.id.record_counts);
        countdown = findViewById(R.id.countdown);
        modeRecognize = findViewById(R.id.mode_recognize);
        modePractice = findViewById(R.id.mode_practice);
        modeRecord = findViewById(R.id.mode_record);
        recordButton = findViewById(R.id.record);
        target = findViewById(R.id.target);
        recordLabel = findViewById(R.id.record_label);
        recognizePanel = findViewById(R.id.recognize_panel);
        practicePanel = findViewById(R.id.practice_panel);
        recordPanel = findViewById(R.id.record_panel);
        permissionPanel = findViewById(R.id.permission_panel);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        modeRecognize.setOnClickListener(v -> setMode(Mode.RECOGNIZE));
        modePractice.setOnClickListener(v -> setMode(Mode.PRACTICE));
        modeRecord.setOnClickListener(v -> setMode(Mode.RECORD));
        modelName.setOnClickListener(v -> cvExecutor.execute(this::switchClassifier));
        target.setOnItemClickListener((parent, view, position, id) -> {
            String label = (String) parent.getItemAtPosition(position);
            chosenTarget = label;
            cvExecutor.execute(() -> choosePracticeTarget(label));
        });
        recordButton.setOnClickListener(v -> startRecordingCountdown());
        findViewById(R.id.undo).setOnClickListener(v -> deleteLastRecording());
        findViewById(R.id.export).setOnClickListener(v -> exportRecordings());
        findViewById(R.id.allow_camera).setOnClickListener(v -> cameraPermission.launch(Manifest.permission.CAMERA));
        findViewById(R.id.open_settings).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))));
    }

    private void applyInsets() {
        View top = findViewById(R.id.top);
        View panel = findViewById(R.id.panel);
        // Portrait: the panel covers the bottom of the camera. Landscape: it is a column beside it.
        boolean side = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int topStart = top.getPaddingLeft(), topPad = top.getPaddingTop();
        int panelTop = panel.getPaddingTop(), panelEnd = panel.getPaddingRight(), panelPad = panel.getPaddingBottom();
        int[] bottomBar = {0};
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            top.setPadding(topStart + bars.left, topPad + bars.top, top.getPaddingRight(), top.getPaddingBottom());
            panel.setPadding(panel.getPaddingLeft(), panelTop + (side ? bars.top : 0), panelEnd + bars.right, panelPad + bars.bottom);
            bottomBar[0] = bars.bottom;
            return insets;
        });
        float gap = 12 * getResources().getDisplayMetrics().density;
        View.OnLayoutChangeListener guide = (v, l, t, r, b, ol, ot, or, ob) ->
                overlay.setGuideInsets(top.getBottom(), (side ? bottomBar[0] : overlay.getHeight() - panel.getTop()) + gap);
        top.addOnLayoutChangeListener(guide);
        panel.addOnLayoutChangeListener(guide);
    }

    // ------------------------------------------------------------------ camera

    private void startCamera() {
        permissionPanel.setVisibility(View.GONE);
        try {
            tracker = new HandTracker(this, this);
            tracker.setKeepFrames(false);
        } catch (RuntimeException e) {
            status.setText("Hand tracking could not start: " + e.getMessage());
            return;
        }
        HandTracker analyzer = tracker;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                overlay.setMirror(selector == CameraSelector.DEFAULT_FRONT_CAMERA);
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
                provider.bindToLifecycle(this, selector, p, analysis);
            } catch (Exception e) {
                status.setText("Camera could not start: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onHands(HandFrame frame, int imageWidth, int imageHeight) {
        if (cvExecutor.isShutdown()) return; // a last frame can arrive after the screen closes
        runOnUiThread(() -> overlay.setHands(frame, imageWidth, imageHeight));
        float aspect = imageHeight / (float) Math.max(1, imageWidth);
        try {
            cvExecutor.execute(() -> process(frame, aspect));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing down.
        }
    }

    @Override
    public void onTrackerError(String message) {
        runOnUiThread(() -> status.setText("Hand tracking error: " + message));
    }

    // ------------------------------------------------------------------ recognition (cv thread)

    private void loadClassifiers() {
        String error = null;
        try {
            trained = TfliteSignClassifier.loadFromAssets(this);
        } catch (Exception e) {
            error = e.getMessage();
        }
        for (SignSample s : store.loadAll()) {
            mine.add(s.label, FeatureEncoder.encodeSequence(s.frames, FeatureEncoder.SEQUENCE_LENGTH));
        }
        recognizer = new SignRecognizer(trained != null ? trained : mine);
        String loadError = error;
        runOnUiThread(() -> {
            if (loadError != null) Toast.makeText(this, loadError, Toast.LENGTH_LONG).show();
            refreshModelViews();
        });
    }

    private void switchClassifier() {
        if (recognizer == null || trained == null) {
            runOnUiThread(() -> Toast.makeText(this,
                    "No trained model in assets yet. Using your recordings. See ml/README.md to train one.",
                    Toast.LENGTH_LONG).show());
            return;
        }
        recognizer.setClassifier(recognizer.classifier() == trained ? mine : trained);
        runOnUiThread(this::refreshModelViews);
    }

    private void process(HandFrame frame, float aspect) {
        if (recognizer == null) return;
        if (recording && recorder.add(frame)) finishRecording();
        HandFrame.Status refused = frame.status;
        if (frame.crowded()) {
            // Not one signer's hands: recognition and scoring wait.
            runOnUiThread(() -> showRefused(refused));
            return;
        }

        SignRecognizer.State state = recognizer.onFrame(frame);
        if (state != null && practiceTarget != null) {
            targetProbability = recognizer.classifier().labels().contains(practiceTarget)
                    ? state.probabilityOf(practiceTarget) : -1;
        }
        Feedback feedback = null;
        if (mode == Mode.PRACTICE) {
            // A new attempt starts once the hands have been out of view for 1.5 s: the whole-sign
            // match that ends where the hands leave needs up to about 0.8 s of later frames.
            if (frame.hasHands()) {
                lastHandMs = frame.timestampMs;
            } else if (lastHandMs != Long.MIN_VALUE && frame.timestampMs - lastHandMs >= 1500) {
                if (scorer.bestScore() >= 0) previousBest = scorer.bestScore();
                scorer.reset();
                lastHandMs = Long.MIN_VALUE;
            }
            feedback = scorer.onFrame(frame.timestampMs, frame.landmarksByConfidence(), aspect);
            // References are matched as whole movements (best of this attempt); a trained model is the fallback.
            int score = scorer.hasReference() ? (scorer.bestScore() >= 0 ? scorer.bestScore() : previousBest)
                    : feedback.handVisible && targetProbability >= 0 ? Math.round(targetProbability * 100) : -1;
            int shown = score;
            Feedback fb = feedback;
            runOnUiThread(() -> {
                showPractice(fb, shown);
                showRefused(refused);
            });
        }
        if (mode == Mode.RECOGNIZE) {
            if (refused == HandFrame.Status.NO_FACE) runOnUiThread(() -> showRefused(refused));
            else if (state != null) runOnUiThread(() -> showRecognition(state));
        }
        Feedback fb = feedback;
        if (fb != null) runOnUiThread(() -> overlay.setFeedback(fb));
    }

    private void choosePracticeTarget(String label) {
        practiceTarget = label;
        targetProbability = -1;
        previousBest = -1;
        // The sign's built-in references (FSL-105 clips) come first; recordings stand in for signs without them.
        List<ReferenceClip> refs = SignReferences.load(this, label);
        boolean builtIn = !refs.isEmpty();
        if (!builtIn) {
            refs = new ArrayList<>();
            for (SignSample s : store.loadAll()) if (s.label.equals(label)) refs.add(s.toReference());
        }
        int count = refs.size();
        scorer = new SignScorer(refs);
        boolean modelKnows = recognizer != null && recognizer.classifier().labels().contains(label);
        practiceScorable = count > 0 || modelKnows;
        String note = builtIn ? "Score compares you with " + count + " FSL-105 signer clip(s) built into the app."
                : count > 0 ? "Score compares you with " + count + " recording(s)."
                : modelKnows ? "Score comes from the classifier."
                : "No built-in reference or recording of this sign yet. Record it in Record mode.";
        practiceNote = note;
        runOnUiThread(() -> {
            guidance.setText(note);
            practiceScore.setText("—");
        });
    }

    // ------------------------------------------------------------------ recording

    private void startRecordingCountdown() {
        String label = SignSample.normalizeLabel(String.valueOf(recordLabel.getText()));
        if (label.isEmpty()) {
            recordLabel.setError("Type the sign's name first, e.g. salamat");
            return;
        }
        recordButton.setEnabled(false);
        countdown.setVisibility(View.VISIBLE);
        for (int i = 3; i >= 1; i--) {
            int n = i;
            countdown.postDelayed(() -> countdown.setText(String.valueOf(n)), (3 - i) * 800L);
        }
        countdown.postDelayed(() -> {
            countdown.setText("Sign now");
            runOnCv(() -> {
                recordingLabel = label;
                recorder.start();
                recording = true;
            });
        }, 3 * 800L);
    }

    private void finishRecording() {
        recording = false;
        List<HandFrame> frames = recorder.frames();
        String label = recordingLabel;
        String message;
        int crowded = 0, noFace = 0;
        for (HandFrame f : frames) {
            if (f.crowded()) crowded++;
            else if (f.status == HandFrame.Status.NO_FACE) noFace++;
        }
        if (crowded * 10 > frames.size()) {
            // A reference is one signer's: the take had others in view, so its hands have holes.
            message = getString(R.string.detect_record_crowded);
        } else if (FeatureEncoder.handPresence(frames) < 0.5f) {
            message = noFace * 4 > frames.size() ? getString(R.string.detect_record_no_face)
                    : "Hands were not in view for most of the recording. Try again.";
        } else {
            try {
                store.save(new SignSample(label, "senyas-app:" + Build.MODEL, frames));
                mine.add(label, FeatureEncoder.encodeSequence(frames, FeatureEncoder.SEQUENCE_LENGTH));
                message = "Saved take " + mine.count(label) + " of \"" + Prediction.displayName(label) + "\".";
            } catch (Exception e) {
                message = "Could not save: " + e.getMessage();
            }
        }
        String shown = message;
        runOnUiThread(() -> {
            countdown.setVisibility(View.GONE);
            recordButton.setEnabled(true);
            Toast.makeText(this, shown, Toast.LENGTH_SHORT).show();
            refreshModelViews();
        });
    }

    private void deleteLastRecording() {
        String label = SignSample.normalizeLabel(String.valueOf(recordLabel.getText()));
        if (store.deleteLast(label)) {
            Toast.makeText(this, "Deleted the last take of \"" + label + "\". Restart Sign Lab to drop it from matching.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No takes of \"" + label + "\" to delete.", Toast.LENGTH_SHORT).show();
        }
        refreshModelViews();
    }

    private void exportRecordings() {
        try {
            File zip = store.exportZip();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", zip);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "Senyas FSL recordings")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Send recordings to your computer"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ views (main thread)

    private void setMode(Mode m) {
        mode = m;
        modeRecognize.setChecked(m == Mode.RECOGNIZE);
        modePractice.setChecked(m == Mode.PRACTICE);
        modeRecord.setChecked(m == Mode.RECORD);
        recognizePanel.setVisibility(m == Mode.RECOGNIZE ? View.VISIBLE : View.GONE);
        practicePanel.setVisibility(m == Mode.PRACTICE ? View.VISIBLE : View.GONE);
        recordPanel.setVisibility(m == Mode.RECORD ? View.VISIBLE : View.GONE);
        overlay.setFeedback(null);
    }

    private void refreshModelViews() {
        runOnCv(() -> {
            String name = recognizer == null ? "Loading…" : recognizer.classifier().name();
            TreeSet<String> labels = new TreeSet<>(mine.labels());
            if (trained != null) labels.addAll(trained.labels());
            Map<String, Integer> counts = store.counts();
            runOnUiThread(() -> {
                modelName.setText("Model: " + name + (trained != null ? "  ·  tap to switch" : ""));
                List<String> list = new ArrayList<>(labels);
                target.setSimpleItems(list.toArray(new String[0]));
                // Suggest the app's own sign words, so a recording's label matches its Practice Room sign.
                List<String> words = new ArrayList<>(labels);
                for (Scenario sc : Repo.SCENARIOS) {
                    for (String[] s : sc.signs) addWord(words, s[0]);
                }
                for (String[] s : Repo.SIGNS) addWord(words, s[0]);
                recordLabel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, words));
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (sb.length() > 0) sb.append("   ");
                    sb.append(e.getKey()).append(" ×").append(e.getValue());
                }
                recordCounts.setText(sb.length() == 0 ? "No recordings yet." : sb.toString());
                if (recognizer != null && recognizer.classifier().labels().isEmpty()) {
                    status.setText("No signs known yet. Record a few takes of 2 or more signs in Record mode, or add a trained model.");
                }
            });
        });
    }

    private static void addWord(List<String> words, String word) {
        String w = SignSample.normalizeLabel(word);
        if (!w.isEmpty() && !words.contains(w)) words.add(w);
    }

    private void showRecognition(SignRecognizer.State s) {
        switch (s.status) {
            case NO_MODEL:
                status.setText("No signs known yet. Record a few takes of 2 or more signs in Record mode, or add a trained model.");
                sign.setText("—");
                confidence.setProgress(0);
                top3.setText("");
                return;
            case NO_HANDS:
                status.setText("Show your hands to the camera.");
                sign.setText("—");
                confidence.setProgress(0);
                top3.setText("");
                return;
            case RECOGNIZED:
                status.setText("Recognized");
                sign.setText(Prediction.displayName(s.sign));
                confidence.setProgress(Math.round(s.confidence * 100));
                break;
            case UNSURE:
                status.setText("Not sure yet. Sign a little slower, inside the guide.");
                break;
            default:
                status.setText("Watching…");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, s.predictions.size()); i++) {
            Prediction p = s.predictions.get(i);
            if (i > 0) sb.append('\n');
            sb.append(p.percent()).append("%  ").append(Prediction.displayName(p.label));
        }
        top3.setText(sb);
    }

    private void showPractice(Feedback fb, int score) {
        if (practiceTarget == null) {
            guidance.setText("Choose a sign to practise.");
            return;
        }
        guidance.setText(fb.tips.isEmpty() && practiceNote != null ? practiceNote : fb.message);
        if (score < 0) {
            practiceScore.setText("—");
            practiceVerdict.setText(!fb.handVisible ? "No hand in view"
                    : practiceScorable ? "Sign the whole sign to get a score" : "Recording needed to score");
            practiceBar.setProgress(0);
            return;
        }
        practiceScore.setText(score + "%");
        practiceVerdict.setText(score >= SignScorer.PASS ? "Looks right" : "Keep practicing");
        practiceBar.setProgress(score);
    }

    /** Says in the open panel why the camera's hands are not used; false when they are. */
    private boolean showRefused(HandFrame.Status s) {
        int text;
        switch (s) {
            case NO_FACE: text = R.string.detect_show_face; break;
            case TOO_MANY_HANDS: text = R.string.detect_too_many_hands; break;
            case TOO_MANY_PEOPLE: text = R.string.detect_too_many_people; break;
            default: return false;
        }
        overlay.setFeedback(null);
        if (mode == Mode.RECOGNIZE) {
            status.setText(text);
            sign.setText("—");
            confidence.setProgress(0);
            top3.setText("");
        } else if (mode == Mode.PRACTICE) {
            guidance.setText(text);
        }
        return true;
    }

    /** Runs on the CV thread unless the screen is closing. */
    private void runOnCv(Runnable r) {
        try {
            cvExecutor.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closing down.
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
        stopped.set(true);
        HandTracker t = tracker;
        // Frames are analysed on cameraExecutor, so closing there waits for one in flight.
        if (t != null) cameraExecutor.execute(t::close);
        cameraExecutor.shutdown();
        cvExecutor.execute(() -> {
            if (trained != null) trained.close();
        });
        cvExecutor.shutdown();
    }
}
