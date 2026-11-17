package com.benten.senyas;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.TextView;
import androidx.camera.view.PreviewView;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class SessionAccessTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Fragment screen(MainActivity activity) { return activity.getSupportFragmentManager().findFragmentById(R.id.nav_host); }
    private ActivityScenario<MainActivity> launch() { return ActivityScenario.launch(new Intent(context(), MainActivity.class)); }

    @Test public void studentCannotSelectAnAdminRoleOrOpenTutorConsole() throws Exception {
        AccountStore.Account student = TestAccounts.student();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context(), MainActivity.class)
                .putExtra("role", Repo.ROLE_ADMIN).putExtra("name", "Fake Admin"))) {
            scenario.onActivity(activity -> {
                assertEquals(student.id, Repo.accountId());
                assertEquals(Repo.ROLE_STUDENT, Repo.accountRole());
                assertEquals(student.name, Repo.userName);
                activity.open(new AdminDashboardFragment());
                activity.open(new AdminUsersFragment());
                activity.open(new TutorHomeFragment());
                activity.selectTab(R.id.nav_a_dashboard);
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof LearnFragment);
                com.google.android.material.bottomnavigation.BottomNavigationView nav = activity.findViewById(R.id.bottom_nav);
                assertNull(nav.getMenu().findItem(R.id.nav_a_dashboard));
                assertNull(nav.getMenu().findItem(R.id.nav_t_home));
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals(Repo.ROLE_STUDENT, Repo.accountRole()));
        }
    }

    @Test public void tutorAndAdminUseDifferentConsoles() throws Exception {
        TestAccounts.tutor();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertTrue(screen(activity) instanceof TutorHomeFragment);
                activity.open(new AdminUsersFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof TutorHomeFragment);
            });
        }
        TestAccounts.admin();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                assertTrue(screen(activity) instanceof AdminDashboardFragment);
                activity.open(new TutorSessionsFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof AdminDashboardFragment);
                assertEquals(Repo.ROLE_ADMIN, Repo.accountRole());
            });
        }
    }

    @Test public void matchingDisplayNamesDoNotTransferTutorData() throws Exception {
        AccountStore.Account student = TestAccounts.student();
        AccountStore.Account first = TestAccounts.create(2, "Same tutor");
        Repo.TutorSession booked = TestAccounts.session(student, first,
                System.currentTimeMillis() - 60000, System.currentTimeMillis() + 600000);
        TestAccounts.create(2, "Same tutor");
        assertTrue(Repo.myLessons().isEmpty());
        assertTrue(Repo.sessionsFor("Same tutor").isEmpty());
        assertEquals(0, Repo.myPendingPayout());
        assertFalse(Repo.canJoinSession(booked.reference, System.currentTimeMillis()));
        TestAccounts.signIn(first);
        assertEquals(1, Repo.sessionsFor("Same tutor").size());
    }

    @Test public void futureSessionCannotBeJoinedThroughButtonRouteOrRestoredFragment() throws Exception {
        AccountStore.Account student = TestAccounts.student(), tutor = TestAccounts.tutor();
        long start = System.currentTimeMillis() + 3600000;
        Repo.TutorSession session = TestAccounts.session(student, tutor, start, start + 3600000);
        TestAccounts.signIn(student);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(new BookingsFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertFalse(activity.findViewById(R.id.btn_join).isEnabled());
                activity.open(LiveClassroomFragment.newInstance(session.reference));
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof BookingsFragment);
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.nav_host, LiveClassroomFragment.newInstance(session.reference)).commitNow();
                assertNotNull(activity.findViewById(R.id.session_blocked_message));
                assertNull(activity.findViewById(R.id.camera_preview));
            });
        }
    }

    @Test public void anotherStudentCannotSeeCancelOrJoinTheBooking() throws Exception {
        AccountStore.Account owner = TestAccounts.student(), tutor = TestAccounts.tutor();
        Repo.TutorSession session = TestAccounts.session(owner, tutor,
                System.currentTimeMillis() - 60000, System.currentTimeMillis() + 600000);
        Repo.Booking booking = Repo.BOOKINGS.get(Repo.BOOKINGS.size() - 1);
        TestAccounts.student();
        assertTrue(Repo.myBookings().isEmpty());
        assertFalse(Repo.cancelBooking(booking));
        assertFalse(Repo.canJoinSession(session.reference, System.currentTimeMillis()));
        TestAccounts.signIn(owner);
        assertTrue(Repo.canJoinSession(session.reference, System.currentTimeMillis()));
        assertTrue(Repo.cancelBooking(booking));
        TestAccounts.signIn(tutor);
        assertFalse("Cancellation must also close tutor admission", Repo.canJoinSession(session.reference, System.currentTimeMillis()));
    }

    @Test public void studentAndTutorCanCheckCameraAndJoinTheirActiveSession() throws Exception {
        if (Build.VERSION.SDK_INT >= 28) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context().getPackageName(), Manifest.permission.CAMERA);
        AccountStore.Account student = TestAccounts.student(), tutor = TestAccounts.tutor();
        Repo.TutorSession session = TestAccounts.session(student, tutor,
                System.currentTimeMillis() - 60000, System.currentTimeMillis() + 600000);
        for (AccountStore.Account participant : new AccountStore.Account[]{student, tutor}) {
            TestAccounts.signIn(participant);
            try (ActivityScenario<MainActivity> scenario = launch()) {
                scenario.onActivity(activity -> {
                    if (participant.role == Repo.ROLE_TUTOR) activity.selectTab(R.id.nav_t_sessions);
                    else activity.open(new BookingsFragment());
                    activity.getSupportFragmentManager().executePendingTransactions();
                    activity.findViewById(R.id.btn_camera_check).performClick();
                    activity.getSupportFragmentManager().executePendingTransactions();
                    assertTrue(screen(activity) instanceof CameraCheckFragment);
                });
                waitForCamera(scenario);
                scenario.onActivity(activity -> {
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    activity.getSupportFragmentManager().executePendingTransactions();
                    activity.open(LiveClassroomFragment.newInstance(session.reference));
                    activity.getSupportFragmentManager().executePendingTransactions();
                    assertTrue(screen(activity) instanceof LiveClassroomFragment);
                });
                waitForCamera(scenario);
            }
        }
    }

    @Test public void tutorHomeUsesTheEarliestSessionAndEnablesJoinAtStart() throws Exception {
        AccountStore.Account student = TestAccounts.student(), tutor = TestAccounts.tutor();
        long now = System.currentTimeMillis();
        TestAccounts.session(student, tutor, now + 7200000, now + 10800000).title = "Later session";
        Repo.TutorSession earlier = TestAccounts.session(student, tutor, now + 3600000, now + 7200000);
        earlier.title = "Earlier session";
        TestAccounts.signIn(tutor);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                View next = activity.findViewById(R.id.next_holder);
                assertEquals("Earlier session", ((TextView) next.findViewById(R.id.tv_title)).getText().toString());
                assertFalse(next.findViewById(R.id.btn_join).isEnabled());
                earlier.startAtMillis = System.currentTimeMillis() + 1200;
                earlier.endAtMillis = earlier.startAtMillis + 60000;
                earlier.duration = 1;
            });
            waitFor("Join enabling at the actual start", () -> {
                AtomicBoolean enabled = new AtomicBoolean();
                scenario.onActivity(activity -> enabled.set(activity.findViewById(R.id.next_holder)
                        .findViewById(R.id.btn_join).isEnabled()));
                return enabled.get();
            });
        }
    }

    @Test public void expiredSessionClosesTheClassroom() throws Exception {
        if (Build.VERSION.SDK_INT >= 28) InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context().getPackageName(), Manifest.permission.CAMERA);
        AccountStore.Account student = TestAccounts.student(), tutor = TestAccounts.tutor();
        Repo.TutorSession session = TestAccounts.session(student, tutor,
                System.currentTimeMillis() - 60000, System.currentTimeMillis() + 600000);
        TestAccounts.signIn(student);
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(LiveClassroomFragment.newInstance(session.reference));
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            waitForCamera(scenario);
            scenario.onActivity(activity -> {
                session.endAtMillis = System.currentTimeMillis() - 1;
                session.startAtMillis = session.endAtMillis - session.duration * 60000L;
            });
            waitFor("Classroom closing at its deadline", () -> {
                AtomicBoolean closed = new AtomicBoolean();
                scenario.onActivity(activity -> closed.set(!(screen(activity) instanceof LiveClassroomFragment)
                        && activity.findViewById(R.id.camera_preview) == null));
                return closed.get();
            });
        }
    }

    @Test public void bookingFlowSelectsAFutureSlotAndSavesItsActualOwner() throws Exception {
        AccountStore.Account student = TestAccounts.student();
        try (ActivityScenario<MainActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                activity.open(ScheduleFragment.newInstance(1));
                activity.getSupportFragmentManager().executePendingTransactions();
                activity.findViewById(R.id.btn_review).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof ReviewFragment);
                assertTrue(Repo.draftStartAtMillis > System.currentTimeMillis());
                activity.findViewById(R.id.btn_pay).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertTrue(screen(activity) instanceof ConfirmedFragment);
                assertNotNull(Repo.lastBooking);
                assertEquals(student.id, Repo.lastBooking.learnerId);
                assertFalse(Repo.canJoinSession(Repo.lastBooking.reference, System.currentTimeMillis()));
            });
        }
    }

    private void waitForCamera(ActivityScenario<MainActivity> scenario) throws Exception {
        waitFor("Live camera frames", () -> {
            AtomicBoolean streaming = new AtomicBoolean();
            scenario.onActivity(activity -> {
                PreviewView preview = activity.findViewById(R.id.camera_preview);
                streaming.set(preview != null && preview.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING);
            });
            return streaming.get();
        });
    }
    private void waitFor(String label, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(150); }
        fail("Timed out waiting for " + label);
    }
}
