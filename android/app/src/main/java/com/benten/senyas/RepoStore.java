package com.benten.senyas;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Private, atomic snapshots of committed records. Identity, drafts and credentials are excluded. */
final class RepoStore {
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RECORDS = 10000;
    private final AtomicFile file;

    RepoStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "senyas_committed_state.json"));
    }

    /** A failed or unsupported snapshot leaves every live collection untouched. */
    synchronized boolean load() {
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) return false;
                bytes.write(buffer, 0, count);
            }
            Snapshot snapshot = parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
            Repo.LESSONS.clear(); Repo.LESSONS.addAll(snapshot.lessons);
            Repo.BOOKINGS.clear(); Repo.BOOKINGS.addAll(snapshot.bookings);
            Repo.TUTOR_SESSIONS.clear(); Repo.TUTOR_SESSIONS.addAll(snapshot.sessions);
            Repo.PAYOUTS.clear(); Repo.PAYOUTS.addAll(snapshot.payouts);
            Repo.NOTIFS.clear(); Repo.NOTIFS.addAll(snapshot.notifications);
            Repo.nextLessonId = snapshot.nextLessonId;
            Repo.nextRef = snapshot.nextRef;
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    /** AtomicFile retains the previous complete snapshot if writing is interrupted. */
    synchronized boolean save() {
        FileOutputStream output = null;
        try {
            JSONObject json = encode();
            parse(json); // Never replace a good file with a malformed in-memory record.
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return false;
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
            return true;
        } catch (Exception invalid) {
            if (output != null) file.failWrite(output);
            return false;
        }
    }

    private JSONObject encode() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("version", VERSION).put("nextLessonId", Repo.nextLessonId).put("nextRef", Repo.nextRef);
        JSONArray lessons = new JSONArray();
        for (Lesson l : Repo.LESSONS) {
            lessons.put(new JSONObject().put("id", l.id).put("title", l.title).put("tutor", l.tutor)
                    .put("tutorId", l.tutorId).put("initials", l.initials).put("category", l.category)
                    .put("description", l.description).put("distanceKm", l.distanceKm).put("price", l.price)
                    .put("duration", l.duration).put("previewSeconds", l.previewSeconds).put("tileColor", l.tileColor)
                    .put("available", l.available).put("darkText", l.darkText).put("city", l.city)
                    .put("day", l.day).put("days", new JSONArray(l.days)).put("slots", l.slots).put("created", l.created)
                    .put("hasCover", l.hasCover).put("hasPreview", l.hasPreview).put("reviewed", l.reviewed));
        }
        json.put("lessons", lessons);
        JSONArray bookings = new JSONArray();
        for (Repo.Booking b : Repo.BOOKINGS) {
            bookings.put(new JSONObject().put("title", b.title).put("tutor", b.tutor).put("dayLabel", b.dayLabel)
                    .put("timeRange", b.timeRange).put("payment", b.payment).put("reference", b.reference)
                    .put("duration", b.duration).put("price", b.price).put("past", b.past)
                    .put("learnerId", b.learnerId).put("tutorId", b.tutorId).put("startAtMillis", b.startAtMillis)
                    .put("endAtMillis", b.endAtMillis).put("cancelled", b.cancelled));
        }
        json.put("bookings", bookings);
        JSONArray sessions = new JSONArray();
        for (Repo.TutorSession s : Repo.TUTOR_SESSIONS) {
            sessions.put(new JSONObject().put("learner", s.learner).put("tutor", s.tutor).put("title", s.title)
                    .put("category", s.category).put("dayLabel", s.dayLabel).put("timeRange", s.timeRange)
                    .put("reference", s.reference).put("duration", s.duration).put("price", s.price).put("past", s.past)
                    .put("learnerId", s.learnerId).put("tutorId", s.tutorId).put("startAtMillis", s.startAtMillis)
                    .put("endAtMillis", s.endAtMillis).put("cancelled", s.cancelled));
        }
        json.put("sessions", sessions);
        JSONArray payouts = new JSONArray();
        for (Repo.Payout p : Repo.PAYOUTS) {
            payouts.put(new JSONObject().put("title", p.title).put("tutor", p.tutor).put("tutorId", p.tutorId)
                    .put("amount", p.amount).put("released", p.released).put("reference", p.reference == null ? "" : p.reference));
        }
        json.put("payouts", payouts);
        JSONArray notifications = new JSONArray();
        for (Repo.Notif n : Repo.NOTIFS) {
            notifications.put(new JSONObject().put("recipientId", n.recipientId).put("group", n.group).put("type", n.type)
                    .put("a", n.a).put("b", n.b).put("minutesAgo", n.minutesAgo).put("read", n.read));
        }
        return json.put("notifications", notifications);
    }

    private static Snapshot parse(JSONObject json) throws JSONException {
        check(integer(json, "version") == VERSION);
        Snapshot result = new Snapshot();
        result.nextLessonId = integer(json, "nextLessonId");
        result.nextRef = integer(json, "nextRef");
        check(result.nextLessonId > 0 && result.nextRef > 0);
        Set<Integer> lessonIds = new HashSet<>();
        JSONArray lessons = array(json, "lessons");
        for (int i = 0; i < lessons.length(); i++) {
            JSONObject o = lessons.getJSONObject(i);
            int id = integer(o, "id");
            check(id > 0 && id < result.nextLessonId && lessonIds.add(id));
            double distance = number(o, "distanceKm");
            check(Double.isFinite(distance) && distance >= 0);
            int price = nonnegative(o, "price"), duration = duration(o);
            int preview = nonnegative(o, "previewSeconds");
            Lesson l = new Lesson(id, required(o, "title"), required(o, "tutor"), text(o, "initials"),
                    required(o, "category"), distance, price, duration, bool(o, "available"), text(o, "description"),
                    preview, integer(o, "tileColor"), bool(o, "darkText"));
            l.tutorId = required(o, "tutorId");
            l.city = text(o, "city"); l.day = text(o, "day"); l.slots = text(o, "slots");
            // Earlier local snapshots contained only the single legacy day field.
            if (o.has("days")) {
                JSONArray dates = o.getJSONArray("days");
                check(dates.length() <= LessonAvailability.MAX_DAYS);
                Set<java.time.LocalDate> unique = new HashSet<>();
                List<String> labels = new ArrayList<>();
                for (int j = 0; j < dates.length(); j++) {
                    Object raw = dates.get(j);
                    check(raw instanceof String);
                    java.time.LocalDate date = LessonAvailability.parseDay((String) raw);
                    check(date != null && unique.add(date));
                    labels.add(LessonAvailability.formatDay(date));
                }
                if (!labels.isEmpty()) LessonAvailability.setDays(l, labels);
            }
            l.created = text(o, "created"); l.hasCover = bool(o, "hasCover");
            l.hasPreview = bool(o, "hasPreview"); l.reviewed = bool(o, "reviewed");
            l.mine = Repo.accountRole() == Repo.ROLE_TUTOR && Repo.accountId().equals(l.tutorId);
            result.lessons.add(l);
        }
        Set<String> bookingRefs = new HashSet<>();
        JSONArray bookings = array(json, "bookings");
        for (int i = 0; i < bookings.length(); i++) {
            JSONObject o = bookings.getJSONObject(i);
            Repo.Booking b = new Repo.Booking();
            b.title = required(o, "title"); b.tutor = required(o, "tutor");
            b.dayLabel = text(o, "dayLabel"); b.timeRange = text(o, "timeRange");
            b.payment = text(o, "payment"); b.reference = reference(o, result.nextRef);
            check(bookingRefs.add(b.reference));
            b.duration = duration(o); b.price = nonnegative(o, "price"); b.past = bool(o, "past");
            b.learnerId = required(o, "learnerId"); b.tutorId = required(o, "tutorId");
            b.startAtMillis = whole(o, "startAtMillis"); b.endAtMillis = whole(o, "endAtMillis");
            timeRange(b.startAtMillis, b.endAtMillis, b.duration);
            b.cancelled = bool(o, "cancelled"); result.bookings.add(b);
        }
        Set<String> sessionRefs = new HashSet<>();
        JSONArray sessions = array(json, "sessions");
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject o = sessions.getJSONObject(i);
            Repo.TutorSession s = new Repo.TutorSession();
            s.learner = required(o, "learner"); s.tutor = required(o, "tutor"); s.title = required(o, "title");
            s.category = required(o, "category"); s.dayLabel = text(o, "dayLabel"); s.timeRange = text(o, "timeRange");
            s.reference = reference(o, result.nextRef); check(sessionRefs.add(s.reference));
            s.duration = duration(o); s.price = nonnegative(o, "price"); s.past = bool(o, "past");
            s.learnerId = required(o, "learnerId"); s.tutorId = required(o, "tutorId");
            s.startAtMillis = whole(o, "startAtMillis"); s.endAtMillis = whole(o, "endAtMillis");
            timeRange(s.startAtMillis, s.endAtMillis, s.duration);
            s.cancelled = bool(o, "cancelled"); result.sessions.add(s);
        }
        JSONArray payouts = array(json, "payouts");
        for (int i = 0; i < payouts.length(); i++) {
            JSONObject o = payouts.getJSONObject(i);
            Repo.Payout p = new Repo.Payout();
            p.title = required(o, "title"); p.tutor = required(o, "tutor"); p.tutorId = required(o, "tutorId");
            p.amount = nonnegative(o, "amount"); p.released = bool(o, "released");
            p.reference = text(o, "reference");
            if (!p.reference.isEmpty()) reference(o, result.nextRef);
            result.payouts.add(p);
        }
        JSONArray notifications = array(json, "notifications");
        for (int i = 0; i < notifications.length(); i++) {
            JSONObject o = notifications.getJSONObject(i);
            int group = integer(o, "group"), type = integer(o, "type");
            check(group >= 0 && group <= 2 && type >= Repo.N_BOOKING_CONFIRMED && type <= Repo.N_PAYOUT_PENDING);
            result.notifications.add(new Repo.Notif(required(o, "recipientId"), group, type,
                    text(o, "a"), text(o, "b"), nonnegative(o, "minutesAgo"), bool(o, "read")));
        }
        return result;
    }

    private static void timeRange(long start, long end, int duration) throws JSONException {
        check((start == 0 && end == 0) || (start > 0 && end > start && end == SessionTime.endAt(start, duration)));
    }
    private static String reference(JSONObject o, int next) throws JSONException {
        String value = required(o, "reference");
        check(value.matches("SEN-[1-9][0-9]{0,9}"));
        try { check(Long.parseLong(value.substring(4)) < next); }
        catch (NumberFormatException invalid) { throw new JSONException("Invalid reference"); }
        return value;
    }
    private static JSONArray array(JSONObject o, String key) throws JSONException {
        JSONArray value = o.getJSONArray(key); check(value.length() <= MAX_RECORDS); return value;
    }
    private static String text(JSONObject o, String key) throws JSONException {
        Object value = o.get(key); check(value instanceof String && ((String) value).length() <= 100000);
        return (String) value;
    }
    private static String required(JSONObject o, String key) throws JSONException {
        String value = text(o, key); check(!value.trim().isEmpty()); return value;
    }
    private static boolean bool(JSONObject o, String key) throws JSONException {
        Object value = o.get(key); check(value instanceof Boolean); return (Boolean) value;
    }
    private static double number(JSONObject o, String key) throws JSONException {
        Object value = o.get(key); check(value instanceof Number); return ((Number) value).doubleValue();
    }
    private static long whole(JSONObject o, String key) throws JSONException {
        Object value = o.get(key);
        check(value instanceof Integer || value instanceof Long);
        return ((Number) value).longValue();
    }
    private static int integer(JSONObject o, String key) throws JSONException {
        long value = whole(o, key); check(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE); return (int) value;
    }
    private static int nonnegative(JSONObject o, String key) throws JSONException {
        int value = integer(o, key); check(value >= 0); return value;
    }
    private static int duration(JSONObject o) throws JSONException {
        int value = integer(o, "duration"); check(value > 0 && value <= 120); return value;
    }
    private static void check(boolean valid) throws JSONException {
        if (!valid) throw new JSONException("Invalid committed repository snapshot");
    }
    private static final class Snapshot {
        int nextLessonId, nextRef;
        final List<Lesson> lessons = new ArrayList<>();
        final List<Repo.Booking> bookings = new ArrayList<>();
        final List<Repo.TutorSession> sessions = new ArrayList<>();
        final List<Repo.Payout> payouts = new ArrayList<>();
        final List<Repo.Notif> notifications = new ArrayList<>();
    }
}
