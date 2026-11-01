package com.senyas.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How the Practice Room scorer behaves on real signing: FSL-105 takes that are not among the
 * built-in references, fed the way the camera feeds them.
 */
public class SignScorerTest {
    /** FSL-105 clips are 640x360. */
    private static final float FSL_ASPECT = 360f / 640f;
    private static JSONObject fixture;

    @BeforeClass
    public static void load() throws Exception {
        try (InputStream in = SignScorerTest.class.getClassLoader().getResourceAsStream("scorer_parity.json")) {
            fixture = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static JSONObject sign(String name) throws Exception {
        JSONArray signs = fixture.getJSONArray("signs");
        for (int i = 0; i < signs.length(); i++) if (signs.getJSONObject(i).getString("sign").equals(name)) return signs.getJSONObject(i);
        throw new AssertionError(name);
    }

    /** The frames of a fixture case whose name starts with {@code prefix}. */
    private static JSONArray frames(String sign, String prefix) throws Exception {
        JSONArray cases = sign(sign).getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            if (c.getString("name").startsWith(prefix)) return c.getJSONArray("frames");
        }
        throw new AssertionError(sign + " " + prefix);
    }

    /** Scorer coordinates back to MediaPipe's raw landmarks of a 640x360 clip, optionally as signed with the other hand. */
    private static List<float[]> raw(JSONArray hands, boolean otherHand) throws Exception {
        List<float[]> out = new ArrayList<>();
        for (int h = 0; h < hands.length(); h++) {
            JSONArray a = hands.getJSONArray(h);
            float[] lm = new float[Landmarks.FLOATS];
            for (int i = 0; i < Landmarks.POINTS; i++) {
                double x = a.getDouble(i * 3);
                lm[i * 3] = (float) (otherHand ? x : 1 - x);
                lm[i * 3 + 1] = (float) (a.getDouble(i * 3 + 1) / FSL_ASPECT);
                lm[i * 3 + 2] = (float) a.getDouble(i * 3 + 2);
            }
            out.add(lm);
        }
        return out;
    }

    /** Feeds a clip frame by frame like the camera; returns the best score and the last feedback. */
    private static Feedback live(SignScorer scorer, JSONArray frames, boolean otherHand) throws Exception {
        scorer.reset();
        Feedback last = null;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject f = frames.getJSONObject(i);
            last = scorer.onFrame(f.getLong("t"), raw(f.getJSONArray("hands"), otherHand), FSL_ASPECT);
        }
        return last;
    }

    private static SignScorer scorer(String sign) throws Exception {
        return new SignScorer(ReferenceClipTest.load(sign));
    }

    @Test
    public void anUnseenSignerPassesLive() throws Exception {
        for (String s : new String[]{"kumusta", "salamat", "oo", "tatay"}) {
            SignScorer scorer = scorer(s);
            JSONArray cases = sign(s).getJSONArray("cases");
            live(scorer, cases.getJSONObject(1).getJSONArray("frames"), false);
            assertTrue(s + " best " + scorer.bestScore(), scorer.bestScore() >= SignScorer.PASS);
        }
    }

    @Test
    public void aLeftHandedSignerPassesToo() throws Exception {
        for (String s : new String[]{"kumusta", "salamat", "tatay"}) {
            SignScorer scorer = scorer(s);
            JSONArray cases = sign(s).getJSONArray("cases");
            live(scorer, cases.getJSONObject(1).getJSONArray("frames"), true);
            assertTrue(s + " mirrored best " + scorer.bestScore(), scorer.bestScore() >= SignScorer.PASS);
        }
    }

    @Test
    public void aHeldPoseNeverPasses() throws Exception {
        for (String s : new String[]{"kumusta", "salamat", "oo", "tatay"}) {
            JSONArray cases = sign(s).getJSONArray("cases");
            JSONArray hold = null;
            for (int i = 0; i < cases.length(); i++) if (cases.getJSONObject(i).getString("name").endsWith("hold")) hold = cases.getJSONObject(i).getJSONArray("frames");
            SignScorer scorer = scorer(s);
            live(scorer, hold, false);
            assertTrue(s + " hold " + scorer.bestScore(), scorer.bestScore() < 50);
        }
    }

    @Test
    public void anotherSignDoesNotPass() throws Exception {
        SignScorer scorer = scorer("salamat");
        live(scorer, frames("salamat", "3/17 as"), false); // kumusta
        assertTrue(scorer.bestScore() < SignScorer.PASS);
        live(scorer, frames("salamat", "53/17 as"), false); // nanay
        assertTrue(scorer.bestScore() < SignScorer.PASS);
    }

    @Test
    public void aOneHandedSalamatIsToldToUseBothHands() throws Exception {
        SignScorer scorer = scorer("salamat");
        assertTrue(scorer.twoHanded());
        JSONArray cases = sign("salamat").getJSONArray("cases");
        JSONArray user = null;
        for (int i = 0; i < cases.length(); i++) if (cases.getJSONObject(i).getString("name").startsWith("user_")) user = cases.getJSONObject(i).getJSONArray("frames");
        SignScorer.Result r = SignScorerParityTest.replay(scorer, user);
        assertNotNull(r);
        assertTrue(r.score < SignScorer.PASS);
        boolean both = false;
        for (Tip t : r.tips) both |= t.kind == Tip.Kind.BOTH_HANDS;
        assertTrue(r.tips.toString(), both);
    }

