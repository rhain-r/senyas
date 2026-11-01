package com.benten.senyas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** What accounts save on the device: the account itself, its data, and the shared marketplace. */
public class SavedDataTest {

    @Test
    public void emailsAreMatchedWithoutCase() {
        assertEquals("ana.reyes@email.com", Account.key("  Ana.Reyes@Email.com "));
        assertEquals("", Account.key(null));
    }

    @Test
    public void accountRoundTripsAndKeepsOnlyTheHash() {
        Account a = new Account("Ana Reyes", Repo.ROLE_STUDENT, "Ana@Email.com", "pbkdf2-sha256$1$AA==$AA==");
        String json = a.toJson();
        Account back = Account.fromJson(json);
        assertEquals("Ana Reyes", back.name);
        assertEquals(Repo.ROLE_STUDENT, back.role);
        assertEquals("ana@email.com", back.email);
        assertEquals(a.passwordHash, back.passwordHash);

        assertNull(Account.fromJson(null));
        assertNull(Account.fromJson("{}"));
        assertNull(Account.fromJson("nope"));
        // Admin is never an account role; it is chosen at log-in.
        assertEquals(Repo.ROLE_LEARNER, new Account("X", Repo.ROLE_ADMIN, "x@y.z", "h").role);
    }

    @Test
    public void newAccountDataIsEmpty() {
        AccountData d = AccountData.fromJson(null);
        assertTrue(d.bookings.isEmpty());
        assertTrue(d.notifs.isEmpty());
        assertEquals(0, d.progress.streak(20759));
    }

    @Test
    public void accountDataRoundTrip() {
        AccountData d = new AccountData();
        Repo.Booking b = new Repo.Booking();
        b.title = "Basic Bookkeeping in FSL"; b.tutor = "Maria Santos"; b.dayLabel = "Mon, 19 October 2026";
        b.timeRange = "3:00–4:00 pm"; b.payment = "GCash"; b.reference = "SEN-831"; b.duration = 60; b.price = 400;
        d.bookings.add(b);
        d.notifs.add(new Repo.Notif(0, Repo.N_BOOKING_CONFIRMED, "Basic Bookkeeping in FSL", "", 1_700_000_000_000L, true));
        Scenario sc = new Scenario(0, "t", "l", 0, new String[][]{{"Kumusta", "Hello"}});
        d.progress.store(sc, new int[]{77}, 20759);

        AccountData back = AccountData.fromJson(d.toJson());
        assertEquals(1, back.bookings.size());
        Repo.Booking bb = back.bookings.get(0);
        assertEquals("Basic Bookkeeping in FSL", bb.title);
        assertEquals("Maria Santos", bb.tutor);
        assertEquals("3:00–4:00 pm", bb.timeRange);
        assertEquals("SEN-831", bb.reference);
        assertEquals(400, bb.price);
        assertFalse(bb.past);
        Repo.Notif n = back.notifs.get(0);
        assertEquals(Repo.N_BOOKING_CONFIRMED, n.type);
        assertEquals(1_700_000_000_000L, n.at);
        assertTrue(n.read);
        assertEquals(77, back.progress.best(0, "Kumusta"));
    }

