package com.benten.senyas;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CloudSnapshotGuardTest {
    @Test public void staleIdentityOrRoleCannotReplaceVisibleLessonsAndValidRefreshKeepsDraftReference() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Field identity;
            try { identity = Repo.class.getDeclaredField("identity"); identity.setAccessible(true); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            AccountStore.Account previous;
            try { previous = (AccountStore.Account) identity.get(null); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            List<Lesson> saved = new ArrayList<>(Repo.LESSONS);
            Lesson previousDraft = Repo.draftLesson;
            int previousRole = Repo.role; String previousName = Repo.userName, previousOwner = Repo.ownerName;
            try {
                AccountStore.Account actor = AccountStore.Account.cloud("current-user", "current@example.com", "Current", Repo.ROLE_STUDENT, true);
                // Avoid activating a synthetic account through persistence or a connected Auth session.
                identity.set(null, actor); Repo.role = actor.role;
                Lesson canonical = lesson("Old title"); Repo.LESSONS.clear(); Repo.LESSONS.add(canonical); Repo.draftLesson = canonical;
                List<Lesson> incoming = Collections.singletonList(lesson("Server title"));
                assertFalse(CloudData.applyLessonSnapshot("previous-user", actor.role, incoming));
                assertFalse(CloudData.applyLessonSnapshot(actor.id, Repo.ROLE_TUTOR, incoming));
                assertEquals("Old title", canonical.title);
                assertSame(canonical, Repo.draftLesson);
                assertTrue(CloudData.applyLessonSnapshot(actor.id, actor.role, incoming));
                assertSame("Scheduled drafts retain their canonical lesson", canonical, Repo.byId(17));
                assertSame(canonical, Repo.draftLesson);
                assertEquals("Server title", canonical.title);
                assertTrue(CloudData.applyLessonSnapshot(actor.id, actor.role, Collections.emptyList()));
                assertNull("A deleted lesson cannot remain bookable through a stale draft", Repo.draftLesson);
            } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            finally {
                try { identity.set(null, previous); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                Repo.role = previousRole; Repo.userName = previousName; Repo.ownerName = previousOwner;
                Repo.LESSONS.clear(); Repo.LESSONS.addAll(saved); Repo.draftLesson = previousDraft;
            }
        });
    }

    private Lesson lesson(String title) {
        Lesson lesson = new Lesson(17, title, "Tutor", "T", Repo.CAT_TECH, 1, 200, 30,
                true, "Description", 0, 0xff112233, false);
        lesson.tutorId = "tutor"; return lesson;
    }
}
