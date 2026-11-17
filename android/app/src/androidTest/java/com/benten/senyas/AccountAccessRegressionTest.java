package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AccountAccessRegressionTest {
    @Test
    public void callerCannotChooseAdminThroughIntentExtras() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE).edit().clear().commit();
        Intent forged = new Intent(context, MainActivity.class)
                .putExtra("role", Repo.ROLE_ADMIN).putExtra("name", "Student User");
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(forged)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("No signed-in account must redirect to login, regardless of caller-supplied role",
                    Lifecycle.State.DESTROYED, activity.getState());
        }
    }
}
