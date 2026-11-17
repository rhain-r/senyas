package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Cross-process integration: run writeCommittedState, verifyCommittedStateAndCancel, then
 * verifyCancelledStateAndRebook individually with -e class <this class>#<method>.
 * Start with pm clear com.benten.senyas and force-stop the app between each invocation.
 * The regular suite should exclude this class with -e notClass, and unselected phases skip.
 */
@RunWith(AndroidJUnit4.class)
public class ColdRestartPersistenceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private SharedPreferences expected() { return context().getSharedPreferences("cold_restart_test", Context.MODE_PRIVATE); }
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(context(), MainActivity.class));
    }
    private void requireIndividualPhase(String name) {
        String selected = InstrumentationRegistry.getArguments().getString("class", "");
        Assume.assumeTrue("Run cross-process phases individually", selected.equals(getClass().getName() + "#" + name));
    }

    @Test public void writeCommittedState() throws Exception {
        requireIndividualPhase("writeCommittedState");
        String suffix = UUID.randomUUID().toString();
        AccountStore accounts = new AccountStore(context());
        AccountStore.Account tutor = accounts.register("Cold Restart Tutor", "cold-tutor-" + suffix + "@senyas.test",
                "Cold-restart-2026", Repo.ROLE_TUTOR);
        long start = SessionTime.startAt(LocalDate.now(SessionTime.ZONE).plusDays(1), 10 * 60);
        int[] lessonId = new int[1];
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertEquals(tutor.id, Repo.accountId());
                Repo.startDraft();
                Repo.draft.title = "Cold restart lesson";
                Repo.draft.description = "Committed records survive process death";
                Repo.draft.category = Repo.CAT_TECH;
                Repo.draft.city = "Quezon City";
                Repo.draft.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(1));
                Repo.draft.slots = "10:00 am";
                Repo.draft.duration = 60;
                Repo.draft.price = 600;
                Lesson published = Repo.publishDraft();
                // Approval is outside this lifecycle test; persist the same committed reviewed flag.
                published.reviewed = true;
                LessonAvailability.setDays(published, java.util.Arrays.asList(published.day,
                        LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(3)),
                        LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(5))));
                Repo.persist();
                lessonId[0] = published.id;
            });
        }
        AccountStore.Account learner = accounts.register("Cold Restart Student", "cold-student-" + suffix + "@senyas.test",
                "Cold-restart-2026", Repo.ROLE_STUDENT);
        String[] reference = new String[1];
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertEquals(learner.id, Repo.accountId());
                prepareBooking(Repo.byId(lessonId[0]), start);
                reference[0] = Repo.confirmDraft().reference;
                Repo.startPractice(0, 0);
                assertTrue(Repo.recordVerifiedMatch(0, 0, 91));
                Repo.startPractice(1, 2);
                assertTrue(Repo.skipPracticeSign());
                assertEquals(3, Repo.practiceIndex);
                assertEquals(-2, Repo.practiceBest[2]);
                assertEquals(10, Repo.xp);
                assertEquals(91, Repo.pathScore(0, 0));
            });
        }
        assertTrue(expected().edit().putInt("lesson", lessonId[0]).putLong("start", start)
                .putString("reference", reference[0]).putString("tutor", tutor.id).putString("learner", learner.id)
                .putInt("pid", Process.myPid()).putInt("phase", 1).commit());
    }

    @Test public void verifyCommittedStateAndCancel() {
        requireIndividualPhase("verifyCommittedStateAndCancel");
        assertEquals("Run write phase first", 1, expected().getInt("phase", 0));
        assertNotEquals("Restart the app process between phases", expected().getInt("pid", -1), Process.myPid());
        String reference = expected().getString("reference", "");
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertEquals(expected().getString("learner", ""), Repo.accountId());
                assertEquals(Repo.ROLE_STUDENT, Repo.accountRole());
                Lesson lesson = Repo.byId(expected().getInt("lesson", -1));
                assertNotNull("Application initialization must restore published lessons", lesson);
                assertEquals("Cold restart lesson", lesson.title);
                assertEquals(expected().getString("tutor", ""), lesson.tutorId);
                assertEquals(600, lesson.price);
                assertTrue(lesson.reviewed);
                assertEquals(3, LessonAvailability.days(lesson).size());
                assertTrue(LessonAvailability.offers(lesson,
                        SessionTime.startAt(LocalDate.now(SessionTime.ZONE).plusDays(5), 600), 60));
                Repo.Booking booking = findBooking(reference);
                assertNotNull("Confirmed bookings must survive process death", booking);
                assertTrue(Repo.myBookings().contains(booking));
                assertEquals(expected().getLong("start", -1), booking.startAtMillis);
                Repo.TutorSession session = Repo.sessionByReference(reference);
                assertNotNull(session);
                assertFalse(session.cancelled);
                Repo.Payout payout = findPayout(reference);
                assertNotNull(payout);
                assertEquals(600, payout.amount);
                assertFalse(payout.released);
                assertEquals(1, Repo.practiceScenario);
                assertEquals(3, Repo.practiceIndex);
                assertEquals(-2, Repo.practiceBest[2]);
                assertEquals(10, Repo.xp);
                assertEquals(91, Repo.pathScore(0, 0));
                assertTrue(Repo.cancelBooking(booking));
                assertTrue(session.cancelled);
                assertNull(findPayout(reference));
            });
        }
        assertTrue(expected().edit().putInt("pid", Process.myPid()).putInt("phase", 2).commit());
    }

    @Test public void verifyCancelledStateAndRebook() {
        requireIndividualPhase("verifyCancelledStateAndRebook");
        assertEquals("Run cancel phase first", 2, expected().getInt("phase", 0));
        assertNotEquals("Restart the app process between phases", expected().getInt("pid", -1), Process.myPid());
        String previous = expected().getString("reference", "");
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertEquals(expected().getString("learner", ""), Repo.accountId());
                assertNull("Cancelled bookings must remain removed after restart", findBooking(previous));
                Repo.TutorSession oldSession = Repo.sessionByReference(previous);
                assertNotNull(oldSession);
                assertTrue("Cancellation must survive process death", oldSession.cancelled);
                assertNull("Cancellation must keep its pending payout removed", findPayout(previous));
                Lesson lesson = Repo.byId(expected().getInt("lesson", -1));
                assertNotNull(lesson);
                long start = expected().getLong("start", -1);
                assertTrue(Repo.slotAvailable(lesson.tutorId, start, 60));
                prepareBooking(lesson, start);
                Repo.Booking replacement = Repo.confirmDraft();
                assertNotEquals("References must not be reused after restart", previous, replacement.reference);
                assertTrue(Integer.parseInt(replacement.reference.substring(4)) > Integer.parseInt(previous.substring(4)));
                assertNotNull(Repo.sessionByReference(replacement.reference));
                assertNotNull(findPayout(replacement.reference));
            });
        }
        assertTrue(expected().edit().putInt("phase", 3).commit());
    }

    private void prepareBooking(Lesson lesson, long start) {
        assertNotNull(lesson);
        Repo.draftLesson = lesson;
        Repo.draftStartAtMillis = start;
        Repo.draftDuration = 60;
        Repo.draftPrice = 600;
        Repo.draftPayment = "GCash";
    }
    private Repo.Booking findBooking(String reference) {
        for (Repo.Booking booking : Repo.BOOKINGS) if (reference.equals(booking.reference)) return booking;
        return null;
    }
    private Repo.Payout findPayout(String reference) {
        for (Repo.Payout payout : Repo.PAYOUTS) if (reference.equals(payout.reference)) return payout;
        return null;
    }
}
