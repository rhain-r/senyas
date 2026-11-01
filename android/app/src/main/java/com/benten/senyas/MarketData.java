package com.benten.senyas;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marketplace records that accounts made on this device and every account sees: listings
 * published by tutor accounts (with the email key of their owner), sessions and payouts booked on
 * those listings, and admin notifications. The seeded sample tutors are never saved here.
 */
final class MarketData {
    /** Sample lessons use ids 1-5 and sample references end at SEN-830. */
    static final int FIRST_LESSON_ID = 100, FIRST_REF = 831;

    final List<Lesson> lessons = new ArrayList<>();
    final Map<Integer, String> owners = new HashMap<>(); // listing id -> owner's email key
    final List<Repo.TutorSession> sessions = new ArrayList<>();
    final List<Repo.Payout> payouts = new ArrayList<>();
    final List<Repo.Notif> notifs = new ArrayList<>();
    int nextLessonId = FIRST_LESSON_ID, nextRef = FIRST_REF;

    String toJson() {
        try {
            JSONArray l = new JSONArray(), s = new JSONArray(), p = new JSONArray();
            for (Lesson x : lessons) l.put(Records.lesson(x).put("owner", owners.get(x.id)));
            for (Repo.TutorSession x : sessions) s.put(Records.session(x));
            for (Repo.Payout x : payouts) p.put(Records.payout(x));
            return new JSONObject().put("v", 1).put("nextLessonId", nextLessonId).put("nextRef", nextRef)
                    .put("lessons", l).put("sessions", s).put("payouts", p)
                    .put("notifs", Records.notifs(notifs)).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static MarketData fromJson(@Nullable String json) {
        MarketData m = new MarketData();
        if (json == null) return m;
        try {
            JSONObject o = new JSONObject(json);
            m.nextLessonId = Math.max(o.optInt("nextLessonId", FIRST_LESSON_ID), FIRST_LESSON_ID);
            m.nextRef = Math.max(o.optInt("nextRef", FIRST_REF), FIRST_REF);
            JSONArray l = o.optJSONArray("lessons");
            for (int i = 0; l != null && i < l.length(); i++) {
                JSONObject x = l.optJSONObject(i);
                String owner = x == null ? null : Records.text(x, "owner");
                if (owner == null) continue;
                Lesson lesson = Records.lesson(x);
                m.lessons.add(lesson);
                m.owners.put(lesson.id, owner);
                m.nextLessonId = Math.max(m.nextLessonId, lesson.id + 1);
            }
            JSONArray s = o.optJSONArray("sessions");
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject x = s.optJSONObject(i);
                if (x != null) m.sessions.add(Records.session(x));
            }
            JSONArray p = o.optJSONArray("payouts");
            for (int i = 0; p != null && i < p.length(); i++) {
                JSONObject x = p.optJSONObject(i);
                if (x != null) m.payouts.add(Records.payout(x));
            }
            Records.readNotifs(o.optJSONArray("notifs"), m.notifs);
        } catch (JSONException e) {
            return new MarketData();
        }
        return m;
    }
}
