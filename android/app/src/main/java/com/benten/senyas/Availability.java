package com.benten.senyas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tutor's weekly availability: the weekdays they teach (a bit mask, Monday = bit 0) and the
 * start times of their sessions (minutes after midnight, Philippine time). Learners book from it.
 */
public final class Availability {
    /** Monday to Saturday: the sample tutors' week. */
    public static final int MON_TO_SAT = 0b0111111;
    /** Earliest and latest start a tutor may offer. */
    public static final int EARLIEST = 6 * 60, LATEST = 21 * 60;
    /** At most this many time slots per listing. */
    public static final int MAX_TIMES = 12;

    private Availability() {
    }

    public static int bit(DayOfWeek d) {
        return 1 << (d.getValue() - 1);
    }

    public static boolean isOpen(int days, LocalDate date) {
        return (days & bit(date.getDayOfWeek())) != 0;
    }

    /** The next open dates from {@code from} (inclusive) within {@code horizonDays}, at most {@code max}. */
    public static List<LocalDate> nextDates(int days, LocalDate from, int horizonDays, int max) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < horizonDays && out.size() < max; i++) {
            LocalDate d = from.plusDays(i);
            if (isOpen(days, d)) out.add(d);
        }
        return out;
    }

    /** Sorted, without duplicates, and only times a tutor may offer. */
    public static List<Integer> normalize(List<Integer> times) {
        List<Integer> out = new ArrayList<>();
        for (Integer t : times) {
            if (t != null && t >= EARLIEST && t <= LATEST && !out.contains(t)) out.add(t);
        }
        Collections.sort(out);
        return out.size() > MAX_TIMES ? new ArrayList<>(out.subList(0, MAX_TIMES)) : out;
    }

    /** Whether two sessions [start, start + duration) overlap. */
    public static boolean overlaps(int start1, int dur1, int start2, int dur2) {
        return start1 < start2 + dur2 && start2 < start1 + dur1;
    }

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?\\s*m\\.?");

    /** Reads the times from text saved by older versions, such as "3:00 pm, 4:00 pm"; junk is ignored. */
    public static List<Integer> parseLegacy(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = CLOCK.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (h < 1 || h > 12 || min > 59) continue;
            int h24 = h % 12 + ("p".equals(m.group(3)) ? 12 : 0);
            out.add(h24 * 60 + min);
        }
        return normalize(out);
    }
}
