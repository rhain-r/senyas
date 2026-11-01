package com.senyas.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java scorer against the Python implementation it was designed in: the same bundled
 * references and the same inputs (unseen FSL-105 takes, held poses, played backwards, half frame
 * rate, other signs, one-handed attempts) must give the same tolerances, scores, sub-scores and
 * advice. Fixtures: src/test/resources/scorer_parity.json, written by the design's parity script.
 */
public class SignScorerParityTest {
    private static JSONObject fixture;

    @BeforeClass
    public static void load() throws Exception {
        try (InputStream in = SignScorerParityTest.class.getClassLoader().getResourceAsStream("scorer_parity.json")) {
            fixture = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void matchesThePythonDesign() throws Exception {
        JSONArray signs = fixture.getJSONArray("signs");
        StringBuilder failures = new StringBuilder();
        int cases = 0;
        for (int s = 0; s < signs.length(); s++) {
            JSONObject sign = signs.getJSONObject(s);
            String name = sign.getString("sign");
            SignScorer scorer = new SignScorer(ReferenceClipTest.load(name));
            assertEquals(name + " two-handed", sign.getBoolean("two"), scorer.twoHanded());
            assertEquals(name + " pass threshold", sign.optDouble("passK", SignScorer.K), scorer.passThreshold(), 1e-9);
            double[] tau = scorer.tolerances();
            JSONArray want = sign.getJSONArray("tau");
            for (int k = 0; k < tau.length; k++) {
                assertEquals(name + " tolerance " + k, want.getDouble(k), tau[k], 1e-4 * Math.max(1, want.getDouble(k)));
            }
            JSONArray cs = sign.getJSONArray("cases");
            for (int c = 0; c < cs.length(); c++) {
                JSONObject cse = cs.getJSONObject(c);
                cases++;
                String label = name + " / " + cse.getString("name");
                SignScorer.Result r = replay(scorer, cse.getJSONArray("frames"));
                int score = cse.getInt("score");
                if (score < 0) {
                    assertNull(label, r);
                    continue;
                }
                if (r == null) {
                    failures.append(label).append(": no result, want ").append(score).append('\n');
                    continue;
                }
                if (Math.abs(r.score - score) > 1) {
                    failures.append(label).append(": score ").append(r.score).append(" want ").append(score).append('\n');
                }
                JSONArray sub = cse.getJSONArray("sub");
                for (int k = 0; k < sub.length(); k++) {
                    if (Math.abs(r.sub[k] - sub.getDouble(k)) > 1.0) {
                        failures.append(label).append(": sub ").append(k).append(' ').append(r.sub[k])
                                .append(" want ").append(sub.getDouble(k)).append('\n');
                    }
                }
                JSONArray msgs = cse.getJSONArray("messages");
                List<String> got = new ArrayList<>();
                for (Tip t : r.tips) got.add(t.english());
                List<String> exp = new ArrayList<>();
                for (int k = 0; k < msgs.length(); k++) exp.add(msgs.getString(k));
                if (!exp.equals(got)) {
                    failures.append(label).append(": tips ").append(got).append(" want ").append(exp).append('\n');
                }
            }
        }
        assertTrue("cases", cases > 30);
        assertEquals("", failures.toString());
    }

    static SignScorer.Result replay(SignScorer scorer, JSONArray frames) throws Exception {
        int n = frames.length();
        long[] ts = new long[n];
        List<List<double[]>> hands = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            JSONObject f = frames.getJSONObject(i);
            ts[i] = f.getLong("t");
            JSONArray hs = f.getJSONArray("hands");
            List<double[]> list = new ArrayList<>();
            for (int h = 0; h < hs.length(); h++) {
                JSONArray a = hs.getJSONArray(h);
                double[] v = new double[a.length()];
                for (int k = 0; k < v.length; k++) v[k] = a.getDouble(k);
                list.add(v);
            }
            hands.add(list);
        }
        return scorer.replay(ts, hands);
    }
}
