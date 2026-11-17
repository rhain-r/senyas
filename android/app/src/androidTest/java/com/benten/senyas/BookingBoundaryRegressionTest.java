package com.benten.senyas;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Public repository boundaries must enforce the same rules as the forms. */
@RunWith(AndroidJUnit4.class)
public class BookingBoundaryRegressionTest {
    private final List<Lesson> added = new ArrayList<>();
    private final int originalNextLessonId = Repo.nextLessonId;
    private final List<Lesson> lessons = new ArrayList<>(Repo.LESSONS);
    private final List<Repo.Booking> bookings = new ArrayList<>(Repo.BOOKINGS);
    private final List<Repo.TutorSession> sessions = new ArrayList<>(Repo.TUTOR_SESSIONS);
    private final List<Repo.Payout> payouts = new ArrayList<>(Repo.PAYOUTS);
    private final List<Repo.Notif> notifications = new ArrayList<>(Repo.NOTIFS);

    @After public void cleanup() {
        Repo.LESSONS.clear(); Repo.LESSONS.addAll(lessons);
        Repo.BOOKINGS.clear(); Repo.BOOKINGS.addAll(bookings);
        Repo.TUTOR_SESSIONS.clear(); Repo.TUTOR_SESSIONS.addAll(sessions);
        Repo.PAYOUTS.clear(); Repo.PAYOUTS.addAll(payouts);
        Repo.NOTIFS.clear(); Repo.NOTIFS.addAll(notifications);
        Repo.draft = null; Repo.draftLesson = null; Repo.lastBooking = null;
        Repo.nextLessonId = originalNextLessonId;
    }

    private Lesson offeredLesson() {
        Lesson lesson = new Lesson(600000 + added.size(), "QA boundary lesson", "QA Tutor", "QT",
                Repo.CAT_TECH, 1, 400, 60, true, "QA lesson", 0, 0xFF1E3AB8, false);
        lesson.tutorId = "qa-boundary-tutor";
        lesson.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(10));
        lesson.slots = "9:00 am";
        Repo.LESSONS.add(lesson); added.add(lesson);
        Repo.nextLessonId = Math.max(Repo.nextLessonId, lesson.id + 1);
        return lesson;
    }

    private void select(Lesson lesson) {
        Repo.draftLesson = lesson;
        Repo.draftDay = lesson.day;
        Repo.draftStartMin = 540;
        Repo.draftStartAtMillis = SessionTime.parseStart(lesson.day, "9:00 am");
        Repo.draftDuration = 60;
        Repo.draftPrice = 400;
        Repo.draftPayment = "GCash";
    }

    private void expectUnavailable() {
        int count = Repo.BOOKINGS.size();
        try { Repo.confirmDraft(); fail("Unavailable listing must not create a booking"); }
        catch (IllegalStateException expected) { }
        assertEquals(count, Repo.BOOKINGS.size());
    }

    @Test public void removedListingCannotBeBookedFromAnExistingReview() throws Exception {
        TestAccounts.student();
        Lesson lesson = offeredLesson(); select(lesson);
        Repo.LESSONS.remove(lesson);
        expectUnavailable();
    }

    @Test public void changedRateRequiresAnewReviewBeforeBooking() throws Exception {
        TestAccounts.student();
        Lesson lesson = offeredLesson(); select(lesson);
        lesson.price = 600;
        expectUnavailable();
    }

    @Test public void pendingApprovalListingIsHiddenFromLearnerSearch() throws Exception {
        TestAccounts.student();
        Lesson lesson = offeredLesson(); lesson.reviewed = false;
        assertFalse(Repo.query(null, 50, "QA boundary").contains(lesson));
    }

    @Test public void pendingApprovalListingCannotBeBookedDirectly() throws Exception {
        TestAccounts.student();
        Lesson lesson = offeredLesson(); lesson.reviewed = false; select(lesson);
        expectUnavailable();
    }

    @Test public void incompleteLessonCannotBePublishedBySkippingTheDetailsForm() throws Exception {
        TestAccounts.tutor(); Repo.startDraft();
        Repo.draft.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(10));
        Repo.draft.slots = "9:00 am"; Repo.draft.city = "Manila";
        int count = Repo.LESSONS.size();
        try { Repo.publishDraft(); fail("Blank title and zero rate must be rejected"); }
        catch (IllegalStateException expected) { }
        assertEquals(count, Repo.LESSONS.size());
        assertNotNull(Repo.draft);
    }

    @Test public void cancellationRemovesItsPendingPayout() throws Exception {
        TestAccounts.student();
        Lesson lesson = offeredLesson(); select(lesson);
        int original = Repo.PAYOUTS.size();
        Repo.Booking booking = Repo.confirmDraft();
        assertEquals(original + 1, Repo.PAYOUTS.size());
        assertTrue(Repo.cancelBooking(booking));
        assertEquals("Cancelled sessions must not remain payable", original, Repo.PAYOUTS.size());
        assertTrue(Repo.sessionByReference(booking.reference).cancelled);
    }
}
