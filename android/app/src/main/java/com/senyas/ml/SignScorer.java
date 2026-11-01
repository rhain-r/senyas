package com.senyas.ml;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Scores live signing against reference performances of one sign and says what to correct.
 *
 * A sign is judged on the parameters FSL teachers name, each on its own:
 *   handshape    finger joints in the hand's own 3D palm frame, in palm sizes
 *   orientation  where the fingers point and where the palm faces
 *   movement     the dominant hand's path (palm sizes, centred on its own mean), its size, and
 *                its direction at each moment (a sign made backwards does not pass)
 *   two hands    two-handed signs: the second hand is there, placed and turned as in the
 *                references; one-handed signs: the second hand does not move along
 *
 * The live stream is aligned with each whole reference by open-begin / open-end dynamic time
 * warping: a reference frame takes one to three live frames, or two reference frames share one
 * (a learner may be 3x slower or 2x faster), so only the whole movement can match and a held pose
 * cannot. Along the best alignment every parameter gets its own distance, and each distance is
 * turned into a 0-100 sub-score with a tolerance learned from the references themselves (how much
 * different signers of the sign differ). The weakest sub-score is the score.
 *
 * MediaPipe's Left/Right labels are ignored (they flip mid-sign): hands are followed by position
 * ({@link HandSlots}), and each match tries both hands as the dominant one, as is and mirrored, so
 * label flips, a resting second hand and left-handed learners need no special case. Everything
 * runs on a fixed time grid, so 7-30 fps cameras look alike.
 *
 * Designed and tuned in Python against FSL-105 (leave-one-signer-out: right sign by an unseen
 * signer passes about 85-90%, a different sign about 2%, a held pose never); this port is checked
 * against that implementation by SignScorerParityTest.
 *
 * Coordinates: x mirrored like the preview, y in units of the image width, z as MediaPipe gives.
 */
public final class SignScorer {
    /** The room's pass mark. */
    public static final int PASS = 85;
    /** Image height over width of the phone's upright camera analysis (640x480 turned upright). */
    public static final float PHONE_ASPECT = 4f / 3f;

    static final double RATE_HZ = 10.0;       // time grid
    static final double GAP_MS = 350.0;       // a hand missing up to this long is interpolated
    static final double DUP_DIST = 1.0;       // two detections closer than this (palm sizes) are one hand
    static final double STALE_MS = 1000.0;    // a slot not seen this long forgets where its hand was
    static final double V0 = 5.0;             // palm sizes/s at which the movement direction saturates
    static final double Z_W = 0.5;            // weight of depth in the handshape
    static final double SIG_HS = 0.45, SIG_OR = 0.80, SIG_VEL = 1.00, SIG_REL = 2.00; // alignment cost scales
    static final double MISS = 3.0;           // alignment cost of a frame without the dominant hand
    static final double REL_MISS = 6.0;       // second-hand error when it is missing but should be there
    static final double STEP_PEN = 0.3;       // cost of a non-diagonal alignment step
    static final double SKIP_MS = 300.0;      // reference start / end that may go unmatched where the hand enters / leaves
    static final double SKIP_PEN = 1.0;       // cost per unmatched reference frame
    static final double K = 1.25;             // a parameter scores 85 at K x its tolerance
    static final double ONE_HAND_TOL = 0.35;  // one-handed signs: tolerated second-hand activity
    static final int MATCH_EVERY = 3;         // grid frames between whole-sign matches
    static final int TOP = 3;                 // references fully evaluated per match
    /** Tolerance floors per parameter (handshape, orientation, movement, size, two hands, direction). */
    static final double[] FLOOR = {0.15, 0.15, 0.5, Math.log(1.3), 0.8, 0.35};
    /** Direction counts only where the reference clearly moves (saturated speed at least this). */
    static final double DIR_MIN_G = 0.15;
    /** A score with the dominant hand seen in less of the sign than this is capped at 50. */
    static final double PRESENT_MIN = 0.7;
    /** Variants tried live: (slot A, slot B) and (B, A) as dominant / second hand, each as is and mirrored. */
    static final int VARIANTS = 4;
    /** The camera guide, in image units, that hands should stay inside when the screen does not say. */
    static final float EDGE_MIN = 0.06f, EDGE_MAX = 0.94f;
    static final float[] DEFAULT_GUIDE = {EDGE_MIN, EDGE_MIN, EDGE_MAX, EDGE_MAX};
    /** Tips come from the best attempt within this long. */
    static final long RECENT_MS = 2500;
    /** A reference is cut at a tracking gap longer than this. */
    static final long MAX_REF_GAP_MS = 700;

    private static final Tip.Part[] PARTS = Tip.Part.values();

    private final Pack pack;
    private final int capacity;
    private volatile float[] guide = DEFAULT_GUIDE;

    // Live state (reset per attempt).
    private HandSlots slots;
    private TimeGrid grid;
    private Matcher matcher;
    private HandFeatures[] previous, pending;
    private int segmentsDone;
    private long lastFrameMs = Long.MIN_VALUE;
    private Result lastResult, bestResult;
    private final ArrayDeque<Result> recent = new ArrayDeque<>();
    private int bestScore = -1;

    public SignScorer(List<ReferenceClip> clips) {
        List<Reference> refs = new ArrayList<>();
        if (clips != null) {
            for (ReferenceClip c : clips) {
                Reference r = Reference.of(c);
                if (r != null) refs.add(r);
            }
        }
        pack = refs.isEmpty() ? null : Pack.calibrated(refs);
        if (pack != null) pack.k = passK(clips);
        int longest = 0;
        for (Reference r : refs) longest = Math.max(longest, r.m);
        capacity = 3 * longest + 8;
        reset();
    }

    /** The clips' shared pass threshold, or the default K when they do not all carry the same one. */
    private static double passK(List<ReferenceClip> clips) {
        double k = Double.NaN;
        for (ReferenceClip c : clips) {
            if (Double.isNaN(c.passK) || (!Double.isNaN(k) && k != c.passK)) return K;
            k = c.passK;
        }
        return Double.isNaN(k) ? K : k;
    }

