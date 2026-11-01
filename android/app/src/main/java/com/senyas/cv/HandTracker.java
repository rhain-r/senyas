package com.senyas.cv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector;
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorResult;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Camera frames in, the signer's hand landmarks out. A CameraX analyzer that turns each frame
 * upright, looks for faces in it (MediaPipe Face Detector, BlazeFace short range) and feeds it to
 * MediaPipe Hand Landmarker (on-device, free, 21 keypoints per hand). {@link HandGate} then keeps
 * only the hands of the one signer in view, so listeners never see a foot or someone else's hands.
 *
 * Frames go to MediaPipe as captured, not mirrored, matching the Python pipeline. The overlay
 * mirrors them for display so the learner sees themselves as in a mirror.
 */
public final class HandTracker implements ImageAnalysis.Analyzer, AutoCloseable {
    public static final String MODEL_ASSET = "hand_landmarker.task";
    public static final String FACE_MODEL_ASSET = "blaze_face_short_range.tflite";
    /** More than one signer's two hands, so extra hands are seen and the frame refused. */
    static final int TRACKED_HANDS = 4;
    /** Faces are looked for at most this often; frames in between reuse the last result. */
    static final long FACE_EVERY_MS = 100;
    /** Frames waiting for their hand result; MediaPipe drops frames when busy, so few ever wait. */
    private static final int MAX_PENDING = 16;

    public interface Listener {
        /** Called on MediaPipe's thread for every processed frame. */
        void onHands(HandFrame frame, int imageWidth, int imageHeight);

        void onTrackerError(String message);
    }

    /** The faces of one frame sent to the hand landmarker. */
    private static final class Pending {
        final long t;
        final List<float[]> faces;

        Pending(long t, List<float[]> faces) {
            this.t = t;
            this.faces = faces;
        }
    }

    private final HandLandmarker landmarker;
    private final FaceDetector faceDetector;
    private final Listener listener;
    private final HandGate gate = new HandGate(); // MediaPipe's result thread only
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private volatile Bitmap lastFrame;
    private volatile boolean keepFrames;
    // Camera thread.
    private long lastTimestamp, lastFaceRunMs = -FACE_EVERY_MS;
    private volatile List<float[]> lastFaces = Collections.emptyList();

    public HandTracker(Context context, Listener listener) {
        this.listener = listener;
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(TRACKED_HANDS)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onResult)
                .setErrorListener(e -> listener.onTrackerError(e.getMessage()))
                .build();
        landmarker = HandLandmarker.createFromOptions(context, options);
        FaceDetector.FaceDetectorOptions faceOptions = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL_ASSET).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO)
                .setMinDetectionConfidence(0.6f)
                .build();
        try {
            faceDetector = FaceDetector.createFromOptions(context, faceOptions);
        } catch (RuntimeException e) {
            landmarker.close();
            throw e;
        }
    }

    /** Keep a copy of the latest frame (for the practice snapshot). Off by default to save memory. */
    public void setKeepFrames(boolean keep) {
        keepFrames = keep;
        if (!keep) lastFrame = null;
    }

    /** The most recent upright camera frame, or null. */
    public Bitmap lastFrame() {
        return lastFrame;
    }

    @Override
    public void analyze(@NonNull ImageProxy proxy) {
        // MediaPipe refuses a timestamp that is not later than the previous one.
        long timestamp = Math.max(SystemClock.uptimeMillis(), lastTimestamp + 1);
        lastTimestamp = timestamp;
        int rotation = proxy.getImageInfo().getRotationDegrees();
        Bitmap bitmap;
        try {
            bitmap = proxy.toBitmap();
        } finally {
            proxy.close();
        }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        }
        if (keepFrames) lastFrame = bitmap;
        MPImage image = new BitmapImageBuilder(bitmap).build();
        try {
            if (timestamp - lastFaceRunMs >= FACE_EVERY_MS) {
                lastFaces = faceBoxes(faceDetector.detectForVideo(image, timestamp), bitmap.getWidth(), bitmap.getHeight());
                lastFaceRunMs = timestamp;
            }
            synchronized (pending) {
                pending.addLast(new Pending(timestamp, lastFaces));
                while (pending.size() > MAX_PENDING) pending.removeFirst();
            }
            landmarker.detectAsync(image, timestamp);
        } catch (RuntimeException e) {
            // Thrown on the camera thread it would close the app; the next frame is tried instead.
            listener.onTrackerError(String.valueOf(e.getMessage()));
        }
    }

    /** Face boxes {left, top, right, bottom} in image units (MediaPipe gives them in pixels). */
    private static List<float[]> faceBoxes(FaceDetectorResult result, int width, int height) {
        List<float[]> out = new ArrayList<>();
        float w = Math.max(1, width), h = Math.max(1, height);
        for (Detection d : result.detections()) {
            RectF b = d.boundingBox();
            out.add(new float[]{b.left / w, b.top / h, b.right / w, b.bottom / h});
        }
        return out;
    }

    /** The faces of the frame sent at {@code t}; entries of frames MediaPipe skipped are dropped. */
    private List<float[]> facesAt(long t) {
        synchronized (pending) {
            while (!pending.isEmpty() && pending.peekFirst().t < t) pending.removeFirst();
            Pending p = pending.peekFirst();
            if (p != null && p.t == t) return pending.removeFirst().faces;
        }
        return lastFaces;
    }

    private void onResult(HandLandmarkerResult result, MPImage input) {
        List<Hand> hands = new ArrayList<>();
        List<List<NormalizedLandmark>> all = result.landmarks();
        for (int i = 0; i < all.size(); i++) {
            List<NormalizedLandmark> points = all.get(i);
            float[] lm = new float[points.size() * 3];
            for (int k = 0; k < points.size(); k++) {
                NormalizedLandmark p = points.get(k);
                lm[k * 3] = p.x();
                lm[k * 3 + 1] = p.y();
                lm[k * 3 + 2] = p.z();
            }
            Category side = result.handedness().get(i).get(0);
            hands.add(new Hand(side.categoryName(), side.score(), lm));
        }
        float aspect = input.getHeight() / (float) Math.max(1, input.getWidth());
        HandFrame frame = gate.apply(result.timestampMs(), hands, facesAt(result.timestampMs()), aspect);
        listener.onHands(frame, input.getWidth(), input.getHeight());
    }

    @Override
    public void close() {
        landmarker.close();
        faceDetector.close();
    }
}
