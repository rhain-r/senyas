package com.senyas.cv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One camera frame after the signer check ({@link HandGate}): zero, one or two hands of one
 * signer. Hands the check refused are not in {@link #hands}; {@link #status} says why.
 */
public final class HandFrame {
    public enum Status {
        /** One signer: up to two hands accepted. */
        OK,
        /** Nothing that looks like a hand. */
        NO_HANDS,
        /** Hands but no face in view, e.g. a foot or the camera pointed down: none accepted. */
        NO_FACE,
        /** More than two hands: none accepted. */
        TOO_MANY_HANDS,
        /** More than one face: none accepted. */
        TOO_MANY_PEOPLE
    }

    public final long timestampMs;
    /** The accepted hands only. */
    public final List<Hand> hands;
    public final Status status;
    /** Faces in view (one hidden behind a signing hand still counts), or -1 when not checked. */
    public final int faces;

    /** A frame that was not checked for faces, e.g. one read back from a recording. */
    public HandFrame(long timestampMs, List<Hand> hands) {
        this(timestampMs, hands, hands == null || hands.isEmpty() ? Status.NO_HANDS : Status.OK, -1);
    }

    public HandFrame(long timestampMs, List<Hand> hands, Status status, int faces) {
        this.timestampMs = timestampMs;
        this.hands = hands == null ? Collections.emptyList() : hands;
        this.status = status;
        this.faces = faces;
    }

    public boolean hasHands() {
        return !hands.isEmpty();
    }

    /** More than one signer, or more than two hands, in view: nothing here should be scored. */
    public boolean crowded() {
        return status == Status.TOO_MANY_HANDS || status == Status.TOO_MANY_PEOPLE;
    }

    /** The hands' landmarks, most confident hand first (the order the sign scorer expects). */
    public List<float[]> landmarksByConfidence() {
        List<Hand> sorted = new ArrayList<>(hands);
        Collections.sort(sorted, (a, b) -> Float.compare(b.score, a.score));
        List<float[]> out = new ArrayList<>(sorted.size());
        for (Hand h : sorted) out.add(h.landmarks);
        return out;
    }
}
