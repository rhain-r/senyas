package com.senyas.cv;

import android.content.Context;

import com.senyas.ml.ReferenceClip;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * The built-in references in assets/sign_refs/{label}.json: both hands tracked from the FSL-105
 * demonstration clips of the sign, several signers each. Learners never record anything; Sign
 * Lab recordings are only used for signs without built-in references.
 */
public final class SignReferences {
    static final String DIR = "sign_refs";

    private SignReferences() {
    }

    /** The sign's built-in references, or an empty list when it has none. */
    public static List<ReferenceClip> load(Context context, String label) {
        try (InputStream in = context.getAssets().open(DIR + "/" + label + ".json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n = in.read(buf); n > 0; n = in.read(buf)) out.write(buf, 0, n);
            return ReferenceClip.parseSet(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return Collections.emptyList(); // no built-in reference for this sign
        }
    }
}
