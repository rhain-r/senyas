package com.senyas.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Live recognition with the on-phone matcher, using made-up hand motions (not real FSL). */
public class SignRecognizerTest {
    private static final long FRAME_MS = 33;

    /** Open hand (all fingers up) or fist, wrist at (cx, cy), hand size 0.1. */
    private static float[] hand(boolean open, float cx, float cy) {
        float[] f = new float[63];
        float[][] p = new float[21][];
        p[0] = new float[]{0, 0};
        p[1] = new float[]{-0.35f, -0.25f};
        p[2] = new float[]{-0.6f, -0.5f};
        p[3] = new float[]{-0.8f, -0.75f};
        p[4] = open ? new float[]{-0.95f, -1.0f} : new float[]{-0.3f, -0.9f};
        for (int finger = 1; finger <= 4; finger++) {
            float x = -0.3f + 0.2f * (finger - 1);
            int b = 1 + finger * 4;
            p[b] = new float[]{x, -1f};
            p[b + 1] = new float[]{x, open ? -1.5f : -1.3f};
            p[b + 2] = new float[]{x, open ? -1.9f : -1.0f};
            p[b + 3] = new float[]{x, open ? -2.2f : -0.8f};
        }
        for (int i = 0; i < 21; i++) {
            f[i * 3] = cx + p[i][0] * 0.1f;
            f[i * 3 + 1] = cy + p[i][1] * 0.1f;
        }
        return f;
    }

    /** "wave": open hand moving side to side. "drop": fist moving down. */
    private static List<HandFrame> sign(String name, long start, int frames, float noise, long seed) {
        java.util.Random r = new java.util.Random(seed);
        List<HandFrame> out = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            float u = i / (float) (frames - 1);
            float[] lm = name.equals("wave")
                    ? hand(true, 0.5f + 0.12f * (float) Math.sin(u * 4 * Math.PI), 0.6f)
                    : hand(false, 0.5f, 0.45f + 0.25f * u);
            for (int k = 0; k < lm.length; k++) lm[k] += (float) (r.nextGaussian() * noise);
            List<Hand> hs = new ArrayList<>();
            hs.add(new Hand("Right", 0.95f, lm));
            out.add(new HandFrame(start + i * FRAME_MS, hs));
        }
        return out;
    }

    private static TemplateSignClassifier trainedMatcher() {
        TemplateSignClassifier m = new TemplateSignClassifier();
        for (int take = 0; take < 3; take++) {
            m.add("wave", FeatureEncoder.encodeSequence(sign("wave", 0, 60, 0.003f, take), FeatureEncoder.SEQUENCE_LENGTH));
            m.add("drop", FeatureEncoder.encodeSequence(sign("drop", 0, 60, 0.003f, 10 + take), FeatureEncoder.SEQUENCE_LENGTH));
        }
        return m;
    }

    private static SignRecognizer.State feed(SignRecognizer r, List<HandFrame> frames) {
        SignRecognizer.State last = null;
        for (HandFrame f : frames) {
            SignRecognizer.State s = r.onFrame(f);
            if (s != null) last = s;
        }
        return last;
    }

    @Test
    public void recognisesTheSignBeingMade() {
        SignRecognizer r = new SignRecognizer(trainedMatcher());
        List<HandFrame> frames = new ArrayList<>();
        // The learner repeats "wave" for a few seconds, a bit noisier than the recordings.
        for (int rep = 0; rep < 3; rep++) frames.addAll(sign("wave", rep * 60 * FRAME_MS, 60, 0.004f, 100 + rep));
        SignRecognizer.State s = feed(r, frames);
        assertEquals(SignRecognizer.Status.RECOGNIZED, s.status);
        assertEquals("wave", s.sign);
        assertTrue(s.confidence >= SignRecognizer.MIN_PROBABILITY);
    }

    @Test
    public void tellsTheTwoSignsApart() {
        SignRecognizer r = new SignRecognizer(trainedMatcher());
        List<HandFrame> frames = new ArrayList<>();
        for (int rep = 0; rep < 3; rep++) frames.addAll(sign("drop", rep * 60 * FRAME_MS, 60, 0.004f, 200 + rep));
        SignRecognizer.State s = feed(r, frames);
        assertEquals("drop", s.sign);
    }

    @Test
    public void noHandsMeansNoGuess() {
        SignRecognizer r = new SignRecognizer(trainedMatcher());
        List<HandFrame> empty = new ArrayList<>();
        for (int i = 0; i < 60; i++) empty.add(new HandFrame(i * FRAME_MS, null));
        SignRecognizer.State s = feed(r, empty);
        assertEquals(SignRecognizer.Status.NO_HANDS, s.status);
        assertNull(s.sign);
    }

    @Test
    public void unknownMotionIsNotForcedIntoALabel() {
        SignRecognizer r = new SignRecognizer(trainedMatcher());
        List<HandFrame> frames = new ArrayList<>();
        // A fist that stays still near the top: neither recorded sign.
        for (int i = 0; i < 180; i++) {
            List<Hand> hs = new ArrayList<>();
            hs.add(new Hand("Right", 0.95f, hand(false, 0.3f, 0.3f)));
            frames.add(new HandFrame(i * FRAME_MS, hs));
        }
        SignRecognizer.State s = feed(r, frames);
        assertTrue("got " + s.status + " " + s.sign, s.status != SignRecognizer.Status.RECOGNIZED);
    }

    @Test
    public void withoutSignsItAsksForAModel() {
        SignRecognizer r = new SignRecognizer(new TemplateSignClassifier());
        SignRecognizer.State s = feed(r, sign("wave", 0, 30, 0f, 1));
        assertEquals(SignRecognizer.Status.NO_MODEL, s.status);
    }

    @Test
    public void recordingRoundTripsThroughJson() throws Exception {
        List<HandFrame> frames = sign("wave", 1000, 20, 0f, 3);
        SignSample sample = new SignSample("Salamat po!", "test", frames);
        assertEquals("salamat_po", sample.label);
        java.io.File f = java.io.File.createTempFile("sample", ".json");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), sample.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SignSample back = SampleStore.read(f);
        assertEquals("salamat_po", back.label);
        assertEquals(20, back.frames.size());
        assertEquals(0, back.frames.get(0).timestampMs);
        assertEquals(frames.get(5).hands.get(0).landmarks[7], back.frames.get(5).hands.get(0).landmarks[7], 1e-5f);
    }
}
