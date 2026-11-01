package com.senyas.ml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One reference performance of a sign: both hands, frame by frame, already followed through the
 * clip and split into the dominant (more active) hand and the other one. Built-in references are
 * tracked from the FSL-105 demonstration clips (assets/sign_refs/{sign}.json); Sign Lab
 * recordings become references through {@link SignScorer#prepare}.
 *
 * Coordinates are the scorer's: x mirrored like the preview (1 - x), y in units of the image
 * width (y * height / width), z as MediaPipe gives it.
 */
public final class ReferenceClip {
    public final String clip;
    /** Who signed it (references by the same signer are not used to calibrate each other), or -1. */
    public final int signer;
    /** Milliseconds from the first frame. */
    public final long[] t;
    /** Per frame: 63 floats for the dominant / other hand, or null where that hand is not seen. */
    public final float[][] dom, nd;
    /**
     * The sign's pass threshold in tolerance units, set by the export just below the closest other
     * built-in sign, or NaN (the scorer's default) for recordings.
     */
    public final double passK;

    public ReferenceClip(String clip, int signer, long[] t, float[][] dom, float[][] nd) {
        this(clip, signer, t, dom, nd, Double.NaN);
    }

    public ReferenceClip(String clip, int signer, long[] t, float[][] dom, float[][] nd, double passK) {
        this.clip = clip;
        this.signer = signer;
        this.t = t;
        this.dom = dom;
        this.nd = nd;
        this.passK = passK;
    }

    public int size() {
        return t.length;
    }

    /** Parses a sign's reference file. */
    public static List<ReferenceClip> parseSet(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray refs = root.getJSONArray("refs");
        double passK = root.optDouble("passK", Double.NaN);
        List<ReferenceClip> out = new ArrayList<>(refs.length());
        for (int r = 0; r < refs.length(); r++) {
            JSONObject ref = refs.getJSONObject(r);
            JSONArray frames = ref.getJSONArray("frames");
            int n = frames.length();
            long[] t = new long[n];
            float[][] dom = new float[n][], nd = new float[n][];
            for (int i = 0; i < n; i++) {
                JSONObject f = frames.getJSONObject(i);
                t[i] = f.getLong("t");
                dom[i] = hand(f.optJSONArray("dom"));
                nd[i] = hand(f.optJSONArray("nd"));
            }
            out.add(new ReferenceClip(ref.optString("clip"), ref.optInt("signer", -1), t, dom, nd, passK));
        }
        return out;
    }

    private static float[] hand(JSONArray a) throws JSONException {
        if (a == null || a.length() != Landmarks.FLOATS) return null;
        float[] out = new float[Landmarks.FLOATS];
        for (int k = 0; k < out.length; k++) out[k] = (float) a.getDouble(k);
        return out;
    }
}