    public boolean hasReference() {
        return pack != null;
    }

    /** Whether most references use both hands. */
    public boolean twoHanded() {
        return pack != null && pack.two;
    }

    /** Best score in this attempt, or -1. */
    public int bestScore() {
        return bestScore;
    }

    /** This sign's pass threshold in tolerance units (tests). */
    double passThreshold() {
        return pack == null ? K : pack.k;
    }

    /** Learned tolerance per parameter (tests). */
    double[] tolerances() {
        return pack == null ? null : pack.tau.clone();
    }

    public void reset() {
        slots = new HandSlots(DUP_DIST, STALE_MS);
        grid = new TimeGrid(RATE_HZ, GAP_MS);
        matcher = pack == null ? null : new Matcher(pack, capacity);
        previous = null;
        pending = null;
        segmentsDone = 0;
        lastFrameMs = Long.MIN_VALUE;
        lastResult = null;
        bestResult = null;
        recent.clear();
        bestScore = -1;
    }

    // ------------------------------------------------------------------ live

    /**
     * Call once per camera frame with MediaPipe's raw landmarks (63 floats per hand, not mirrored,
     * highest confidence first) and the analysed image's height over width.
     */
    public Feedback onFrame(long tMs, List<float[]> hands, float aspect) {
        List<double[]> iso = new ArrayList<>(hands.size());
        for (float[] h : hands) iso.add(toScorer(h, aspect));
        double[][] cur = track(tMs, iso);
        return feedback(hands, iso, cur);
    }

    /** One frame of hands already in scorer coordinates (highest confidence first); returns the two slots. */
    double[][] track(long tMs, List<double[]> hands) {
        if (lastFrameMs != Long.MIN_VALUE && tMs <= lastFrameMs) return new double[2][]; // out of order: ignored
        lastFrameMs = tMs;
        List<double[]> iso = new ArrayList<>(merged(hands));
        Collections.sort(iso, (a, b) -> Double.compare(a[0], b[0]));
        double[][] cur = slots.push(tMs, iso);
        grid.add(tMs, cur[0], cur[1]);
        // Match after every tick: a long camera gap releases many ticks at once, more than the
        // matcher's ring buffer holds.
        for (double[][] tick = grid.poll(false); tick != null; tick = grid.poll(false)) {
            onTick(tick);
            if (matcher != null) runSegments(false);
        }
        return cur;
    }

    /** Ends a recording: scores what is left (for offline replay and tests). */
    Result finish() {
        for (double[][] tick = grid.flush(); tick != null; tick = grid.flush()) {
            onTick(tick);
            if (matcher != null) runSegments(false);
        }
        if (pending != null && matcher != null) {
            matcher.addRow(pending);
            matcher.finishEnds();
        }
        pending = null;
        if (matcher != null) runSegments(true);
        return lastResult;
    }

    /** Best result of an offline replay of a whole recording in scorer coordinates (tests). */
    Result replay(long[] ts, List<List<double[]>> frames) {
        reset();
        for (int i = 0; i < ts.length; i++) track(ts[i], frames.get(i));
        finish();
        return bestResult;
    }

    private void onTick(double[][] tick) {
        HandFeatures[] feats = new HandFeatures[VARIANTS];
        for (int v = 0; v < VARIANTS; v++) {
            double[] d = v < 2 ? tick[0] : tick[1], n = v < 2 ? tick[1] : tick[0];
            feats[v] = HandFeatures.frame(d, n, (v & 1) == 1, Z_W);
        }
        if (pending != null) {
            for (int v = 0; v < VARIANTS; v++) {
                if (previous != null) HandFeatures.velocity(previous[v], pending[v], feats[v], RATE_HZ, V0);
            }
            if (matcher != null) matcher.addRow(pending);
        }
        previous = pending;
        pending = feats;
    }

    /** Whole-sign matches for every finished group of MATCH_EVERY grid frames. */
    private void runSegments(boolean flush) {
        while (true) {
            int lo = segmentsDone * MATCH_EVERY, hi = lo + MATCH_EVERY;
            if (matcher.endsDone < hi) {
                if (!flush || matcher.endsDone <= lo) return;
                hi = matcher.endsDone;
            }
            Result r = matcher.step(lo, hi);
            segmentsDone++;
            if (r == null) continue;
            lastResult = r;
            if (bestResult == null || r.score > bestResult.score) bestResult = r;
            bestScore = Math.max(bestScore, r.score);
            recent.addLast(r);
            while (recent.size() > 1 && r.row - recent.peekFirst().row > RECENT_MS * RATE_HZ / 1000) recent.removeFirst();
        }
    }

    private Feedback feedback(List<float[]> raw, List<double[]> iso, double[][] cur) {
        boolean[] fingerOk = {true, true, true, true, true};
        if (raw.isEmpty()) return Feedback.noHand();
        List<Tip> tips = new ArrayList<>();
        Feedback.Arrow arrow = edgeArrow(raw, guide);
        if (arrow != Feedback.Arrow.NONE) {
            Tip.Dir d = arrow == Feedback.Arrow.UP ? Tip.Dir.UP : arrow == Feedback.Arrow.DOWN ? Tip.Dir.DOWN
                    : arrow == Feedback.Arrow.LEFT ? Tip.Dir.LEFT : Tip.Dir.RIGHT;
            tips.add(new Tip(Tip.Kind.EDGE, -1, d, null, null));
        }
        Result shown = null;
        for (Result r : recent) if (shown == null || r.score > shown.score) shown = r;
        if (shown != null) {
            tips.addAll(shown.tips);
            for (Tip t : shown.tips) {
                if (t.kind == Tip.Kind.STRAIGHTEN_FINGER || t.kind == Tip.Kind.BEND_FINGER) fingerOk[t.finger] = false;
            }
        }
        // The hand the advice is about: the dominant slot of the alignment shown.
        float[] dominant = null;
        double[] slot = shown == null ? null : cur[shown.detail.variant < 2 ? 0 : 1];
        for (int i = 0; i < iso.size() && slot != null; i++) if (iso.get(i) == slot) dominant = raw.get(i);
        if (dominant == null) Arrays.fill(fingerOk, true); // the advised hand is not in this frame: no red fingers
        int score = lastResult == null ? -1 : lastResult.score;
        return new Feedback(true, score, tips, arrow, fingerOk, dominant);
    }

