package com.benten.senyas;

/** Exact app-word mappings to the licensed FSL-105 dataset labels. */
final class FslSigns {
    private FslSigns() {}
    static int label(String word) {
        if (word == null) return -1;
        switch (word) {
            case "Magandang umaga": return 0;
            case "Magandang gabi": return 2;
            case "Kumusta": return 3;
            case "Salamat": return 7;
            case "Walang anuman": return 8;
            case "Hindi": return 14;
            case "Oo": return 15;
            case "Tatay": return 52;
            case "Nanay": return 53;
            default: return -1;
        }
    }
}
