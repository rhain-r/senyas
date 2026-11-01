package com.senyas.cv;

import com.senyas.ml.ReferenceClip;
import com.senyas.ml.SignScorer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One recorded example of a sign: the raw hand landmarks over time plus its label.
 *
 * Saved as JSON in the same format that ml/extract_landmarks.py writes for video datasets, so
 * recordings made by Deaf tutors on the phone can be added straight to the training data:
 * {"label": "salamat", "source": "...", "frames": [{"t": 0, "hands": [{"label": "Right",
 * "score": 0.98, "lm": [63 numbers]}]}]}
 */
public final class SignSample {
    public final String label;
    public final String source;
    public final List<HandFrame> frames;

    public SignSample(String label, String source, List<HandFrame> frames) {
        this.label = normalizeLabel(label);
        this.source = source;
        this.frames = frames;
    }

    /** Labels are lowercase words joined by underscores: "Good morning" becomes "good_morning". */
    public static String normalizeLabel(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_");
        return s.replaceAll("^_+|_+$", "");
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(frames.size() * 900);
        sb.append("{\"label\":\"").append(escape(label)).append("\",\"source\":\"").append(escape(source))
                .append("\",\"featureVersion\":").append(FeatureEncoder.VERSION).append(",\"frames\":[");
        long t0 = frames.isEmpty() ? 0 : frames.get(0).timestampMs;
        for (int i = 0; i < frames.size(); i++) {
            HandFrame f = frames.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":").append(f.timestampMs - t0).append(",\"hands\":[");
            for (int h = 0; h < f.hands.size(); h++) {
                Hand hand = f.hands.get(h);
                if (h > 0) sb.append(',');
                sb.append("{\"label\":\"").append(escape(hand.label)).append("\",\"score\":")
                        .append(round(hand.score)).append(",\"lm\":[");
                for (int k = 0; k < hand.landmarks.length; k++) {
                    if (k > 0) sb.append(',');
                    sb.append(round(hand.landmarks[k]));
                }
                sb.append("]}");
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /**
     * This recording as a reference for the sign scorer. Recordings do not store the camera
     * image's shape; the app always analyses 640x480 frames turned upright.
     */
    public ReferenceClip toReference() {
        long[] t = new long[frames.size()];
        List<List<float[]>> hands = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            t[i] = frames.get(i).timestampMs;
            hands.add(frames.get(i).landmarksByConfidence());
        }
        return SignScorer.prepare(label, t, hands, SignScorer.PHONE_ASPECT);
    }

    /** Six decimals keeps files small and is far finer than MediaPipe's own precision. */
    private static String round(float v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Collects frames for a fixed time after {@link #start}. */
    public static final class Recorder {
        public static final long DURATION_MS = 2500;
        private final List<HandFrame> frames = new ArrayList<>();
        private long startMs = -1;

        public void start() {
            frames.clear();
            startMs = -1;
        }

        /** Adds a frame; returns true once the recording is long enough. */
        public boolean add(HandFrame f) {
            if (startMs < 0) startMs = f.timestampMs;
            frames.add(f);
            return f.timestampMs - startMs >= DURATION_MS;
        }

        public List<HandFrame> frames() {
            return new ArrayList<>(frames);
        }
    }
}
