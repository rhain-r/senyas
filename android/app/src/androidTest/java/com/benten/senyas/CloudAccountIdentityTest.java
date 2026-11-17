package com.benten.senyas;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import com.google.firebase.auth.FirebaseUser;

/** Run only with connectedEmulator: these operations must never use production accounts. */
@RunWith(AndroidJUnit4.class)
public class CloudAccountIdentityTest {
    private Context emulatorContext() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getResources().getBoolean(R.bool.firebase_emulators_enabled));
        return context;
    }
    @Test public void cloudIdentityUsesUidAndRetainsProfileAcrossLogin() throws Exception {
        Context context = emulatorContext();
        AccountStore store = new AccountStore(context);
        store.logout();
        String email = "android-" + UUID.randomUUID() + "@senyas.test";
        AccountStore.Account created = store.register("Shared Tutor", email, "Tutor-pass-2026", 2);
        assertNotEquals("Cloud identity must use the Firebase UID", email, created.id);
        assertEquals("Shared Tutor", created.name);
        assertEquals(2, created.role);
        assertTrue(created.active);
        assertEquals(created.id, CloudRuntime.auth().getCurrentUser().getUid());
        assertEquals("demo-senyas", CloudRuntime.db().getApp().getOptions().getProjectId());
        store.logout();
        assertNull(store.current());
        assertNull(store.login(email, "wrong-password"));
        AccountStore.Account restored = store.login(email.toUpperCase(), "Tutor-pass-2026");
        assertNotNull(restored);
        assertEquals(created.id, restored.id);
        assertEquals("Shared Tutor", restored.name);
        assertEquals(2, restored.role);
        assertEquals(created.id, new AccountStore(context).current().id);
        store.logout();
        assertNull(new AccountStore(context).current());
    }

    @Test public void connectedBuildHasNoBootstrapAdminAndPublicSignupCannotCreateOne() throws Exception {
        AccountStore store = new AccountStore(emulatorContext());
        store.logout();
        assertTrue(store.profiles().isEmpty());
        assertNull(store.profile(AccountStore.DEMO_ADMIN));
        try {
            store.register("Owner", "admin-" + UUID.randomUUID() + "@senyas.test", "Admin-pass-2026", 3);
            fail("Public clients must not create admins");
        } catch (AccountStore.AccountException expected) {
            assertEquals(AccountStore.Problem.ROLE_NOT_ALLOWED, expected.problem);
        }
        assertNull(CloudRuntime.auth().getCurrentUser());
    }

    @Test public void suspendedServerProfileInvalidatesCachedIdentityAndLogin() throws Exception {
        AccountStore store = new AccountStore(emulatorContext());
        store.logout();
        String email = "suspended-" + UUID.randomUUID() + "@senyas.test";
        AccountStore.Account account = store.register("Student", email, "Student-pass-2026", 1);
        adminProfileMutation(account.id, "PATCH", "?updateMask.fieldPaths=active", "{\"fields\":{\"active\":{\"booleanValue\":false}}}");
        try {
            Tasks.await(CloudAccounts.refreshProfile(), 30, TimeUnit.SECONDS);
            fail("Suspended profiles cannot retain an active session");
        } catch (java.util.concurrent.ExecutionException expected) { }
        assertNull(store.current());
        assertNull(CloudRuntime.auth().getCurrentUser());
        assertNull(store.login(email, "Student-pass-2026"));
        assertNull(CloudRuntime.auth().getCurrentUser());
    }

    @Test public void missingServerProfileCannotFallBackToCachedOrLocalIdentity() throws Exception {
        AccountStore store = new AccountStore(emulatorContext());
        store.logout();
        String email = "missing-" + UUID.randomUUID() + "@senyas.test";
        AccountStore.Account account = store.register("Learner", email, "Learner-pass-2026", 0);
        adminProfileMutation(account.id, "DELETE", "", null);
        store.logout();
        assertNull(store.login(email, "Learner-pass-2026"));
        assertNull(store.current());
        assertNull(CloudRuntime.auth().getCurrentUser());
    }

    @Test public void failedProfileWriteRollsBackNewAuthIdentityAndAllowsRetry() throws Exception {
        AccountStore store = new AccountStore(emulatorContext());
        store.logout();
        String email = "rollback-" + UUID.randomUUID() + "@senyas.test";
        Tasks.await(CloudRuntime.db().disableNetwork(), 10, TimeUnit.SECONDS);
        try {
            store.register("Retry Student", email, "Student-pass-2026", 1);
            fail("An offline profile write must not report successful registration");
        } catch (AccountStore.AccountException expected) {
            assertEquals(AccountStore.Problem.STORAGE_FAILURE, expected.problem);
            assertNull(store.current());
            assertNull(CloudRuntime.auth().getCurrentUser());
        } finally {
            Tasks.await(CloudRuntime.db().enableNetwork(), 10, TimeUnit.SECONDS);
        }
        AccountStore.Account retry = store.register("Retry Student", email, "Student-pass-2026", 1);
        assertEquals(1, retry.role);
        assertEquals("Retry Student", retry.name);
        store.logout();
    }

    @Test public void interruptedSignupCanFinishOnlyItsMatchingSafeProfileOnNextLogin() throws Exception {
        Context context = emulatorContext();
        AccountStore store = new AccountStore(context);
        store.logout();
        String email = "pending-" + UUID.randomUUID() + "@senyas.test";
        FirebaseUser user = Tasks.await(CloudRuntime.auth().createUserWithEmailAndPassword(email, "Student-pass-2026"), 25, TimeUnit.SECONDS).getUser();
        JSONObject pending = new JSONObject().put("uid", user.getUid()).put("email", email).put("name", "Interrupted Student")
                .put("role", 1).put("active", true);
        assertTrue(context.getSharedPreferences("senyas_emulator_identity", Context.MODE_PRIVATE).edit()
                .putString("pending:" + user.getUid(), pending.toString()).commit());
        store.logout();
        AccountStore.Account restored = store.login(email, "Student-pass-2026");
        assertNotNull(restored);
        assertEquals(user.getUid(), restored.id);
        assertEquals("Interrupted Student", restored.name);
        assertEquals(1, restored.role);
        assertFalse(context.getSharedPreferences("senyas_emulator_identity", Context.MODE_PRIVATE).contains("pending:" + user.getUid()));
        store.logout();
    }

    @Test public void pendingSignupCannotProvisionAnAdministrator() throws Exception {
        Context context = emulatorContext();
        AccountStore store = new AccountStore(context);
        store.logout();
        String email = "pending-admin-" + UUID.randomUUID() + "@senyas.test";
        FirebaseUser user = Tasks.await(CloudRuntime.auth().createUserWithEmailAndPassword(email, "Student-pass-2026"), 25, TimeUnit.SECONDS).getUser();
        JSONObject pending = new JSONObject().put("uid", user.getUid()).put("email", email).put("name", "Forged Owner")
                .put("role", 3).put("active", true);
        assertTrue(context.getSharedPreferences("senyas_emulator_identity", Context.MODE_PRIVATE).edit()
                .putString("pending:" + user.getUid(), pending.toString()).commit());
        store.logout();
        assertNull(store.login(email, "Student-pass-2026"));
        assertNull(store.current());
        assertNull(CloudRuntime.auth().getCurrentUser());
    }

    private void adminProfileMutation(String uid, String method, String query, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://10.0.2.2:8180/v1/projects/demo-senyas/databases/(default)/documents/profiles/" + uid + query).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer owner");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try { assertTrue("Emulator admin fixture must be written", connection.getResponseCode() / 100 == 2); }
        finally { connection.disconnect(); }
    }
}
