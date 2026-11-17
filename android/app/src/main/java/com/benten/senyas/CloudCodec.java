package com.benten.senyas;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Firestore wire records. These helpers copy mutable UI input before any asynchronous work. */
final class CloudCodec {
    private CloudCodec() { }

    static Map<String, Object> lessonPayload(Lesson source, int id, String uid, String tutor) {
        if (source == null || id <= 0 || uid == null || uid.isEmpty()) invalid("Invalid listing");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("tutorId", uid); out.put("tutor", text(tutor, 100, true));
        out.put("title", text(source.title, 200, true));
        if (!java.util.Arrays.asList(Repo.CATEGORIES).contains(source.category)) invalid("Invalid category");
        out.put("category", source.category); out.put("description", text(source.description, 10000, false));
        if (source.price < 1 || source.price > 10000000 || !duration(source.duration)) invalid("Invalid price or duration");
        out.put("price", source.price); out.put("duration", source.duration);
        out.put("available", source.available); out.put("reviewed", false);
        List<String> days = new ArrayList<>(LessonAvailability.dateLabels(source));
        List<Integer> slots = LessonAvailability.parseSlots(source.slots);
        if (days.isEmpty() || days.size() > 100 || slots.isEmpty()) invalid("Choose valid dates and start times");
        List<Long> offers = new ArrayList<>();
        for (String day : days) for (int minute : slots) {
            if (minute + source.duration > 1440) invalid("A session must finish on its selected day");
            offers.add(SessionTime.startAt(LessonAvailability.parseDay(day), minute));
        }
        if (offers.size() > 4096) invalid("Too many offered times");
        out.put("days", days); out.put("slots", LessonAvailability.formatSlots(slots)); out.put("offers", offers);
        out.put("city", text(source.city, 200, true));
        if (!Double.isFinite(source.distanceKm) || source.distanceKm < 0) invalid("Invalid distance");
        out.put("distanceKm", source.distanceKm); out.put("tileColor", source.tileColor);
        out.put("darkText", source.darkText); out.put("hasCover", source.hasCover); out.put("hasPreview", source.hasPreview);
        if (source.previewSeconds < 0 || source.previewSeconds > 3600) invalid("Invalid preview duration");
        out.put("previewSeconds", source.hasPreview ? source.previewSeconds : 0);
        return out;
    }

    static Lesson lesson(Map<String, Object> data) {
        if (data == null) invalid("Listing no longer exists");
        Lesson out = new Lesson(integer(data, "id"), string(data, "title"), string(data, "tutor"),
                Repo.initialsOf(string(data, "tutor")), string(data, "category"), number(data, "distanceKm").doubleValue(),
                integer(data, "price"), integer(data, "duration"), bool(data, "available"), string(data, "description"),
                integer(data, "previewSeconds"), integer(data, "tileColor"), bool(data, "darkText"));
        out.tutorId = string(data, "tutorId"); out.city = string(data, "city"); out.slots = string(data, "slots");
        out.reviewed = bool(data, "reviewed"); out.hasCover = bool(data, "hasCover"); out.hasPreview = bool(data, "hasPreview");
        Object labels = data.get("days");
        if (!(labels instanceof List)) invalid("Invalid listing dates");
        List<String> days = new ArrayList<>();
        for (Object day : (List<?>) labels) {
            if (!(day instanceof String)) invalid("Invalid listing date");
            days.add((String) day);
        }
        LessonAvailability.setDays(out, days);
        if (out.id <= 0 || out.tutorId.isEmpty() || out.price <= 0 || !duration(out.duration)) invalid("Invalid listing record");
        return out;
    }

    static void copyLesson(Lesson from, Lesson to, String actorUid) {
        to.id = from.id; to.title = from.title; to.tutor = from.tutor; to.initials = from.initials;
        to.tutorId = from.tutorId; to.category = from.category; to.description = from.description;
        to.distanceKm = from.distanceKm; to.price = from.price; to.duration = from.duration;
        to.available = from.available; to.previewSeconds = from.previewSeconds; to.tileColor = from.tileColor;
        to.darkText = from.darkText; to.city = from.city; to.day = from.day;
        to.days.clear(); to.days.addAll(from.days); to.slots = from.slots; to.reviewed = from.reviewed;
        to.hasCover = from.hasCover; to.hasPreview = from.hasPreview; to.mine = actorUid.equals(from.tutorId);
    }

    static Map<String, Object> bookingPayload(String reference, Lesson lesson, String learnerId, String learner,
            long start, int duration, int price, String paymentKind, String last4) {
        if (!duration(duration) || start <= 0 || start % 60000L != 0 || price <= 0
                || SessionTime.endAt(start, duration) <= start) invalid("Invalid booking");
        if (!("GCash".equals(paymentKind) || "Maya".equals(paymentKind) || "Card".equals(paymentKind))
                || !(last4.isEmpty() || last4.matches("[0-9]{4}"))
                || ("Card".equals(paymentKind) != !last4.isEmpty())) invalid("Invalid payment details");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", reference); out.put("lessonId", lesson.id);
        out.put("learnerId", learnerId); out.put("tutorId", lesson.tutorId);
        out.put("learner", learner); out.put("tutor", lesson.tutor); out.put("title", lesson.title); out.put("category", lesson.category);
        out.put("startAtMillis", start); out.put("endAtMillis", SessionTime.endAt(start, duration));
        out.put("duration", duration); out.put("price", price); out.put("paymentKind", paymentKind); out.put("paymentLast4", last4);
        out.put("cancelled", false); out.put("released", false);
        return out;
    }

