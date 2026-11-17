package com.benten.senyas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Shared tutor availability contract. All dates and start times refer to Philippine time. */
public final class LessonAvailability {
    public static final int MAX_DAYS = 100;
    public enum Error { NONE, DATE, TIME, PAST }
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("h:mm a").toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    private LessonAvailability() { }

    public static String formatDay(LocalDate day) { return DAY.format(day); }
    public static LocalDate parseDay(String label) {
        if (label == null) return null;
        try {
            String date = label.substring(label.indexOf(',') + 1).trim();
            return LocalDate.parse(date, DATE);
        } catch (RuntimeException invalid) { return null; }
    }

    /** Invalid members invalidate the complete list rather than silently changing availability. */
    public static List<LocalDate> parseDays(List<String> labels) {
        if (labels == null || labels.isEmpty() || labels.size() > MAX_DAYS) return Collections.emptyList();
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (String label : labels) {
            LocalDate date = parseDay(label);
            if (date == null) return Collections.emptyList();
            dates.add(date);
        }
        return new ArrayList<>(dates);
    }

    public static List<LocalDate> days(Lesson lesson) {
        if (lesson == null) return Collections.emptyList();
        return parseDays(lesson.days.isEmpty() ? Collections.singletonList(lesson.day) : lesson.days);
    }

    public static List<String> dateLabels(Lesson lesson) {
        List<String> labels = new ArrayList<>();
        for (LocalDate date : days(lesson)) labels.add(formatDay(date));
        return labels;
    }

    public static void setDays(Lesson lesson, List<String> labels) {
        List<LocalDate> dates = parseDays(labels);
        if (labels == null || (!labels.isEmpty() && dates.isEmpty())) throw new IllegalArgumentException("Invalid availability dates");
        lesson.days.clear();
        for (LocalDate date : dates) lesson.days.add(formatDay(date));
        lesson.day = lesson.days.isEmpty() ? "" : lesson.days.get(0);
    }

    /** Empty means missing or invalid; partial malformed lists never become bookable. */
    public static List<Integer> parseSlots(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        TreeSet<Integer> minutes = new TreeSet<>();
        try {
            for (String token : text.split(",", -1)) {
                String timeText = token.trim();
                if (!timeText.matches("(?i)([1-9]|1[0-2]):[0-5][0-9]\\s+[ap]m")) return Collections.emptyList();
                LocalTime time = LocalTime.parse(timeText.replaceAll("\\s+", " "), TIME);
                if (!minutes.add(time.getHour() * 60 + time.getMinute())) return Collections.emptyList();
            }
            return new ArrayList<>(minutes);
        } catch (RuntimeException invalid) { return Collections.emptyList(); }
    }

    public static String formatSlots(List<Integer> minutes) {
        List<String> labels = new ArrayList<>();
        for (int minute : new TreeSet<>(minutes)) {
            labels.add(LocalTime.of(minute / 60, minute % 60).format(TIME).toLowerCase(Locale.ENGLISH));
        }
        return String.join(", ", labels);
    }

    public static Error validate(String dayLabel, String text, int duration, long now) {
        return validate(Collections.singletonList(dayLabel), text, duration, now);
    }

    public static Error validate(List<String> dayLabels, String text, int duration, long now) {
        List<LocalDate> days = parseDays(dayLabels);
        if (days.isEmpty()) return Error.DATE;
        List<Integer> slots = parseSlots(text);
        if (slots.isEmpty() || duration <= 0 || duration > 120) return Error.TIME;
        for (LocalDate day : days) for (int minute : slots) {
            if (minute + duration > 1440) return Error.TIME;
            if (SessionTime.startAt(day, minute) <= now) return Error.PAST;
        }
        return Error.NONE;
    }

    public static boolean offers(Lesson lesson, long start, int duration) {
        if (lesson == null || start <= 0 || duration <= 0 || duration > 120) return false;
        for (LocalDate day : days(lesson)) for (int minute : parseSlots(lesson.slots)) {
            if (minute + duration <= 1440 && SessionTime.startAt(day, minute) == start) return true;
        }
        return false;
    }
}
