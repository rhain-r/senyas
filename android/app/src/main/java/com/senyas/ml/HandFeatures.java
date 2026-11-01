package com.senyas.ml;

/**
 * What the scorer compares at one moment (one time-grid frame): the dominant hand's shape,
 * direction and place, and where the second hand is relative to it.
 *
 * Every measure is in the hand's own units (palm sizes, unit directions), so a learner close to
 * the phone and a signer filmed from across a room compare directly.
 */
final class HandFeatures {
    /** Finger joints that make up the handshape (wrist and knuckles are the palm itself). */
    static final int[] SHAPE_PTS = {2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20};
    static final int SHAPE = SHAPE_PTS.length * 3;
    /** Entries of SHAPE_PTS per finger, thumb first; the last one is the fingertip. */
    static final int[][] FINGERS = {{0, 1, 2}, {3, 4}, {5, 6}, {7, 8}, {9, 10}};

    /** Dominant hand seen / second hand seen. */
    boolean pd, pn;
    /** Handshape: shape points in the palm's own frame, in palm sizes (depth weighted). */
    final double[] q = new double[SHAPE];
    /** Finger direction (wrist to middle knuckle) and palm normal, camera space. */
    final double[] u = new double[3], n = new double[3];
    /** Palm centre and 3D palm size (NaN when the dominant hand is missing). */
    double cx = Double.NaN, cy = Double.NaN, s = Double.NaN;
    /** Movement direction of the palm centre, saturating (see {@link SignScorer#V0}). */
    double gx, gy;
    /** Second hand: palm centre relative to the dominant hand (palm sizes), its directions. */
    double rx, ry;
    final double[] nu = new double[3], nn = new double[3];
    /** Second hand's palm centre (NaN when missing). */
    double ncx = Double.NaN, ncy = Double.NaN;

    /** Mean 3D length of the palm's edges: steady when the hand tips toward the camera. */
    static double palmSize(double[] h) {
        double s = (dist3(h, 0, 5) + dist3(h, 0, 17) + dist3(h, 5, 17) + dist3(h, 0, 9)) / 4;
        return s > 0 ? s : 1e-6;
    }

    /** Shape, directions, palm centre and palm size of one hand (63 floats, scorer coordinates). */
    static Hand measure(double[] h, double zWeight) {
        Hand o = new Hand();
        o.s = Math.max(palmSize(h), 1e-6);
        double[] y = sub(h, 9, 0);
        normalize(y);
        double[] x = sub(h, 5, 17);
        double d = dot(x, y);
        for (int k = 0; k < 3; k++) x[k] -= y[k] * d;
        normalize(x);
        double[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};
        for (int p = 0; p < SHAPE_PTS.length; p++) {
            int i = SHAPE_PTS[p];
            double rx = (h[i * 3] - h[0]) / o.s, ry = (h[i * 3 + 1] - h[1]) / o.s, rz = (h[i * 3 + 2] - h[2]) / o.s;
            o.q[p * 3] = rx * x[0] + ry * x[1] + rz * x[2];
            o.q[p * 3 + 1] = rx * y[0] + ry * y[1] + rz * y[2];
            o.q[p * 3 + 2] = zWeight * (rx * z[0] + ry * z[1] + rz * z[2]);
        }
        System.arraycopy(y, 0, o.u, 0, 3);
        System.arraycopy(z, 0, o.n, 0, 3);
        int[] palm = {0, 5, 9, 13, 17};
        for (int i : palm) {
            o.cx += h[i * 3];
            o.cy += h[i * 3 + 1];
        }
        o.cx /= palm.length;
        o.cy /= palm.length;
        return o;
    }

    /** One hand's measures. */
    static final class Hand {
        final double[] q = new double[SHAPE], u = new double[3], n = new double[3];
        double cx, cy, s;
    }

    /**
     * Features of a whole grid sequence: dom / second hand per frame (null = missing), mirrored
     * (x negated) when {@code mirror}. Movement direction needs both neighbours of a frame, so the
     * first and last frames get none.
     */
    static HandFeatures[] sequence(double[][] dom, double[][] nd, boolean mirror, double rateHz, double v0, double zWeight) {
        int g = dom.length;
        HandFeatures[] out = new HandFeatures[g];
        for (int k = 0; k < g; k++) out[k] = frame(dom[k], nd[k], mirror, zWeight);
        for (int k = 1; k + 1 < g; k++) velocity(out[k - 1], out[k], out[k + 1], rateHz, v0);
        return out;
    }

    /** One frame's features without the movement direction (set by {@link #velocity}). */
    static HandFeatures frame(double[] dom, double[] nd, boolean mirror, double zWeight) {
        HandFeatures f = new HandFeatures();
        if (dom != null) {
            Hand h = measure(mirror ? flip(dom) : dom, zWeight);
            f.pd = true;
            System.arraycopy(h.q, 0, f.q, 0, SHAPE);
            System.arraycopy(h.u, 0, f.u, 0, 3);
            System.arraycopy(h.n, 0, f.n, 0, 3);
            f.cx = h.cx;
            f.cy = h.cy;
            f.s = h.s;
        }
        if (nd != null) {
            Hand h = measure(mirror ? flip(nd) : nd, zWeight);
            f.pn = true;
            f.ncx = h.cx;
            f.ncy = h.cy;
            if (f.pd) {
                f.rx = (h.cx - f.cx) / f.s;
                f.ry = (h.cy - f.cy) / f.s;
                System.arraycopy(h.u, 0, f.nu, 0, 3);
                System.arraycopy(h.n, 0, f.nn, 0, 3);
            }
        }
        return f;
    }

    /** Palm-centre velocity at {@code cur} from its neighbours, in palm sizes per second, saturated. */
    static void velocity(HandFeatures prev, HandFeatures cur, HandFeatures next, double rateHz, double v0) {
        cur.gx = 0;
        cur.gy = 0;
        // Like the reference implementation, a frame between two seen ones gets a direction even
        // if it is missing itself; such frames are never compared (their cost is the miss cost).
        if (!(prev.pd && next.pd) || !cur.pd) return;
        double dt = 2.0 / rateHz;
        double vx = (next.cx - prev.cx) / dt / cur.s, vy = (next.cy - prev.cy) / dt / cur.s;
        double norm = Math.sqrt(vx * vx + vy * vy + v0 * v0);
        cur.gx = vx / norm;
        cur.gy = vy / norm;
    }

    static double[] flip(double[] h) {
        double[] g = h.clone();
        for (int i = 0; i < g.length; i += 3) g[i] = -g[i];
        return g;
    }

    private static double dist3(double[] h, int a, int b) {
        double dx = h[a * 3] - h[b * 3], dy = h[a * 3 + 1] - h[b * 3 + 1], dz = h[a * 3 + 2] - h[b * 3 + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] sub(double[] h, int a, int b) {
        return new double[]{h[a * 3] - h[b * 3], h[a * 3 + 1] - h[b * 3 + 1], h[a * 3 + 2] - h[b * 3 + 2]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void normalize(double[] v) {
        double l = Math.max(Math.sqrt(dot(v, v)), 1e-9);
        for (int k = 0; k < 3; k++) v[k] /= l;
    }
}
