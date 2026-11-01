package com.senyas.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone and the training script must turn landmarks into identical numbers, or a model that
 * scores well in training fails on the phone. Both encoders are checked against the same fixture
 * (written by ml/tests/make_parity_fixture.py, also checked by ml/tests/test_pipeline.py).
 */
public class FeatureEncoderParityTest {

    private static JSONObject fixture() throws Exception {
        try (InputStream in = FeatureEncoderParityTest.class.getResourceAsStream("/cv/parity_fixture.json")) {
            assertNotNull("parity_fixture.json missing from src/test/resources/cv", in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static List<HandFrame> frames(JSONArray arr) throws Exception {
        List<HandFrame> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.getJSONObject(i);
            JSONArray hands = f.getJSONArray("hands");
            List<Hand> hs = new ArrayList<>();
            for (int h = 0; h < hands.length(); h++) {
                JSONObject o = hands.getJSONObject(h);
                JSONArray lm = o.getJSONArray("lm");
                float[] a = new float[lm.length()];
                for (int k = 0; k < a.length; k++) a[k] = (float) lm.getDouble(k);
                hs.add(new Hand(o.getString("label"), (float) o.getDouble("score"), a));
            }
            out.add(new HandFrame(f.getLong("t"), hs));
        }
        return out;
    }

    @Test
    public void framesMatchPython() throws Exception {
        JSONObject fx = fixture();
        assertEquals(FeatureEncoder.VERSION, fx.getInt("featureVersion"));
        List<HandFrame> frames = frames(fx.getJSONArray("frames"));
        JSONArray expected = fx.getJSONArray("frameFeatures");
        for (int i = 0; i < frames.size(); i++) {
            assertRowEquals("frame " + i, expected.getJSONArray(i), FeatureEncoder.encodeFrame(frames.get(i)));
        }
    }

    @Test
    public void sequenceMatchesPython() throws Exception {
        JSONObject fx = fixture();
        float[][] seq = FeatureEncoder.encodeSequence(frames(fx.getJSONArray("frames")), FeatureEncoder.SEQUENCE_LENGTH);
        JSONArray expected = fx.getJSONArray("sequence");
        assertEquals(FeatureEncoder.SEQUENCE_LENGTH, seq.length);
        for (int k = 0; k < seq.length; k++) assertRowEquals("step " + k, expected.getJSONArray(k), seq[k]);
    }

    @Test
    public void tooFewHandFramesGiveNoSequence() {
        List<HandFrame> frames = new ArrayList<>();
        frames.add(new HandFrame(0, null));
        frames.add(new HandFrame(33, null));
        assertNull(FeatureEncoder.encodeSequence(frames, FeatureEncoder.SEQUENCE_LENGTH));
    }

    private static void assertRowEquals(String where, JSONArray expected, float[] actual) throws Exception {
        assertEquals(where + " length", expected.length(), actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(where + " value " + i, expected.getDouble(i), actual[i], 1e-5);
        }
    }
}
