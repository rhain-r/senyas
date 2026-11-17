package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.UUID;
import org.junit.Test;

public class ProgressPersistenceTest {
    private Object store(SharedPreferences prefs) throws Exception {
        try {
            java.lang.reflect.Constructor<?> constructor = Class.forName("com.benten.senyas.LearningProgressStore").getDeclaredConstructor(SharedPreferences.class);
            constructor.setAccessible(true); return constructor.newInstance(prefs);
        } catch (ClassNotFoundException missing) { throw new AssertionError("Practice progress must survive a cold app restart", missing); }
    }
    private boolean call(Object store, String method, String account) throws Exception {
        java.lang.reflect.Method operation = store.getClass().getDeclaredMethod(method, String.class);
        operation.setAccessible(true); return (boolean) operation.invoke(store, account);
    }
    @Test public void skippedProgressSurvivesReloadWithoutLeakingToAnotherAccount() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("progress-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        AccountStore.Account firstAccount = TestAccounts.student();
        String account = Repo.accountId();
        Repo.startPractice(1, 2); Repo.practiceBest[1] = -2;
        Object first = store(prefs);
        assertTrue(call(first, "save", account));
        TestAccounts.student();
        assertFalse(call(store(prefs), "load", Repo.accountId()));
        assertEquals(0, Repo.practiceScenario);
        TestAccounts.signIn(firstAccount);
        assertTrue(call(store(prefs), "load", account));
        assertEquals(1, Repo.practiceScenario); assertEquals(2, Repo.practiceIndex); assertEquals(-2, Repo.practiceBest[1]);
        prefs.edit().clear().commit();
    }
}
