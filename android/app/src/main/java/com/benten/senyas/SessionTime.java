package com.benten.senyas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

/** Booking timestamps use Philippine time; display labels never confer permission to join. */
public final class SessionTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Manila");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .appendPattern("h:mm a").toFormatter(Locale.ENGLISH);
    private SessionTime() { }

    public static long startAt(LocalDate day, int minutes) {
        if (day == null || minutes < 0 || minutes >= 1440) return 0;
        return day.atTime(minutes / 60, minutes % 60).atZone(ZONE).toInstant().toEpochMilli();
    }

    public static long endAt(long start, int durationMinutes) {
        if (start <= 0 || durationMinutes <= 0) return 0;
        try { return Math.addExact(start, Math.multiplyExact((long) durationMinutes, 60000L)); }
        catch (ArithmeticException invalid) { return 0; }
    }

    public static long parseStart(String dayLabel, String range) {
        if (dayLabel == null || range == null) return 0;
        try {
            String day = dayLabel.substring(dayLabel.indexOf(',') + 1).trim();
            if (!Character.isDigit(day.charAt(0))) day = day.replaceFirst("^[A-Za-z]+\\s+", "");
            String[] times = range.split("[–—-]", 2);
            String first = times[0].trim().toLowerCase(Locale.ENGLISH);
            if (!first.endsWith("am") && !first.endsWith("pm")) {
                if (times.length != 2) return 0;
                String last = times[1].trim().toLowerCase(Locale.ENGLISH);
                if (!last.endsWith("am") && !last.endsWith("pm")) return 0;
                first += " " + last.substring(last.length() - 2);
            }
            LocalTime time = LocalTime.parse(first, TIME);
            return startAt(LocalDate.parse(day, DAY), time.getHour() * 60 + time.getMinute());
        } catch (RuntimeException invalid) { return 0; }
    }
}
