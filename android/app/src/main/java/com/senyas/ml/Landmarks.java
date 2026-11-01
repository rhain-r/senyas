package com.senyas.ml;

/**
 * MediaPipe's 21 hand landmarks. A raw frame is 63 floats (x, y, z per point).
 *
 * Point order: 0 wrist; thumb 1-4; index 5-8; middle 9-12; ring 13-16; little 17-20.
 */
public final class Landmarks {
    public static final int POINTS = 21;
    public static final int FLOATS = POINTS * 3;

    public static final String[] FINGER_NAMES = {"thumb", "index finger", "middle finger", "ring finger", "little finger"};
    /** Bones to draw, as pairs of point indexes. */
    public static final int[][] BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16},
            {13, 17}, {0, 17}, {17, 18}, {18, 19}, {19, 20}
    };

    private Landmarks() {
    }
}
