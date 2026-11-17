package com.benten.senyas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** FSL1 feature contract shared with the training extractor. No Android dependencies. */
public final class SignFeatures {
    public static final int COUNT = 156;
    public static final int HAND_SIZE = 67;
    public static final int BODY_PRESENT = 155;
    private static final int[] PALM = {5, 9, 13, 17};
    private static final int[] POSE = {0, 11, 12, 13, 14, 15, 16};

    private SignFeatures() {}

    /**
     * Input coordinates are normalized landmarks from an upright detector image. Width and height
     * must be that image's dimensions after rotation. Mirroring is supported by training augmentation;
     * do not independently mirror hands or pose. Pose rows are x,y,z,visibility; hands are 21x3.
     */
    public static float[] extract(float[][][] hands, float[][] pose, int width, int height) {
        float[] result = new float[COUNT];
        if (width <= 0 || height <= 0) return result;
        float aspect = (float) width / height;
        boolean body = validPose(pose);
        float originX = .5f * aspect, originY = .5f, bodyScale = .3f * aspect;
        if (body) {
            float dx = (pose[12][0] - pose[11][0]) * aspect;
            float dy = pose[12][1] - pose[11][1];
            float candidate = (float) Math.hypot(dx, dy);
            body = candidate >= .05f;
            if (body) {
                originX = (pose[11][0] + pose[12][0]) * .5f * aspect;
                originY = (pose[11][1] + pose[12][1]) * .5f;
                bodyScale = candidate;
            }
        }
        List<float[][]> validHands = new ArrayList<>(2);
        if (hands != null) for (float[][] hand : hands) if (validHand(hand)) validHands.add(hand);
        validHands.sort(Comparator.comparingDouble(h -> h[0][0]));
        for (int slot = 0; slot < Math.min(2, validHands.size()); slot++) {
            float[][] hand = validHands.get(slot);
            float palm = 0f;
            for (int joint : PALM) {
                float dx = (hand[joint][0] - hand[0][0]) * aspect;
                float dy = hand[joint][1] - hand[0][1];
                float dz = (hand[joint][2] - hand[0][2]) * aspect;
                palm += (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * .25f;
            }
            palm = Math.max(palm, .0001f);
            int at = slot * HAND_SIZE;
            for (int joint = 0; joint < 21; joint++) {
                result[at++] = (hand[joint][0] - hand[0][0]) * aspect / palm;
                result[at++] = (hand[joint][1] - hand[0][1]) / palm;
                result[at++] = (hand[joint][2] - hand[0][2]) * aspect / palm;
            }
            result[at++] = (hand[0][0] * aspect - originX) / bodyScale;
            result[at++] = (hand[0][1] - originY) / bodyScale;
            result[at++] = palm / bodyScale;
            result[at] = 1f;
        }
        if (body) {
            int at = 134;
            for (int joint : POSE) {
                result[at++] = (pose[joint][0] * aspect - originX) / bodyScale;
                result[at++] = (pose[joint][1] - originY) / bodyScale;
                result[at++] = Math.max(0f, Math.min(1f, pose[joint][3]));
            }
            result[BODY_PRESENT] = 1f;
        }
        // Fail closed for arithmetic overflow as well as non-finite detector output.
        for (float value : result) if (!Float.isFinite(value)) return new float[COUNT];
        return result;
    }

    private static boolean validHand(float[][] hand) {
        if (hand == null || hand.length != 21) return false;
        for (float[] joint : hand) if (!finite(joint, 3)) return false;
        return true;
    }

    private static boolean validPose(float[][] pose) {
        if (pose == null || pose.length < 33) return false;
        for (int joint = 0; joint < 33; joint++) if (!finite(pose[joint], 4)) return false;
        return pose[11][3] >= .3f && pose[12][3] >= .3f;
    }

    private static boolean finite(float[] values, int count) {
        if (values == null || values.length < count) return false;
        for (int i = 0; i < count; i++) if (!Float.isFinite(values[i])) return false;
        return true;
    }
}
