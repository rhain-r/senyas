package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AccountIsolationTest {
    @Test public void notificationsBelongToTheRecipientRatherThanEveryStudent() throws Exception {
        AccountStore.Account first = TestAccounts.student();
        Repo.addNotif(0, Repo.N_BOOKING_CONFIRMED, "Private booking", "", 0, false);
        assertTrue(Repo.unreadCount() > 0);
        TestAccounts.student();
        assertEquals("A new student must not inherit another student's messages", 0, Repo.unreadCount());
        TestAccounts.signIn(first);
        assertTrue("The original recipient retains their unread notification", Repo.unreadCount() > 0);
    }
    @Test public void missingOrForeignListingIdsCannotOpenATutorEditor() throws Exception {
        TestAccounts.tutor();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String originalTitle = Repo.LESSONS.get(0).title;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> {
                activity.open(LessonFormFragment.newInstance(999999));
                activity.getSupportFragmentManager().executePendingTransactions();
                assertFalse(activity.getSupportFragmentManager().findFragmentById(R.id.nav_host) instanceof LessonFormFragment);
                activity.open(MediaFragment.newInstance(Repo.LESSONS.get(0).id));
                activity.getSupportFragmentManager().executePendingTransactions();
                assertFalse(activity.getSupportFragmentManager().findFragmentById(R.id.nav_host) instanceof MediaFragment);
                assertEquals(originalTitle, Repo.LESSONS.get(0).title);
            });
        }
    }
    @Test public void learnerCannotBookTwoTutorsAtTheSameTime() throws Exception {
        AccountStore.Account student = TestAccounts.student();
        AccountStore.Account first = TestAccounts.tutor(), second = TestAccounts.tutor();
        long start = System.currentTimeMillis() + 3600000;
        TestAccounts.session(student, first, start, start + 3600000);
        TestAccounts.signIn(student);
        assertFalse("The learner's existing booking also reserves their time", Repo.slotAvailable(second.id, start, 60));
    }
    @Test public void aMissingConfirmationDoesNotCreateAnotherBooking() throws Exception {
        TestAccounts.student();
        Repo.lastBooking = null;
        Repo.draftLesson = null;
        int originalCount = Repo.BOOKINGS.size();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> {
                activity.open(new ConfirmedFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals(originalCount, Repo.BOOKINGS.size());
                assertNotNull(activity.findViewById(R.id.session_blocked_message));
            });
        }
    }
}