    static Repo.Booking booking(Map<String, Object> data) {
        Repo.Booking out = new Repo.Booking();
        out.reference = string(data, "reference"); out.learnerId = string(data, "learnerId"); out.tutorId = string(data, "tutorId");
        out.title = string(data, "title"); out.tutor = string(data, "tutor"); out.duration = integer(data, "duration"); out.price = integer(data, "price");
        out.startAtMillis = number(data, "startAtMillis").longValue(); out.endAtMillis = number(data, "endAtMillis").longValue();
        out.cancelled = bool(data, "cancelled"); out.past = System.currentTimeMillis() >= out.endAtMillis;
        ZonedDateTime start = Instant.ofEpochMilli(out.startAtMillis).atZone(SessionTime.ZONE);
        out.dayLabel = LessonAvailability.formatDay(start.toLocalDate());
        out.timeRange = Ui.timeRange(start.getHour() * 60 + start.getMinute(), out.duration);
        String kind = string(data, "paymentKind"), last4 = string(data, "paymentLast4");
        out.payment = "Card".equals(kind) ? "Card •••• " + last4 : kind;
        if (!duration(out.duration) || out.startAtMillis <= 0 || out.endAtMillis != SessionTime.endAt(out.startAtMillis, out.duration)
                || out.reference.isEmpty()) invalid("Invalid booking record");
        return out;
    }

    static Repo.TutorSession session(Map<String, Object> data, Repo.Booking booking) {
        Repo.TutorSession out = new Repo.TutorSession();
        out.learner = string(data, "learner"); out.tutor = booking.tutor; out.title = booking.title; out.category = string(data, "category");
        out.learnerId = booking.learnerId; out.tutorId = booking.tutorId; out.reference = booking.reference;
        out.dayLabel = booking.dayLabel; out.timeRange = booking.timeRange; out.duration = booking.duration; out.price = booking.price;
        out.startAtMillis = booking.startAtMillis; out.endAtMillis = booking.endAtMillis; out.cancelled = booking.cancelled; out.past = booking.past;
        return out;
    }

    static long dayBucket(long start) { return Math.floorDiv(start + 28800000L, 86400000L); }
    static String dayId(String tutorId, long start) { return tutorId + "_" + dayBucket(start); }
    static Map<String, Object> busy(Map<String, Object> day) {
        Map<String, Object> copy = new HashMap<>();
        if (day == null) return copy;
        Object value = day.get("busy");
        if (!(value instanceof Map)) invalid("Invalid availability record");
        for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
            if (!(item.getKey() instanceof String) || !Boolean.TRUE.equals(item.getValue())) invalid("Invalid occupied time");
            copy.put((String) item.getKey(), true);
        }
        return copy;
    }
    static Map<String, Object> reserveMinutes(Map<String, Object> busy, long start, int duration) {
        checkMinutes(start, duration);
        Map<String, Object> out = new HashMap<>(busy);
        for (int i = 0; i < duration; i++) {
            String key = Long.toString(start / 60000L + i);
            if (out.containsKey(key)) throw new IllegalStateException("That tutor time was just booked. Choose another time.");
            out.put(key, true);
        }
        return out;
    }
    static Map<String, Object> releaseMinutes(Map<String, Object> busy, long start, int duration) {
        checkMinutes(start, duration);
        Map<String, Object> out = new HashMap<>(busy);
        for (int i = 0; i < duration; i++) {
            String key = Long.toString(start / 60000L + i);
            if (!Boolean.TRUE.equals(out.remove(key))) throw new IllegalStateException("The booking occupancy is inconsistent");
        }
        return out;
    }
    static Map<String, Object> dayPayload(String tutor, long start, String reference, Map<String, Object> busy) {
        Map<String, Object> out = new HashMap<>(); out.put("tutorId", tutor); out.put("day", dayBucket(start));
        out.put("bookingId", reference); out.put("busy", new HashMap<>(busy)); return out;
    }
    static boolean offered(Map<String, Object> lesson, long start) {
        Object offers = lesson.get("offers");
        if (!(offers instanceof List)) return false;
        for (Object offer : (List<?>) offers) if (offer instanceof Number && ((Number) offer).longValue() == start) return true;
        return false;
    }
    static boolean duration(int value) { return value == 30 || value == 60; }
    private static void checkMinutes(long start, int duration) {
        if (start <= 0 || start % 60000L != 0 || !duration(duration)
                || dayBucket(start) != dayBucket(SessionTime.endAt(start, duration) - 1)) invalid("Invalid session time");
    }
    static String string(Map<String, Object> data, String key) {
        Object value = data.get(key); if (!(value instanceof String)) invalid("Missing " + key); return (String) value;
    }
    static Number number(Map<String, Object> data, String key) {
        Object value = data.get(key); if (!(value instanceof Number)) invalid("Missing " + key); return (Number) value;
    }
    static int integer(Map<String, Object> data, String key) {
        Number value = number(data, key); long result = value.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE || value.doubleValue() != result) invalid("Invalid " + key);
        return (int) result;
    }
    static boolean bool(Map<String, Object> data, String key) {
        Object value = data.get(key); if (!(value instanceof Boolean)) invalid("Missing " + key); return (Boolean) value;
    }
    private static String text(String value, int max, boolean required) {
        if (value == null || value.length() > max || (required && value.trim().isEmpty())) invalid("Incomplete listing details");
        return value.trim();
    }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