    @Test
    public void withoutReferencesThereIsNoScore() {
        SignScorer scorer = new SignScorer(null);
        assertFalse(scorer.hasReference());
        float[] hand = new float[Landmarks.FLOATS];
        for (int i = 0; i < Landmarks.POINTS; i++) {
            hand[i * 3] = 0.5f + 0.01f * (i % 5);
            hand[i * 3 + 1] = 0.5f + 0.01f * (i / 5);
        }
        Feedback fb = null;
        for (int k = 0; k < 30; k++) fb = scorer.onFrame(k * 66L, Collections.singletonList(hand), SignScorer.PHONE_ASPECT);
        assertTrue(fb.handVisible);
        assertEquals(-1, fb.score);
        assertFalse(scorer.onFrame(31 * 66L, Collections.emptyList(), SignScorer.PHONE_ASPECT).handVisible);
    }

    @Test
    public void aHandAtTheEdgeIsMovedBackIn() {
        float[] hand = new float[Landmarks.FLOATS];
        for (int i = 0; i < Landmarks.POINTS; i++) {
            hand[i * 3] = 0.5f;
            hand[i * 3 + 1] = 0.5f;
        }
        List<float[]> one = Collections.singletonList(hand);
        assertEquals(Feedback.Arrow.NONE, SignScorer.edgeArrow(one, SignScorer.DEFAULT_GUIDE));
        hand[8 * 3 + 1] = 0.01f; // a fingertip past the edge is fine (signs at the forehead)
        assertEquals(Feedback.Arrow.NONE, SignScorer.edgeArrow(one, SignScorer.DEFAULT_GUIDE));
        moveHand(hand, 0, -0.47f); // the palm above the picture
        assertEquals(Feedback.Arrow.DOWN, SignScorer.edgeArrow(one, SignScorer.DEFAULT_GUIDE));
        moveHand(hand, 0.47f, 0.47f); // raw x near the right edge = the learner's left in the mirrored preview
        assertEquals(Feedback.Arrow.RIGHT, SignScorer.edgeArrow(one, SignScorer.DEFAULT_GUIDE));
        moveHand(hand, -0.47f, -0.25f);
        // The preview crops the picture: a hand inside the image can still be outside the visible guide.
        float[] cropped = {0.06f, 0.3f, 0.94f, 0.7f};
        assertEquals(Feedback.Arrow.DOWN, SignScorer.edgeArrow(one, cropped));
    }

    private static void moveHand(float[] hand, float dx, float dy) {
        for (int i = 0; i < Landmarks.POINTS; i++) {
            hand[i * 3] += dx;
            hand[i * 3 + 1] += dy;
        }
    }

    @Test
    public void aLongCameraGapDoesNotBreakScoring() throws Exception {
        // A gap of several seconds releases dozens of grid ticks in one frame, more than the
        // matcher's ring buffer holds; the next performance must still score normally.
        SignScorer scorer = scorer("salamat");
        JSONArray take = sign("salamat").getJSONArray("cases").getJSONObject(1).getJSONArray("frames");
        long shift = 0;
        for (int round = 0; round < 3; round++) {
            long last = 0;
            for (int i = 0; i < take.length(); i++) {
                JSONObject f = take.getJSONObject(i);
                last = f.getLong("t") + shift;
                scorer.onFrame(last, raw(f.getJSONArray("hands"), false), FSL_ASPECT);
            }
            shift = last + 12_000; // camera stalls for 12 s
        }
        assertTrue("best " + scorer.bestScore(), scorer.bestScore() >= SignScorer.PASS);
    }

    @Test
    public void framesOutOfOrderAreIgnored() throws Exception {
        SignScorer scorer = scorer("oo");
        JSONArray take = sign("oo").getJSONArray("cases").getJSONObject(1).getJSONArray("frames");
        for (int i = 0; i < take.length(); i++) {
            JSONObject f = take.getJSONObject(i);
            List<float[]> hands = raw(f.getJSONArray("hands"), false);
            scorer.onFrame(f.getLong("t"), hands, FSL_ASPECT);
            scorer.onFrame(f.getLong("t"), hands, FSL_ASPECT); // a repeated timestamp
            if (i > 0) scorer.onFrame(f.getLong("t") - 40, hands, FSL_ASPECT); // an older one
        }
        assertTrue("best " + scorer.bestScore(), scorer.bestScore() >= SignScorer.PASS);
    }

    @Test
    public void aRecordingBecomesAReference() throws Exception {
        // A Sign Lab style recording (raw MediaPipe hands, most confident first) of an unseen take.
        JSONArray frames = sign("tatay").getJSONArray("cases").getJSONObject(0).getJSONArray("frames");
        long[] t = new long[frames.length()];
        List<List<float[]>> hands = new ArrayList<>();
        for (int i = 0; i < frames.length(); i++) {
            t[i] = frames.getJSONObject(i).getLong("t");
            hands.add(raw(frames.getJSONObject(i).getJSONArray("hands"), false));
        }
        ReferenceClip clip = SignScorer.prepare("tatay", t, hands, FSL_ASPECT);
        assertEquals(frames.length(), clip.size());
        assertEquals(0, clip.t[0]);
        SignScorer scorer = new SignScorer(Collections.singletonList(clip));
        assertTrue(scorer.hasReference());
        // Its own performance matches it.
        live(scorer, frames, false);
        assertTrue("self " + scorer.bestScore(), scorer.bestScore() >= SignScorer.PASS);
    }
}
