package com.benten.senyas;

import java.util.regex.Pattern;

/**
 * What the app accepts in its text fields. Letters of any language, digits and ordinary
 * punctuation; never emoji, symbols or control characters, so listings and names stay readable.
 */
public final class InputRules {
    /** A person's name: words of letters joined by single spaces, dots, hyphens or apostrophes. */
    private static final Pattern NAME = Pattern.compile("\\p{L}+(?:[ .'-]{1,2}\\p{L}+)*\\.?");
    /** A city or place: starts with a letter; letters, digits, spaces and . , ' - after it. */
    private static final Pattern CITY = Pattern.compile("\\p{L}[\\p{L}\\d .,'-]*");
    /** A lesson title: letters, digits, spaces and common punctuation. */
    private static final Pattern TITLE = Pattern.compile("[\\p{L}\\d][\\p{L}\\d .,'&:()/!?+-]*");
    /** A description: any letters, digits, spaces, line breaks and punctuation. */
    private static final Pattern TEXT = Pattern.compile("[\\p{L}\\p{N}\\p{P}\\p{Zs}\\n₱%$+=<>]*");
    private static final Pattern LETTER = Pattern.compile(".*\\p{L}.*", Pattern.DOTALL);
    private static final Pattern REPEATS = Pattern.compile(".*(\\S)\\1{3,}.*", Pattern.DOTALL);
    private static final Pattern SPACES = Pattern.compile(".*\\s{2,}.*", Pattern.DOTALL);

    private InputRules() {
    }

    public static boolean isPersonName(String s) {
        return s != null && s.length() >= 2 && s.length() <= 60 && NAME.matcher(s).matches();
    }

    public static boolean isCity(String s) {
        return s != null && s.length() >= 2 && s.length() <= 60 && CITY.matcher(s).matches()
                && !SPACES.matcher(s).matches() && !REPEATS.matcher(s).matches();
    }

    public static boolean isTitle(String s) {
        return s != null && s.length() >= 3 && s.length() <= 80 && TITLE.matcher(s).matches()
                && LETTER.matcher(s).matches() && !SPACES.matcher(s).matches() && !REPEATS.matcher(s).matches();
    }

    /** Empty is allowed (the description is optional); otherwise 10 to 600 readable characters. */
    public static boolean isDescription(String s) {
        if (s == null || s.isEmpty()) return true;
        return s.length() >= 10 && s.length() <= 600 && TEXT.matcher(s).matches()
                && LETTER.matcher(s).matches() && !REPEATS.matcher(s).matches();
    }

    /** A session rate in pesos: whole number from 50 to 10,000. */
    public static boolean isRate(int pesos) {
        return pesos >= 50 && pesos <= 10_000;
    }
}
