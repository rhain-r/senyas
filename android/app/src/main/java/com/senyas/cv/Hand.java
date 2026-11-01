package com.senyas.cv;

/**
 * One hand found by MediaPipe in one camera frame.
 *
 * Coordinates are exactly what MediaPipe returns for the camera image as captured (not mirrored):
 * x and y run 0..1 across the image, z is depth relative to the wrist. The Python training
 * pipeline uses the same convention, so phone recordings and video datasets line up.
 */
public final class Hand {
    /** MediaPipe's handedness label, "Left" or "Right". */
    public final String label;
    /** Handedness confidence, 0..1. */
    public final float score;
    /** 21 landmarks x 3 (x, y, z). */
    public final float[] landmarks;

    public Hand(String label, float score, float[] landmarks) {
        this.label = label;
        this.score = score;
        this.landmarks = landmarks;
    }
}
