package com.senyas.ml;

import java.util.Collections;
import java.util.List;

/**
 * One piece of advice about an attempt, in the terms FSL teachers use: hand shape, palm and
 * finger direction, movement, and the second hand. The app turns it into words in the learner's
 * language; {@link #english()} is the plain version.
 */
public final class Tip {
    /** The parameters a sign is judged on. */
    public enum Part { HANDSHAPE, ORIENTATION, MOVEMENT, SIZE, TWO_HANDS, DIRECTION }

    public enum Kind {
        /** The attempt passed. */
        GOOD,
        /** These parts are right ({@link #parts}). */
        PARTS_RIGHT,
        /** The dominant hand left the picture for much of the sign. */
        KEEP_IN_VIEW,
        /** A hand is at the edge of the picture; move it {@link #dir}. */
        EDGE,
        STRAIGHTEN_FINGER,
        BEND_FINGER,
        /** Turn the palm to face {@link #dir}. */
        PALM_FACE,
        /** Point the fingers {@link #dir}. */
        POINT_FINGERS,
        /** Move the hand further {@link #dir}. */
        MOVE,
        /** At that moment the hand should move {@link #dir} (it moved another way). */
        MOVE_TOWARD,
        /** At that moment the hand should stay still. */
        HOLD_STILL,
        BIGGER,
        SMALLER,
        BOTH_HANDS,
        /** Move the second hand further {@link #dir} (relative to the dominant hand). */
        SECOND_HAND_MOVE,
        SECOND_HAND_COPY,
        ONE_HAND,
        /** Nothing specific: watch the video again. */
        ALMOST
    }

    /** Screen directions as the learner sees the (mirrored) preview. */
    public enum Dir { LEFT, RIGHT, UP, DOWN, TOWARD_CAMERA, AWAY }

    /** When in the sign. */
    public enum When { START, MIDDLE, END }

    public static final String[] FINGER_NAMES = Landmarks.FINGER_NAMES;

    public final Kind kind;
    /** Finger index (thumb = 0) for the finger tips, else -1. */
    public final int finger;
    public final Dir dir;
    public final When when;
    public final List<Part> parts;

    Tip(Kind kind, int finger, Dir dir, When when, List<Part> parts) {
        this.kind = kind;
        this.finger = finger;
        this.dir = dir;
        this.when = when;
        this.parts = parts == null ? Collections.emptyList() : parts;
    }

    static Tip of(Kind kind) {
        return new Tip(kind, -1, null, null, null);
    }

    public String english() {
        String w = when == null ? "" : " " + whenWord();
        switch (kind) {
            case GOOD: return "Good! Your sign matches the reference signers.";
            case PARTS_RIGHT: {
                StringBuilder sb = new StringBuilder("Your ");
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) sb.append(" and ");
                    sb.append(partWord(parts.get(i)));
                }
                return sb.append(parts.size() > 1 ? " are right." : " is right.").toString();
            }
            case KEEP_IN_VIEW: return "Keep your hand inside the camera view for the whole sign.";
            case EDGE: return "Keep your whole hand inside the camera guide: move it " + dirWord() + ".";
            case STRAIGHTEN_FINGER: return "Hand shape" + w + ": straighten your " + FINGER_NAMES[finger] + " more.";
            case BEND_FINGER: return "Hand shape" + w + ": bend your " + FINGER_NAMES[finger] + " more.";
            case PALM_FACE: return "Palm direction" + w + ": turn your palm to face " + dirWord() + ".";
            case POINT_FINGERS: return "Hand direction" + w + ": point your fingers " + dirWord() + ".";
            case MOVE: return "Movement" + w + ": move your hand further " + dirWord() + ".";
            case MOVE_TOWARD: return "Movement" + w + ": move your hand " + dirWord() + ".";
            case HOLD_STILL: return "Movement" + w + ": keep your hand still.";
            case BIGGER: return "Movement: make the movement bigger.";
            case SMALLER: return "Movement: make the movement smaller.";
            case BOTH_HANDS: return "This sign uses both hands: keep both hands in view.";
            case SECOND_HAND_MOVE: return "Second hand: move it further " + dirWord() + " (compared with your other hand).";
            case SECOND_HAND_COPY: return "Second hand: copy its shape and direction from the video.";
            case ONE_HAND: return "This sign uses one hand: keep the other hand still.";
            default: return "Almost: watch the video once more and repeat the whole sign.";
        }
    }

    @Override
    public String toString() {
        return english();
    }

    private String whenWord() {
        switch (when) {
            case START: return "at the start";
            case MIDDLE: return "in the middle";
            default: return "at the end";
        }
    }

    private String dirWord() {
        switch (dir) {
            case LEFT: return "left";
            case RIGHT: return "right";
            case UP: return "up";
            case DOWN: return "down";
            case TOWARD_CAMERA: return "toward the camera";
            default: return "away from you";
        }
    }

    static String partWord(Part p) {
        switch (p) {
            case HANDSHAPE: return "hand shape";
            case ORIENTATION: return "palm and finger direction";
            case MOVEMENT: return "movement path";
            case SIZE: return "movement size";
            case DIRECTION: return "movement direction";
            default: return "second hand";
        }
    }

    /** A 3D direction (x right on screen, y down, z away from the camera) as a word. */
    static Dir direction(double x, double y, double z, boolean mirrored) {
        if (mirrored) x = -x;
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ax >= ay && ax >= az) return x > 0 ? Dir.RIGHT : Dir.LEFT;
        if (ay >= az) return y > 0 ? Dir.DOWN : Dir.UP;
        return z > 0 ? Dir.AWAY : Dir.TOWARD_CAMERA;
    }

    static When when(int j, int m) {
        int k = Math.min(2, (int) (3.0 * j / Math.max(m, 1)));
        return When.values()[k];
    }
}
