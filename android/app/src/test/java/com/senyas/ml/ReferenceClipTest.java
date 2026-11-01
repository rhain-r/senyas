package com.senyas.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** The built-in references shipped in assets/sign_refs. */
public class ReferenceClipTest {
    /** Every sign of the practice scenarios and the dictionary has built-in references. */
    static final String[] SIGNS = {"kumusta", "magandang_umaga", "magandang_gabi", "salamat", "walang_anuman",
            "oo", "hindi", "nanay", "tatay", "magandang_hapon", "kita_tayo_bukas", "naiintindihan",
            "hindi_naiintindihan", "alam_ko", "hindi_ko_alam", "mali", "tama", "mabagal", "mabilis", "dalawa", "lima",
            "tinapay", "itlog", "gatas", "kape", "asukal"};

    /** Unit tests run in the app module, so the assets are read straight from the source tree. */
    static List<ReferenceClip> load(String sign) throws Exception {
        File f = new File("src/main/assets/sign_refs/" + sign + ".json");
        return ReferenceClip.parseSet(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void everyCoveredSignShipsReferencesFromFourSigners() throws Exception {
        for (String sign : SIGNS) {
            List<ReferenceClip> refs = load(sign);
            assertEquals(sign, 8, refs.size());
            for (ReferenceClip r : refs) {
                assertTrue(sign + " " + r.clip, r.size() >= 5);
                assertEquals(0, r.t[0]);
                int dominant = 0;
                for (int i = 0; i < r.size(); i++) {
                    if (i > 0) assertTrue(r.t[i] > r.t[i - 1]);
                    if (r.dom[i] != null) dominant++;
                }
                assertTrue(sign + " " + r.clip + " dominant hand seen", dominant >= r.size() / 2);
                assertNotNull(r.clip);
            }
        }
    }

    @Test
    public void salamatIsSignedWithBothHands() throws Exception {
        int both = 0, frames = 0;
        for (ReferenceClip r : load("salamat")) {
            for (int i = 0; i < r.size(); i++) {
                frames++;
                if (r.dom[i] != null && r.nd[i] != null) both++;
            }
        }
        assertTrue("both hands in most salamat frames", both > frames / 2);
    }
}
