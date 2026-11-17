package com.benten.senyas;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Small CPU-only temporal classifier. Detection/presence never contributes a sign score. */
public final class SequenceRecognizer {
    private static final int FRAMES = 16, CLASSES = 105, INFERENCE_INTERVAL_MS = 250;
    private static final long MAX_GAP_MS = 1000;
    private static final long TRACKING_GRACE_MS = 300;
    private final Layer[] layers;
    private final float temperature, minimumMotion;
    private final float[] thresholds, margins;
    private final int minimumDurationMs, maximumDurationMs, minimumHandFrames, minimumBodyFrames;
    private final List<Frame> buffer = new ArrayList<>();
    private long lastTimestamp = -1, lastInference = -1;
    private long lastUsableTimestamp = -1;
    private boolean trackingGap;
    private int lastTarget = -1;
    private Prediction previous;

    public static final class Prediction {
        public final float targetConfidence, topConfidence, margin;
        public final int targetClassId, topClassId;
        /** Decision-relative match score: 85 marks the calibrated acceptance threshold, not accuracy. */
        public final int scorePercent;
        /** Timestamp of the inference, unchanged when a throttled callback reuses this prediction. */
        public final long evaluatedAtMs;
        public final boolean ready, accepted;
        public final boolean completed;
        public final String reason;

        private Prediction(int targetClassId, float target, int top, float confidence, float margin,
                           boolean ready, boolean accepted, String reason, int score, long evaluatedAtMs) {
            this(targetClassId, target, top, confidence, margin, ready, accepted, reason, score, evaluatedAtMs, false);
        }

        private Prediction(int targetClassId, float target, int top, float confidence, float margin,
                           boolean ready, boolean accepted, String reason, int score, long evaluatedAtMs, boolean completed) {
            this.targetClassId = targetClassId;
            targetConfidence = target;
            topClassId = top;
            topConfidence = confidence;
            this.margin = margin;
            this.ready = ready;
            this.accepted = accepted;
            this.reason = reason;
            scorePercent = score;
            this.evaluatedAtMs = evaluatedAtMs;
            this.completed = completed;
        }

        private static Prediction waiting(String reason, int targetClassId, long timestampMs) {
            return new Prediction(targetClassId, 0f, -1, 0f, 0f, false, false, reason, 0, timestampMs);
        }
    }

    private SequenceRecognizer(Layer[] layers, float temperature, float[] thresholds, float[] margins,
                               int minimumDurationMs, int maximumDurationMs, int minimumHandFrames,
                               float minimumMotion, int minimumBodyFrames) {
        this.layers = layers;
        this.temperature = temperature;
        this.thresholds = thresholds;
        this.margins = margins;
        this.minimumDurationMs = minimumDurationMs;
        this.maximumDurationMs = maximumDurationMs;
        this.minimumHandFrames = minimumHandFrames;
        this.minimumMotion = minimumMotion;
        this.minimumBodyFrames = minimumBodyFrames;
    }

    /** Read the bounded, little-endian FSL1 model; caller retains ownership of the stream. */
    public static SequenceRecognizer load(InputStream source) throws IOException {
        if (source == null) throw new IOException("Missing FSL model");
        DataInputStream input = new DataInputStream(source);
        if (input.readInt() != 0x46534c31) throw new IOException("Unsupported FSL model");
        if (integer(input) != FRAMES || integer(input) != SignFeatures.COUNT || integer(input) != 3)
            throw new IOException("Incompatible FSL feature dimensions");
        Layer[] layers = new Layer[3];
        int expectedInput = FRAMES * SignFeatures.COUNT;
        for (int i = 0; i < layers.length; i++) {
            int in = integer(input), out = integer(input);
            int maxOutput = i == 0 ? 512 : i == 1 ? 256 : CLASSES;
            if (in != expectedInput || out < 1 || out > maxOutput || (i == 2 && out != CLASSES))
                throw new IOException("Invalid FSL layer dimensions");
            layers[i] = new Layer(in, out, numbers(input, in * out), numbers(input, out));
            expectedInput = out;
        }
        float temperature = number(input);
        float[] thresholds = numbers(input, CLASSES), margins = numbers(input, CLASSES);
        int minDuration = integer(input), maxDuration = integer(input), minHands = integer(input);
        float motion = number(input);
        int minBody = integer(input);
        if (temperature <= 0f || minDuration < 100 || maxDuration < minDuration || maxDuration > 4000
                || minHands < 2 || minHands > FRAMES || minBody < 0 || minBody > FRAMES || motion < 0f)
            throw new IOException("Invalid FSL temporal calibration");
        for (int i = 0; i < CLASSES; i++)
            if (thresholds[i] < 0f || thresholds[i] > 1.01f || margins[i] < 0f || margins[i] > 1.01f)
                throw new IOException("Invalid FSL confidence calibration");
        if (input.read() != -1) throw new IOException("Unexpected FSL model data");
        return new SequenceRecognizer(layers, temperature, thresholds, margins,
                minDuration, maxDuration, minHands, motion, minBody);
    }

