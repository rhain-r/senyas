package com.benten.senyas;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class SequenceRecognizerTest {
    @Test public void completedAttemptCanBeGradedOnlyOnce() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        moving(engine, 2, 0);
        java.lang.reflect.Method finish = SequenceRecognizer.class.getDeclaredMethod("finishAttempt", long.class, int.class);
        SequenceRecognizer.Prediction result = (SequenceRecognizer.Prediction) finish.invoke(engine, 2300L, 2);
        assertTrue(result.accepted);
        assertTrue(SequenceRecognizer.Prediction.class.getField("completed").getBoolean(result));
        SequenceRecognizer.Prediction repeated = (SequenceRecognizer.Prediction) finish.invoke(engine, 2800L, 2);
        assertFalse("An empty completed attempt must not reuse a previous match", repeated.accepted);
    }

    @Test public void completedWrongTargetCannotBeAccepted() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        moving(engine, 1, 0);
        java.lang.reflect.Method finish = SequenceRecognizer.class.getDeclaredMethod("finishAttempt", long.class, int.class);
        SequenceRecognizer.Prediction result = (SequenceRecognizer.Prediction) finish.invoke(engine, 2300L, 1);
        assertFalse(result.accepted); assertTrue(result.scorePercent < 85);
    }
    @Test public void executesDenseLayersTemperatureAndTargetThreshold() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        SequenceRecognizer.Prediction p = moving(engine, 2, 0);
        assertTrue(p.ready);
        assertEquals(2, p.topClassId);
        // Hand-calculated: exp(12/2) / (exp(12/2) + 104).
        assertEquals(.795046f, p.targetConfidence, .00001f);
        assertTrue(p.accepted);
        assertTrue(p.margin > .79f);
        assertEquals(2, p.targetClassId);
        assertEquals(97, p.scorePercent);
        assertTrue(p.evaluatedAtMs >= 700);
    }

    @Test public void highProbabilityOfAnotherClassDoesNotAcceptTarget() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        SequenceRecognizer.Prediction p = moving(engine, 1, 0);
        assertEquals(2, p.topClassId);
        assertTrue(p.targetConfidence < .01f);
        assertFalse(p.accepted);
        assertTrue(p.scorePercent < 85);
    }

    @Test public void emptyAndStaticSequencesHaveZeroUsableConfidence() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        for (int i = 0; i < 20; i++) {
            SequenceRecognizer.Prediction p = engine.addFrame(frame(0f), i * 100, 2);
            assertFalse(p.accepted);
            assertEquals(0f, p.targetConfidence, 0f);
        }
        float[] empty = new float[156];
        SequenceRecognizer.Prediction p = engine.addFrame(empty, 2100, 2);
        assertEquals("missing_hands", p.reason);
        assertEquals(0f, p.targetConfidence, 0f);
    }

    @Test public void resetRequiresFreshSequenceBeforeAnotherAttempt() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        engine.reset();
        SequenceRecognizer.Prediction p = engine.addFrame(frame(.2f), 2000, 2);
        assertFalse(p.ready);
        assertFalse(p.accepted);
        assertEquals(0f, p.targetConfidence, 0f);
    }

    @Test public void noHandsAndTimestampDiscontinuityDiscardOldEvidence() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        engine.addFrame(new float[156], 2300, 2);
        assertFalse(engine.addFrame(frame(.2f), 2400, 2).ready);
        assertTrue(moving(engine, 2, 3000).accepted);
        assertFalse(engine.addFrame(frame(.2f), 100, 2).ready);
        assertFalse(engine.addFrame(frame(.2f), 10000, 2).ready);
    }

    @Test public void missingBodyAndUnknownTargetCannotEarnAcceptance() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        for (int i = 0; i < 20; i++) {
            float[] f = frame(i * .03f);
            f[155] = 0;
            SequenceRecognizer.Prediction p = engine.addFrame(f, i * 100, 2);
            assertFalse(p.accepted);
            assertEquals(0f, p.targetConfidence, 0f);
        }
        assertFalse(moving(engine, -1, 3000).accepted);
    }

    @Test public void malformedModelFilesAreRejectedBeforeInference() throws Exception {
        assertInvalid(new byte[0]);
        byte[] bytes = model();
        bytes[0] = 'X';
        assertInvalid(bytes);
        bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(24, Float.NaN);
        assertInvalid(bytes);
        bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Integer.MAX_VALUE);
        assertInvalid(bytes);
        bytes = model();
        assertInvalid(java.util.Arrays.copyOf(bytes, bytes.length - 1));
    }

    @Test public void nonFiniteFrameDoesNotReusePreviousPrediction() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        float[] invalid = frame(.2f);
        invalid[4] = Float.POSITIVE_INFINITY;
        SequenceRecognizer.Prediction p = engine.addFrame(invalid, 2000, 2);
        assertEquals(0f, p.targetConfidence, 0f);
        assertFalse(p.accepted);
    }

    @Test public void disabledCalibrationTargetsAreUnavailable() throws Exception {
        byte[] bytes = model();
        int thresholdStart = bytes.length - 860;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(thresholdStart + 2 * 4, 1.01f);
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(bytes));
        assertFalse(engine.supportsTarget(2));
        assertTrue(engine.supportsTarget(1));
        assertFalse(engine.supportsTarget(-1));
        assertFalse(engine.supportsTarget(105));
        SequenceRecognizer.Prediction p = moving(engine, 2, 0);
        assertEquals("unknown_target", p.reason);
        assertEquals(2, p.targetClassId);
        assertEquals(0, p.scorePercent);
    }

    @Test public void repeatedFramesWithinThrottleDoNotCountAsSeparatePredictions() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        SequenceRecognizer.Prediction first = moving(engine, 2, 0);
        SequenceRecognizer.Prediction repeated = engine.addFrame(frame(.6f), 1950, 2);
        assertEquals(first.evaluatedAtMs, repeated.evaluatedAtMs);
        assertEquals(first.targetClassId, repeated.targetClassId);
    }

    @Test public void exposesTheSameCpuMathForOfflineTrainingParity() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        float[][] sequence = new float[16][156];
        sequence[0][0] = 1f;
        float[] scores = engine.scoreSequence(sequence);
        assertEquals(.795046f, scores[2], .00001f);
        assertEquals(.00197071f, scores[0], .000001f);
        float sum = 0;
        for (float p : scores) sum += p;
        assertEquals(1f, sum, .00001f);
    }

    @Test public void failedMarginCannotCrossTheUiDecisionMarker() throws Exception {
        byte[] bytes = model();
        int marginStart = bytes.length - 440;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(marginStart + 2 * 4, .99f);
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(bytes));
        SequenceRecognizer.Prediction p = moving(engine, 2, 0);
        assertTrue(p.ready);
        assertEquals("uncertain", p.reason);
        assertFalse(p.accepted);
        assertEquals(84, p.scorePercent);
    }

    @Test public void targetChangeRequiresNewEvidenceEvenIfPreviousTargetMatched() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        SequenceRecognizer.Prediction p = engine.addFrame(frame(.6f), 2000, 1);
        assertEquals(1, p.targetClassId);
        assertFalse(p.ready);
        assertFalse(p.accepted);
        assertEquals(0, p.scorePercent);
    }

    @Test public void irregularCameraCadenceResamplesByTimestampRatherThanFrameIndex() throws Exception {
        byte[] bytes = model();
        ByteBuffer weights = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        weights.putFloat(24, 0f);
        weights.putFloat(24 + 8 * 156 * 2 * 4, 2f); // sample 8's first feature drives logits
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(bytes));
        SequenceRecognizer.Prediction p = null;
        for (int time : new int[]{0, 70, 180, 310, 450, 590, 780, 1000}) {
            float[] f = frame(time * .001f);
            f[0] = time * .001f;
            p = engine.addFrame(f, time, 2);
        }
        assertTrue(p.ready);
        assertEquals(1000, p.evaluatedAtMs);
        // Uniform sample 8/15 of a second: logit12*(8/15)/temperature2 = 3.2.
        assertEquals(.190866f, p.targetConfidence, .00001f);
    }

    @Test public void shortTrackingDropoutIsZeroScoredButPreservesTheAttempt() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        SequenceRecognizer.Prediction blank = engine.addFrame(new float[156], 2000, 2);
        assertFalse(blank.accepted);
        assertEquals(0, blank.scorePercent);
        assertEquals(0f, blank.targetConfidence, 0f);
        SequenceRecognizer.Prediction returned = engine.addFrame(frame(.63f), 2100, 2);
        assertTrue(returned.ready);
        assertTrue(returned.accepted);
        assertEquals(2100, returned.evaluatedAtMs);
    }

    @Test public void prolongedBodyTrackingLossRequiresANewAttempt() throws Exception {
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(model()));
        assertTrue(moving(engine, 2, 0).accepted);
        float[] hidden = frame(.6f);
        hidden[155] = 0;
        assertEquals(0, engine.addFrame(hidden, 2000, 2).scorePercent);
        assertEquals(0, engine.addFrame(hidden, 2300, 2).scorePercent);
        assertFalse(engine.addFrame(frame(.65f), 2400, 2).ready);
    }

    @Test public void finiteWeightsThatOverflowDuringInferenceCannotProduceAResult() throws Exception {
        byte[] bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(24, Float.MAX_VALUE);
        SequenceRecognizer engine = SequenceRecognizer.load(new ByteArrayInputStream(bytes));
        SequenceRecognizer.Prediction p = moving(engine, 2, 0);
        assertEquals("invalid_frame", p.reason);
        assertEquals(0, p.scorePercent);
        assertFalse(p.accepted);
    }

    private static SequenceRecognizer.Prediction moving(SequenceRecognizer engine, int target, long start) {
        SequenceRecognizer.Prediction p = null;
        for (int i = 0; i < 20; i++) p = engine.addFrame(frame(i * .03f), start + i * 100, target);
        return p;
    }

    private static float[] frame(float wristX) {
        float[] f = new float[156];
        f[0] = 1f;
        f[63] = wristX;
        f[66] = 1f;
        f[155] = 1f;
        return f;
    }

    static byte[] model() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'F', 'S', 'L', '1'});
        for (int n : new int[]{16, 156, 3}) integer(out, n);
        int[][] dims = {{2496, 2}, {2, 2}, {2, 105}};
        for (int layer = 0; layer < dims.length; layer++) {
            int in = dims[layer][0], size = dims[layer][1];
            integer(out, in); integer(out, size);
            for (int i = 0; i < in; i++) for (int j = 0; j < size; j++) {
                float w = layer == 0 && i == 0 && j == 0 ? 2f
                        : layer == 1 && i == 0 && j == 0 ? 1f
                        : layer == 2 && i == 0 && j == 2 ? 6f : 0f;
                number(out, w);
            }
            for (int j = 0; j < size; j++) number(out, 0f);
        }
        number(out, 2f);
        for (int i = 0; i < 105; i++) number(out, .7f);
        for (int i = 0; i < 105; i++) number(out, .2f);
        integer(out, 700); integer(out, 2000); integer(out, 8);
        number(out, .0001f); integer(out, 8);
        return out.toByteArray();
    }

    private static void integer(ByteArrayOutputStream out, int value) throws IOException {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private static void number(ByteArrayOutputStream out, float value) throws IOException {
        integer(out, Float.floatToIntBits(value));
    }

    private static void assertInvalid(byte[] bytes) throws IOException {
        try {
            SequenceRecognizer.load(new ByteArrayInputStream(bytes));
            fail("Malformed weights must not create a usable classifier");
        } catch (IOException expected) { /* callers can show model-unavailable state */ }
    }
}
