package com.senyas.ml;

import java.util.Collections;
import java.util.List;

/** What the Practice Room shows after each camera frame. */
public final class Feedback {
    public enum Arrow { NONE, UP, DOWN, LEFT, RIGHT }

    /** True while a hand is in view. */
    public final boolean handVisible;
    /** Latest whole-sign score 0-100, or -1 before the first match (or without references). */
    public final int score;
    /** Advice, most useful first (may be empty). */
    public final List<Tip> tips;
    /** Which way to move a hand that is at the edge of the picture. */
    public final Arrow arrow;
    /** One flag per finger (thumb first): false for a finger the advice is about. */
    public final boolean[] fingerOk;
    /** The first piece of advice in plain English, always shown so no cue depends on colour. */
    public final String message;
    /** Raw landmarks of the hand the advice is about (the dominant hand), or null. */
    public final float[] dominant;

    public Feedback(boolean handVisible, int score, List<Tip> tips, Arrow arrow, boolean[] fingerOk, float[] dominant) {
        this.handVisible = handVisible;
        this.score = score;
        this.tips = tips == null ? Collections.emptyList() : tips;
        this.arrow = arrow;
        this.fingerOk = fingerOk;
        this.dominant = dominant;
        this.message = this.tips.isEmpty()
                ? (handVisible ? "Sign when ready." : "Show your hands inside the camera guide.")
                : this.tips.get(0).english();
    }

    public static Feedback noHand() {
        return new Feedback(false, -1, null, Arrow.NONE, new boolean[]{true, true, true, true, true}, null);
    }
}
