package com.benten.senyas;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDate;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TutorBookingRegressionTest {
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), MainActivity.class));
    }

    @Test public void reviewWithoutDraftShowsUnavailableEvenWhenCatalogIsEmpty() throws Exception {
        TestAccounts.student();
        List<Lesson> original = new ArrayList<>(Repo.LESSONS);
        Repo.LESSONS.clear();
        Repo.draftLesson = null;
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(new ReviewFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertNotNull(activity.findViewById(R.id.session_blocked_message));
                assertNull(activity.findViewById(R.id.btn_pay));
            });
        } finally {
            Repo.LESSONS.clear();
            Repo.LESSONS.addAll(original);
        }
    }

    @Test public void selectedMediaSurvivesRecreationBeforePublishing() throws Exception {
        TestAccounts.tutor();
        Repo.startDraft();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(MediaFragment.newInstance(-1));
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            onView(withId(R.id.btn_add_media)).perform(click());
            onView(withText(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getResources().getStringArray(R.array.media_options)[0])).perform(click());
            onView(withId(R.id.btn_add_media)).perform(click());
            onView(withText(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getResources().getStringArray(R.array.media_options)[2])).perform(click());
            scenario.recreate();
            scenario.onActivity(activity -> {
                String added = activity.getString(R.string.state_added);
                assertEquals(added, ((TextView) activity.findViewById(R.id.tv_cover_state)).getText().toString());
                assertEquals(added, ((TextView) activity.findViewById(R.id.tv_preview_state)).getText().toString());
            });
        } finally { Repo.draft = null; }
    }

    @Test public void highListingRateKeepsItsCorrectScheduleQuote() throws Exception {
        TestAccounts.student();
        Lesson lesson = new Lesson(700001, "QA rate lesson", "QA Tutor", "QT", Repo.CAT_TECH,
                1, 100000000, 60, true, "QA", 0, 0xFF1E3AB8, false);
        lesson.tutorId = "qa-rate-tutor";
        lesson.day = LessonAvailability.formatDay(LocalDate.now(SessionTime.ZONE).plusDays(10));
        lesson.slots = "9:00 am";
        Repo.LESSONS.add(lesson);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(ScheduleFragment.newInstance(lesson.id));
                activity.getSupportFragmentManager().executePendingTransactions();
                activity.findViewById(R.id.btn_review).performClick();
                assertEquals(100000000, Repo.draftPrice);
            });
        } finally { Repo.LESSONS.remove(lesson); Repo.draftLesson = null; }
    }

    @Test public void tutorSeesCancelledSessionAsCancelled() throws Exception {
        AccountStore.Account learner = TestAccounts.student(), tutor = TestAccounts.tutor();
        long start = System.currentTimeMillis() + 86400000L;
        Repo.TutorSession session = TestAccounts.session(learner, tutor, start, start + 3600000L);
        session.cancelled = true;
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(new TutorSessionsFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                ((com.google.android.material.button.MaterialButtonToggleGroup) activity.findViewById(R.id.toggle_when))
                        .check(R.id.btn_past);
                LinearLayout list = activity.findViewById(R.id.list);
                assertEquals(1, list.getChildCount());
                View row = list.getChildAt(0);
                com.google.android.material.chip.ChipGroup tags = row.findViewById(R.id.tags);
                assertEquals(activity.getString(R.string.session_cancelled_label), ((TextView) tags.getChildAt(0)).getText().toString());
                assertEquals(activity.getString(R.string.session_cancelled_note), ((TextView) row.findViewById(R.id.tv_note)).getText().toString());
                assertEquals(View.GONE, row.findViewById(R.id.btn_join).getVisibility());
            });
        } finally {
            Repo.TUTOR_SESSIONS.remove(session);
            Repo.BOOKINGS.removeIf(booking -> session.reference.equals(booking.reference));
        }
    }

    @Test public void linkedPayoutCannotBeReleasedUntilItsSessionEnds() throws Exception {
        AccountStore.Account learner = TestAccounts.student(), tutor = TestAccounts.tutor();
        long start = System.currentTimeMillis() + 86400000L;
        Repo.TutorSession session = TestAccounts.session(learner, tutor, start, start + 3600000L);
        Repo.Payout payout = new Repo.Payout();
        payout.title = "QA pending session"; payout.tutor = tutor.name; payout.tutorId = tutor.id;
        payout.amount = 400; payout.reference = session.reference;
        Repo.PAYOUTS.add(0, payout);
        TestAccounts.admin();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(new AdminPayoutsFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                LinearLayout list = activity.findViewById(R.id.list);
                assertFalse("An upcoming session is not ready for payout", list.getChildAt(0).findViewById(R.id.btn_release).isEnabled());
                session.endAtMillis = System.currentTimeMillis() - 1;
                session.startAtMillis = session.endAtMillis - session.duration * 60000L;
                activity.open(new AdminPayoutsFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                list = activity.findViewById(R.id.list);
                View release = list.getChildAt(0).findViewById(R.id.btn_release);
                assertTrue(release.isEnabled());
                release.performClick();
                assertTrue(payout.released);
            });
        } finally {
            Repo.PAYOUTS.remove(payout);
            Repo.TUTOR_SESSIONS.remove(session);
            Repo.BOOKINGS.removeIf(booking -> session.reference.equals(booking.reference));
        }
    }

    @Test public void listingFieldsBoundInputBeforeAdvancingToMedia() throws Exception {
        TestAccounts.tutor(); Repo.startDraft();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(LessonFormFragment.newInstance(-1));
                activity.getSupportFragmentManager().executePendingTransactions();
                TextView title = activity.findViewById(R.id.et_title), description = activity.findViewById(R.id.et_desc);
                title.setText(new String(new char[1000]).replace('\0', 'T'));
                description.setText(new String(new char[20000]).replace('\0', 'D'));
                assertEquals(200, title.getText().length());
                assertEquals(10000, description.getText().length());
                ((TextView) activity.findViewById(R.id.et_rate)).setText("400");
                activity.findViewById(R.id.btn_primary).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                TextView city = activity.findViewById(R.id.et_city);
                city.setText(new String(new char[1000]).replace('\0', 'C'));
                assertEquals(200, city.getText().length());
            });
        } finally { Repo.draft = null; }
    }

    @Test public void failedListingEditRestoresCommittedFieldsAndKeepsFormOpen() throws Exception {
        TestAccounts.tutor();
        Lesson lesson = new Lesson(Repo.nextLessonId++, "Original title", Repo.userName, "QT", Repo.CAT_TECH,
                1, 400, 60, true, "Original description", 0, 0xFF1E3AB8, false);
        lesson.tutorId = Repo.accountId(); Repo.LESSONS.add(lesson);
        Lesson invalid = new Lesson(Repo.nextLessonId++, new String(new char[100001]).replace('\0', 'X'),
                "QA invalid", "QI", Repo.CAT_TECH, 1, 400, 60, true, "Invalid storage fixture", 0, 0, false);
        invalid.tutorId = "qa-invalid"; Repo.LESSONS.add(invalid);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(LessonFormFragment.newInstance(lesson.id));
                activity.getSupportFragmentManager().executePendingTransactions();
                ((TextView) activity.findViewById(R.id.et_title)).setText("Changed title");
                ((TextView) activity.findViewById(R.id.et_rate)).setText("600");
                activity.findViewById(R.id.btn_primary).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("Original title", lesson.title);
                assertEquals(400, lesson.price);
                assertNotNull(activity.findViewById(R.id.et_title));
            });
        } finally { Repo.LESSONS.remove(lesson); Repo.LESSONS.remove(invalid); }
    }
}
