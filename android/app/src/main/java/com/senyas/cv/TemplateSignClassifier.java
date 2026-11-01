package com.senyas.cv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works with no trained model: compares a sign with recordings made on the phone (a few per
 * sign) and picks the closest, using dynamic time warping so speed differences do not matter.
 *
 * Three things are compared, because in FSL each can change the meaning:
 *   handshape  the fingers relative to the wrist
 *   movement   the wrist's path during the sign, in hand-size units
 *   location   where in the frame the sign happens (weighted lightly, as signers stand differently)
 *
 * Good for a demo or for signs the trained model does not know yet. A model trained on many
 * signers (TfliteSignClassifier) generalises much better to new people.
 */
public final class TemplateSignClassifier implements SignClassifier {
    /** Distance beyond which a sign counts as "none of these". Tune with real recordings. */
    static final float REJECT_DISTANCE = 0.45f;
    /** Softness of the distance-to-probability conversion. */
    static final float TEMPERATURE = 0.08f;
    /** How much the average position counts, per hand-size of offset. */
    static final float LOCATION_WEIGHT = 0.5f;

    private static final int PER_HAND = FeatureEncoder.PER_HAND;

    /** A recording broken into the parts that are compared. */
    static final class Prepared {
        final float[][] shape;      // per frame: 63 landmark numbers for each of the 2 slots
        final float[][] movement;   // per frame: wrist x, y for each slot, centred, in hand sizes
        final float[] center = new float[4];
        final float size;

        Prepared(float[][] seq) {
            int n = seq.length;
            shape = new float[n][126];
            movement = new float[n][4];
            float sizeSum = 0;
            int sizeCount = 0;
            for (int s = 0; s < 2; s++) {
                int off = s * PER_HAND;
                float sx = 0, sy = 0;
                int count = 0;
                for (float[] f : seq) {
                    if (f[off + 65] > 0) {
                        sx += f[off + 63];
                        sy += f[off + 64];
                        sizeSum += f[off + 65];
                        sizeCount++;
                        count++;
                    }
                }
                if (count > 0) {
                    center[s * 2] = sx / count;
                    center[s * 2 + 1] = sy / count;
                }
            }
            size = sizeCount > 0 ? sizeSum / sizeCount : 0.1f;
            for (int t = 0; t < n; t++) {
                float[] f = seq[t];
                System.arraycopy(f, 0, shape[t], 0, 63);
                System.arraycopy(f, PER_HAND, shape[t], 63, 63);
                for (int s = 0; s < 2; s++) {
                    int off = s * PER_HAND;
                    if (f[off + 65] > 0) {
                        movement[t][s * 2] = (f[off + 63] - center[s * 2]) / size;
                        movement[t][s * 2 + 1] = (f[off + 64] - center[s * 2 + 1]) / size;
                    }
                }
            }
        }
    }

    private final Map<String, List<Prepared>> samples = new LinkedHashMap<>();

    @Override
    public String name() {
        return "My recordings (" + samples.size() + " signs)";
    }

    @Override
    public List<String> labels() {
        return new ArrayList<>(samples.keySet());
    }

    public void add(String label, float[][] sequence) {
        if (sequence != null) samples.computeIfAbsent(label, k -> new ArrayList<>()).add(new Prepared(sequence));
    }

    public int count(String label) {
        List<Prepared> list = samples.get(label);
        return list == null ? 0 : list.size();
    }

    @Override
    public List<Prediction> classify(float[][] sequence) {
        List<Prediction> out = new ArrayList<>();
        if (samples.isEmpty() || sequence == null) return out;
        Prepared live = new Prepared(sequence);
        List<String> labels = new ArrayList<>();
        List<Float> best = new ArrayList<>();
        for (Map.Entry<String, List<Prepared>> e : samples.entrySet()) {
            float min = Float.MAX_VALUE;
            for (Prepared p : e.getValue()) min = Math.min(min, distance(live, p));
            labels.add(e.getKey());
            best.add(min);
        }
        // Softmax over negative distances, with a "none of these" option at the reject distance.
        double reject = Math.exp(-REJECT_DISTANCE / TEMPERATURE);
        double sum = reject;
        double[] w = new double[labels.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.exp(-best.get(i) / TEMPERATURE);
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) out.add(new Prediction(labels.get(i), (float) (w[i] / sum)));
        out.sort((a, b) -> Float.compare(b.probability, a.probability));
        return out;
    }

    /** Handshape and movement along the best time alignment, plus a light location term. */
    static float distance(Prepared a, Prepared b) {
        float loc = 0;
        for (int s = 0; s < 2; s++) {
            float dx = a.center[s * 2] - b.center[s * 2];
            float dy = a.center[s * 2 + 1] - b.center[s * 2 + 1];
            loc += (float) Math.sqrt(dx * dx + dy * dy);
        }
        loc /= (a.size + b.size) / 2f;
        return dtw(a, b) + LOCATION_WEIGHT * loc;
    }

    static float frameDistance(Prepared a, int i, Prepared b, int j) {
        float shape = meanSquare(a.shape[i], b.shape[j]);
        float move = meanSquare(a.movement[i], b.movement[j]);
        return (float) Math.sqrt(shape + move);
    }

    private static float meanSquare(float[] x, float[] y) {
        double sum = 0;
        for (int k = 0; k < x.length; k++) {
            double d = x[k] - y[k];
            sum += d * d;
        }
        return (float) (sum / x.length);
    }

    /** Average frame distance along the best time alignment of the two sequences. */
    static float dtw(Prepared a, Prepared b) {
        int n = a.shape.length, m = b.shape.length;
        float[][] cost = new float[n + 1][m + 1];
        int[][] steps = new int[n + 1][m + 1];
        for (float[] row : cost) java.util.Arrays.fill(row, Float.MAX_VALUE);
        cost[0][0] = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                float d = frameDistance(a, i - 1, b, j - 1);
                float c = cost[i - 1][j - 1];
                int s = steps[i - 1][j - 1];
                if (cost[i - 1][j] < c) {
                    c = cost[i - 1][j];
                    s = steps[i - 1][j];
                }
                if (cost[i][j - 1] < c) {
                    c = cost[i][j - 1];
                    s = steps[i][j - 1];
                }
                cost[i][j] = c + d;
                steps[i][j] = s + 1;
            }
        }
        return cost[n][m] / steps[n][m];
    }
}
