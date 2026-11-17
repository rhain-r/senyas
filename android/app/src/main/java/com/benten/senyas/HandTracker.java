package com.benten.senyas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;

/** Locates hands on-device. Hand detection is deliberately never treated as an FSL sign match. */
final class HandTracker implements ImageAnalysis.Analyzer, AutoCloseable {
    interface Listener {
        void onHands(List<List<NormalizedLandmark>> hands, int width, int height);
        void onError();
        default void onFeedback(int label, int revision, SequenceRecognizer.Prediction result) { }
        default void onFeedbackUnavailable(int label, int revision, boolean unsupported) { }
    }

    private final Context context;
    private final boolean mirror;
    private final Listener listener;
    private HandLandmarker detector;
    private volatile boolean stopped;
    private boolean failed;
    private long lastTimestamp = -1;
    private boolean receivedFrame;
    private SequenceRecognizer recognizer;
    private PoseLandmarker pose;
    private boolean recognitionFailed;
    private long lastRecognition = -1;
    private long lastUsableRecognition = -1;
    private volatile Target requestedTarget = new Target(-1, 0);
    private Target activeTarget;

    private static final class Target {
        final int label, revision;
        Target(int label, int revision) { this.label = label; this.revision = revision; }
    }

    void setTarget(int label, int revision) { requestedTarget = new Target(label, revision); }

    HandTracker(Context context, boolean mirror, Listener listener) {
        this.context = context.getApplicationContext();
        this.mirror = mirror;
        this.listener = listener;
    }

