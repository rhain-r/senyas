package com.senyas.cv;

import java.util.List;

/**
 * Turns MediaPipe hand landmarks into the numbers the sign classifier reads.
 *
 * This is feature spec v1. ml/senyas_ml/features.py implements the same steps in Python, and
 * FeatureEncoderTest checks both against one shared fixture. Change one, change both, and bump
 * {@link #VERSION} (the trained model records the version it was trained with).
 *
 * Per frame, 132 numbers: two hand slots of 66. Slot 0 is the hand MediaPipe labels "Right",
 * slot 1 the hand it labels "Left". For each present hand:
 *   0..62  the 21 landmarks relative to the wrist, divided by hand size
 *          (hand size = wrist to middle-finger knuckle, in image units)
 *   63, 64 wrist position in the image, centred (x - 0.5, y - 0.5): where the sign happens
 *   65     hand size: how near the hand is to the camera
 * A missing hand is all zeros.
 *
 * Per sign, frames with no hands are trimmed from both ends and the rest is resampled to
 * {@link #SEQUENCE_LENGTH} frames evenly spaced in time, so fast and slow signers line up and the
 * camera's frame rate does not matter.
 */
public final class FeatureEncoder {
    public static final int VERSION = 1;
    public static final int SEQUENCE_LENGTH = 32;
    public static final int PER_HAND = 66;
    public static final int FEATURES = PER_HAND * 2;

    private static final int WRIST = 0;
    private static final int MIDDLE_MCP = 9;

    private FeatureEncoder() {
    }

    public static float[] encodeFrame(HandFrame frame) {
        float[] out = new float[FEATURES];
        Hand[] slots = assignSlots(frame.hands);
        for (int s = 0; s < 2; s++) {
            if (slots[s] != null) encodeHand(slots[s].landmarks, out, s * PER_HAND);
        }
        return out;
    }

    /**
     * Slot 0 for "Right", slot 1 for "Left". When MediaPipe gives both hands the same label, the
     * more confident hand keeps that slot and the other takes the free one.
     */
    static Hand[] assignSlots(List<Hand> hands) {
        Hand[] slots = new Hand[2];
        Hand[] sorted = hands.toArray(new Hand[0]);
        java.util.Arrays.sort(sorted, (a, b) -> Float.compare(b.score, a.score));
        for (Hand h : sorted) {
            int preferred = "Right".equals(h.label) ? 0 : 1;
            if (slots[preferred] == null) slots[preferred] = h;
            else if (slots[1 - preferred] == null) slots[1 - preferred] = h;
        }
        return slots;
    }

    static void encodeHand(float[] lm, float[] out, int offset) {
        float x0 = lm[WRIST * 3], y0 = lm[WRIST * 3 + 1], z0 = lm[WRIST * 3 + 2];
        float dx = lm[MIDDLE_MCP * 3] - x0;
        float dy = lm[MIDDLE_MCP * 3 + 1] - y0;
        float size = (float) Math.sqrt(dx * dx + dy * dy);
        if (size < 1e-6f) size = 1f;
        for (int i = 0; i < 21; i++) {
            out[offset + i * 3] = (lm[i * 3] - x0) / size;
            out[offset + i * 3 + 1] = (lm[i * 3 + 1] - y0) / size;
            out[offset + i * 3 + 2] = (lm[i * 3 + 2] - z0) / size;
        }
        out[offset + 63] = x0 - 0.5f;
        out[offset + 64] = y0 - 0.5f;
        out[offset + 65] = size;
    }

    /**
     * Trims hand-less frames at both ends and resamples to {@code length} frames by nearest
     * timestamp. Returns null when fewer than two frames have hands.
     */
    public static float[][] encodeSequence(List<HandFrame> frames, int length) {
        int first = -1, last = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).hasHands()) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0 || last - first < 1) return null;
        double t0 = frames.get(first).timestampMs;
        double t1 = frames.get(last).timestampMs;
        float[][] out = new float[length][];
        int j = first;
        for (int k = 0; k < length; k++) {
            double target = t0 + (t1 - t0) * k / (length - 1);
            while (j < last && Math.abs(frames.get(j + 1).timestampMs - target) <= Math.abs(frames.get(j).timestampMs - target)) {
                j++;
            }
            out[k] = encodeFrame(frames.get(j));
        }
        return out;
    }

    /** Share of frames (0..1) in which at least one hand was found. */
    public static float handPresence(List<HandFrame> frames) {
        if (frames.isEmpty()) return 0f;
        int n = 0;
        for (HandFrame f : frames) if (f.hasHands()) n++;
        return n / (float) frames.size();
    }
}
