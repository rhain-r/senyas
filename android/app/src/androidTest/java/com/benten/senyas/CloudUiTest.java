package com.benten.senyas;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;

/** A pending/failed server write must never look like a saved booking or listing. */
public class CloudUiTest {
    public static class TaskPage extends Fragment { public TaskPage() { super(R.layout.fragment_session_blocked); } }
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private void run(Fragment page, View button, Task<Integer> task, Consumer<Integer> success) {
        try {
            Class<?> helper = Class.forName("com.benten.senyas.CloudUi");
            java.lang.reflect.Method method = helper.getDeclaredMethod("run", Fragment.class, View.class, Task.class, Consumer.class);
            method.setAccessible(true); method.invoke(null, page, button, task, success);
        } catch (Exception error) { throw new AssertionError("Cloud actions need an acknowledgement and lifecycle guard", error); }
    }
    private TaskPage show(MainActivity activity) {
        TaskPage page = new TaskPage();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.nav_host, page).commitNow();
        return page;
    }
    @Test public void actionWaitsForAcknowledgementAndReenablesItsButton() throws Exception {
        TestAccounts.student();
        TaskCompletionSource<Integer> pending = new TaskCompletionSource<>();
        AtomicInteger confirmed = new AtomicInteger();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                TaskPage page = show(activity); View button = page.requireView().findViewById(R.id.btn_back);
                run(page, button, pending.getTask(), confirmed::set);
                assertFalse(button.isEnabled()); assertEquals(0, confirmed.get());
            });
            pending.setResult(7); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> { assertEquals(7, confirmed.get()); assertTrue(activity.findViewById(R.id.btn_back).isEnabled()); });
        }
    }
    @Test public void aFailedWriteCannotRunTheConfirmation() throws Exception {
        TestAccounts.student();
        TaskCompletionSource<Integer> pending = new TaskCompletionSource<>();
        AtomicInteger confirmed = new AtomicInteger();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                TaskPage page = show(activity);
                run(page, page.requireView().findViewById(R.id.btn_back), pending.getTask(), confirmed::set);
            });
            pending.setException(new IllegalStateException("offline"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> { assertEquals(0, confirmed.get()); assertTrue(activity.findViewById(R.id.btn_back).isEnabled()); });
        }
    }
    @Test public void delayedResultsCannotConfirmForADifferentAccountOrDestroyedScreen() throws Exception {
        TestAccounts.student();
        TaskCompletionSource<Integer> pending = new TaskCompletionSource<>();
        AtomicInteger confirmed = new AtomicInteger();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                TaskPage page = show(activity);
                run(page, page.requireView().findViewById(R.id.btn_back), pending.getTask(), confirmed::set);
            });
            TestAccounts.student();
            pending.setResult(7); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, confirmed.get());
        }
        TaskCompletionSource<Integer> detached = new TaskCompletionSource<>();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                TaskPage page = show(activity);
                run(page, page.requireView().findViewById(R.id.btn_back), detached.getTask(), confirmed::set);
                activity.getSupportFragmentManager().beginTransaction().remove(page).commitNow();
            });
            detached.setResult(7); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, confirmed.get());
        }
    }
}
