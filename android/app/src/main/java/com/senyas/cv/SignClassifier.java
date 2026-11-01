package com.senyas.cv;

import java.util.List;

/** Reads one encoded sign (see {@link FeatureEncoder}) and says which FSL sign it most looks like. */
public interface SignClassifier {
    /** Shown on screen, e.g. "Trained model (105 signs)". */
    String name();

    List<String> labels();

    /** Every label with its probability, most likely first. Probabilities may sum to less than 1. */
    List<Prediction> classify(float[][] sequence);

    default void close() {
    }
}
