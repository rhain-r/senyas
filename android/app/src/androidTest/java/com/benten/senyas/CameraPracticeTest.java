package com.benten.senyas;

import static org.junit.Assert.*;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CameraPracticeTest {
    @Test
    public void appCanRequestCameraAccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue("The app must declare camera access before Android can grant it",
                info.requestedPermissions != null
                        && Arrays.asList(info.requestedPermissions).contains(Manifest.permission.CAMERA));
    }

    @Test
    public void cameraDenialThenGrantSupportsNavigationBackgroundAndRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AccountStore.Account student = TestAccounts.student();
        AccountStore.Account tutor = TestAccounts.tutor();
        TestAccounts.signIn(student);
        Repo.TutorSession session = TestAccounts.session(student, tutor,
                System.currentTimeMillis() - 60000, System.currentTimeMillis() + 600000);
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        assertEquals("Run this test with fresh test-app data", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.CAMERA));
        Repo.startPractice(0, 0);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.selectTab(R.id.nav_practice));
            clickPermissionButton(automation, "permission_deny_button");
            waitFor("Permission recovery action", () -> {
                if (scenario.getState() != Lifecycle.State.RESUMED) return false;
                AtomicBoolean visible = new AtomicBoolean();
                scenario.onActivity(activity -> {
                    View action = activity.findViewById(R.id.camera_action);
                    visible.set(action != null && action.isShown() && action.isEnabled());
                });
                return visible.get();
            });
            scenario.onActivity(activity -> activity.findViewById(R.id.camera_action).performClick());
            clickPermissionButton(automation, "permission_allow_foreground_only_button");
            waitForStream(scenario);
            saveScreenshot(context, automation, "practice-camera.png");

            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            waitForStream(scenario);
            scenario.recreate();
            waitForStream(scenario);

            scenario.onActivity(activity -> activity.open(LiveClassroomFragment.newInstance(session.reference)));
            waitForStream(scenario);
            saveScreenshot(context, automation, "classroom-camera.png");
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            waitForStream(scenario);
        }
    }

    @Test
    public void practiceDoesNotInventScoresWithoutASignModel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestAccounts.student();
        Repo.practiceBest = null;
        Repo.startPractice(0, 0);
        int startingXp = Repo.xp;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                new Intent(context, MainActivity.class))) {
            scenario.onActivity(activity -> activity.selectTab(R.id.nav_practice));
            // The old demo auto-saves an invented score after a 1.4 second animation.
            Thread.sleep(2400);
            scenario.onActivity(activity -> {
                assertEquals("No model result means no recorded sign score", -1, Repo.practiceBest[0]);
                assertEquals("Camera access alone must not earn XP", startingXp, Repo.xp);
                View bar = activity.findViewById(R.id.score_bar);
                assertNotNull("Keep the original match bar even while grading is unavailable", bar);
                assertEquals(View.VISIBLE, bar.getVisibility());
                assertTrue(bar.getHeight() > 0);
                View skip = activity.findViewById(R.id.btn_skip);
                skip.requestRectangleOnScreen(new android.graphics.Rect(0, 0, skip.getWidth(), skip.getHeight()), true);
            });
            Thread.sleep(250);
            saveScreenshot(context, InstrumentationRegistry.getInstrumentation().getUiAutomation(), "practice-bar-restored.png");
        }
    }

    private static void waitForStream(ActivityScenario<MainActivity> scenario) throws Exception {
        waitFor("Live camera frames", () -> {
            AtomicBoolean streaming = new AtomicBoolean();
            scenario.onActivity(activity -> {
                PreviewView camera = activity.findViewById(R.id.camera_preview);
                streaming.set(camera != null
                        && camera.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING);
            });
            return streaming.get();
        });
    }

    private static void clickPermissionButton(UiAutomation automation, String id) throws Exception {
        waitFor("Android permission button: " + id, () -> {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo button = findById(root, ":id/" + id);
            return button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        });
    }

    private static AccessibilityNodeInfo findById(AccessibilityNodeInfo node, String suffix) {
        String resource = node.getViewIdResourceName();
        if (resource != null && resource.endsWith(suffix)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            AccessibilityNodeInfo found = findById(child, suffix);
            if (found != null) return found;
        }
        return null;
    }

    private static void waitFor(String description, BooleanSupplier ready) throws Exception {
        long deadline = System.currentTimeMillis() + 40000;
        while (System.currentTimeMillis() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(150);
        }
        fail("Timed out waiting for " + description);
    }

    private static void saveScreenshot(Context context, UiAutomation automation, String name) throws Exception {
        try (FileOutputStream file = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            assertTrue(automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, file));
        }
    }
}
