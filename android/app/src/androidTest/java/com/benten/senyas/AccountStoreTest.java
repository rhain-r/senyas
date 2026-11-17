package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class AccountStoreTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private String email() { return "test-" + UUID.randomUUID() + "@senyas.test"; }

    @Test public void roleAndCredentialsSurviveAStoreReload() throws Exception {
        AccountStore store = new AccountStore(context());
        String email = email();
        AccountStore.Account student = store.register("Student", email, "Student-pass-2026", 1);
        store.logout();
        AccountStore restored = new AccountStore(context());
        assertNull(restored.login(email, "wrong-password"));
        AccountStore.Account account = restored.login(email.toUpperCase(), "Student-pass-2026");
        assertNotNull(account);
        assertEquals(student.id, account.id);
        assertEquals(1, account.role);
        assertEquals(1, new AccountStore(context()).current().role);
        restored.logout();
    }
    @Test public void aStudentEmailCannotBeRegisteredAgainAsTutorOrAdmin() throws Exception {
        AccountStore store = new AccountStore(context());
        String email = email();
        store.register("Student", email, "Student-pass-2026", 1);
        try {
            store.register("Tutor", email, "Tutor-pass-2026", 2);
            fail("Duplicate email must not change the existing account role");
        } catch (AccountStore.AccountException expected) { }
        try {
            store.register("Admin", email(), "Admin-pass-2026", 3);
            fail("Public registration must not create administrators");
        } catch (AccountStore.AccountException expected) { }
        assertEquals(1, store.login(email, "Student-pass-2026").role);
        store.logout();
    }
    @Test public void identicalNamesDoNotMergeAccountIdentitiesAndPasswordsAreHashed() throws Exception {
        AccountStore store = new AccountStore(context());
        String password = "Unique-" + UUID.randomUUID();
        AccountStore.Account first = store.register("Same Name", email(), password, 2);
        AccountStore.Account second = store.register("Same Name", email(), "Other-pass-2026", 2);
        assertNotEquals(first.id, second.id);
        for (Object value : context().getSharedPreferences("senyas_accounts", Context.MODE_PRIVATE).getAll().values()) {
            assertFalse("Account records must never contain a plaintext password", value.toString().contains(password));
        }
        store.logout();
        assertNull(new AccountStore(context()).current());
    }
}
