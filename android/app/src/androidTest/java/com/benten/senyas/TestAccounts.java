package com.benten.senyas;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Test-only fixtures: tests authenticate through the same account store as the app. */
final class TestAccounts {
    static final String PASSWORD = "Qa-only-password-2026";
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    static AccountStore.Account create(int role, String name) throws Exception {
        AccountStore.Account account = new AccountStore(context()).register(name,
                "qa-" + UUID.randomUUID() + "@senyas.test", PASSWORD, role);
        Repo.activateAccount(account);
        return account;
    }
    static AccountStore.Account student() throws Exception { return create(1, "QA Student"); }
    static AccountStore.Account tutor() throws Exception { return create(2, "QA Tutor"); }
    static void signIn(AccountStore.Account account) {
        AccountStore.Account signedIn = new AccountStore(context()).login(account.email, PASSWORD);
        if (signedIn == null) throw new AssertionError("Fixture login failed");
        Repo.activateAccount(signedIn);
    }
    static AccountStore.Account admin() throws Exception {
        try (InputStream stream = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("demo_accounts_private.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONArray accounts = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            for (int index = 0; index < accounts.length(); index++) {
                JSONObject fixture = accounts.getJSONObject(index);
                if (fixture.getInt("role") != 3) continue;
                AccountStore.Account account = new AccountStore(context()).login(fixture.getString("email"), fixture.getString("password"));
                if (account == null) throw new AssertionError("Admin fixture login failed");
                Repo.activateAccount(account);
                return account;
            }
        }
        throw new AssertionError("Admin fixture absent");
    }
    static Repo.TutorSession session(AccountStore.Account student, AccountStore.Account tutor, long start, long end) {
        Repo.TutorSession session = new Repo.TutorSession();
        session.reference = "SEN-" + Repo.nextRef++;
        session.learner = student.name; session.learnerId = student.id;
        session.tutor = tutor.name; session.tutorId = tutor.id;
        session.title = "QA session"; session.category = Repo.CAT_TECH;
        session.startAtMillis = start; session.endAtMillis = end;
        session.duration = Math.max(1, (int) ((end - start) / 60000));
        session.endAtMillis = SessionTime.endAt(start, session.duration);
        session.dayLabel = DateTimeFormatter.ofPattern("EEE, d MMMM uuuu", Locale.ENGLISH)
                .format(Instant.ofEpochMilli(start).atZone(SessionTime.ZONE));
        session.timeRange = "3:00–4:00 pm";
        Repo.TUTOR_SESSIONS.add(session);
        Repo.Booking booking = new Repo.Booking();
        booking.reference = session.reference; booking.learnerId = student.id; booking.tutorId = tutor.id;
        booking.tutor = tutor.name; booking.title = session.title; booking.dayLabel = session.dayLabel;
        booking.timeRange = session.timeRange; booking.duration = session.duration;
        booking.payment = "GCash";
        booking.startAtMillis = start; booking.endAtMillis = session.endAtMillis;
        Repo.BOOKINGS.add(booking);
        return session;
    }
}