    @Test
    public void marketKeepsListingOwnersAndNeverReusesIds() {
        MarketData m = new MarketData();
        Lesson l = new Lesson(104, "FSL for Cashiers", "Rina Cruz", "RC", Repo.CAT_VOC, 1.2, 300, 30, true,
                "Counting change in FSL.", 30, 0xFF1E3AB8, false);
        l.reviewed = false;
        l.slots = "9:00 am";
        m.lessons.add(l);
        m.owners.put(104, "rina@email.com");
        Repo.TutorSession s = new Repo.TutorSession();
        s.learner = "Ana Reyes"; s.tutor = "Rina Cruz"; s.tutorKey = "rina@email.com"; s.title = l.title;
        s.reference = "SEN-840";
        m.sessions.add(s);
        Repo.Payout p = new Repo.Payout();
        p.title = l.title; p.tutor = "Rina Cruz"; p.tutorKey = "rina@email.com"; p.amount = 300;
        m.payouts.add(p);
        m.nextRef = 841;

        MarketData back = MarketData.fromJson(m.toJson());
        assertEquals(1, back.lessons.size());
        Lesson bl = back.lessons.get(0);
        assertEquals(104, bl.id);
        assertEquals("FSL for Cashiers", bl.title);
        assertEquals("9:00 am", bl.slots);
        assertFalse(bl.reviewed);
        assertEquals(0xFF1E3AB8, bl.tileColor);
        assertEquals("rina@email.com", back.owners.get(104));
        assertEquals(105, back.nextLessonId); // past every saved listing
        assertEquals(841, back.nextRef);
        assertEquals("rina@email.com", back.sessions.get(0).tutorKey);
        assertEquals(300, back.payouts.get(0).amount);
        assertEquals("rina@email.com", back.payouts.get(0).tutorKey);
    }

    @Test
    public void sampleTutorRecordsHaveNoOwner() {
        Repo.Payout p = new Repo.Payout();
        p.title = "Basic Bookkeeping"; p.tutor = "Maria Santos"; p.amount = 400;
        assertNull(Records.payout(roundTrip(p)).tutorKey);
        assertNull(Records.payout(roundTrip(p)).reference);
    }

    @Test
    public void payoutKeepsTheReferenceOfItsBooking() throws Exception {
        Repo.Payout p = new Repo.Payout();
        p.title = "FSL for Cashiers"; p.tutor = "Rina Cruz"; p.tutorKey = "rina@email.com"; p.amount = 300;
        p.reference = "SEN-840";
        Repo.Payout back = Records.payout(roundTrip(p));
        assertEquals("SEN-840", back.reference);
        assertFalse(back.released);

        MarketData m = new MarketData();
        m.payouts.add(p);
        assertEquals("SEN-840", MarketData.fromJson(m.toJson()).payouts.get(0).reference);
        // Payouts saved before references existed read back without one.
        assertNull(Records.payout(new org.json.JSONObject("{\"title\":\"x\",\"amount\":1}")).reference);
    }

    @Test
    public void accountActiveFlagRoundTrips() {
        Account a = new Account("Ana Reyes", Repo.ROLE_LEARNER, "ana@email.com", "pbkdf2-sha256$1$AA==$AA==");
        assertTrue(a.active);
        assertTrue(Account.fromJson(a.toJson()).active);

        Account off = a.withActive(false);
        assertFalse(off.active);
        assertEquals(a.passwordHash, off.passwordHash);
        Account back = Account.fromJson(off.toJson());
        assertFalse(back.active);
        assertEquals("ana@email.com", back.email);
        assertTrue(back.withActive(true).active);

        // Accounts saved before suspension existed are active.
        assertTrue(Account.fromJson("{\"name\":\"A\",\"role\":0,\"email\":\"a@b.c\",\"hash\":\"h\"}").active);
    }

    @Test
    public void suspendedAccountsAreRefusedOnlyWithTheRightPassword() {
        String hash = Passwords.hash("sari-sari 2026", new byte[]{1, 2, 3, 4}, 1);
        Account on = new Account("Ana Reyes", Repo.ROLE_LEARNER, "ana@email.com", hash);
        Account off = on.withActive(false);
        assertEquals(AccountStore.NO_ACCOUNT, AccountStore.checkAccount(null, "sari-sari 2026"));
        assertEquals(AccountStore.OK, AccountStore.checkAccount(on, "sari-sari 2026"));
        assertEquals(AccountStore.WRONG_PASSWORD, AccountStore.checkAccount(on, "wrong one"));
        assertEquals(AccountStore.SUSPENDED, AccountStore.checkAccount(off, "sari-sari 2026"));
        // A wrong password never reveals that the account is suspended.
        assertEquals(AccountStore.WRONG_PASSWORD, AccountStore.checkAccount(off, "wrong one"));
    }

    private static org.json.JSONObject roundTrip(Repo.Payout p) {
        try {
            return new org.json.JSONObject(Records.payout(p).toString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
