package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Intent;
import android.widget.TextView;
import android.widget.DatePicker;
import android.widget.TimePicker;
import android.view.View;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import org.hamcrest.Matcher;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.textfield.TextInputLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AvailabilityRegressionTest {
    private String futureDay() {
        return DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.ENGLISH)
                .format(LocalDate.now(SessionTime.ZONE).plusDays(5));
    }
    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(InstrumentationRegistry.getInstrumentation()
                .getTargetContext(), MainActivity.class));
    }
    @Test public void tutorCannotSaveArbitraryTextAsAvailability() throws Exception {
        TestAccounts.tutor();
        Repo.startDraft();
        Repo.draft.day = futureDay();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(MediaFragment.newInstance(-1));
                activity.getSupportFragmentManager().executePendingTransactions();
                ((TextView) activity.findViewById(R.id.et_city)).setText("Manila");
                ((TextView) activity.findViewById(R.id.et_slots)).setText("Eydhhehw");
                activity.findViewById(R.id.btn_publish).performClick();
                assertNotNull("Malformed input must keep the draft open", Repo.draft);
                assertNotNull(((TextInputLayout) activity.findViewById(R.id.til_slots)).getError());
                assertEquals("", Repo.draft.slots);
            });
        }
    }
    @Test public void learnerSeesOnlyTheTutorPublishedDateAndTimes() throws Exception {
        TestAccounts.student();
        Lesson lesson = Repo.byId(1);
        String originalDay = lesson.day, originalSlots = lesson.slots;
        lesson.day = futureDay();
        lesson.slots = "9:15 am, 11:45 am";
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(ScheduleFragment.newInstance(lesson.id));
                activity.getSupportFragmentManager().executePendingTransactions();
                com.google.android.material.chip.ChipGroup times = activity.findViewById(R.id.cg_time);
                assertEquals(2, times.getChildCount());
                assertEquals("9:15 am", ((TextView) times.getChildAt(0)).getText().toString().toLowerCase(Locale.ENGLISH));
                activity.findViewById(R.id.btn_review).performClick();
                assertEquals(9 * 60 + 15, Repo.draftStartMin);
                assertEquals(lesson.day, Repo.draftDay);
                assertEquals(SessionTime.parseStart(lesson.day, "9:15 am"), Repo.draftStartAtMillis);
            });
        } finally { lesson.day = originalDay; lesson.slots = originalSlots; }
    }

    @Test public void tutorCanPickFutureDateAddRemoveTimesAndRestoreTheForm() throws Exception {
        TestAccounts.tutor();
        Repo.startDraft();
        Repo.draft.title = "QA availability lesson";
        Repo.draft.price = 400;
        LocalDate selected = LocalDate.now(SessionTime.ZONE).plusDays(7);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(MediaFragment.newInstance(-1));
                activity.getSupportFragmentManager().executePendingTransactions();
                activity.findViewById(R.id.actv_day).performClick();
            });
            onView(isAssignableFrom(DatePicker.class)).perform(new ViewAction() {
                public Matcher<View> getConstraints() { return isAssignableFrom(DatePicker.class); }
                public String getDescription() { return "choose upcoming availability date"; }
                public void perform(UiController controller, View view) {
                    ((DatePicker) view).updateDate(selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
                }
            });
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(R.id.et_slots)).perform(scrollTo(), click());
            pickTime(9, 15);
            onView(withId(R.id.et_slots)).perform(scrollTo(), click());
            onView(withText(R.string.availability_add_time)).perform(click());
            pickTime(11, 45);
            onView(withId(R.id.et_slots)).perform(scrollTo(), click());
            onView(withText("Remove 9:15 am")).perform(click());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(LessonAvailability.formatDay(selected), ((TextView) activity.findViewById(R.id.actv_day)).getText().toString());
                assertEquals("11:45 am", ((TextView) activity.findViewById(R.id.et_slots)).getText().toString());
                ((TextView) activity.findViewById(R.id.et_city)).setText("Manila");
                Lesson draft = Repo.draft;
                activity.findViewById(R.id.btn_publish).performClick();
                assertNull(Repo.draft);
                assertEquals(LessonAvailability.formatDay(selected), draft.day);
                assertEquals("11:45 am", draft.slots);
                Repo.LESSONS.remove(draft);
            });
        }
    }

    private void pickTime(int hour, int minute) {
        onView(isAssignableFrom(TimePicker.class)).perform(new ViewAction() {
            public Matcher<View> getConstraints() { return isAssignableFrom(TimePicker.class); }
            public String getDescription() { return "choose availability start time"; }
            public void perform(UiController controller, View view) {
                ((TimePicker) view).setHour(hour);
                ((TimePicker) view).setMinute(minute);
            }
        });
        onView(withId(android.R.id.button1)).perform(click());
    }

    @Test public void learnerSelectedTimeAndDurationSurviveRecreation() throws Exception {
        TestAccounts.student();
        Lesson lesson = Repo.byId(1);
        String originalDay = lesson.day, originalSlots = lesson.slots;
        lesson.day = futureDay();
        lesson.slots = "9:15 am, 11:45 am";
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(ScheduleFragment.newInstance(lesson.id));
                activity.getSupportFragmentManager().executePendingTransactions();
                com.google.android.material.chip.ChipGroup times = activity.findViewById(R.id.cg_time);
                times.check(times.getChildAt(1).getId());
                ((com.google.android.material.button.MaterialButtonToggleGroup) activity.findViewById(R.id.toggle_duration))
                        .check(R.id.btn_dur30);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.btn_review).performClick();
                assertEquals(11 * 60 + 45, Repo.draftStartMin);
                assertEquals(30, Repo.draftDuration);
                assertEquals(SessionTime.parseStart(lesson.day, "11:45 am"), Repo.draftStartAtMillis);
            });
        } finally { lesson.day = originalDay; lesson.slots = originalSlots; }
    }
}
