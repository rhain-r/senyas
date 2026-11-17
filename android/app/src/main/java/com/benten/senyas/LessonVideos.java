package com.benten.senyas;

/** Exact concept matches against FSL-105 v2 labels.csv. No substitute signs for missing clips. */
final class LessonVideos {
    private LessonVideos() {}

    static int forWord(String word) {
        if (word == null) return 0;
        switch (word) {
            case "Magandang umaga": return R.raw.fsl105_0; // GOOD MORNING
            case "Magandang gabi": return R.raw.fsl105_2; // GOOD EVENING
            case "Kumusta": return R.raw.fsl105_3; // HELLO (the app's displayed translation)
            case "Salamat": return R.raw.fsl105_7; // THANK YOU
            case "Walang anuman": return R.raw.fsl105_8; // YOURE WELCOME
            case "Hindi": return R.raw.fsl105_14; // NO
            case "Oo": return R.raw.fsl105_15; // YES
            case "Tatay": return R.raw.fsl105_52; // FATHER
            case "Nanay": return R.raw.fsl105_53; // MOTHER
            default: return 0;
        }
    }
}
