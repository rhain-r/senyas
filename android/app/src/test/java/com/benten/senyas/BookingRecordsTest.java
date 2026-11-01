package com.benten.senyas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Bookings and listings stay consistent for the learner, the tutor and the admin (no account signed in). */
public class BookingRecordsTest {

    private static final String REF = "SEN-9001", OTHER_REF = "SEN-9002", TUTOR = "rina@email.com";

    @After
    public void cleanUp() {
        Repo.BOOKINGS.removeIf(b -> REF.equals(b.reference) || OTHER_REF.equals(b.reference));
        Repo.TUTOR_SESSIONS.removeIf(s -> REF.equals(s.reference) || OTHER_REF.equals(s.reference));
        Repo.PAYOUTS.removeIf(p -> REF.equals(p.reference) || OTHER_REF.equals(p.reference));
        Repo.LESSONS.removeIf(l -> "FSL for Cashiers".equals(l.title));
        Repo.draft = null;
        Repo.draftLesson = null;
        Repo.lastBooking = null;
    }

    @Test
    public void cancellingABookingRemovesTheTutorSessionAndPendingPayout() {
        Repo.Booking bk = booking(REF);
        Repo.BOOKINGS.add(0, bk);
        Repo.lastBooking = bk;
        Repo.TUTOR_SESSIONS.add(0, session(REF));
        Repo.TUTOR_SESSIONS.add(0, session(OTHER_REF));
        Repo.Payout pending = payout(REF, false), released = payout(REF, true), other = payout(OTHER_REF, false);
        Repo.PAYOUTS.add(0, pending);
        Repo.PAYOUTS.add(0, released);
        Repo.PAYOUTS.add(0, other);
        int samplePayouts = Repo.PAYOUTS.size() - 3, sampleSessions = Repo.TUTOR_SESSIONS.size() - 2;

        Repo.cancelBooking(bk);

        assertFalse(Repo.BOOKINGS.contains(bk));
        assertNull(Repo.lastBooking);
        assertNull(Repo.findBooking(REF));
        for (Repo.TutorSession s : Repo.TUTOR_SESSIONS) assertFalse(REF.equals(s.reference));
        assertEquals(sampleSessions + 1, Repo.TUTOR_SESSIONS.size()); // the other booking's session stays
        assertFalse(Repo.PAYOUTS.contains(pending));
        assertTrue(Repo.PAYOUTS.contains(released)); // money already sent is kept on record
        assertTrue(Repo.PAYOUTS.contains(other));
        assertEquals(samplePayouts + 2, Repo.PAYOUTS.size());
    }

    @Test
    public void bookingsAreFoundByReference() {
        Repo.Booking bk = booking(REF);
        Repo.BOOKINGS.add(0, bk);
        assertSame(bk, Repo.findBooking(REF));
        assertNull(Repo.findBooking(OTHER_REF));
        assertNull(Repo.findBooking(null));
    }

    @Test
    public void withoutADraftNothingIsBooked() {
        Repo.draftLesson = null;
        int bookings = Repo.BOOKINGS.size(), sessions = Repo.TUTOR_SESSIONS.size(), payouts = Repo.PAYOUTS.size();
        assertNull(Repo.confirmDraft());
        assertEquals(bookings, Repo.BOOKINGS.size());
        assertEquals(sessions, Repo.TUTOR_SESSIONS.size());
        assertEquals(payouts, Repo.PAYOUTS.size());
    }

    @Test
    public void aListingNeedsATitleAndARate() {
        int lessons = Repo.LESSONS.size();
        Repo.draft = null;
        assertNull(Repo.publishDraft());

        Repo.startDraft(); // blank, as after the app was closed
        assertNull(Repo.publishDraft());
        Repo.draft.title = "FSL for Cashiers";
        assertNull(Repo.publishDraft()); // still P0
        Repo.draft.title = "  ";
        Repo.draft.price = 300;
        assertNull(Repo.publishDraft());
        assertEquals(lessons, Repo.LESSONS.size());

        Repo.draft.title = "FSL for Cashiers";
        Lesson l = Repo.publishDraft();
        assertNotNull(l);
        assertSame(l, Repo.find(l.id));
        assertNull(Repo.draft);
        assertEquals(lessons + 1, Repo.LESSONS.size());
    }

    @Test
    public void findReturnsNullForAMissingLesson() {
        assertNull(Repo.find(-42));
        assertNotNull(Repo.find(1)); // a sample lesson
    }

    private static Repo.Booking booking(String ref) {
        Repo.Booking b = new Repo.Booking();
        b.title = "FSL for Cashiers"; b.tutor = "Rina Cruz"; b.reference = ref; b.price = 300; b.duration = 30;
        return b;
    }

    private static Repo.TutorSession session(String ref) {
        Repo.TutorSession s = new Repo.TutorSession();
        s.learner = "Ana Reyes"; s.tutor = "Rina Cruz"; s.tutorKey = TUTOR; s.title = "FSL for Cashiers";
        s.reference = ref;
        return s;
    }

    private static Repo.Payout payout(String ref, boolean released) {
        Repo.Payout p = new Repo.Payout();
        p.title = "FSL for Cashiers"; p.tutor = "Rina Cruz"; p.tutorKey = TUTOR; p.amount = 300;
        p.reference = ref; p.released = released;
        return p;
    }
}
