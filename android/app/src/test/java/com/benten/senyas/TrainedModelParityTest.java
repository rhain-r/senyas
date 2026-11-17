package com.benten.senyas;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
public class TrainedModelParityTest {
    @Test public void allProbabilitiesMatchThePythonExporter() throws Exception {
        SequenceRecognizer model;
        try (InputStream input = new FileInputStream("src/main/assets/fsl_recognizer.bin")) { model = SequenceRecognizer.load(input); }
        try (Scanner input = new Scanner(getClass().getResourceAsStream("/fsl-model-golden.txt")).useLocale(Locale.US)) {
            float[][] sequence = new float[16][156];
            for (float[] frame : sequence) for (int j = 0; j < frame.length; j++) frame[j] = input.nextFloat();
            float[] result = model.scoreSequence(sequence);
            for (int i = 0; i < 105; i++) assertEquals("Probability " + i, input.nextFloat(), result[i], .00001f);
        }
        assertTrue(model.supportsTarget(3)); assertFalse(model.supportsTarget(2));
    }
}