    /**
     * The part of the camera image the learner sees inside the on-screen guide, as {left, top,
     * right, bottom} in image units (the preview crops the image to fit its card). Defaults to the
     * whole image less a margin.
     */
    public void setGuide(float[] box) {
        guide = box == null ? DEFAULT_GUIDE : box.clone();
    }

    /**
     * Which way to move a hand whose palm is outside the guide (box in image units; the guide is
     * centred, so mirroring does not change it). Fingertips may reach past the guide, as they do
     * in signs made at the forehead or chin.
     */
    static Feedback.Arrow edgeArrow(List<float[]> raw, float[] box) {
        int[] palm = {0, 5, 9, 13, 17};
        for (float[] h : raw) {
            float x = 0, y = 0;
            for (int i : palm) {
                x += 1f - h[i * 3];
                y += h[i * 3 + 1];
            }
            x /= palm.length;
            y /= palm.length;
            if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) continue;
            return y < box[1] ? Feedback.Arrow.DOWN : y > box[3] ? Feedback.Arrow.UP
                    : x < box[0] ? Feedback.Arrow.RIGHT : Feedback.Arrow.LEFT;
        }
        return Feedback.Arrow.NONE;
    }

    // ------------------------------------------------------------------ input conversion

    /** MediaPipe landmarks -> scorer coordinates (mirrored x, y in image widths). */
    static double[] toScorer(float[] lm, float aspect) {
        double[] out = new double[Landmarks.FLOATS];
        for (int i = 0; i < Landmarks.POINTS; i++) {
            out[i * 3] = 1.0 - lm[i * 3];
            out[i * 3 + 1] = lm[i * 3 + 1] * (double) aspect;
            out[i * 3 + 2] = lm[i * 3 + 2];
        }
        return out;
    }

    /** At most two hands; a second detection on top of the first is the same hand. */
    private static List<double[]> merged(List<double[]> hands) {
        if (hands.size() < 2) return hands;
        HandSlots probe = new HandSlots(DUP_DIST, STALE_MS);
        double[][] one = probe.push(0, hands.subList(0, 2));
        List<double[]> out = new ArrayList<>(2);
        if (one[0] != null) out.add(one[0]);
        if (one[1] != null) out.add(one[1]);
        return out;
    }

    /**
     * A recording (frames of MediaPipe hands, highest confidence first) as a reference: hands
     * followed through it and the more active one made dominant.
     */
    public static ReferenceClip prepare(String clip, long[] t, List<List<float[]>> frames, float aspect) {
        HandSlots tracker = new HandSlots(DUP_DIST, STALE_MS);
        int n = t.length;
        double[][][] seq = new double[n][][];
        for (int i = 0; i < n; i++) {
            List<double[]> iso = new ArrayList<>();
            for (float[] h : frames.get(i)) iso.add(toScorer(h, aspect));
            seq[i] = tracker.push(t[i], iso);
        }
        double[] act = new double[2];
        for (int s = 0; s < 2; s++) {
            double[] lastPos = null;
            int seen = 0;
            double path = 0;
            for (double[][] f : seq) {
                if (f[s] == null) continue;
                seen++;
                if (lastPos != null) path += Math.hypot(f[s][0] - lastPos[0], f[s][1] - lastPos[1]) / HandFeatures.palmSize(f[s]);
                lastPos = new double[]{f[s][0], f[s][1]};
            }
            act[s] = seen * 0.1 + path;
        }
        int d = act[1] > act[0] ? 1 : 0;
        long[] rel = new long[n];
        float[][] dom = new float[n][], nd = new float[n][];
        for (int i = 0; i < n; i++) {
            rel[i] = t[i] - t[0];
            dom[i] = toFloats(seq[i][d]);
            nd[i] = toFloats(seq[i][1 - d]);
        }
        return new ReferenceClip(clip, -1, rel, dom, nd);
    }

    private static float[] toFloats(double[] h) {
        if (h == null) return null;
        float[] out = new float[h.length];
        for (int k = 0; k < h.length; k++) out[k] = (float) h[k];
        return out;
    }

    private static double[] toDoubles(float[] h) {
        if (h == null) return null;
        double[] out = new double[h.length];
        for (int k = 0; k < h.length; k++) out[k] = h[k];
        return out;
    }

    // ------------------------------------------------------------------ references

    /** One reference on the time grid, with its features. */
    static final class Reference {
        final String name;
        final int signer, m;
        final HandFeatures[] f;
        /** Palm centre over the reference's median palm size (NaN where the hand is missing). */
        final double[] posx, posy;
        final boolean two;
        /** The grid itself (dominant, second hand), to calibrate against the other references. */
        final double[][] gridDom, gridNd;

        private Reference(String name, int signer, HandFeatures[] f, double[] posx, double[] posy, boolean two,
                          double[][] gridDom, double[][] gridNd) {
            this.name = name;
            this.signer = signer;
            this.m = f.length;
            this.f = f;
            this.posx = posx;
            this.posy = posy;
            this.two = two;
            this.gridDom = gridDom;
            this.gridNd = gridNd;
        }

        /**
         * The longest run of frames with the dominant hand (a stray detection before or after a
         * long gap in tracking is not part of the sign); null if too short.
         */
        static Reference of(ReferenceClip c) {
            int first = -1, last = -1, count = 0;
            int runFirst = -1, runLast = -1, runCount = 0;
            for (int i = 0; i < c.size(); i++) {
                if (c.dom[i] == null) continue;
                if (runFirst < 0 || c.t[i] - c.t[runLast] > MAX_REF_GAP_MS) {
                    runFirst = i;
                    runCount = 0;
                }
                runLast = i;
                runCount++;
                if (runCount > count) {
                    first = runFirst;
                    last = runLast;
                    count = runCount;
                }
            }
            if (count < 3) return null;
            TimeGrid g = new TimeGrid(RATE_HZ, GAP_MS);
            for (int i = first; i <= last; i++) g.add(c.t[i], toDoubles(c.dom[i]), toDoubles(c.nd[i]));
            List<double[]> ds = new ArrayList<>(), ns = new ArrayList<>();
            for (double[][] tick = g.flush(); tick != null; tick = g.flush()) {
                ds.add(tick[0]);
                ns.add(tick[1]);
            }
            double[][] dom = ds.toArray(new double[0][]), nd = ns.toArray(new double[0][]);
            HandFeatures[] f = HandFeatures.sequence(dom, nd, false, RATE_HZ, V0, Z_W);
            List<Double> sizes = new ArrayList<>();
            int withSecond = 0;
            for (HandFeatures h : f) {
                if (!h.pd) continue;
                sizes.add(h.s);
                if (h.pn) withSecond++;
            }
            if (sizes.size() < 3) return null;
            double S = median(sizes);
            double[] px = new double[f.length], py = new double[f.length];
            for (int k = 0; k < f.length; k++) {
                px[k] = f[k].cx / S;
                py[k] = f[k].cy / S;
            }
            boolean two = withSecond / (double) sizes.size() >= 0.5;
            return new Reference(c.clip, c.signer, f, px, py, two, dom, nd);
        }
    }

    /** The references of one sign with their learned tolerances. */
    static final class Pack {
        final List<Reference> refs;
        boolean two;
        /** Per parameter: the typical distance between different signers of this sign. */
        final double[] tau = new double[PARTS.length];
        /** A parameter scores PASS at k x its tolerance (per sign, see ReferenceClip#passK). */
        double k = K;

        Pack(List<Reference> refs) {
            this.refs = refs;
            int two = 0;
            for (Reference r : refs) if (r.two) two++;
            this.two = two * 2 > refs.size();
        }

        /**
         * Tolerances: each reference is matched against the references by other signers; the
         * median distance per parameter (never below its floor) is how much real signers differ.
         */
        static Pack calibrated(List<Reference> refs) {
            Pack p = new Pack(refs);
            List<List<Double>> dists = new ArrayList<>();
            for (int k = 0; k < PARTS.length; k++) dists.add(new ArrayList<>());
            for (Reference r : refs) {
                List<Reference> others = new ArrayList<>();
                for (Reference o : refs) if (o != r && (r.signer < 0 || o.signer != r.signer)) others.add(o);
                if (others.isEmpty()) continue;
                Pack po = new Pack(others);
                po.two = p.two;
                Detail d = matchWhole(po, r.gridDom, r.gridNd);
                if (d == null) continue;
                for (int k = 0; k < PARTS.length; k++) dists.get(k).add(d.d[k]);
            }
            for (int k = 0; k < PARTS.length; k++) {
                List<Double> v = new ArrayList<>();
                for (double x : dists.get(k)) if (!Double.isNaN(x) && !Double.isInfinite(x)) v.add(x);
                p.tau[k] = Math.max(FLOOR[k], v.isEmpty() ? FLOOR[k] : median(v));
            }
            if (!p.two) p.tau[Tip.Part.TWO_HANDS.ordinal()] = ONE_HAND_TOL;
            return p;
        }
    }

    /** The best alignment of a whole sequence with any of the references (calibration). */
    static Detail matchWhole(Pack p, double[][] dom, double[][] nd) {
        HandFeatures[][] vs = new HandFeatures[VARIANTS][];
        for (int v = 0; v < VARIANTS; v++) {
            double[][] d = v < 2 ? dom : nd, n = v < 2 ? nd : dom;
            vs[v] = HandFeatures.sequence(d, n, (v & 1) == 1, RATE_HZ, V0, Z_W);
        }
        int rows = dom.length;
        Matcher mt = new Matcher(p, rows + 4);
        HandFeatures[] row = new HandFeatures[VARIANTS];
        for (int i = 0; i < rows; i++) {
            for (int v = 0; v < VARIANTS; v++) row[v] = vs[v][i];
            mt.addRow(row.clone());
        }
        mt.finishEnds();
        return mt.bestWhole();
    }

    // ------------------------------------------------------------------ matching

    /** One evaluated alignment. */
    static final class Result {
        int score;
        final double[] sub = new double[PARTS.length];
        Detail detail;
        boolean mirrored;
        String ref;
        /** Grid frame where the alignment ends. */
        int row;
        List<Tip> tips = Collections.emptyList();

        @Override
        public String toString() {
            return "score " + score + " sub " + Arrays.toString(sub) + " ref " + ref + " tips " + tips;
        }
    }

    /** Per-parameter distances along one alignment, and what the corrections need. */
    static final class Detail {
        final double[] d = new double[PARTS.length];
        double sizeRatio, ndSeen = 1, present;
        double[] relErr;
        int variant, ref, m;
        int[] pj, pi;
        double[] pw;
        boolean[] okd;
        double[] hsj, orj, dirj;
        int[] okIdx;
        double[][] pl0, pr0;
    }

    /**
     * Incremental dynamic time warping of the live variants against every reference: one row per
     * grid frame, kept in ring buffers long enough to trace back any alignment that ends now.
     */
    static final class Matcher {
        final Pack pack;
        final int refsN, batches, cap;
        final int[] m, jmax;
        final HandFeatures[][] live;
        final double[][][] acc, cost;
        final byte[][][] ch;
        final double[][] ends;
        final int[][] endj;
        int rows, endsDone;
        private final double[] tmp = new double[3];

        Matcher(Pack pack, int cap) {
            this.pack = pack;
            this.refsN = pack.refs.size();
            this.batches = VARIANTS * refsN;
            this.cap = cap;
            m = new int[batches];
            jmax = new int[batches];
            int skip = (int) Math.round(SKIP_MS * RATE_HZ / 1000.0);
            acc = new double[batches][][];
            cost = new double[batches][][];
            ch = new byte[batches][][];
            for (int b = 0; b < batches; b++) {
                m[b] = pack.refs.get(b % refsN).m;
                jmax[b] = Math.min(skip, m[b] / 5);
                acc[b] = new double[cap][m[b]];
                cost[b] = new double[cap][m[b]];
                ch[b] = new byte[cap][m[b]];
            }
            live = new HandFeatures[cap][];
            ends = new double[cap][batches];
            endj = new int[cap][batches];
        }

        void addRow(HandFeatures[] feats) {
            int i = rows, s = i % cap;
            live[s] = feats;
            for (int b = 0; b < batches; b++) {
                int v = b / refsN;
                Reference ref = pack.refs.get(b % refsN);
                HandFeatures L = feats[v];
                boolean startOk = i >= 1 && L.pd && !live[(i - 1) % cap][v].pd;
                double[] c = cost[b][s], A = acc[b][s];
                byte[] k = ch[b][s];
                int mb = m[b];
                for (int j = 0; j < mb; j++) c[j] = local(L, ref, j, pack.two, tmp);
                A[0] = c[0];
                k[0] = 0;
                double[] A1 = i >= 1 ? acc[b][(i - 1) % cap] : null;
                double[] A2 = i >= 2 ? acc[b][(i - 2) % cap] : null;
                double[] A3 = i >= 3 ? acc[b][(i - 3) % cap] : null;
                double[] C1 = i >= 1 ? cost[b][(i - 1) % cap] : null;
                double[] C2 = i >= 2 ? cost[b][(i - 2) % cap] : null;
                for (int j = 1; j < mb; j++) {
                    if (i == 0) {
                        A[j] = Double.POSITIVE_INFINITY;
                        k[j] = 0;
                        continue;
                    }
                    double best = A1[j - 1] + c[j];
                    byte arg = 0;
                    if (A2 != null) {
                        double st = A2[j - 1] + (C1[j] + c[j]) / 2 + STEP_PEN;
                        if (st < best) {
                            best = st;
                            arg = 1;
                        }
                    }
                    if (A3 != null) {
                        double st = A3[j - 1] + (C2[j] + C1[j] + c[j]) / 3 + 2 * STEP_PEN;
                        if (st < best) {
                            best = st;
                            arg = 2;
                        }
                    }
                    if (j >= 2) {
                        double st = A1[j - 2] + c[j - 1] + c[j] + 2 * STEP_PEN;
                        if (st < best) {
                            best = st;
                            arg = 3;
                        }
                    }
                    if (startOk && j <= jmax[b]) {
                        double st = c[j] + j * SKIP_PEN;
                        if (st < best) {
                            best = st;
                            arg = 4;
                        }
                    }
                    A[j] = best;
                    k[j] = arg;
                }
            }
            rows++;
            if (rows >= 2) computeEnds(rows - 2, true);
        }

        /** The last row has no next frame: it cannot end where the hand leaves. */
        void finishEnds() {
            if (rows >= 1 && endsDone < rows) computeEnds(rows - 1, false);
        }

        /** Mean alignment cost of each reference ending at row i (with the end skip where the hand leaves). */
        private void computeEnds(int i, boolean hasNext) {
            int s = i % cap;
            for (int b = 0; b < batches; b++) {
                int v = b / refsN, mb = m[b];
                double[] A = acc[b][s];
                double e = A[mb - 1];
                int ej = mb - 1;
                boolean endOk = hasNext && live[s][v].pd && !live[(i + 1) % cap][v].pd;
                if (endOk) {
                    for (int sk = 1; sk <= jmax[b]; sk++) {
                        double e2 = A[Math.max(mb - 1 - sk, 0)] + sk * SKIP_PEN;
                        if (e2 < e) {
                            e = e2;
                            ej = mb - 1 - sk;
                        }
                    }
                }
                ends[s][b] = e / mb;
                endj[s][b] = ej;
            }
            endsDone = i + 1;
        }

        /**
         * A live match over the alignments ending at rows lo..hi-1: the TOP references with the
         * lowest cost there are evaluated and the best scoring one is returned.
         */
        Result step(int lo, int hi) {
            double[] best = new double[refsN];
            int[] endi = new int[refsN], varOf = new int[refsN];
            Integer[] order = new Integer[refsN];
            int floor = Math.max(0, rows - cap); // older rows have been overwritten in the ring
            for (int r = 0; r < refsN; r++) {
                order[r] = r;
                best[r] = Double.POSITIVE_INFINITY;
                endi[r] = lo;
                for (int i = Math.max(lo, floor); i < hi; i++) {
                    double[] e = ends[i % cap];
                    double c = Double.POSITIVE_INFINITY;
                    int vb = 0;
                    for (int v = 0; v < VARIANTS; v++) {
                        if (e[v * refsN + r] < c) {
                            c = e[v * refsN + r];
                            vb = v;
                        }
                    }
                    if (c < best[r]) {
                        best[r] = c;
                        endi[r] = i;
                        varOf[r] = vb;
                    }
                }
            }
            Arrays.sort(order, (a, b) -> Double.compare(best[a], best[b]));
            Result out = null;
            for (int t = 0; t < Math.min(TOP, refsN); t++) {
                int r = order[t];
                if (Double.isInfinite(best[r]) || Double.isNaN(best[r])) continue;
                int v = varOf[r], b = v * refsN + r, i = endi[r];
                Detail d = detail(b, i, endj[i % cap][b]);
                if (d == null) continue;
                Result res = result(d);
                res.row = i;
                if (out == null || res.score > out.score) out = res;
            }
            if (out != null) out.tips = explain(out);
            return out;
        }

        /** Calibration: the lowest-cost alignment over every end, reference and variant. */
        Detail bestWhole() {
            double best = Double.POSITIVE_INFINITY;
            int bb = -1, bi = -1;
            for (int b = 0; b < batches; b++) {
                for (int i = Math.max(0, rows - cap); i < endsDone; i++) {
                    double e = ends[i % cap][b];
                    if (e < best) {
                        best = e;
                        bb = b;
                        bi = i;
                    }
                }
            }
            if (bb < 0) return null;
            return detail(bb, bi, endj[bi % cap][bb]);
        }

        /** Traces the alignment back and measures every parameter along it. */
        Detail detail(int b, int i, int j) {
            int v = b / refsN, r = b % refsN;
            Reference ref = pack.refs.get(r);
            int mb = m[b];
            // Trace back: (reference frame, live row, weight).
            List<int[]> steps = new ArrayList<>();
            List<Double> weights = new ArrayList<>();
            boolean started = false;
            int floor = Math.max(0, rows - cap);
            while (j > 0) {
                if (i < floor) return null; // the alignment starts in rows the ring no longer holds
                int k = ch[b][i % cap][j];
                if (k == 4) {
                    steps.add(new int[]{j, i});
                    weights.add(1.0);
                    started = true;
                    break;
                }
                if (k == 0) {
                    steps.add(new int[]{j, i});
                    weights.add(1.0);
                    i--;
                    j--;
                } else if (k == 1) {
                    steps.add(new int[]{j, i - 1});
                    steps.add(new int[]{j, i});
                    weights.add(0.5);
                    weights.add(0.5);
                    i -= 2;
                    j--;
                } else if (k == 2) {
                    steps.add(new int[]{j, i - 2});
                    steps.add(new int[]{j, i - 1});
                    steps.add(new int[]{j, i});
                    for (int q = 0; q < 3; q++) weights.add(1 / 3.0);
                    i -= 3;
                    j--;
                } else {
                    steps.add(new int[]{j, i});
                    steps.add(new int[]{j - 1, i});
                    weights.add(1.0);
                    weights.add(1.0);
                    i--;
                    j -= 2;
                }
            }
            if (!started && j == 0) {
                if (i < floor) return null;
                steps.add(new int[]{0, i});
                weights.add(1.0);
            }
            int n = steps.size();
            int[] pj = new int[n], pi = new int[n];
            double[] pw = new double[n];
            for (int q = 0; q < n; q++) {
                pj[q] = steps.get(n - 1 - q)[0];
                pi[q] = steps.get(n - 1 - q)[1];
                if (pi[q] < floor) return null;
                pw[q] = weights.get(n - 1 - q);
            }
            return measure(v, r, ref, mb, pj, pi, pw);
        }

        private HandFeatures liveAt(int row, int v) {
            return live[row % cap][v];
        }

        private Detail measure(int v, int r, Reference ref, int mb, int[] pj, int[] pi, double[] pw) {
            int n = pj.length;
            double[] hsj = new double[mb], orj = new double[mb], relj = new double[mb], seen = new double[mb];
            double[] dirj = new double[mb];
            double[] clx = new double[mb], cly = new double[mb], rlx = new double[mb], rly = new double[mb];
            boolean[] miss = new boolean[mb];
            int[] count = new int[mb];
            double[] loc = new double[3];
            List<Double> sizes = new ArrayList<>();
            double wSum = 0, wPn = 0, wAbsent = 0;
            int pnEntries = 0;
            for (int q = 0; q < n; q++) {
                HandFeatures L = liveAt(pi[q], v);
                int j = pj[q];
                double w = pw[q];
                local(L, ref, j, pack.two, loc);
                hsj[j] += w * loc[0];
                orj[j] += w * loc[1];
                HandFeatures R = ref.f[j];
                dirj[j] += w * Math.hypot(L.gx - R.gx, L.gy - R.gy);
                relj[j] += w * loc[2];
                count[j]++;
                if (!L.pd) miss[j] = true;
                if (L.pd) {
                    sizes.add(L.s);
                    clx[j] += w * L.cx;
                    cly[j] += w * L.cy;
                }
                if (L.pn) {
                    seen[j] += w;
                    rlx[j] += w * L.rx;
                    rly[j] += w * L.ry;
                    wPn += w;
                    pnEntries++;
                }
                wSum += w;
                if (!L.pd) wAbsent += w;
            }
            boolean[] okd = new boolean[mb];
            int okCount = 0;
            for (int j = 0; j < mb; j++) {
                okd[j] = !miss[j] && ref.f[j].pd && count[j] > 0;
                if (okd[j]) okCount++;
            }
            if (okCount < 2) return null;
            Detail d = new Detail();
            d.variant = v;
            d.ref = r;
            d.m = mb;
            d.pj = pj;
            d.pi = pi;
            d.pw = pw;
            d.okd = okd;
            d.hsj = hsj;
            d.dirj = dirj;
            d.orj = orj;
            int[] ok = new int[okCount];
            for (int j = 0, q = 0; j < mb; j++) if (okd[j]) ok[q++] = j;
            d.okIdx = ok;
            double hs = 0, or = 0;
            for (int j : ok) {
                hs += hsj[j];
                or += orj[j];
            }
            d.d[0] = hs / okCount;
            d.d[1] = or / okCount;
            // Movement: palm-centre path in palm sizes, centred on its own mean, against the reference's.
            double S = median(sizes);
            double mlx = 0, mly = 0, mrx = 0, mry = 0;
            for (int j : ok) {
                mlx += clx[j] / S;
                mly += cly[j] / S;
                mrx += ref.posx[j];
                mry += ref.posy[j];
            }
            mlx /= okCount;
            mly /= okCount;
            mrx /= okCount;
            mry /= okCount;
            d.pl0 = new double[okCount][2];
            d.pr0 = new double[okCount][2];
            double err = 0, radL = 0, radR = 0;
            for (int q = 0; q < okCount; q++) {
                int j = ok[q];
                d.pl0[q][0] = clx[j] / S - mlx;
                d.pl0[q][1] = cly[j] / S - mly;
                d.pr0[q][0] = ref.posx[j] - mrx;
                d.pr0[q][1] = ref.posy[j] - mry;
                err += Math.hypot(d.pl0[q][0] - d.pr0[q][0], d.pl0[q][1] - d.pr0[q][1]);
                radL += d.pl0[q][0] * d.pl0[q][0] + d.pl0[q][1] * d.pl0[q][1];
                radR += d.pr0[q][0] * d.pr0[q][0] + d.pr0[q][1] * d.pr0[q][1];
            }
            d.d[2] = err / okCount;
            radL = Math.sqrt(radL / okCount);
            radR = Math.sqrt(radR / okCount);
            d.sizeRatio = (radL + 0.1) / (radR + 0.1);
            d.d[3] = Math.abs(Math.log(d.sizeRatio));
            if (pack.two) {
                double rel = 0, nds = 0, ex = 0, ey = 0;
                int wr = 0, ok2 = 0;
                for (int j : ok) {
                    if (!ref.f[j].pn) continue;
                    wr++;
                    rel += relj[j];
                    nds += seen[j];
                    if (seen[j] > 0.5) {
                        ok2++;
                        ex += ref.f[j].rx - rlx[j] / seen[j];
                        ey += ref.f[j].ry - rly[j] / seen[j];
                    }
                }
                d.d[4] = wr > 0 ? rel / wr : 0;
                d.ndSeen = wr > 0 ? nds / wr : 1;
                d.relErr = ok2 > 0 ? new double[]{ex / ok2, ey / ok2} : null;
            } else {
                // One-handed sign: a second hand moving along is wrong, one resting in view is not.
                double frac = wPn / wSum;
                d.ndSeen = frac;
                if (pnEntries >= 3) {
                    double mx = 0, my = 0;
                    for (int q = 0; q < n; q++) {
                        HandFeatures L = liveAt(pi[q], v);
                        if (!L.pn) continue;
                        mx += L.ncx / S;
                        my += L.ncy / S;
                    }
                    mx /= pnEntries;
                    my /= pnEntries;
                    double var = 0;
                    for (int q = 0; q < n; q++) {
                        HandFeatures L = liveAt(pi[q], v);
                        if (!L.pn) continue;
                        double dx = L.ncx / S - mx, dy = L.ncy / S - my;
                        var += dx * dx + dy * dy;
                    }
                    double nrad = Math.sqrt(var / pnEntries);
                    d.d[4] = frac * nrad / Math.max(radL, 0.5);
                } else {
                    d.d[4] = 0;
                }
            }
            // Direction: does the hand move the way the references move at each aligned moment.
            double dir = 0;
            int moving = 0;
            for (int j : ok) {
                if (Math.hypot(ref.f[j].gx, ref.f[j].gy) < DIR_MIN_G) continue;
                dir += dirj[j];
                moving++;
            }
            d.d[5] = moving > 0 ? dir / moving : 0;
            d.present = 1.0 - wAbsent / wSum;
            return d;
        }

        private Result result(Detail d) {
            Result res = new Result();
            res.detail = d;
            res.mirrored = (d.variant & 1) == 1;
            res.ref = pack.refs.get(d.ref).name;
            double sc = Double.POSITIVE_INFINITY;
            for (int k = 0; k < PARTS.length; k++) {
                double x = d.d[k] / (pack.k * pack.tau[k]);
                res.sub[k] = 100.0 * Math.pow(0.85, x * x);
                sc = Math.min(sc, res.sub[k]);
            }
            if (d.present < PRESENT_MIN) sc = Math.min(sc, 50.0);
            res.score = (int) Math.rint(sc);
            return res;
        }

        /** What to correct, weakest parameters first (at most one tip once the sign passes). */
        private List<Tip> explain(Result res) {
            Detail d = res.detail;
            Reference ref = pack.refs.get(d.ref);
            boolean mir = res.mirrored, passed = res.score >= PASS;
            List<Tip> tips = new ArrayList<>();
            if (passed) tips.add(Tip.of(Tip.Kind.GOOD));
            if (d.present < PRESENT_MIN) tips.add(Tip.of(Tip.Kind.KEEP_IN_VIEW));
            Integer[] order = {0, 1, 2, 3, 4, 5};
            Arrays.sort(order, (a, b) -> Double.compare(res.sub[a], res.sub[b]));
            for (int np = 0; np < 2; np++) {
                int p = order[np];
                if (passed && (np > 0 || res.sub[p] >= 92)) break;
                if (!passed && np > 0 && res.sub[p] >= PASS) break;
                Tip.Part part = PARTS[p];
                if (part == Tip.Part.HANDSHAPE || part == Tip.Part.ORIENTATION) {
                    double[] per = part == Tip.Part.HANDSHAPE ? d.hsj : d.orj;
                    int j = d.okIdx[0];
                    for (int jj : d.okIdx) if (per[jj] > per[j]) j = jj;
                    Tip.When when = Tip.when(j, d.m);
                    double[] mean = meanLive(d, j, part == Tip.Part.HANDSHAPE ? 0 : 1);
                    HandFeatures R = ref.f[j];
                    if (part == Tip.Part.HANDSHAPE) {
                        int worst = 0;
                        double gap = -1;
                        for (int f = 0; f < HandFeatures.FINGERS.length; f++) {
                            double e = 0;
                            for (int pt : HandFeatures.FINGERS[f]) {
                                for (int c = 0; c < 3; c++) {
                                    double x = mean[pt * 3 + c] - R.q[pt * 3 + c];
                                    e += x * x;
                                }
                            }
                            e = Math.sqrt(e);
                            if (e > gap) {
                                worst = f;
                                gap = e;
                            }
                        }
                        int[] pts = HandFeatures.FINGERS[worst];
                        int tip = pts[pts.length - 1];
                        boolean straighten = norm3(mean, tip * 3) < norm3(R.q, tip * 3);
                        tips.add(new Tip(straighten ? Tip.Kind.STRAIGHTEN_FINGER : Tip.Kind.BEND_FINGER, worst, null, when, null));
                    } else {
                        double du = 0, dn = 0;
                        for (int c = 0; c < 3; c++) {
                            du += sq(mean[c] - R.u[c]);
                            dn += sq(mean[3 + c] - R.n[c]);
                        }
                        if (Math.sqrt(dn) >= Math.sqrt(du)) {
                            tips.add(new Tip(Tip.Kind.PALM_FACE, -1, Tip.direction(-R.n[0], -R.n[1], -R.n[2], mir), when, null));
                        } else {
                            tips.add(new Tip(Tip.Kind.POINT_FINGERS, -1, Tip.direction(R.u[0], R.u[1], R.u[2], mir), when, null));
                        }
                    }
                } else if (part == Tip.Part.MOVEMENT) {
                    int k = 0;
                    double far = -1;
                    for (int q = 0; q < d.okIdx.length; q++) {
                        double e = Math.hypot(d.pr0[q][0] - d.pl0[q][0], d.pr0[q][1] - d.pl0[q][1]);
                        if (e > far) {
                            far = e;
                            k = q;
                        }
                    }
                    int lo = Math.max(0, k - 1), hi = Math.min(d.okIdx.length, k + 2);
                    double vx = 0, vy = 0;
                    for (int q = lo; q < hi; q++) {
                        vx += d.pr0[q][0] - d.pl0[q][0];
                        vy += d.pr0[q][1] - d.pl0[q][1];
                    }
                    tips.add(new Tip(Tip.Kind.MOVE, -1, Tip.direction(vx / (hi - lo), vy / (hi - lo), 0, mir),
                            Tip.when(d.okIdx[k], d.m), null));
                } else if (part == Tip.Part.DIRECTION) {
                    int j = d.okIdx[0];
                    for (int jj : d.okIdx) if (d.dirj[jj] > d.dirj[j]) j = jj;
                    HandFeatures R = ref.f[j];
                    Tip.When when = Tip.when(j, d.m);
                    if (Math.hypot(R.gx, R.gy) < 0.1) tips.add(new Tip(Tip.Kind.HOLD_STILL, -1, null, when, null));
                    else tips.add(new Tip(Tip.Kind.MOVE_TOWARD, -1, Tip.direction(R.gx, R.gy, 0, mir), when, null));
                } else if (part == Tip.Part.SIZE) {
                    tips.add(Tip.of(d.sizeRatio < 1 ? Tip.Kind.BIGGER : Tip.Kind.SMALLER));
                } else if (pack.two) {
                    if (d.ndSeen < 0.5) tips.add(Tip.of(Tip.Kind.BOTH_HANDS));
                    else if (d.relErr != null) {
                        tips.add(new Tip(Tip.Kind.SECOND_HAND_MOVE, -1, Tip.direction(d.relErr[0], d.relErr[1], 0, mir), null, null));
                    } else tips.add(Tip.of(Tip.Kind.SECOND_HAND_COPY));
                } else {
                    tips.add(Tip.of(Tip.Kind.ONE_HAND));
                }
            }
            List<Tip.Part> good = new ArrayList<>();
            for (Tip.Part p : PARTS) {
                if (res.sub[p.ordinal()] < 92 || p == Tip.Part.SIZE || p == Tip.Part.DIRECTION
                        || (p == Tip.Part.TWO_HANDS && !pack.two)) continue;
                good.add(p);
            }
            if (!passed && !good.isEmpty()) {
                tips.add(0, new Tip(Tip.Kind.PARTS_RIGHT, -1, null, null, good.subList(0, Math.min(2, good.size()))));
            }
            if (tips.isEmpty()) tips.add(Tip.of(Tip.Kind.ALMOST));
            return tips;
        }

        /** Mean live handshape (what 0) or directions u, n (what 1) over the rows aligned with reference frame j. */
        private double[] meanLive(Detail d, int j, int what) {
            double[] out = new double[what == 0 ? HandFeatures.SHAPE : 6];
            int count = 0;
            for (int q = 0; q < d.pj.length; q++) {
                if (d.pj[q] != j) continue;
                HandFeatures L = liveAt(d.pi[q], d.variant);
                count++;
                if (what == 0) {
                    for (int k = 0; k < out.length; k++) out[k] += L.q[k];
                } else {
                    for (int c = 0; c < 3; c++) {
                        out[c] += L.u[c];
                        out[3 + c] += L.n[c];
                    }
                }
            }
            for (int k = 0; k < out.length; k++) out[k] /= Math.max(count, 1);
            return out;
        }
    }

    /**
     * Distances between a live frame and reference frame j: out[0] handshape, out[1]
     * orientation, out[2] second hand. Returns the alignment cost.
     */
    static double local(HandFeatures L, Reference ref, int j, boolean two, double[] out) {
        HandFeatures R = ref.f[j];
        double sq = 0;
        for (int k = 0; k < HandFeatures.SHAPE; k++) sq += sq(L.q[k] - R.q[k]);
        double hs = Math.sqrt(sq / HandFeatures.SHAPE);
        double or = Math.sqrt((sq3(L.u, R.u) + sq3(L.n, R.n)) / 2);
        double vel = Math.sqrt(sq(L.gx - R.gx) + sq(L.gy - R.gy));
        double c = hs / SIG_HS + or / SIG_OR + vel / SIG_VEL;
        double rel = 0;
        if (two) {
            double no = Math.sqrt((sq3(L.nu, R.nu) + sq3(L.nn, R.nn)) / 2);
            rel = Math.sqrt(sq(L.rx - R.rx) + sq(L.ry - R.ry)) + 2.0 * no;
            rel = L.pn && R.pn ? rel : R.pn ? REL_MISS : 0.0;
            c += rel / SIG_REL;
        }
        out[0] = hs;
        out[1] = or;
        out[2] = rel;
        double miss = MISS + (two && R.pn ? REL_MISS / SIG_REL : 0.0);
        c = L.pd ? c : miss;
        return R.pd ? c : L.pd ? 1.0 : 0.0;
    }

    private static double sq(double x) {
        return x * x;
    }

    private static double sq3(double[] a, double[] b) {
        return sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]);
    }

    private static double norm3(double[] a, int o) {
        return Math.sqrt(sq(a[o]) + sq(a[o + 1]) + sq(a[o + 2]));
    }

    /** Median like numpy (mean of the two middle values for an even count). */
    static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double[] v = new double[values.size()];
        for (int i = 0; i < v.length; i++) v[i] = values.get(i);
        Arrays.sort(v);
        int h = v.length / 2;
        return v.length % 2 == 1 ? v[h] : (v[h - 1] + v[h]) / 2;
    }
}
