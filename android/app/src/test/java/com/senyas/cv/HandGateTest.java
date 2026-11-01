package com.senyas.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The signer check: which hands of a camera frame belong to the one signer in view. */
public class HandGateTest {
    private static final long FRAME_MS = 33;
    /** Upright 480x640 phone frame. */
    private static final float ASPECT = 4f / 3f;
    /** A face at arm's length, top centre of the frame. */
    private static final float[] FACE = {0.38f, 0.12f, 0.62f, 0.30f};

    /** A hand whose 21 landmarks fill a box of the given size (image widths) centred at (cx, cy). */
    private static Hand hand(float cx, float cy, float size, float score) {
        float[] lm = new float[63];
        for (int i = 0; i < 21; i++) {
            float u = (i % 5) / 4f - 0.5f, v = (i / 5) / 4f - 0.5f;
            if (i == 20) {
                u = 0.5f;
                v = 0.5f;
            }
            lm[i * 3] = cx + u * size;
            lm[i * 3 + 1] = cy + v * size / ASPECT;
        }
        return new Hand("Right", score, lm);
    }

    private static List<float[]> faces(float[]... boxes) {
        return Arrays.asList(boxes);
    }

    /** Feeds the same hands and faces for {@code frames} frames from {@code start}; returns the last result. */
    private static HandFrame run(HandGate gate, long start, int frames, List<float[]> faces, Hand... hands) {
        HandFrame last = null;
        for (int i = 0; i < frames; i++) last = gate.apply(start + i * FRAME_MS, Arrays.asList(hands), faces, ASPECT);
        return last;
    }

    @Test
    public void oneSignerWithTwoHandsIsAccepted() {
        HandGate gate = new HandGate();
        Hand left = hand(0.3f, 0.6f, 0.2f, 0.9f), right = hand(0.7f, 0.6f, 0.2f, 0.97f);
        HandFrame first = gate.apply(0, Arrays.asList(left, right), faces(FACE), ASPECT);
        assertEquals(HandFrame.Status.NO_HANDS, first.status); // a hand counts from its second frame
        HandFrame f = run(gate, FRAME_MS, 3, faces(FACE), left, right);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(1, f.faces);
        assertEquals(2, f.hands.size());
        assertSame(right, f.hands.get(0)); // most confident first
    }

    @Test
    public void aDetectionInOneFrameOnlyIsDropped() {
        HandGate gate = new HandGate();
        Hand real = hand(0.5f, 0.6f, 0.2f, 0.95f);
        run(gate, 0, 5, faces(FACE), real);
        HandFrame blip = gate.apply(5 * FRAME_MS, Arrays.asList(real, hand(0.15f, 0.9f, 0.2f, 0.95f)), faces(FACE), ASPECT);
        assertEquals(HandFrame.Status.OK, blip.status);
        assertEquals(1, blip.hands.size());
        assertSame(real, blip.hands.get(0));
    }

    @Test
    public void handsWithoutAFaceAreRefused() {
        // A foot, or the camera pointed down at the hands: never scored.
        HandGate gate = new HandGate();
        HandFrame f = run(gate, 0, 10, Collections.emptyList(), hand(0.5f, 0.5f, 0.3f, 0.9f));
        assertEquals(HandFrame.Status.NO_FACE, f.status);
        assertEquals(0, f.faces);
        assertTrue(f.hands.isEmpty());
    }

    @Test
    public void aFaceMissedForAMomentStillCounts() {
        HandGate gate = new HandGate();
        Hand h = hand(0.5f, 0.7f, 0.2f, 0.95f);
        run(gate, 0, 5, faces(FACE), h);
        long t = 4 * FRAME_MS;
        assertEquals(HandFrame.Status.OK, gate.apply(t + 300, Collections.singletonList(h), null, ASPECT).status);
        assertEquals(HandFrame.Status.NO_FACE, gate.apply(t + 600, Collections.singletonList(h), null, ASPECT).status);
    }

    @Test
    public void aFaceHiddenBehindTheSigningHandStillCounts() {
        HandGate gate = new HandGate();
        Hand atChin = hand(0.5f, 0.3f, 0.2f, 0.95f);
        run(gate, 0, 5, faces(FACE), atChin);
        HandFrame covered = run(gate, 5 * FRAME_MS, 60, Collections.emptyList(), atChin); // 2 s
        assertEquals(HandFrame.Status.OK, covered.status);
        assertEquals(1, covered.faces);
        HandFrame later = run(gate, 65 * FRAME_MS, 40, Collections.emptyList(), atChin); // over 3 s
        assertEquals(HandFrame.Status.NO_FACE, later.status);
    }

