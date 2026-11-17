package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class AdminLoginEntryTest {
    @Test public void administratorPasswordUpgradePreservesProfileAndStudentSession() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AccountStore.Account student = TestAccounts.student();
        android.content.SharedPreferences prefs = context.getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE);
        String key = "account:" + AccountStore.DEMO_ADMIN;
        String original = prefs.getString(key, null);
        int version = prefs.getInt("debug_admin_credentials_version", 0);
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec("Legacy-admin-test".toCharArray(), salt, 210000, 256);
        byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        org.json.JSONObject legacy = new org.json.JSONObject(original).put("name", "Preserved Administrator")
                .put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                .put("hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP));
        prefs.edit().putString(key, legacy.toString()).putInt("debug_admin_credentials_version", 1).commit();
        try {
            AccountStore upgraded = new AccountStore(context);
            assertEquals(student.id, upgraded.current().id);
            assertEquals("Preserved Administrator", upgraded.profile(AccountStore.DEMO_ADMIN).name);
            AccountStore.Account admin = upgraded.login(AccountStore.DEMO_ADMIN, "admin@123");
            assertNotNull("The requested password must work for an existing test admin", admin);
            assertEquals(Repo.ROLE_ADMIN, admin.role);
            assertNull(upgraded.login(AccountStore.DEMO_ADMIN, "Legacy-admin-test"));
        } finally {
            prefs.edit().putString(key, original).putInt("debug_admin_credentials_version", version)
                    .putString("session", student.id).commit();
        }
    }
    @Test public void testBuildRestoresMissingAdministratorWithoutChangingStudentRole() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AccountStore.Account student = TestAccounts.student();
        context.getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE).edit()
                .remove("account:" + AccountStore.DEMO_ADMIN).putBoolean("debug_seeded", true).commit();
        AccountStore repaired = new AccountStore(context);
        assertNotNull("An upgraded test build must retain its separate admin account", repaired.profile(AccountStore.DEMO_ADMIN));
        assertEquals(Repo.ROLE_ADMIN, repaired.profile(AccountStore.DEMO_ADMIN).role);
        assertEquals(student.id, repaired.current().id);
        assertEquals(Repo.ROLE_STUDENT, repaired.current().role);
    }
    @Test public void adminHasDiscoverableLoginWithoutPublicAdminRegistration() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AccountStore store = new AccountStore(context);
        AccountStore.Account admin = TestAccounts.admin();
        assertEquals(Repo.ROLE_ADMIN, admin.role);
        store.logout(); Repo.clearIdentity();
        try (ActivityScenario<LoginActivity> screen = ActivityScenario.launch(new Intent(context, LoginActivity.class))) {
            screen.onActivity(activity -> {
                int id = activity.getResources().getIdentifier("btn_admin_login", "id", context.getPackageName());
                View entry = activity.findViewById(id);
                assertNotNull("Separate administrator sign-in must be discoverable", entry);
                entry.performClick();
                assertEquals(View.GONE, activity.findViewById(R.id.role_section).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.til_name).getVisibility());
            });
        }
        // A student cannot obtain the admin role by selecting this login entry.
        try { store.register("Student", "admin-attempt@senyas.test", TestAccounts.PASSWORD, Repo.ROLE_ADMIN); fail(); }
        catch (AccountStore.AccountException denied) { assertEquals(AccountStore.Problem.ROLE_NOT_ALLOWED, denied.problem); }
    }
}