    /** Add a frame using a monotonic millisecond timestamp. Call reset on skip, retry, or lesson exit. */
    public synchronized Prediction addFrame(float[] features, long timestampMs, int targetClassId) {
        if (!supportsTarget(targetClassId)) {
            reset();
            return Prediction.waiting("unknown_target", targetClassId, timestampMs);
        }
        if (features == null || features.length != SignFeatures.COUNT || timestampMs < 0) {
            reset();
            return Prediction.waiting("invalid_frame", targetClassId, timestampMs);
        }
        for (float f : features) if (!Float.isFinite(f)) {
            reset();
            return Prediction.waiting("invalid_frame", targetClassId, timestampMs);
        }
        if (lastTarget != targetClassId || (lastTimestamp >= 0
                && (timestampMs <= lastTimestamp || timestampMs - lastTimestamp > MAX_GAP_MS))) reset();
        boolean handsPresent = hasHands(features);
        boolean bodyPresent = features[SignFeatures.BODY_PRESENT] >= .5f;
        if (trackingGap && lastUsableTimestamp >= 0 && timestampMs - lastUsableTimestamp > TRACKING_GRACE_MS)
            reset();
        lastTarget = targetClassId;
        lastTimestamp = timestampMs;
        if (!handsPresent || !bodyPresent) {
            String reason = handsPresent ? "missing_body" : "missing_hands";
            if (lastUsableTimestamp < 0 || timestampMs - lastUsableTimestamp > TRACKING_GRACE_MS) {
                reset();
                return Prediction.waiting(reason, targetClassId, timestampMs);
            }
            trackingGap = true;
            append(features, timestampMs);
            return invalidate(reason);
        }
        trackingGap = false;
        lastUsableTimestamp = timestampMs;
        append(features, timestampMs);
        int actualHands = 0;
        for (Frame frame : buffer) if (hasHands(frame.values)) actualHands++;
        if (actualHands < minimumHandFrames || timestampMs - buffer.get(0).time < minimumDurationMs)
            return invalidate("warming_up");
        float[][] sequence = resample();
        int hands = 0, body = 0;
        for (float[] f : sequence) {
            if (hasHands(f)) hands++;
            if (f[SignFeatures.BODY_PRESENT] >= .5f) body++;
        }
        if (hands < minimumHandFrames) return invalidate("missing_hands");
        if (body < minimumBodyFrames) return invalidate("missing_body");
        float movement = motion(sequence);
        if (movement <= 0f || movement < minimumMotion) return invalidate("insufficient_motion");
        if (previous != null && lastInference >= 0 && timestampMs - lastInference < INFERENCE_INTERVAL_MS)
            return previous;
        float[] activations = scoreSequence(sequence);
        if (activations == null) return invalidate("invalid_frame");
        int top = 0;
        float second = 0f;
        for (int i = 1; i < activations.length; i++) if (activations[i] > activations[top]) top = i;
        for (int i = 0; i < activations.length; i++) if (i != top) second = Math.max(second, activations[i]);
        float margin = activations[top] - second;
        boolean accepted = top == targetClassId && activations[top] >= thresholds[top] && margin >= margins[top];
        lastInference = timestampMs;
        int score = Math.min(100, Math.round(85f * activations[targetClassId] / Math.max(.000001f, thresholds[targetClassId])));
        if (!accepted) score = Math.min(84, score);
        previous = new Prediction(targetClassId, activations[targetClassId], top, activations[top], margin, true,
                accepted, accepted ? "match" : top != targetClassId ? "different_sign" : "uncertain", score, timestampMs);
        return previous;
    }

