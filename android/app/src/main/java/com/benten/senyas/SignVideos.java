package com.benten.senyas;

import android.content.Context;

import androidx.annotation.Nullable;

import com.senyas.cv.SignSample;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where a sign's demonstration video lives in assets, named by its label ("salamat.mp4",
 * "magandang_umaga.mp4"). The team's own recordings go in signs/ and win; signs/fsl105/ holds clips
 * from the FSL-105 dataset (De La Salle University, CC BY 4.0), which must be credited on screen.
 */
public final class SignVideos {
    private static final String OWN = "signs/";
    private static final String FSL105 = "signs/fsl105/";

    private SignVideos() {}

    /** The asset path of this sign's clip, or null when the app has none yet. */
    @Nullable
    public static String find(Context c, String word) {
        String name = SignSample.normalizeLabel(word) + ".mp4";
        if (exists(c, OWN + name)) return OWN + name;
        if (exists(c, FSL105 + name)) return FSL105 + name;
        return null;
    }

    public static boolean isFsl105(String path) {
        return path.startsWith(FSL105);
    }

    private static boolean exists(Context c, String asset) {
        try (InputStream ignored = c.getAssets().open(asset)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
