package com.benten.senyas;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;

/** Reproduces the phone crash with the real Path callback held until its view is destroyed. */
@RunWith(AndroidJUnit4.class)
public class PathLifecycleRegressionTest {
    private final List<DeferredScrollView> deferredScrolls = new ArrayList<>();

    @After public void removeDelayedTestWork() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (DeferredScrollView scroll : deferredScrolls) {
                if (scroll.callbackHandler != null)
                    scroll.callbackHandler.removeCallbacks(scroll.currentNodeScroll);
            }
        });
    }

    @Test public void currentNodeScrollStillBringsTheActivePathIntoView() throws Exception {
        TestAccounts.student();
        AtomicReference<DeferredPathFragment> shown = new AtomicReference<>();
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> shown.set(showDeferredPath(activity)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                DeferredScrollView scroll = shown.get().scroll;
                scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                int bottomPosition = scroll.getScrollY();
                assertTrue("The path must be laid out and scrollable", bottomPosition > 0);
                scroll.currentNodeScroll.run();
                assertTrue("The callback must scroll back toward the current node",
                        scroll.getScrollY() < bottomPosition);
                ViewGroup units = scroll.findViewById(R.id.units);
                ViewGroup nodes = units.getChildAt(0).findViewById(R.id.nodes);
                View current = nodes.getChildAt(3).findViewById(R.id.node);
                assertTrue("The current Salamat node must be visible", current.getGlobalVisibleRect(new Rect()));
            });
        }
    }

    @Test public void queuedCurrentNodeScrollIsHarmlessAfterPathIsDetached() throws Exception {
        TestAccounts.student();
        AtomicReference<RuntimeException> callbackFailure = new AtomicReference<>();
        AtomicReference<DeferredPathFragment> shown = new AtomicReference<>();
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> shown.set(showDeferredPath(activity)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                DeferredPathFragment path = shown.get();
                DeferredScrollView oldScroll = path.scroll;
                Runnable queued = oldScroll.currentNodeScroll;
                assertNotNull("Path must schedule its current-node scroll", queued);
                oldScroll.scrollTo(0, oldScroll.getChildAt(0).getHeight());
                assertTrue("The old path must have a real scroll position", oldScroll.getScrollY() > 0);
                activity.getSupportFragmentManager().beginTransaction().remove(path).commitNow();
                assertFalse("The callback must really outlive its fragment attachment", path.isAdded());
                int oldPosition = oldScroll.getScrollY();
                try {
                    queued.run();
                } catch (RuntimeException error) {
                    callbackFailure.set(error);
                }
                assertEquals("A stale callback must leave the destroyed view alone",
                        oldPosition, oldScroll.getScrollY());
            });
        }
        assertNull("The queued Path scroll must not call requireContext after detach: "
                + callbackFailure.get(), callbackFailure.get());
    }

    @Test public void destroyingPathRemovesItsQueuedCurrentNodeScroll() throws Exception {
        TestAccounts.student();
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> {
                DeferredPathFragment path = showDeferredPath(activity);
                Handler handler = path.scroll.getHandler();
                Runnable queued = path.scroll.currentNodeScroll;
                assertNotNull(queued);
                assertTrue("The real view Handler must have the queued scroll", handler.hasCallbacks(queued));
                activity.getSupportFragmentManager().beginTransaction().remove(path).commitNow();
                assertFalse("Destroying Path must cancel its pending work", handler.hasCallbacks(queued));
            });
        }
    }

    @Test public void rapidPathLessonPathPracticeKeepsTheCameraScreenOpen() throws Exception {
        TestAccounts.student();
        Repo.startPractice(0, 0);
        Repo.skipPracticeSign();
        Repo.skipPracticeSign();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        try (ActivityScenario<MainActivity> screen = launch()) {
            screen.onActivity(activity -> {
                FragmentManager manager = activity.getSupportFragmentManager();
                for (int repeat = 0; repeat < 5; repeat++) {
                    activity.selectTab(R.id.nav_path);
                    manager.executePendingTransactions();
                    // Reach Magkano using explicit learner progress, as in the phone recording.
                    ViewGroup units = activity.findViewById(R.id.units);
                    ViewGroup nodes = units.getChildAt(0).findViewById(R.id.nodes);
                    nodes.getChildAt(2).findViewById(R.id.node).performClick();
                    manager.executePendingTransactions();
                    assertEquals("Magkano", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
                    activity.findViewById(R.id.btn_back).performClick();
                    manager.executePendingTransactions();
                    assertTrue(manager.findFragmentById(R.id.nav_host) instanceof PathFragment);
                    activity.selectTab(R.id.nav_practice);
                    manager.executePendingTransactions();
                    assertTrue(manager.findFragmentById(R.id.nav_host) instanceof PracticeFragment);
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            screen.onActivity(activity -> {
                assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.nav_host)
                        instanceof PracticeFragment);
                assertNotNull("Practice must still display its camera preview", activity.findViewById(R.id.camera_preview));
                assertFalse(activity.isFinishing());
            });
        }
    }

    private static ActivityScenario<MainActivity> launch() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return ActivityScenario.launch(new Intent(context, MainActivity.class));
    }

    private DeferredPathFragment showDeferredPath(MainActivity activity) {
        // Exercise scrolling to Salamat using real skipped progress, not catalogue demo values.
        Repo.startPractice(0, 0);
        for (int i = 0; i < 3; i++) Repo.skipPracticeSign();
        DeferredPathFragment path = new DeferredPathFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.nav_host, path).commitNow();
        deferredScrolls.add(path.scroll);
        return path;
    }

    /** Only changes callback timing; all node construction and callback code is production Path code. */
    public static class DeferredPathFragment extends PathFragment {
        DeferredScrollView scroll;

        @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
                @Nullable ViewGroup container, @Nullable Bundle state) {
            View root = super.onCreateView(inflater, container, state);
            ScrollView original = root.findViewById(R.id.path_scroll);
            ViewGroup parent = (ViewGroup) original.getParent();
            int index = parent.indexOfChild(original);
            ViewGroup.LayoutParams params = original.getLayoutParams();
            View content = original.getChildAt(0);
            original.removeView(content);
            parent.removeView(original);
            scroll = new DeferredScrollView(inflater.getContext());
            scroll.setId(R.id.path_scroll);
            scroll.addView(content);
            parent.addView(scroll, index, params);
            scroll.captureNextPost = true;
            return root;
        }
    }

    private static class DeferredScrollView extends ScrollView {
        boolean captureNextPost;
        Runnable currentNodeScroll;
        Handler callbackHandler;

        DeferredScrollView(Context context) { super(context); }

        @Override public boolean post(Runnable action) {
            if (!captureNextPost) return super.post(action);
            captureNextPost = false;
            currentNodeScroll = action;
            // Keep the runnable on the real Android queue, outside this test's execution window.
            boolean posted = super.postDelayed(action, 60_000);
            callbackHandler = getHandler();
            return posted;
        }
    }
}