    @Override
    public void analyze(@NonNull ImageProxy frame) {
        Bitmap raw = null, upright = null;
        try {
            if (stopped || failed) return;
            if (!receivedFrame) {
                receivedFrame = true;
                CameraDiagnostics.event("hand_tracker_first_frame width=" + frame.getWidth()
                        + " height=" + frame.getHeight() + " format=" + frame.getFormat()
                        + " rotation=" + frame.getImageInfo().getRotationDegrees());
            }
            raw = frame.toBitmap();
            Matrix transform = new Matrix();
            transform.postRotate(frame.getImageInfo().getRotationDegrees());
            if (mirror) transform.postScale(-1f, 1f);
            upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), transform, false);
            track(upright);
        } catch (RuntimeException | LinkageError error) {
            failed = true;
            CameraDiagnostics.event("hand_tracker_failed type=" + error.getClass().getName());
            Log.e("SenyasHandTracker", "Hand tracking could not process the camera image", error);
            if (!stopped) listener.onError();
        } finally {
            frame.close();
            if (upright != null && upright != raw && !upright.isRecycled()) upright.recycle();
            if (raw != null && !raw.isRecycled()) raw.recycle();
        }
    }

    /** Called on the same analysis executor as analyze() and close(). */
    void track(Bitmap upright) {
        track(upright, SystemClock.uptimeMillis());
    }

    /** Explicit timestamps also allow recorded-video inference to exercise the live pipeline. */
    void track(Bitmap upright, long frameTimestamp) {
        if (stopped) return;
        if (upright == null || upright.isRecycled()) throw new IllegalArgumentException("A fresh camera bitmap is required");
        if (detector == null) {
            CameraDiagnostics.event("hand_tracker_initializing delegate=default_cpu");
            detector = HandLandmarker.createFromOptions(context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder()
                                    .setModelAssetPath("hand_landmarker.task").build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(2)
                            // Match training extraction while retaining confidence gates for empty frames.
                            .setMinHandDetectionConfidence(0.35f)
                            .setMinHandPresenceConfidence(0.35f)
                            .setMinTrackingConfidence(0.35f)
                            .build());
            CameraDiagnostics.event("hand_tracker_initialized");
        }
        long timestamp = Math.max(frameTimestamp, lastTimestamp + 1);
        lastTimestamp = timestamp;
        try (MPImage image = new BitmapImageBuilder(upright).build()) {
            HandLandmarkerResult result = detector.detectForVideo(image, timestamp);
            if (!stopped) listener.onHands(result.landmarks(), upright.getWidth(), upright.getHeight());
            recognize(image, result.landmarks(), upright.getWidth(), upright.getHeight(), timestamp);
        }
    }

    private void recognize(MPImage image, List<List<NormalizedLandmark>> hands, int width, int height, long timestamp) {
        Target target = requestedTarget;
        if (target != activeTarget) {
            activeTarget = target;
            lastRecognition = -1;
            lastUsableRecognition = -1;
            if (recognizer != null) recognizer.reset();
            if (target.label < 0 && !stopped) listener.onFeedbackUnavailable(target.label, target.revision, true);
        }
        if (target.label < 0 || stopped || recognitionFailed || (lastRecognition >= 0 && timestamp - lastRecognition < 125)) return;
        lastRecognition = timestamp;
        try {
            if (recognizer == null) {
                CameraDiagnostics.event("sign_recognizer_initializing");
                try (java.io.InputStream weights = context.getAssets().open("fsl_recognizer.bin")) {
                    recognizer = SequenceRecognizer.load(weights);
                }
            }
            if (!recognizer.supportsTarget(target.label)) {
                listener.onFeedbackUnavailable(target.label, target.revision, true);
                return;
            }
            if (pose == null) {
                pose = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.35f).setMinPosePresenceConfidence(0.35f)
                        .setMinTrackingConfidence(0.35f).build());
                CameraDiagnostics.event("sign_recognizer_initialized");
            }
            PoseLandmarkerResult body = pose.detectForVideo(image, timestamp);
            float[][][] handPoints = new float[Math.min(2, hands.size())][21][3];
            for (int h = 0; h < handPoints.length; h++) for (int p = 0; p < 21; p++) {
                NormalizedLandmark point = hands.get(h).get(p);
                handPoints[h][p][0] = point.x(); handPoints[h][p][1] = point.y(); handPoints[h][p][2] = point.z();
            }
            float[][] bodyPoints = null;
            if (!body.landmarks().isEmpty()) {
                List<NormalizedLandmark> points = body.landmarks().get(0);
                bodyPoints = new float[points.size()][4];
                for (int p = 0; p < points.size(); p++) {
                    NormalizedLandmark point = points.get(p);
                    bodyPoints[p][0] = point.x(); bodyPoints[p][1] = point.y(); bodyPoints[p][2] = point.z();
                    bodyPoints[p][3] = point.visibility().orElse(0f);
                }
            }
            float[] features = SignFeatures.extract(handPoints, bodyPoints, width, height);
            boolean usable = !hands.isEmpty() && features[SignFeatures.BODY_PRESENT] >= .5f;
            if (lastUsableRecognition >= 0 && timestamp - lastUsableRecognition > 300) {
                SequenceRecognizer.Prediction completed = recognizer.finishAttempt(timestamp, target.label);
                lastUsableRecognition = -1;
                if (!stopped) listener.onFeedback(target.label, target.revision, completed);
            }
            if (usable) lastUsableRecognition = timestamp;
            SequenceRecognizer.Prediction prediction = recognizer.addFrame(features, timestamp, target.label);
            if (!stopped) listener.onFeedback(target.label, target.revision, prediction);
        } catch (java.io.IOException | RuntimeException | LinkageError error) {
            recognitionFailed = true;
            CameraDiagnostics.event("sign_recognition_unavailable type=" + error.getClass().getName());
            Log.e("SenyasSignRecognizer", "Sign recognition unavailable", error);
            if (!stopped) listener.onFeedbackUnavailable(target.label, target.revision, false);
        }
    }

    void stop() { stopped = true; }

    @Override
    public void close() {
        stopped = true;
        if (pose != null) { pose.close(); pose = null; }
        recognizer = null;
        if (detector != null) {
            CameraDiagnostics.event("hand_tracker_closing");
            detector.close();
            detector = null;
            CameraDiagnostics.event("hand_tracker_closed");
        }
    }
}
