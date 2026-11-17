package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RepoStoreFailureRegressionTest {
    private final List<Lesson> lessons = new ArrayList<>();
    private final List<Repo.Booking> bookings = new ArrayList<>();
    private final List<Repo.TutorSession> sessions = new ArrayList<>();
    private final List<Repo.Payout> payouts = new ArrayList<>();
    private final List<Repo.Notif> notifications = new ArrayList<>();
    private Object previousStore;
    private AccountStore.Account previousIdentity;
    private String previousSession;
    private Lesson previousDraft, previousDraftLesson;
    private Repo.Booking previousLastBooking;
    private int previousLessonId, previousRef;
    private File blockingFile;
    private Context context;

    @Before public void isolateCommittedRecordsAndMakeStorageUnwritable() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        lessons.addAll(Repo.LESSONS); bookings.addAll(Repo.BOOKINGS); sessions.addAll(Repo.TUTOR_SESSIONS);
        payouts.addAll(Repo.PAYOUTS); notifications.addAll(Repo.NOTIFS);
        previousLessonId = Repo.nextLessonId; previousRef = Repo.nextRef;
        previousStore = field("committedStore").get(null);
        previousIdentity = (AccountStore.Account) field("identity").get(null);
        previousSession = context.getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE).getString("session", null);
        previousDraft = Repo.draft; previousDraftLesson = Repo.draftLesson; previousLastBooking = Repo.lastBooking;
        Repo.LESSONS.clear(); Repo.BOOKINGS.clear(); Repo.TUTOR_SESSIONS.clear(); Repo.PAYOUTS.clear(); Repo.NOTIFS.clear();
        Repo.nextLessonId = 2; Repo.nextRef = 831;
        blockingFile = new File(context.getCacheDir(), "repo-blocked-" + UUID.randomUUID());
        assertTrue(blockingFile.createNewFile());
        Context invalidDirectory = new ContextWrapper(context) {
            @Override public File getFilesDir() { return blockingFile; }
        };
        field("committedStore").set(null, new RepoStore(invalidDirectory));
    }

    @After public void restoreCommittedRecordsAndStorage() throws Exception {
        field("committedStore").set(null, previousStore);
        Repo.LESSONS.clear(); Repo.LESSONS.addAll(lessons);
        Repo.BOOKINGS.clear(); Repo.BOOKINGS.addAll(bookings);
        Repo.TUTOR_SESSIONS.clear(); Repo.TUTOR_SESSIONS.addAll(sessions);
        Repo.PAYOUTS.clear(); Repo.PAYOUTS.addAll(payouts);
        Repo.NOTIFS.clear(); Repo.NOTIFS.addAll(notifications);
        Repo.nextLessonId = previousLessonId; Repo.nextRef = previousRef;
        Repo.activateAccount(previousIdentity);
        Repo.draft = previousDraft; Repo.draftLesson = previousDraftLesson; Repo.lastBooking = previousLastBooking;
        SharedPreferences.Editor session = context.getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE).edit();
        if (previousSession == null) session.remove("session"); else session.putString("session", previousSession);
        assertTrue(session.commit());
        assertTrue(blockingFile.delete());
    }

    @Test public void failedBookingSaveRollsBackAllCommittedRecordsAndReference() throws Exception {
        AccountStore.Account tutor = TestAccounts.tutor();
        Lesson lesson = validLesson(tutor);
        Repo.LESSONS.add(lesson);
        TestAccounts.student();
        Repo.draftLesson = lesson;
        Repo.draftStartAtMillis = SessionTime.startAt(LocalDate.now(SessionTime.ZONE).plusDays(1), 600);
        Repo.draftDuration = 60; Repo.draftPrice = 600; Repo.draftPayment = "GCash";
        Repo.lastBooking = null;
        try {
            Repo.confirmDraft();
            fail("A booking must not be confirmed when committed storage fails");
        } catch (IllegalStateException expected) { }
        assertTrue(Repo.BOOKINGS.isEmpty());
        assertTrue(Repo.TUTOR_SESSIONS.isEmpty());
        assertTrue(Repo.PAYOUTS.isEmpty());
        assertTrue(Repo.NOTIFS.isEmpty());
        assertEquals(831, Repo.nextRef);
        assertNull(Repo.lastBooking);
        assertSame("Keep the draft available to retry", lesson, Repo.draftLesson);
    }

    @Test public void failedListingSavePreservesDraftAndLessonSequence() throws Exception {
        AccountStore.Account tutor = TestAccounts.tutor();
        Repo.startDraft();
        Lesson draft = Repo.draft;
        draft.title = "Retry listing"; draft.description = "Still editable";
        draft.duration = 60; draft.price = 600; draft.day = validLesson(tutor).day; draft.slots = "10:00 am";
        assertEquals(-1, draft.id);
        try {
            Repo.publishDraft();
            fail("A listing must not be published when committed storage fails");
        } catch (IllegalStateException expected) { }
        assertTrue(Repo.LESSONS.isEmpty());
        assertTrue(Repo.NOTIFS.isEmpty());
        assertSame(draft, Repo.draft);
        assertEquals("The unpublished draft must keep its draft id", -1, draft.id);
        assertEquals(2, Repo.nextLessonId);
    }

    private Lesson validLesson(AccountStore.Account tutor) {
        Lesson lesson = new Lesson(1, "Retry booking", tutor.name, "QT", Repo.CAT_TECH, 1, 600, 60,
                true, "Retry after storage recovers", 0, 0xff112233, false);
        lesson.tutorId = tutor.id;
        lesson.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(1));
        lesson.slots = "10:00 am";
        return lesson;
    }
    private Field field(String name) throws Exception {
        Field result = Repo.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
}
