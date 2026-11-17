package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RepoPersistenceTest {
    private Context context;
    private List<Lesson> lessons;
    private List<Repo.Booking> bookings;
    private List<Repo.TutorSession> sessions;
    private List<Repo.Payout> payouts;
    private List<Repo.Notif> notifications;
    private int nextLessonId, nextRef;
    private Lesson fixtureLesson;

    @Before public void isolateStorageAndMemory() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(target.getCacheDir(), "repo-test-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        context = new ContextWrapper(target) {
            @Override public File getFilesDir() { return directory; }
        };
        lessons = new ArrayList<>(Repo.LESSONS);
        bookings = new ArrayList<>(Repo.BOOKINGS);
        sessions = new ArrayList<>(Repo.TUTOR_SESSIONS);
        payouts = new ArrayList<>(Repo.PAYOUTS);
        notifications = new ArrayList<>(Repo.NOTIFS);
        nextLessonId = Repo.nextLessonId;
        nextRef = referenceCounter().getInt(null);
        // Other instrumentation classes deliberately create partial UI fixtures. Start from complete records.
        Repo.LESSONS.clear(); Repo.BOOKINGS.clear(); Repo.TUTOR_SESSIONS.clear();
        Repo.PAYOUTS.clear(); Repo.NOTIFS.clear();
        fixtureLesson = new Lesson(1, "Baseline lesson", "Baseline Tutor", "BT", Repo.CAT_TECH,
                1.5, 400, 60, true, "Baseline description", 30, 0xff112233, false);
        fixtureLesson.tutorId = "baseline-tutor";
        Repo.LESSONS.add(fixtureLesson);
        Repo.Booking booking = new Repo.Booking();
        booking.title = "Baseline lesson"; booking.tutor = "Baseline Tutor";
        booking.learnerId = "baseline-learner"; booking.tutorId = "baseline-tutor";
        booking.dayLabel = "Fri, 6 November 2026"; booking.timeRange = "11:00 am–12:00 pm";
        booking.duration = 60; booking.price = 400; booking.payment = "GCash";
        booking.reference = "SEN-830"; booking.startAtMillis = 1793934000000L;
        booking.endAtMillis = 1793937600000L;
        Repo.BOOKINGS.add(booking);
        Repo.TutorSession session = new Repo.TutorSession();
        session.learner = "Baseline Learner"; session.tutor = "Baseline Tutor"; session.title = "Baseline lesson";
        session.category = Repo.CAT_TECH; session.dayLabel = booking.dayLabel; session.timeRange = booking.timeRange;
        session.learnerId = booking.learnerId; session.tutorId = booking.tutorId; session.reference = booking.reference;
        session.duration = 60; session.price = 400; session.startAtMillis = booking.startAtMillis;
        session.endAtMillis = booking.endAtMillis;
        Repo.TUTOR_SESSIONS.add(session);
        Repo.Payout payout = new Repo.Payout();
        payout.title = booking.title; payout.tutor = booking.tutor; payout.tutorId = booking.tutorId;
        payout.amount = 400; payout.reference = "SEN-830";
        Repo.PAYOUTS.add(payout);
        Repo.NOTIFS.add(new Repo.Notif("baseline-tutor", 1, Repo.N_NEW_BOOKING, "Baseline Learner", "Baseline lesson", 0, false));
        Repo.nextLessonId = 2;
        referenceCounter().setInt(null, 831);
    }

    @After public void restoreMemory() throws Exception {
        Repo.LESSONS.clear(); Repo.LESSONS.addAll(lessons);
        Repo.BOOKINGS.clear(); Repo.BOOKINGS.addAll(bookings);
        Repo.TUTOR_SESSIONS.clear(); Repo.TUTOR_SESSIONS.addAll(sessions);
        Repo.PAYOUTS.clear(); Repo.PAYOUTS.addAll(payouts);
        Repo.NOTIFS.clear(); Repo.NOTIFS.addAll(notifications);
        Repo.nextLessonId = nextLessonId;
        referenceCounter().setInt(null, nextRef);
        for (File file : context.getFilesDir().listFiles()) assertTrue(file.delete());
        assertTrue(context.getFilesDir().delete());
    }

    // Catches losing committed listings, cancellations, release status and sequence counters on restart.
    @Test public void committedRecordsAndCountersSurviveNewStoreInstance() throws Exception {
        Lesson lesson = new Lesson(91, "Persisted lesson", "Test Tutor", "TT", Repo.CAT_TECH,
                2.5, 550, 60, true, "Saved description", 30, 0xff112233, false);
        lesson.tutorId = "persist-tutor";
        lesson.reviewed = false;
        lesson.slots = "11:00 am";
        LessonAvailability.setDays(lesson, java.util.Arrays.asList("Friday, 6 November 2026", "Friday, 27 November 2026"));
        Repo.LESSONS.add(0, lesson);
        Repo.nextLessonId = 92;
        referenceCounter().setInt(null, 999);
        Repo.Booking booking = new Repo.Booking();
        booking.title = "Persisted lesson"; booking.tutor = "Test Tutor";
        booking.learnerId = "persist-learner"; booking.tutorId = "persist-tutor";
        booking.dayLabel = "Fri, 6 November 2026"; booking.timeRange = "11:00 am–12:00 pm";
        booking.duration = 60; booking.price = 550; booking.payment = "Card •••• 1234";
        booking.reference = "SEN-998"; booking.startAtMillis = 1793934000000L;
        booking.endAtMillis = 1793937600000L; booking.cancelled = true;
        Repo.BOOKINGS.add(0, booking);
        Repo.TutorSession session = new Repo.TutorSession();
        session.learner = "Test Learner"; session.tutor = "Test Tutor"; session.title = "Persisted lesson";
        session.category = Repo.CAT_TECH; session.dayLabel = booking.dayLabel; session.timeRange = booking.timeRange;
        session.learnerId = booking.learnerId; session.tutorId = booking.tutorId; session.reference = booking.reference;
        session.duration = 60; session.price = 550; session.startAtMillis = booking.startAtMillis;
        session.endAtMillis = booking.endAtMillis; session.cancelled = true;
        Repo.TUTOR_SESSIONS.add(0, session);
        Repo.Payout payout = new Repo.Payout();
        payout.title = booking.title; payout.tutor = booking.tutor; payout.tutorId = booking.tutorId;
        payout.amount = 550; payout.released = true; payout.reference = "SEN-998";
        Repo.PAYOUTS.add(0, payout);
        Repo.NOTIFS.add(0, new Repo.Notif("persist-tutor", 1, Repo.N_PAYOUT_RELEASED, "550", "", 0, true));
        assertTrue(invoke(newStore(), "save"));
        Repo.LESSONS.clear(); Repo.BOOKINGS.clear(); Repo.TUTOR_SESSIONS.clear();
        Repo.PAYOUTS.clear(); Repo.NOTIFS.clear(); Repo.nextLessonId = 1;
        referenceCounter().setInt(null, 1);

        assertTrue(invoke(newStore(), "load"));
        assertEquals("Persisted lesson", Repo.byId(91).title);
        assertEquals("11:00 am", Repo.byId(91).slots);
        assertEquals(2, LessonAvailability.days(Repo.byId(91)).size());
        assertFalse(Repo.byId(91).reviewed);
        assertFalse("Ownership is derived from the active account", Repo.byId(91).mine);
        assertEquals(92, Repo.nextLessonId);
        assertEquals(999, referenceCounter().getInt(null));
        assertTrue(Repo.BOOKINGS.get(0).cancelled);
        assertEquals("persist-learner", Repo.BOOKINGS.get(0).learnerId);
        assertEquals("Card •••• 1234", Repo.BOOKINGS.get(0).payment);
        assertEquals(1793937600000L, Repo.BOOKINGS.get(0).endAtMillis);
        assertEquals(2, Repo.TUTOR_SESSIONS.size());
        assertTrue(Repo.TUTOR_SESSIONS.get(0).cancelled);
        assertEquals("SEN-998", Repo.TUTOR_SESSIONS.get(0).reference);
        assertTrue(Repo.PAYOUTS.get(0).released);
        assertEquals("SEN-998", Repo.PAYOUTS.get(0).reference);
        assertEquals("persist-tutor", Repo.NOTIFS.get(0).recipientId);
        assertTrue(Repo.NOTIFS.get(0).read);
    }

    // Catches destructive clear-before-parse loads when the last record is malformed.
    @Test public void invalidSnapshotPreservesEveryCurrentCollection() throws Exception {
        Object store = newStore();
        assertTrue(invoke(store, "save"));
        JSONObject json = new JSONObject(new String(Files.readAllBytes(snapshot().toPath()), StandardCharsets.UTF_8));
        json.getJSONArray("notifications").put(new JSONObject().put("group", 99));
        Files.write(snapshot().toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        Lesson sentinel = Repo.LESSONS.get(0);
        Repo.Booking booking = Repo.BOOKINGS.get(0);
        Repo.TutorSession session = Repo.TUTOR_SESSIONS.get(0);
        Repo.Payout payout = Repo.PAYOUTS.get(0);
        Repo.Notif notification = Repo.NOTIFS.get(0);
        assertFalse(invoke(newStore(), "load"));
        assertSame(sentinel, Repo.LESSONS.get(0));
        assertEquals(1, Repo.BOOKINGS.size()); assertSame(booking, Repo.BOOKINGS.get(0));
        assertEquals(1, Repo.TUTOR_SESSIONS.size()); assertSame(session, Repo.TUTOR_SESSIONS.get(0));
        assertEquals(1, Repo.PAYOUTS.size()); assertSame(payout, Repo.PAYOUTS.get(0));
        assertEquals(1, Repo.NOTIFS.size()); assertSame(notification, Repo.NOTIFS.get(0));
    }

    // Catches accepting malformed persisted identifiers, time ranges and sequence counters.
    @Test public void invalidIdentifiersTimesAndCountersAreRejected() throws Exception {
        Object store = newStore();
        assertTrue(invoke(store, "save"));
        String valid = new String(Files.readAllBytes(snapshot().toPath()), StandardCharsets.UTF_8);
        JSONObject bad = new JSONObject(valid);
        bad.getJSONArray("lessons").getJSONObject(0).put("id", -1);
        assertRejected(bad);
        bad = new JSONObject(valid);
        bad.getJSONArray("bookings").getJSONObject(0).put("endAtMillis", -1);
        assertRejected(bad);
        bad = new JSONObject(valid);
        bad.put("nextLessonId", 1);
        assertRejected(bad);
        bad = new JSONObject(valid);
        bad.put("nextRef", 1);
        assertRejected(bad);
        bad = new JSONObject(valid);
        bad.getJSONArray("lessons").getJSONObject(0).put("days", new org.json.JSONArray().put("not a date"));
        assertRejected(bad);
        Files.write(snapshot().toPath(), "{truncated".getBytes(StandardCharsets.UTF_8));
        assertFalse(invoke(newStore(), "load"));
    }

    @Test public void olderSingleDateSnapshotStillLoads() throws Exception {
        assertTrue(invoke(newStore(), "save"));
        JSONObject old = new JSONObject(new String(Files.readAllBytes(snapshot().toPath()), StandardCharsets.UTF_8));
        old.getJSONArray("lessons").getJSONObject(0).remove("days");
        Files.write(snapshot().toPath(), old.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(invoke(newStore(), "load"));
        Lesson restored = Repo.LESSONS.get(0);
        assertEquals(1, LessonAvailability.days(restored).size());
        assertEquals(LessonAvailability.parseDay(restored.day), LessonAvailability.days(restored).get(0));
    }

    private void assertRejected(JSONObject bad) throws Exception {
        Files.write(snapshot().toPath(), bad.toString().getBytes(StandardCharsets.UTF_8));
        assertFalse(invoke(newStore(), "load"));
        assertEquals(1, Repo.LESSONS.size());
        assertSame(fixtureLesson, Repo.LESSONS.get(0));
    }
    private File snapshot() { return new File(context.getFilesDir(), "senyas_committed_state.json"); }
    private Field referenceCounter() throws Exception {
        Field field = Repo.class.getDeclaredField("nextRef"); field.setAccessible(true); return field;
    }
    private Object newStore() throws Exception {
        try { return Class.forName("com.benten.senyas.RepoStore").getDeclaredConstructor(Context.class).newInstance(context); }
        catch (ClassNotFoundException missing) { fail("Committed repository records need a durable RepoStore"); return null; }
    }
    private boolean invoke(Object store, String method) throws Exception {
        return (Boolean) store.getClass().getDeclaredMethod(method).invoke(store);
    }
}