    @Test
    public void threeHandsAreTooManyUntilOneLeaves() {
        HandGate gate = new HandGate();
        Hand a = hand(0.25f, 0.6f, 0.2f, 0.95f), b = hand(0.5f, 0.6f, 0.2f, 0.9f), c = hand(0.75f, 0.6f, 0.2f, 0.92f);
        HandFrame crowd = run(gate, 0, 5, faces(FACE), a, b, c);
        assertEquals(HandFrame.Status.TOO_MANY_HANDS, crowd.status);
        assertTrue(crowd.hands.isEmpty());
        assertTrue(crowd.crowded());
        // The message holds for a moment after the third hand leaves, then the signer is back.
        assertEquals(HandFrame.Status.TOO_MANY_HANDS, run(gate, 5 * FRAME_MS, 3, faces(FACE), a, b).status);
        HandFrame back = run(gate, 8 * FRAME_MS, 20, faces(FACE), a, b);
        assertEquals(HandFrame.Status.OK, back.status);
        assertEquals(2, back.hands.size());
    }

    @Test
    public void twoFacesAreTooManyPeople() {
        HandGate gate = new HandGate();
        float[] other = {0.75f, 0.10f, 0.95f, 0.26f};
        HandFrame f = run(gate, 0, 5, faces(FACE, other), hand(0.5f, 0.6f, 0.2f, 0.95f));
        assertEquals(HandFrame.Status.TOO_MANY_PEOPLE, f.status);
        assertEquals(2, f.faces);
        assertTrue(f.hands.isEmpty());
        assertEquals(HandFrame.Status.TOO_MANY_PEOPLE, run(gate, 5 * FRAME_MS, 1, faces(FACE, other)).status);
    }

    @Test
    public void theSameHandFoundTwiceIsOneHand() {
        HandGate gate = new HandGate();
        Hand a = hand(0.3f, 0.6f, 0.2f, 0.95f), twin = hand(0.305f, 0.602f, 0.2f, 0.8f), b = hand(0.7f, 0.6f, 0.2f, 0.9f);
        HandFrame f = run(gate, 0, 5, faces(FACE), a, twin, b);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(2, f.hands.size());
        assertSame(a, f.hands.get(0));
        assertSame(b, f.hands.get(1));
    }

    @Test
    public void unsureAndFarAwayDetectionsAreIgnored() {
        HandGate gate = new HandGate();
        Hand signer = hand(0.5f, 0.6f, 0.2f, 0.95f);
        Hand unsure = hand(0.2f, 0.7f, 0.2f, 0.55f);
        Hand farBehind = hand(0.85f, 0.5f, 0.05f, 0.95f); // much smaller than the face: someone farther back
        Hand third = hand(0.8f, 0.8f, 0.2f, 0.9f);
        HandFrame f = run(gate, 0, 5, faces(FACE), signer, unsure, farBehind, third);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(2, f.hands.size());
    }

    @Test
    public void noHandsIsNoHands() {
        HandGate gate = new HandGate();
        HandFrame f = run(gate, 0, 3, faces(FACE));
        assertEquals(HandFrame.Status.NO_HANDS, f.status);
        assertEquals(1, f.faces);
        HandFrame none = run(new HandGate(), 0, 3, null);
        assertEquals(HandFrame.Status.NO_HANDS, none.status);
    }

    @Test
    public void aDetectionBesideATrackedHandMustSurviveItsOwnSecondFrame() {
        HandGate gate = new HandGate();
        Hand left = hand(0.3f, 0.6f, 0.2f, 0.9f), right = hand(0.7f, 0.6f, 0.2f, 0.97f);
        run(gate, 0, 5, faces(FACE), left, right);
        Hand phantom = hand(0.85f, 0.65f, 0.2f, 0.95f); // within the right hand's follow reach
        HandFrame f = gate.apply(5 * FRAME_MS, Arrays.asList(left, right, phantom), faces(FACE), ASPECT);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(Arrays.asList(right, left), f.hands);
        // Seen again in the next frame it is a real third hand.
        HandFrame again = gate.apply(6 * FRAME_MS, Arrays.asList(left, right, phantom), faces(FACE), ASPECT);
        assertEquals(HandFrame.Status.TOO_MANY_HANDS, again.status);
    }

    @Test
    public void aFootFarBelowASmallFaceIsRefused() {
        // The signer stands back, so the face is small; the foot is near the camera, below the waist.
        HandGate gate = new HandGate();
        float[] small = {0.46f, 0.08f, 0.54f, 0.14f};
        Hand signer = hand(0.6f, 0.3f, 0.07f, 0.9f), foot = hand(0.5f, 0.85f, 0.15f, 0.95f);
        HandFrame f = run(gate, 0, 5, faces(small), signer, foot);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(Collections.singletonList(signer), f.hands);
    }

