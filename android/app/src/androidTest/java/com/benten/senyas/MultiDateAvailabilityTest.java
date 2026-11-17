package com.benten.senyas;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import android.content.Intent;
import android.view.View;
import android.widget.DatePicker;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.time.LocalDate;
import java.util.Arrays;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MultiDateAvailabilityTest {
    @Test public void tutorCalendarAddsDatesWithoutDiscardingEarlierSelections() throws Exception {
        TestAccounts.tutor();
        LocalDate first = LocalDate.now(SessionTime.ZONE).plusDays(5);
        LocalDate second = first.plusDays(2), third = first.plusDays(4);
        Lesson[] saved = new Lesson[1];
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                Repo.startDraft();
                Repo.draft.title = "Multiple available dates";
                Repo.draft.price = 400;
                Repo.draft.day = LessonAvailability.formatDay(first);
                Repo.draft.slots = "9:15 am, 11:45 am";
                saved[0] = Repo.publishDraft();
                activity.open(MediaFragment.newInstance(saved[0].id));
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            addDate(second);
            addDate(third);
            addDate(second); // Selecting an existing date must not duplicate it.
            scenario.recreate();
            scenario.onActivity(activity -> activity.findViewById(R.id.btn_publish).performClick());
            scenario.onActivity(activity -> {
                assertTrue("Adding dates must retain the original selected date",
                        LessonAvailability.offers(saved[0], SessionTime.startAt(first, 555), 60));
                assertTrue("Adding a third date must retain the second selected date",
                        LessonAvailability.offers(saved[0], SessionTime.startAt(second, 555), 60));
                assertTrue(LessonAvailability.offers(saved[0], SessionTime.startAt(third, 555), 60));
                activity.open(MediaFragment.newInstance(saved[0].id));
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            screenshot("multidate-tutor.png");
            scenario.onActivity(activity -> {
                int id = activity.getResources().getIdentifier("cg_selected_days", "id", activity.getPackageName());
                ChipGroup dates = activity.findViewById(id);
                assertNotNull("Selected dates must be visible and individually removable", dates);
                assertEquals(3, dates.getChildCount());
                ((Chip) dates.getChildAt(0)).performCloseIconClick();
                assertEquals(2, dates.getChildCount());
                activity.findViewById(R.id.btn_publish).performClick();
                assertFalse(LessonAvailability.offers(saved[0], SessionTime.startAt(first, 555), 60));
                assertTrue(LessonAvailability.offers(saved[0], SessionTime.startAt(second, 555), 60));
            });
        } finally {
            if (saved[0] != null) { Repo.LESSONS.remove(saved[0]); Repo.persist(); }
        }
    }

    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class));
    }

    @Test public void learnerCanChooseEveryTutorDateAndRetainsTheSelectedDateAfterRecreation() throws Exception {
        TestAccounts.student();
        LocalDate first = LocalDate.now(SessionTime.ZONE).plusDays(5);
        LocalDate second = first.plusDays(2), third = first.plusDays(4);
        Lesson[] fixture = new Lesson[1];
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                Lesson lesson = new Lesson(Repo.nextLessonId++, "Several available dates", "Tutor", "T", Repo.CAT_TECH,
                        1, 400, 60, true, "Multi-date booking", 0, 0, false);
                lesson.tutorId = AccountStore.DEMO_TUTOR;
                lesson.slots = "9:15 am, 11:45 am";
                LessonAvailability.setDays(lesson, Arrays.asList(LessonAvailability.formatDay(first),
                        LessonAvailability.formatDay(second), LessonAvailability.formatDay(third)));
                Repo.LESSONS.add(lesson);
                fixture[0] = lesson;
                activity.open(ScheduleFragment.newInstance(lesson.id));
                activity.getSupportFragmentManager().executePendingTransactions();
                ChipGroup dates = activity.findViewById(R.id.cg_day);
                assertEquals(3, dates.getChildCount());
                for (int index = 0; index < 3; index++) {
                    dates.check(dates.getChildAt(index).getId());
                    assertTrue(((TextView) activity.findViewById(R.id.tv_summary)).getText().toString()
                            .contains(LessonAvailability.formatDay(Arrays.asList(first, second, third).get(index))));
                    assertTrue(activity.findViewById(R.id.btn_review).isEnabled());
                }
                ChipGroup times = activity.findViewById(R.id.cg_time);
                times.check(times.getChildAt(1).getId());
                ((com.google.android.material.button.MaterialButtonToggleGroup) activity.findViewById(R.id.toggle_duration)).check(R.id.btn_dur30);
            });
            screenshot("multidate-learner.png");
            scenario.recreate();
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.btn_review).performClick();
                assertEquals(LessonAvailability.formatDay(third), Repo.draftDay);
                assertEquals(705, Repo.draftStartMin);
                assertEquals(30, Repo.draftDuration);
                assertEquals(SessionTime.startAt(third, 705), Repo.draftStartAtMillis);
            });
        } finally {
            if (fixture[0] != null) { Repo.LESSONS.remove(fixture[0]); Repo.persist(); }
        }
    }

    private void addDate(LocalDate day) {
        onView(withId(R.id.actv_day)).perform(click());
        onView(isAssignableFrom(DatePicker.class)).perform(new ViewAction() {
            public Matcher<View> getConstraints() { return isAssignableFrom(DatePicker.class); }
            public String getDescription() { return "add another tutor availability date"; }
            public void perform(UiController controller, View view) {
                ((DatePicker) view).updateDate(day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
            }
        });
        onView(withId(android.R.id.button1)).perform(click());
    }

    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(250);
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        java.io.File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("qa");
        assertNotNull(directory);
        if (!directory.exists()) assertTrue(directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