    /** CPU model evaluation shared with offline exporter parity checks; temporal gates run in addFrame. */
    float[] scoreSequence(float[][] sequence) {
        if (sequence == null || sequence.length != FRAMES) return null;
        float[] activations = new float[FRAMES * SignFeatures.COUNT];
        for (int i = 0; i < FRAMES; i++) {
            if (sequence[i] == null || sequence[i].length != SignFeatures.COUNT) return null;
            for (float value : sequence[i]) if (!Float.isFinite(value)) return null;
            System.arraycopy(sequence[i], 0, activations, i * SignFeatures.COUNT, SignFeatures.COUNT);
        }
        for (int i = 0; i < layers.length; i++) activations = layers[i].apply(activations, i < 2);
        float max = -Float.MAX_VALUE;
        for (float value : activations) {
            if (!Float.isFinite(value) || !Float.isFinite(value / temperature)) return null;
            max = Math.max(max, value / temperature);
        }
        float total = 0f;
        for (int i = 0; i < activations.length; i++) {
            activations[i] = (float) Math.exp(activations[i] / temperature - max);
            total += activations[i];
        }
        if (!Float.isFinite(total) || total <= 0) return null;
        for (int i = 0; i < activations.length; i++) activations[i] /= total;
        return activations;
    }

    public synchronized void reset() {
        buffer.clear();
        lastTimestamp = -1;
        lastInference = -1;
        lastTarget = -1;
        previous = null;
        lastUsableTimestamp = -1;
        trackingGap = false;
    }

    /** Grade a completed motion, trimming the short hand-lowering interval. Consumes the attempt. */
    public synchronized Prediction finishAttempt(long timestampMs, int targetClassId) {
        try {
            if (!supportsTarget(targetClassId) || lastTarget != targetClassId || buffer.isEmpty()
                    || timestampMs < lastTimestamp || timestampMs - lastTimestamp > MAX_GAP_MS)
                return Prediction.waiting("warming_up", targetClassId, timestampMs);
            while (!buffer.isEmpty() && (!hasHands(buffer.get(buffer.size() - 1).values)
                    || buffer.get(buffer.size() - 1).values[SignFeatures.BODY_PRESENT] < .5f)) buffer.remove(buffer.size() - 1);
            while (!buffer.isEmpty() && (!hasHands(buffer.get(0).values)
                    || buffer.get(0).values[SignFeatures.BODY_PRESENT] < .5f)) buffer.remove(0);
            int actualHands = 0;
            for (Frame frame : buffer) if (hasHands(frame.values) && frame.values[SignFeatures.BODY_PRESENT] >= .5f) actualHands++;
            if (actualHands < minimumHandFrames || buffer.get(buffer.size() - 1).time - buffer.get(0).time < minimumDurationMs)
                return Prediction.waiting("warming_up", targetClassId, timestampMs);
            float[][] sequence = resample();
            int hands = 0, body = 0;
            for (float[] f : sequence) {
                if (hasHands(f)) hands++;
                if (f[SignFeatures.BODY_PRESENT] >= .5f) body++;
            }
            if (hands < minimumHandFrames || body < minimumBodyFrames || motion(sequence) < minimumMotion || motion(sequence) <= 0)
                return Prediction.waiting("insufficient_motion", targetClassId, timestampMs);
            float[] probabilities = scoreSequence(sequence);
            if (probabilities == null) return Prediction.waiting("invalid_frame", targetClassId, timestampMs);
            int top = 0; float second = 0;
            for (int i = 1; i < probabilities.length; i++) if (probabilities[i] > probabilities[top]) top = i;
            for (int i = 0; i < probabilities.length; i++) if (i != top) second = Math.max(second, probabilities[i]);
            float gap = probabilities[top] - second;
            boolean accepted = top == targetClassId && probabilities[top] >= thresholds[targetClassId] && gap >= margins[targetClassId];
            int score = Math.min(100, Math.round(85f * probabilities[targetClassId] / Math.max(.000001f, thresholds[targetClassId])));
            if (!accepted) score = Math.min(84, score);
            return new Prediction(targetClassId, probabilities[targetClassId], top, probabilities[top], gap,
                    true, accepted, accepted ? "match" : "different_sign", score, timestampMs, true);
        } finally { reset(); }
    }

