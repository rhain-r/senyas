package com.senyas.cv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The signer check between MediaPipe and the rest of the app: which tracked hands belong to the
 * one signer in front of the camera. Only accepted hands reach the scorer, Sign Lab recordings and
 * the overlay, so a foot, a second person or a crowd of hands is never scored.
 *
 * Per frame:
 *   a detection is ignored when its handedness score is under MIN_HANDEDNESS, when it is much
 *   smaller than the signer's face (someone farther back), when it is outside the signing space
 *   around the face (a foot, a bystander's hand, something pushed into the lens), or when it lies
 *   on top of a more confident one (the same hand found twice)
 *   a hand counts once it continues a hand of the previous result, matched one to one, so a
 *   one-frame false detection is dropped even right beside a real hand
 *   the first that applies: more than one face is TOO_MANY_PEOPLE, no hand is NO_HANDS, more than
 *   two hands are TOO_MANY_HANDS, hands without a face are NO_FACE; only OK accepts hands
 * A face the detector misses for a moment, or one hidden behind a hand signing in front of it,
 * still counts for a while, so signs made at the face keep working. Meanwhile only hands that
 * continue ones accepted just before count, so a new hand (the camera swung to a foot) is NO_FACE.
 *
 * Coordinates are MediaPipe's image units (0..1 across the upright frame). Pure Java so it is unit
 * tested on the computer. Not thread-safe: one gate per camera stream, called from one thread.
 */
public final class HandGate {
    /** One signer has at most this many hands. */
    public static final int MAX_HANDS = 2;
    /** MediaPipe scores handedness 0.5 when it cannot tell left from right; real hands score higher. */
    static final float MIN_HANDEDNESS = 0.6f;
    /** A hand smaller than this share of the face's width is someone else's, farther back. */
    static final float MIN_HAND_TO_FACE = 0.3f;
    /** Two detections closer than this (mean landmark distance, in hand sizes) are one hand. */
    static final float DUPLICATE = 0.25f;
    /** The signer's hands stay within this many face widths of the face centre, left or right... */
    static final float SPACE_SIDE = 4f;
    /** ...from this many face widths above it... */
    static final float SPACE_ABOVE = 2f;
    /** ...to this many below it (the waist). */
    static final float SPACE_BELOW = 5f;
    /** A hand bigger than this many face widths is something pushed into the lens. */
    static final float MAX_HAND_TO_FACE = 3.5f;
    /** A hand counts once a hand was within this many hand sizes of it in the previous result... */
    static final float FOLLOW = 1.5f;
    /** ...and that result is at most this old (slow phones give one every 300 to 400 ms). */
    static final long FOLLOW_MS = 1000;
    /** Results further apart than this widen that reach in proportion, as hands move further... */
    static final long FOLLOW_STEP_MS = 200;
    /** ...up to this many times. */
    static final float FOLLOW_WIDEN = 2f;
    /** While the face is held, a hand goes on only within this size ratio of the hand before it. */
    static final float SAME_SIZE = 2f;
    /** A face the detector misses for up to this long is still in view. */
    static final long FACE_HOLD_MS = 500;
    /** A face hidden behind a hand that was at it counts for up to this long. */
    static final long FACE_COVERED_MS = 3000;
    /** A hand within this share of the face's width around the face box is at the face. */
    static final float AT_FACE = 0.25f;
    /** TOO_MANY_HANDS and TOO_MANY_PEOPLE last at least this long, so the message does not flicker. */
    static final long CROWD_HOLD_MS = 500;

    private static final long NEVER = Long.MIN_VALUE / 4;

    /** Boxes of the previous result's plausible hands. */
    private List<float[]> previous = Collections.emptyList();
    private long previousMs = NEVER;
    /** The faces last seen (boxes, y in image widths) and whether a hand was at one of them then. */
    private List<float[]> lastFaces = Collections.emptyList();
    private long lastFaceMs = NEVER;
    private boolean handAtLastFace;
    /** Boxes of the hands last accepted, and when; while the face is held only these go on. */
    private List<float[]> accepted = Collections.emptyList();
    private long acceptedMs = NEVER;
    private long manyPeopleMs = NEVER, manyHandsMs = NEVER;

    /**
     * One frame: MediaPipe's hands, the faces found in the same frame as {left, top, right, bottom}
     * boxes in image units, and the image's height over width. Returns the accepted hands (most
     * confident first), the status and the faces in view.
     */
    public HandFrame apply(long tMs, List<Hand> detected, List<float[]> faces, float aspect) {
        List<float[]> faceBoxes = new ArrayList<>();
        if (faces != null) {
            for (float[] f : faces) faceBoxes.add(new float[]{f[0], f[1] * aspect, f[2], f[3] * aspect});
        }
        boolean faceRecent = tMs - lastFaceMs <= FACE_COVERED_MS;
        float faceWidth = widest(!faceBoxes.isEmpty() ? faceBoxes : faceRecent ? lastFaces : null);

        // Candidate hands, most confident first; boxes have y in image widths.
        List<Hand> sorted = new ArrayList<>();
        if (detected != null) sorted.addAll(detected);
        Collections.sort(sorted, (a, b) -> Float.compare(b.score, a.score));
        List<Hand> candidates = new ArrayList<>();
        List<float[]> candidateBoxes = new ArrayList<>();
        for (Hand h : sorted) {
            if (h.score < MIN_HANDEDNESS || h.landmarks == null || h.landmarks.length < 63) continue;
            float[] box = box(h.landmarks, aspect);
            if (faceWidth > 0 && size(box) < MIN_HAND_TO_FACE * faceWidth) continue;
            candidates.add(h);
            candidateBoxes.add(box);
        }

        // The faces in view: this frame's, or the last ones while held.
        int faceCount;
        boolean held = false;
        List<float[]> space = null;
        if (!faceBoxes.isEmpty()) {
            if (faceBoxes.size() > 1) manyPeopleMs = tMs;
            faceCount = faceBoxes.size();
            space = faceBoxes;
        } else {
            long since = tMs - lastFaceMs;
            held = since <= FACE_HOLD_MS
                    || since <= FACE_COVERED_MS && handAtLastFace && atFace(candidateBoxes, lastFaces);
            faceCount = held ? lastFaces.size() : 0;
            if (held) space = lastFaces;
        }

        // Plausible hands: in the signing space of a face in view, each hand once.
        List<Hand> plausible = new ArrayList<>();
        List<float[]> boxes = new ArrayList<>();
        for (int k = 0; k < candidates.size(); k++) {
            Hand h = candidates.get(k);
            float[] box = candidateBoxes.get(k);
            if (space != null && !inSigningSpace(box, space)) continue;
            boolean same = false;
            for (int i = 0; i < plausible.size() && !same; i++) {
                float s = Math.min(size(box), size(boxes.get(i)));
                same = meanDistance(h.landmarks, plausible.get(i).landmarks, aspect) < DUPLICATE * s;
            }
            if (same) continue;
            plausible.add(h);
            boxes.add(box);
        }
        if (!faceBoxes.isEmpty()) {
            lastFaces = faceBoxes;
            lastFaceMs = tMs;
            handAtLastFace = atFace(boxes, faceBoxes);
        }

        // Confirmed: continues a hand of the previous result.
        long gap = tMs - previousMs;
        float reach = FOLLOW * Math.max(1f, Math.min(FOLLOW_WIDEN, gap / (float) FOLLOW_STEP_MS));
        boolean[] follows = match(boxes, gap <= FOLLOW_MS ? previous : Collections.<float[]>emptyList(), reach, false);
        List<Hand> confirmed = new ArrayList<>();
        List<float[]> confirmedBoxes = new ArrayList<>();
        for (int i = 0; i < plausible.size(); i++) {
            if (!follows[i]) continue;
            confirmed.add(plausible.get(i));
            confirmedBoxes.add(boxes.get(i));
        }
        previous = boxes;
        previousMs = tMs;

        // While the face is only held, a hand counts only if it continues one accepted just before.
        List<Hand> signer = confirmed;
        List<float[]> signerBoxes = confirmedBoxes;
        if (held && !confirmed.isEmpty()) {
            List<float[]> before = tMs - acceptedMs <= FOLLOW_MS ? accepted : Collections.<float[]>emptyList();
            boolean[] goesOn = match(confirmedBoxes, before, reach, true);
            signer = new ArrayList<>();
            signerBoxes = new ArrayList<>();
            for (int i = 0; i < confirmed.size(); i++) {
                if (!goesOn[i]) continue;
                signer.add(confirmed.get(i));
                signerBoxes.add(confirmedBoxes.get(i));
            }
            if (signer.isEmpty()) faceCount = 0;
        }

        if (confirmed.size() > MAX_HANDS) manyHandsMs = tMs;
        HandFrame.Status status;
        if (tMs - manyPeopleMs <= CROWD_HOLD_MS) {
            status = HandFrame.Status.TOO_MANY_PEOPLE;
        } else if (confirmed.isEmpty()) {
            status = HandFrame.Status.NO_HANDS;
        } else if (tMs - manyHandsMs <= CROWD_HOLD_MS) {
            status = HandFrame.Status.TOO_MANY_HANDS;
        } else if (faceCount == 0) {
            status = HandFrame.Status.NO_FACE;
        } else {
            status = HandFrame.Status.OK;
        }
        if (status != HandFrame.Status.OK) return new HandFrame(tMs, Collections.emptyList(), status, faceCount);
        accepted = signerBoxes;
        acceptedMs = tMs;
        return new HandFrame(tMs, signer, status, faceCount);
    }

    /** {left, top, right, bottom} of a hand's landmarks, y in image widths. */
    static float[] box(float[] lm, float aspect) {
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (int i = 0; i < 21; i++) {
            float x = lm[i * 3], y = lm[i * 3 + 1] * aspect;
            l = Math.min(l, x);
            r = Math.max(r, x);
            t = Math.min(t, y);
            b = Math.max(b, y);
        }
        return new float[]{l, t, r, b};
    }

    /** A hand's size: the longer side of its box. */
    static float size(float[] box) {
        return Math.max(box[2] - box[0], box[3] - box[1]);
    }

    private static float widest(List<float[]> faces) {
        float w = 0;
        if (faces != null) for (float[] f : faces) w = Math.max(w, f[2] - f[0]);
        return w;
    }

    private static float meanDistance(float[] a, float[] b, float aspect) {
        float sum = 0;
        for (int i = 0; i < 21; i++) {
            float dx = a[i * 3] - b[i * 3], dy = (a[i * 3 + 1] - b[i * 3 + 1]) * aspect;
            sum += (float) Math.sqrt(dx * dx + dy * dy);
        }
        return sum / 21;
    }

    /**
     * Which boxes continue one of {@code earlier}: centres within {@code reach} hand sizes (and,
     * with {@code sameSize}, sizes within SAME_SIZE), matched one to one, nearest pairs first.
     */
    private static boolean[] match(List<float[]> boxes, List<float[]> earlier, float reach, boolean sameSize) {
        List<float[]> pairs = new ArrayList<>(); // {squared distance, box index, earlier index}
        for (int i = 0; i < boxes.size(); i++) {
            float[] a = boxes.get(i);
            for (int j = 0; j < earlier.size(); j++) {
                float[] b = earlier.get(j);
                float big = Math.max(size(a), size(b)), small = Math.min(size(a), size(b));
                if (sameSize && big > SAME_SIZE * small) continue;
                float dx = (a[0] + a[2] - b[0] - b[2]) / 2, dy = (a[1] + a[3] - b[1] - b[3]) / 2;
                float d2 = dx * dx + dy * dy, r = reach * big;
                if (d2 <= r * r) pairs.add(new float[]{d2, i, j});
            }
        }
        Collections.sort(pairs, (p, q) -> Float.compare(p[0], q[0]));
        boolean[] matched = new boolean[boxes.size()], taken = new boolean[earlier.size()];
        for (float[] p : pairs) {
            int i = (int) p[1], j = (int) p[2];
            if (matched[i] || taken[j]) continue;
            matched[i] = true;
            taken[j] = true;
        }
        return matched;
    }

    /** Whether a hand box is in the signing space of any face and not too big for it. */
    private static boolean inSigningSpace(float[] hand, List<float[]> faces) {
        float hx = (hand[0] + hand[2]) / 2, hy = (hand[1] + hand[3]) / 2;
        for (float[] f : faces) {
            float w = f[2] - f[0], cx = (f[0] + f[2]) / 2, cy = (f[1] + f[3]) / 2;
            if (Math.abs(hx - cx) <= SPACE_SIDE * w && hy >= cy - SPACE_ABOVE * w && hy <= cy + SPACE_BELOW * w
                    && size(hand) <= MAX_HAND_TO_FACE * w) return true;
        }
        return false;
    }

    /** Whether any hand box touches a face box grown by AT_FACE of its width. */
    private static boolean atFace(List<float[]> hands, List<float[]> faces) {
        for (float[] f : faces) {
            float g = (f[2] - f[0]) * AT_FACE;
            for (float[] h : hands) {
                if (h[0] <= f[2] + g && h[2] >= f[0] - g && h[1] <= f[3] + g && h[3] >= f[1] - g) return true;
            }
        }
        return false;
    }
}
