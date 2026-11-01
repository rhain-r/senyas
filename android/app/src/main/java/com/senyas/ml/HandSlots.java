package com.senyas.ml;

import java.util.List;

/**
 * Follows up to two hands through a stream of frames in two slots, by wrist continuity.
 * MediaPipe's Left/Right labels are ignored: they flip mid-sign. Two detections that lie on top
 * of each other are one hand found twice. A slot not seen for a while forgets where its hand was,
 * so a hand coming back is not forced into the wrong slot.
 *
 * Hands are in the scorer's coordinates (see {@link SignScorer}): x mirrored like the preview, y in
 * units of the image width.
 */
public final class HandSlots {
    private final double dupDist, staleMs;
    private final double[][] pos = new double[2][];
    private final long[] last = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};

    public HandSlots(double dupDist, double staleMs) {
        this.dupDist = dupDist;
        this.staleMs = staleMs;
    }

    /** Slot 0 and slot 1 for this frame (either may be null). {@code hands} holds 63-float hands. */
    public double[][] push(long t, List<double[]> hands) {
        double[] a = hands.size() > 0 ? hands.get(0) : null;
        double[] b = hands.size() > 1 ? hands.get(1) : null;
        if (b != null && duplicate(a, b)) b = null;
        for (int s = 0; s < 2; s++) if (t - last[s] > staleMs) pos[s] = null;
        double[][] cur = new double[2][];
        if (b != null) {
            if (pos[0] == null && pos[1] == null) {
                cur[0] = a[0] >= b[0] ? a : b;
                cur[1] = cur[0] == a ? b : a;
            } else if (pos[1] == null) {
                boolean keep = dist(a, 0) <= dist(b, 0);
                cur[0] = keep ? a : b;
                cur[1] = keep ? b : a;
            } else if (pos[0] == null) {
                boolean swap = dist(a, 1) <= dist(b, 1);
                cur[0] = swap ? b : a;
                cur[1] = swap ? a : b;
            } else {
                boolean keep = dist(a, 0) + dist(b, 1) <= dist(a, 1) + dist(b, 0);
                cur[0] = keep ? a : b;
                cur[1] = keep ? b : a;
            }
        } else if (a != null) {
            int s;
            if (pos[0] == null && pos[1] == null) s = last[0] >= last[1] ? 0 : 1; // the hand seen last keeps its slot
            else if (pos[1] == null) s = 0;
            else if (pos[0] == null) s = 1;
            else s = dist(a, 0) <= dist(a, 1) ? 0 : 1;
            cur[s] = a;
        }
        for (int s = 0; s < 2; s++) {
            if (cur[s] == null) continue;
            pos[s] = new double[]{cur[s][0], cur[s][1]};
            last[s] = t;
        }
        return cur;
    }

    /** Wrist distance to a slot's last position, in palm sizes. */
    private double dist(double[] h, int s) {
        return Math.hypot(h[0] - pos[s][0], h[1] - pos[s][1]) / HandFeatures.palmSize(h);
    }

    private boolean duplicate(double[] a, double[] b) {
        double s = Math.min(HandFeatures.palmSize(a), HandFeatures.palmSize(b));
        double sum = 0;
        for (int i = 0; i < Landmarks.POINTS; i++) {
            sum += Math.hypot(a[i * 3] - b[i * 3], a[i * 3 + 1] - b[i * 3 + 1]);
        }
        return sum / Landmarks.POINTS < dupDist * s;
    }
}
