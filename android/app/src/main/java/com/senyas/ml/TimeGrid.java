package com.senyas.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the two hand slots on a fixed time grid, so a 7 fps emulator and a 30 fps phone look the
 * same to the scorer. A hand is interpolated between the frames around a tick when they are at
 * most {@code gapMs} apart (a fast hand blurs and is missed for a frame or two).
 *
 * Live, a tick is handed out only once no later camera frame can change it; {@link #flush}
 * hands out the rest when a recording ends.
 */
final class TimeGrid {
    private final double stepMs, gapMs;
    private final List<Long> ts0 = new ArrayList<>(), ts1 = new ArrayList<>();
    private final List<double[]> hs0 = new ArrayList<>(), hs1 = new ArrayList<>();
    private boolean started;
    private long t0, tLast;
    private int next;

    TimeGrid(double rateHz, double gapMs) {
        this.stepMs = 1000.0 / rateHz;
        this.gapMs = gapMs;
    }

    /** One camera frame's slots (either may be null). Times must increase. */
    void add(long t, double[] a, double[] b) {
        if (!started) {
            started = true;
            t0 = t;
        }
        tLast = t;
        if (a != null) {
            ts0.add(t);
            hs0.add(a);
        }
        if (b != null) {
            ts1.add(t);
            hs1.add(b);
        }
    }

    /**
     * The next tick's two slots if it is final (or, with {@code flush}, if it exists at all),
     * else null. Call repeatedly until it returns null.
     */
    double[][] poll(boolean flush) {
        if (!started) return null;
        double T = t0 + stepMs * next;
        if (T > tLast) return null;
        if (!flush && !(isFinal(ts0, T) && isFinal(ts1, T))) return null;
        double[][] out = {valueAt(ts0, hs0, T), valueAt(ts1, hs1, T)};
        next++;
        prune(ts0, hs0, T);
        prune(ts1, hs1, T);
        return out;
    }

    double[][] flush() {
        return poll(true);
    }

    /** Whether frames still to come could change this slot at tick T. */
    private boolean isFinal(List<Long> ts, double T) {
        int b = firstAtOrAfter(ts, T);
        if (b < ts.size()) return true;
        int a = b - 1;
        if (tLast < T + gapMs / 2) return false;
        return a < 0 || T - ts.get(a) > gapMs || tLast >= ts.get(a) + gapMs;
    }

    private double[] valueAt(List<Long> ts, List<double[]> hs, double T) {
        int b = firstAtOrAfter(ts, T);
        if (b < ts.size() && ts.get(b) == T) return hs.get(b);
        int a = b - 1;
        if (a >= 0 && b < ts.size() && ts.get(b) - ts.get(a) <= gapMs) {
            double w = (T - ts.get(a)) / Math.max(ts.get(b) - ts.get(a), 1e-9);
            double[] ha = hs.get(a), hb = hs.get(b), out = new double[ha.length];
            for (int k = 0; k < out.length; k++) out[k] = (1 - w) * ha[k] + w * hb[k];
            return out;
        }
        if (a >= 0 && T - ts.get(a) <= gapMs / 2) return hs.get(a);
        if (b < ts.size() && ts.get(b) - T <= gapMs / 2) return hs.get(b);
        return null;
    }

    private static int firstAtOrAfter(List<Long> ts, double T) {
        int lo = 0, hi = ts.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ts.get(mid) < T) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Frames more than a gap before the next tick can no longer affect it. */
    private void prune(List<Long> ts, List<double[]> hs, double lastTick) {
        double keep = lastTick + stepMs - gapMs - 1;
        int drop = 0;
        while (drop < ts.size() && ts.get(drop) < keep) drop++;
        if (drop == 0) return;
        ts.subList(0, drop).clear();
        hs.subList(0, drop).clear();
    }
}
