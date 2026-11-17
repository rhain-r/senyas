package com.benten.senyas;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class LoginFlowTest {
    @Test public void signupAndLaterLoginRetainStudentRoleThroughTheRealForm() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AccountStore store = new AccountStore(context);
        store.logout(); Repo.clearIdentity();
        String email = "login-" + UUID.randomUUID() + "@senyas.test";
        try (ActivityScenario<LoginActivity> signup = ActivityScenario.launch(new Intent(context, LoginActivity.class))) {
            signup.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.et_name)).setText("Signup Student");
                ((EditText) activity.findViewById(R.id.et_email)).setText(email);
                ((EditText) activity.findViewById(R.id.et_password)).setText(TestAccounts.PASSWORD);
                activity.findViewById(R.id.role_student).performClick();
                activity.findViewById(R.id.btn_submit).performClick();
            });
            MainActivity main = waitForMain();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals(1, Repo.accountRole());
                assertEquals("Signup Student", Repo.userName);
                store.logout(); Repo.clearIdentity(); main.finish();
            });
        }
        try (ActivityScenario<LoginActivity> login = ActivityScenario.launch(new Intent(context, LoginActivity.class)
                .putExtra("role", Repo.ROLE_ADMIN))) {
            login.onActivity(activity -> {
                activity.findViewById(R.id.btn_login).performClick();
                ((EditText) activity.findViewById(R.id.et_email)).setText(email);
                ((EditText) activity.findViewById(R.id.et_password)).setText(TestAccounts.PASSWORD);
                activity.findViewById(R.id.btn_submit).performClick();
            });
            MainActivity main = waitForMain();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals(1, Repo.accountRole());
                com.google.android.material.bottomnavigation.BottomNavigationView nav = main.findViewById(R.id.bottom_nav);
                assertNull(nav.getMenu().findItem(R.id.nav_a_dashboard));
                assertNull(nav.getMenu().findItem(R.id.nav_t_home));
                main.finish();
            });
        }
    }
    private MainActivity waitForMain() throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<MainActivity> result = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof MainActivity) result.set((MainActivity) activity);
            });
            if (result.get() != null) return result.get();
            Thread.sleep(100);
        }
        throw new AssertionError("Successful login did not open the student's main screen");
    }
}
