package com.benten.senyas;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** JSON for the records an account saves: bookings, notifications, listings, sessions and payouts. */
final class Records {
    private Records() {}

    static JSONObject booking(Repo.Booking b) throws JSONException {
        return new JSONObject().put("title", b.title).put("tutor", b.tutor).put("day", b.dayLabel)
                .put("time", b.timeRange).put("payment", b.payment).put("ref", b.reference)
                .put("duration", b.duration).put("price", b.price).put("past", b.past);
    }

    static Repo.Booking booking(JSONObject o) {
        Repo.Booking b = new Repo.Booking();
        b.title = o.optString("title", "");
        b.tutor = o.optString("tutor", "");
        b.dayLabel = o.optString("day", "");
        b.timeRange = o.optString("time", "");
        b.payment = o.optString("payment", "");
        b.reference = o.optString("ref", "");
        b.duration = o.optInt("duration", 60);
        b.price = o.optInt("price", 0);
        b.past = o.optBoolean("past", false);
        return b;
    }

    static JSONObject notif(Repo.Notif n) throws JSONException {
        return new JSONObject().put("group", n.group).put("type", n.type).put("a", n.a).put("b", n.b)
                .put("at", n.at).put("read", n.read);
    }

    static Repo.Notif notif(JSONObject o) {
        return new Repo.Notif(o.optInt("group", 0), o.optInt("type", 0), o.optString("a", ""),
                o.optString("b", ""), o.optLong("at", 0), o.optBoolean("read", false));
    }

    static JSONObject lesson(Lesson l) throws JSONException {
        return new JSONObject().put("id", l.id).put("title", l.title).put("tutor", l.tutor)
                .put("initials", l.initials).put("category", l.category).put("description", l.description)
                .put("km", l.distanceKm).put("price", l.price).put("duration", l.duration)
                .put("previewSeconds", l.previewSeconds).put("tile", l.tileColor).put("available", l.available)
                .put("darkText", l.darkText).put("city", l.city).put("day", l.day).put("slots", l.slots)
                .put("created", l.created).put("hasCover", l.hasCover).put("hasPreview", l.hasPreview)
                .put("reviewed", l.reviewed);
    }

    static Lesson lesson(JSONObject o) {
        Lesson l = new Lesson(o.optInt("id", -1), o.optString("title", ""), o.optString("tutor", ""),
                o.optString("initials", ""), o.optString("category", Repo.CAT_VOC), o.optDouble("km", 1),
                o.optInt("price", 0), o.optInt("duration", 60), o.optBoolean("available", true),
                o.optString("description", ""), o.optInt("previewSeconds", 0), o.optInt("tile", 0xFF1E3AB8),
                o.optBoolean("darkText", false));
        l.city = o.optString("city", l.city);
        l.day = o.optString("day", l.day);
        l.slots = o.optString("slots", l.slots);
        l.created = o.optString("created", l.created);
        l.hasCover = o.optBoolean("hasCover", false);
        l.hasPreview = o.optBoolean("hasPreview", false);
        l.reviewed = o.optBoolean("reviewed", false);
        return l;
    }

    static JSONObject session(Repo.TutorSession s) throws JSONException {
        return new JSONObject().put("learner", s.learner).put("tutor", s.tutor).put("tutorKey", s.tutorKey)
                .put("title", s.title).put("category", s.category).put("day", s.dayLabel)
                .put("time", s.timeRange).put("ref", s.reference).put("duration", s.duration)
                .put("price", s.price).put("past", s.past);
    }

    static Repo.TutorSession session(JSONObject o) {
        Repo.TutorSession s = new Repo.TutorSession();
        s.learner = o.optString("learner", "");
        s.tutor = o.optString("tutor", "");
        s.tutorKey = text(o, "tutorKey");
        s.title = o.optString("title", "");
        s.category = o.optString("category", Repo.CAT_VOC);
        s.dayLabel = o.optString("day", "");
        s.timeRange = o.optString("time", "");
        s.reference = o.optString("ref", "");
        s.duration = o.optInt("duration", 60);
        s.price = o.optInt("price", 0);
        s.past = o.optBoolean("past", false);
        return s;
    }

    static JSONObject payout(Repo.Payout p) throws JSONException {
        return new JSONObject().put("title", p.title).put("tutor", p.tutor).put("tutorKey", p.tutorKey)
                .put("ref", p.reference).put("amount", p.amount).put("released", p.released);
    }

    static Repo.Payout payout(JSONObject o) {
        Repo.Payout p = new Repo.Payout();
        p.title = o.optString("title", "");
        p.tutor = o.optString("tutor", "");
        p.tutorKey = text(o, "tutorKey");
        p.reference = text(o, "ref");
        p.amount = o.optInt("amount", 0);
        p.released = o.optBoolean("released", false);
        return p;
    }

    static JSONArray notifs(List<Repo.Notif> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Repo.Notif n : list) a.put(notif(n));
        return a;
    }

    static void readNotifs(@Nullable JSONArray a, List<Repo.Notif> into) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) into.add(notif(o));
        }
    }

    /** A string that may be missing (put() leaves out nulls), as null. */
    @Nullable
    static String text(JSONObject o, String key) {
        return o.has(key) && !o.isNull(key) ? o.optString(key, null) : null;
    }
}
