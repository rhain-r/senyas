package com.benten.senyas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.senyas.cv.SignSample;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The scenarios and the searchable dictionary only offer signs the app can show and score. */
public class SignDictionaryTest {

    private static Set<String> dictionaryWords() {
        Set<String> words = new HashSet<>();
        for (String[] s : Repo.SIGNS) words.add(s[0]);
        return words;
    }

    @Test
    public void everyScenarioSignIsInTheDictionary() {
        Set<String> words = dictionaryWords();
        assertEquals("no duplicates", Repo.SIGNS.length, words.size());
        Set<String> inScenarios = new HashSet<>();
        for (Scenario sc : Repo.SCENARIOS) {
            for (String[] s : sc.signs) {
                assertTrue(s[0] + " is missing from the dictionary", words.contains(s[0]));
                inScenarios.add(s[0]);
            }
        }
        assertEquals(20, inScenarios.size());
        assertEquals(26, Repo.SIGNS.length);
    }

    @Test
    public void scenariosKeepTheirIdsAndTitles() {
        assertEquals(3, Repo.SCENARIOS.size());
        assertEquals("At the sari-sari store", Repo.SCENARIOS.get(0).title);
        assertEquals("Riding a jeepney", Repo.SCENARIOS.get(1).title);
        assertEquals("Public services", Repo.SCENARIOS.get(2).title);
        for (int i = 0; i < 3; i++) assertEquals(i, Repo.SCENARIOS.get(i).id);
        assertEquals(10, Repo.SCENARIOS.get(0).signs.length);
        assertEquals(6, Repo.SCENARIOS.get(1).signs.length);
        assertEquals(7, Repo.SCENARIOS.get(2).signs.length);
    }

    @Test
    public void everyDictionarySignHasABundledVideoAndReference() {
        File assets = new File("src/main/assets");
        if (!assets.isDirectory()) assets = new File("app/src/main/assets");
        Assume.assumeTrue("assets folder not found from " + new File(".").getAbsolutePath(), assets.isDirectory());
        for (String[] s : Repo.SIGNS) {
            String label = SignSample.normalizeLabel(s[0]);
            assertTrue(label + ".mp4", new File(assets, "signs/fsl105/" + label + ".mp4").isFile()
                    || new File(assets, "signs/" + label + ".mp4").isFile());
            assertTrue(label + ".json", new File(assets, "sign_refs/" + label + ".json").isFile());
        }
    }

    @Test
    public void searchFindsWordsAndTranslations() {
        List<String[]> r = Repo.searchSigns("How do I sign \"you're welcome\"?");
        assertEquals(1, r.size());
        assertEquals("Walang anuman", r.get(0)[0]);
        assertEquals("Hindi ko alam", Repo.searchSigns("i don't know").get(0)[0]);
        assertTrue(Repo.searchSigns("paano i-sign ang salamat").size() >= 1);
        assertTrue(Repo.searchSigns("   ").isEmpty());
        assertTrue(Repo.searchSigns("hospital").isEmpty());
    }

    @Test
    public void dictionaryWordsOpenTheirScenario() {
        assertArrayEquals(new int[]{0, 8}, Repo.findSign("salamat"));
        assertArrayEquals(new int[]{1, 2}, Repo.findSign("Mabilis"));
        assertArrayEquals(new int[]{2, 1}, Repo.findSign("Hindi naiintindihan"));
        assertNull(Repo.findSign("Nanay"));
        assertNull("dictionary-only: signers vary too much to score", Repo.findSign("Mabagal"));
    }
}