    /** A threshold or margin greater than one explicitly disables an unvalidated class. */
    public boolean supportsTarget(int targetClassId) {
        return targetClassId >= 0 && targetClassId < CLASSES
                && thresholds[targetClassId] <= 1f && margins[targetClassId] <= 1f;
    }

    private Prediction invalidate(String reason) {
        previous = null;
        return Prediction.waiting(reason, lastTarget, lastTimestamp);
    }

    private void append(float[] features, long timestampMs) {
        buffer.add(new Frame(timestampMs, features.clone()));
        while (!buffer.isEmpty() && (buffer.get(0).time < timestampMs - maximumDurationMs
                || buffer.size() > 128)) buffer.remove(0);
        // Match training's first/last hand-present active interval after the rolling window expires.
        while (buffer.size() > 1 && !hasHands(buffer.get(0).values)) buffer.remove(0);
    }

    private float[][] resample() {
        float[][] result = new float[FRAMES][SignFeatures.COUNT];
        long first = buffer.get(0).time, duration = buffer.get(buffer.size() - 1).time - first;
        int right = 1;
        for (int i = 0; i < FRAMES; i++) {
            double time = first + duration * (double) i / (FRAMES - 1);
            while (right < buffer.size() - 1 && buffer.get(right).time < time) right++;
            Frame a = buffer.get(right - 1), b = buffer.get(right);
            float fraction = (float) ((time - a.time) / (b.time - a.time));
            for (int j = 0; j < SignFeatures.COUNT; j++)
                result[i][j] = a.values[j] + fraction * (b.values[j] - a.values[j]);
        }
        return result;
    }

    private static float motion(float[][] sequence) {
        double squares = 0;
        int count = 0;
        for (int t = 1; t < sequence.length; t++) for (int slot = 0; slot < 2; slot++) {
            int start = slot * SignFeatures.HAND_SIZE;
            if (sequence[t - 1][start + 66] < .5f || sequence[t][start + 66] < .5f) continue;
            for (int j = start; j < start + 66; j++) {
                double delta = sequence[t][j] - sequence[t - 1][j];
                squares += delta * delta;
                count++;
            }
        }
        return count == 0 ? 0f : (float) Math.sqrt(squares / count);
    }

    private static boolean hasHands(float[] f) { return f[66] >= .5f || f[133] >= .5f; }
    private static int integer(DataInputStream input) throws IOException { return Integer.reverseBytes(input.readInt()); }
    private static float number(DataInputStream input) throws IOException {
        float value = Float.intBitsToFloat(integer(input));
        if (!Float.isFinite(value)) throw new IOException("Non-finite FSL weights");
        return value;
    }
    private static float[] numbers(DataInputStream input, int size) throws IOException {
        float[] values = new float[size];
        for (int i = 0; i < size; i++) values[i] = number(input);
        return values;
    }

    private static final class Frame {
        final long time;
        final float[] values;
        Frame(long time, float[] values) { this.time = time; this.values = values; }
    }

    private static final class Layer {
        final int in, out;
        final float[] weights, bias;
        Layer(int in, int out, float[] weights, float[] bias) {
            this.in = in; this.out = out; this.weights = weights; this.bias = bias;
        }
        float[] apply(float[] input, boolean relu) {
            float[] output = bias.clone();
            for (int i = 0; i < in; i++) {
                float value = input[i];
                int at = i * out;
                for (int j = 0; j < out; j++) output[j] += value * weights[at + j];
            }
            if (relu) for (int j = 0; j < out; j++) output[j] = Math.max(0f, output[j]);
            return output;
        }
    }
}
