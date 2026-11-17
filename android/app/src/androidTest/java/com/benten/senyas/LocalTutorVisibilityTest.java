package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.Test;

public class LocalTutorVisibilityTest {
    @Test public void samePhoneStudentSeesTutorLessonAfterAdministratorApproval() throws Exception {
        TestAccounts.create(Repo.ROLE_TUTOR, "Tutor " + UUID.randomUUID());
        Repo.startDraft(); Repo.draft.title = "Visibility " + UUID.randomUUID(); Repo.draft.price = 400;
        Repo.draft.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(1));
        Repo.draft.slots = "9:00 am";
        Lesson lesson = Repo.publishDraft();
        try {
            AccountStore.Account student = TestAccounts.student();
            assertFalse(Repo.query(null, 10, lesson.title).contains(lesson));
            TestAccounts.admin();
            try (ActivityScenario<MainActivity> screen = launch()) {
                screen.onActivity(activity -> {
                    activity.selectTab(R.id.nav_a_listings);
                    activity.getSupportFragmentManager().executePendingTransactions();
                    LinearLayout list = activity.findViewById(R.id.list);
                    View card = null;
                    for (int i = 0; i < list.getChildCount(); i++) {
                        if (lesson.title.contentEquals(((TextView) list.getChildAt(i).findViewById(R.id.tv_title)).getText()))
                            card = list.getChildAt(i);
                    }
                    assertNotNull(card); card.findViewById(R.id.btn_approve).performClick();
                    assertTrue(lesson.reviewed);
                });
            }
            TestAccounts.signIn(student);
            assertTrue("Approved lessons must be shared across accounts on this installation", Repo.query(null, 10, lesson.title).contains(lesson));
        } finally { Repo.LESSONS.remove(lesson); Repo.persist(); }
    }

    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class));
    }
}
