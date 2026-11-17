package com.benten.senyas;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Locale;
import java.util.Scanner;

public class SignFeaturesTest {
    @Test public void matchesIndependentPythonTrainingFeatureFixture() {
        try (Scanner data = new Scanner(getClass().getResourceAsStream("/fsl-golden-frame.txt"))) {
            data.useLocale(Locale.US);
            int width = data.nextInt(), height = data.nextInt(), count = data.nextInt();
            float[][][] hands = new float[count][21][3];
            for (float[][] hand : hands) for (float[] p : hand) for (int i = 0; i < 3; i++) p[i] = data.nextFloat();
            float[][] pose = new float[33][4];
            for (float[] p : pose) for (int i = 0; i < 4; i++) p[i] = data.nextFloat();
            float[] expected = new float[156];
            for (int i = 0; i < expected.length; i++) expected[i] = data.nextFloat();
            assertArrayEquals(expected, SignFeatures.extract(hands, pose, width, height), .000005f);
        }
    }

    @Test public void emptyDetectionsProduceFiniteZeros() {
        float[] features = SignFeatures.extract(null, null, 640, 480);
        assertEquals(156, features.length);
        for (float f : features) assertEquals(0f, f, 0f);
    }

    @Test public void normalizesPalmAndBodyWithImageAspect() {
        float[][] hand = hand(.6f, .5f);
        float[] f = SignFeatures.extract(new float[][][]{hand}, pose(), 1000, 500);
        assertEquals(0f, f[0], 0f);
        assertEquals(1f, f[15], .00001f); // index MCP: .1 normalized x / .1 palm length
        assertEquals(.5f, f[63], .00001f); // wrist .2 height units right / .4 shoulder width
        assertEquals(.5f, f[64], .00001f);
        assertEquals(.5f, f[65], .00001f);
        assertEquals(1f, f[66], 0f);
        assertEquals(-.5f, f[137], .00001f); // left shoulder body-relative x
        assertEquals(1f, f[155], 0f);
    }

    @Test public void portraitAndLandscapeCropsPreservePhysicalGeometry() {
        float[][] h = hand(.6f, .5f);
        float[][] p = pose();
        float[] landscape = SignFeatures.extract(new float[][][]{h}, p, 1000, 500);
        // Same image plane coordinates after a narrower horizontal crop; z is width-normalized.
        for (float[] point : h) { point[0] = (point[0] - .5f) * 4f + .5f; point[2] *= 4f; }
        for (float[] point : p) { point[0] = (point[0] - .5f) * 4f + .5f; point[2] *= 4f; }
        assertArrayEquals(landscape, SignFeatures.extract(new float[][][]{h}, p, 250, 500), .00001f);
    }

    @Test public void handSlotsFollowWristPositionRegardlessOfDetectorOrder() {
        float[][] left = hand(.3f, .4f), right = hand(.7f, .5f);
        assertArrayEquals(SignFeatures.extract(new float[][][]{left, right}, pose(), 640, 480),
                SignFeatures.extract(new float[][][]{right, left}, pose(), 640, 480), 0f);
    }

    @Test public void mirroringTheWholeDetectorImageReflectsOnlyHorizontalFeatures() {
        float[][] h = hand(.6f, .5f), p = pose();
        for (float[] point : h) point[0] = 1f - point[0];
        for (float[] point : p) point[0] = 1f - point[0];
        float[] mirrored = SignFeatures.extract(new float[][][]{h}, p, 1000, 500);
        assertEquals(-1f, mirrored[15], .00001f);
        assertEquals(-.5f, mirrored[63], .00001f);
        assertEquals(.5f, mirrored[64], .00001f);
        assertEquals(.5f, mirrored[65], .00001f);
        assertEquals(.5f, mirrored[137], .00001f);
        assertEquals(1f, mirrored[155], 0f);
    }

    @Test public void malformedAndNonFiniteDetectionsDoNotBecomePresent() {
        float[][] malformed = hand(.4f, .5f);
        malformed[7][0] = Float.NaN;
        float[][] p = pose();
        p[11][3] = 0f;
        float[] f = SignFeatures.extract(new float[][][]{malformed}, p, 640, 480);
        assertEquals(0f, f[66], 0f);
        assertEquals(0f, f[155], 0f);
        for (float v : f) assertTrue(Float.isFinite(v));
    }

    @Test public void nonFinitePoseOutsideSelectedJointsStillInvalidatesBodyLikeTraining() {
        float[][] p = pose();
        p[20][2] = Float.NaN;
        float[] f = SignFeatures.extract(new float[][][]{hand(.6f, .5f)}, p, 640, 480);
        assertEquals(1f, f[66], 0f);
        assertEquals(0f, f[155], 0f);
    }

    static float[][] hand(float x, float y) {
        float[][] h = new float[21][3];
        for (float[] p : h) { p[0] = x; p[1] = y; }
        for (int j : new int[]{5, 9, 13, 17}) h[j][0] += .1f;
        return h;
    }

    static float[][] pose() {
        float[][] p = new float[33][4];
        for (float[] point : p) { point[0] = .5f; point[1] = .3f; point[3] = 1f; }
        p[11][0] = .4f;
        p[12][0] = .6f;
        return p;
    }
}
