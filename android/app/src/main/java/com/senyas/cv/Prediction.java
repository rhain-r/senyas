package com.senyas.cv;

/** One candidate sign and how likely the classifier thinks it is. */
public final class Prediction {
    public final String label;
    /** 0..1 */
    public final float probability;

    public Prediction(String label, float probability) {
        this.label = label;
        this.probability = probability;
    }

    public int percent() {
        return Math.round(probability * 100);
    }

    /** "good_morning" becomes "Good morning". */
    public static String displayName(String label) {
        if (label == null || label.isEmpty()) return "";
        String s = label.replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public String toString() {
        return label + " " + percent() + "%";
    }
}
