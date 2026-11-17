package com.benten.senyas;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated SDK transactions against test services only; never signs into the hosted project. */
@RunWith(AndroidJUnit4.class)
public class CloudDataEmulatorTest {
    @Test public void sharedListingsBookingsOverlapCancellationAndReadMarkersUseServerState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getResources().getBoolean(R.bool.firebase_emulators_enabled));
        assertEquals("demo-senyas", CloudRuntime.db().getApp().getOptions().getProjectId());
        AccountStore store = new AccountStore(context);
        String token = UUID.randomUUID().toString(); String password = "Shared-pass-2026";
        String firstEmail = "first-" + token + "@senyas.test", secondEmail = "second-" + token + "@senyas.test";
        store.logout();
        try {
            AccountStore.Account tutor = store.register("Transaction Tutor", "tutor-" + token + "@senyas.test", password, Repo.ROLE_TUTOR);
            activate(tutor);
            Lesson first = onMain(() -> CloudData.saveLesson(lesson(tutor, "First listing " + token), true));
            Lesson second = onMain(() -> CloudData.saveLesson(lesson(tutor, "Second listing " + token), true));
            assertNotEquals(first.id, second.id); assertFalse(first.reviewed);
            store.logout();
            AccountStore.Account learner = store.register("First Learner", firstEmail, password, Repo.ROLE_STUDENT);
            activate(learner); onMain(CloudData::refresh);
            assertNull("Pending tutor content must not appear in the learner catalogue", Repo.byId(first.id));
            approveFixture(first.id); approveFixture(second.id);
            onMain(CloudData::refresh); assertNotNull(Repo.byId(first.id));
            long start = SessionTime.startAt(LocalDate.now(SessionTime.ZONE).plusDays(2), 600);
            draft(first.id, start);
            Repo.Booking booked = onMain(CloudData::bookDraft);
            assertTrue(booked.reference.startsWith("SEN-")); assertEquals(600, booked.price);
            assertEquals(1, Repo.myBookings().size());
            AtomicReference<String> marker = new AtomicReference<>();
            main(() -> {
                for (Repo.Notif notification : Repo.NOTIFS) if (notification.type == Repo.N_BOOKING_CONFIRMED) marker.set(CloudData.notificationKey(notification));
            });
            assertNotNull(marker.get()); onMain(() -> CloudData.markNotificationRead(marker.get())); onMain(CloudData::refresh);
            main(() -> {
                boolean found = false;
                for (Repo.Notif notification : Repo.NOTIFS) if (marker.get().equals(CloudData.notificationKey(notification))) { assertTrue(notification.read); found = true; }
                assertTrue(found);
            });

            store.logout(); AccountStore.Account other = store.register("Second Learner", secondEmail, password, Repo.ROLE_STUDENT);
            activate(other); onMain(CloudData::refresh);
            assertTrue("Another learner's private booking is not downloaded", Repo.myBookings().isEmpty());
            draft(second.id, start + 30 * 60000L);
            try { onMain(CloudData::bookDraft); fail("Different lessons from the same tutor must not overlap"); }
            catch (java.util.concurrent.ExecutionException expected) { assertTrue(Repo.myBookings().isEmpty()); }
            store.logout(); activate(store.login(firstEmail, password)); onMain(CloudData::refresh);
            Repo.Booking toCancel = Repo.myBookings().get(0); onMain(() -> CloudData.cancelBooking(toCancel));
            assertTrue(Repo.myBookings().isEmpty());
            store.logout(); activate(store.login(secondEmail, password)); onMain(CloudData::refresh);
            draft(second.id, start + 30 * 60000L);
            Repo.Booking replacement = onMain(CloudData::bookDraft);
            assertNotEquals(booked.reference, replacement.reference);
            assertEquals(start + 30 * 60000L, replacement.startAtMillis);
            onMain(() -> CloudData.cancelBooking(replacement));
        } finally {
            store.logout(); main(Repo::clearIdentity);
        }
    }

    private Lesson lesson(AccountStore.Account tutor, String title) {
        Lesson lesson = new Lesson(-1, title, tutor.name, "TT", Repo.CAT_TECH, 1, 600, 60,
                true, "SDK transaction integration test", 0, 0xff112233, false);
        lesson.tutorId = tutor.id; lesson.hasCover = false; lesson.hasPreview = false;
        lesson.days.add(LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(2)));
        lesson.slots = "10:00 am, 10:30 am, 11:00 am"; return lesson;
    }
    private void draft(int id, long start) {
        main(() -> {
            Repo.draftLesson = Repo.byId(id); Repo.draftStartAtMillis = start;
            Repo.draftDuration = 60; Repo.draftPrice = 600; Repo.draftPayKind = 0; Repo.draftPayment = "GCash";
        });
    }
    private void activate(AccountStore.Account account) { assertNotNull(account); main(() -> Repo.activateAccount(account)); }
    private void main(Runnable work) { InstrumentationRegistry.getInstrumentation().runOnMainSync(work); }
    private <T> T onMain(Supplier<Task<T>> action) throws Exception {
        AtomicReference<Task<T>> task = new AtomicReference<>(); main(() -> task.set(action.get()));
        return Tasks.await(task.get(), 45, TimeUnit.SECONDS);
    }
    private void approveFixture(int id) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://10.0.2.2:8180/v1/projects/demo-senyas/databases/(default)/documents/lessons/" + id + "?updateMask.fieldPaths=reviewed").openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setRequestMethod("PATCH");
        connection.setRequestProperty("Authorization", "Bearer owner"); connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true);
        try {
            try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write("{\"fields\":{\"reviewed\":{\"booleanValue\":true}}}".getBytes(StandardCharsets.UTF_8)); }
            assertEquals("Fixture approval is confined to the emulator", 200, connection.getResponseCode());
        } finally { connection.disconnect(); }
    }
}