    @Test
    public void aBystandersHandAndSomethingAtTheLensAreRefused() {
        HandGate gate = new HandGate();
        float[] small = {0.46f, 0.08f, 0.54f, 0.14f};
        Hand signer = hand(0.6f, 0.3f, 0.07f, 0.9f);
        Hand aside = hand(0.9f, 0.3f, 0.07f, 0.95f); // over 4 face widths to the side
        Hand lens = hand(0.5f, 0.3f, 0.4f, 0.95f); // 5 face widths across
        HandFrame f = run(gate, 0, 5, faces(small), signer, aside, lens);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(Collections.singletonList(signer), f.hands);
    }

    @Test
    public void aNewHandWhileTheFaceIsHeldIsRefused() {
        // The face drops out and the camera swings down to a foot: the held face does not vouch for it.
        HandGate gate = new HandGate();
        run(gate, 0, 5, faces(FACE), hand(0.5f, 0.6f, 0.2f, 0.95f));
        Hand foot = hand(0.3f, 0.9f, 0.2f, 0.95f);
        HandFrame f = null;
        for (int i = 5; i < 20; i++) { // up to FACE_HOLD_MS after the face
            f = gate.apply(i * FRAME_MS, Collections.singletonList(foot), null, ASPECT);
            assertTrue(f.hands.isEmpty());
        }
        assertEquals(HandFrame.Status.NO_FACE, f.status);
        assertEquals(0, f.faces);
        // Nor does something much bigger that turns up where the hand was.
        HandGate other = new HandGate();
        run(other, 0, 5, faces(FACE), hand(0.5f, 0.6f, 0.2f, 0.95f));
        HandFrame big = run(other, 5 * FRAME_MS, 3, null, hand(0.5f, 0.6f, 0.45f, 0.95f));
        assertEquals(HandFrame.Status.NO_FACE, big.status);
    }

    @Test
    public void whileTheFaceIsHeldOnlyTheSignersHandsGoOn() {
        HandGate gate = new HandGate();
        Hand signer = hand(0.5f, 0.6f, 0.2f, 0.9f), newcomer = hand(0.2f, 0.9f, 0.2f, 0.95f);
        run(gate, 0, 5, faces(FACE), signer);
        HandFrame f = run(gate, 5 * FRAME_MS, 3, null, signer, newcomer);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(1, f.faces);
        assertEquals(Collections.singletonList(signer), f.hands);
    }

    @Test
    public void fourHandsWithoutAFaceAreTooManyHands() {
        HandGate gate = new HandGate();
        HandFrame f = run(gate, 0, 3, Collections.emptyList(),
                hand(0.25f, 0.3f, 0.2f, 0.95f), hand(0.75f, 0.3f, 0.2f, 0.9f),
                hand(0.25f, 0.7f, 0.2f, 0.92f), hand(0.75f, 0.7f, 0.2f, 0.93f));
        assertEquals(HandFrame.Status.TOO_MANY_HANDS, f.status);
        assertTrue(f.crowded());
        assertTrue(f.hands.isEmpty());
    }

    @Test
    public void slowPhonesStillConfirmHands() {
        // A result every 400 ms: a hand counts from its second result, even if it moved in between.
        HandGate gate = new HandGate();
        long slow = 400;
        Hand here = hand(0.3f, 0.6f, 0.2f, 0.95f), there = hand(0.65f, 0.6f, 0.2f, 0.95f);
        assertEquals(HandFrame.Status.NO_HANDS, gate.apply(0, Collections.singletonList(here), faces(FACE), ASPECT).status);
        assertEquals(HandFrame.Status.OK, gate.apply(slow, Collections.singletonList(here), faces(FACE), ASPECT).status);
        assertEquals(HandFrame.Status.OK, gate.apply(2 * slow, Collections.singletonList(there), faces(FACE), ASPECT).status);
        // A blip still needs a neighbour in the result just before it.
        assertEquals(HandFrame.Status.NO_HANDS, gate.apply(3 * slow, Collections.emptyList(), faces(FACE), ASPECT).status);
        assertEquals(HandFrame.Status.NO_HANDS, gate.apply(4 * slow, Collections.singletonList(there), faces(FACE), ASPECT).status);
        assertEquals(HandFrame.Status.OK, gate.apply(5 * slow, Collections.singletonList(there), faces(FACE), ASPECT).status);
    }

    @Test
    public void recordedFramesKeepTheirHands() {
        List<Hand> hs = new ArrayList<>();
        hs.add(hand(0.5f, 0.5f, 0.2f, 0.9f));
        HandFrame f = new HandFrame(0, hs);
        assertEquals(HandFrame.Status.OK, f.status);
        assertEquals(-1, f.faces);
        assertEquals(HandFrame.Status.NO_HANDS, new HandFrame(0, null).status);
    }
}
