package com.senyas.cv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live recognition. Keeps the last couple of seconds of hand landmarks, classifies them a few
 * times per second, and only reports a sign once several readings in a row agree, so the label
 * on screen does not flicker.
 *
 * Pure Java (no Android types) so it is unit tested on the computer.
 */
public final class SignRecognizer {
    public enum Status {
        /** No classifier or it knows no signs yet. */
        NO_MODEL,
        /** Hands are not in view (or only briefly). */
        NO_HANDS,
        /** Hands are in view; readings do not agree yet. */
        WATCHING,
        /** The best guess is too weak to call. */
        UNSURE,
        /** Several readings agree on one sign. */
        RECOGNIZED
    }

    public static final class State {
        public final Status status;
        /** All labels, most likely first (empty when nothing was classified). */
        public final List<Prediction> predictions;
        /** The agreed sign when status is RECOGNIZED, else null. */
        public final String sign;
        /** Average probability of the agreed sign. */
        public final float confidence;

        State(Status status, List<Prediction> predictions, String sign, float confidence) {
            this.status = status;
            this.predictions = predictions;
            this.sign = sign;
            this.confidence = confidence;
        }

        /** Probability of one label in the latest reading (0 when absent). */
        public float probabilityOf(String label) {
            for (Prediction p : predictions) if (p.label.equals(label)) return p.probability;
            return 0f;
        }
    }

    /** How much history one reading looks at. FSL-105 clips are 4 s; most signs take 1-2.5 s. */
    public static final long WINDOW_MS = 3500;
    /**
     * Each reading tries these lengths of recent history and keeps each sign's best probability,
     * so a whole sign fits the window whether it was made quickly or slowly.
     */
    public static final long[] WINDOWS_MS = {1500, 2500, 3500};
    /** How often to classify. */
    public static final long EVERY_MS = 250;
    /** Hands must be visible in at least this share of the window. */
    public static final float MIN_PRESENCE = 0.5f;
    /** A reading counts toward agreement only at or above this probability. */
    public static final float MIN_PROBABILITY = 0.6f;
    /** Readings kept, and how many of them must agree. */
    public static final int HISTORY = 4;
    public static final int AGREE = 3;

    private final ArrayDeque<HandFrame> window = new ArrayDeque<>();
    private final ArrayDeque<Prediction> history = new ArrayDeque<>();
    private SignClassifier classifier;
    private long lastRunMs = Long.MIN_VALUE;

    public SignRecognizer(SignClassifier classifier) {
        this.classifier = classifier;
    }

    public void setClassifier(SignClassifier classifier) {
        this.classifier = classifier;
        reset();
    }

    public SignClassifier classifier() {
        return classifier;
    }

    public void reset() {
        window.clear();
        history.clear();
        lastRunMs = Long.MIN_VALUE;
    }

    /** Adds one camera frame. Returns a new state when a reading was taken, otherwise null. */
    public State onFrame(HandFrame frame) {
        window.addLast(frame);
        while (!window.isEmpty() && window.peekFirst().timestampMs < frame.timestampMs - WINDOW_MS) {
            window.removeFirst();
        }
        if (lastRunMs != Long.MIN_VALUE && frame.timestampMs - lastRunMs < EVERY_MS) return null;
        lastRunMs = frame.timestampMs;
        return evaluate();
    }

    private State evaluate() {
        List<Prediction> none = Collections.emptyList();
        if (classifier == null || classifier.labels().isEmpty()) return new State(Status.NO_MODEL, none, null, 0f);

        List<HandFrame> all = new ArrayList<>(window);
        long now = all.get(all.size() - 1).timestampMs;
        Map<String, Float> best = new HashMap<>();
        boolean classified = false;
        for (long length : WINDOWS_MS) {
            List<HandFrame> frames = new ArrayList<>();
            for (HandFrame f : all) if (f.timestampMs >= now - length) frames.add(f);
            if (FeatureEncoder.handPresence(frames) < MIN_PRESENCE) continue;
            float[][] seq = FeatureEncoder.encodeSequence(frames, FeatureEncoder.SEQUENCE_LENGTH);
            if (seq == null) continue;
            classified = true;
            for (Prediction p : classifier.classify(seq)) best.merge(p.label, p.probability, Math::max);
        }
        if (!classified) {
            history.clear();
            return new State(Status.NO_HANDS, none, null, 0f);
        }
        List<Prediction> predictions = new ArrayList<>();
        for (Map.Entry<String, Float> e : best.entrySet()) predictions.add(new Prediction(e.getKey(), e.getValue()));
        predictions.sort((a, b) -> Float.compare(b.probability, a.probability));
        if (predictions.isEmpty()) return new State(Status.UNSURE, predictions, null, 0f);

        history.addLast(predictions.get(0));
        while (history.size() > HISTORY) history.removeFirst();

        Map<String, Integer> votes = new HashMap<>();
        Map<String, Float> sums = new HashMap<>();
        for (Prediction p : history) {
            if (p.probability < MIN_PROBABILITY) continue;
            votes.merge(p.label, 1, Integer::sum);
            sums.merge(p.label, p.probability, Float::sum);
        }
        for (Map.Entry<String, Integer> v : votes.entrySet()) {
            if (v.getValue() >= AGREE) {
                return new State(Status.RECOGNIZED, predictions, v.getKey(), sums.get(v.getKey()) / v.getValue());
            }
        }
        Status s = predictions.get(0).probability < MIN_PROBABILITY ? Status.UNSURE : Status.WATCHING;
        return new State(s, predictions, null, 0f);
    }
}
