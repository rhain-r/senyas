package com.benten.senyas;

import static org.junit.Assert.*;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class SkipSignTest {
    @Test public void lessonCanSkipWithoutOpeningCameraOrAwardingXp() throws Exception {
        TestAccounts.student(); Repo.restartPractice(); Repo.startPractice(0, 0);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int xp = Repo.xp;
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.open(SignLessonFragment.newInstance(0, 0));
                activity.getSupportFragmentManager().executePendingTransactions();
                View skip = activity.findViewById(activity.getResources().getIdentifier("btn_lesson_skip", "id", context.getPackageName()));
                assertNotNull("A sign can be skipped before camera practice", skip);
                skip.performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals(1, Repo.practiceIndex);
                assertEquals(-2, Repo.practiceBest[0]);
                assertEquals(Repo.SCENARIOS.get(0).signs[1][0], ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
                assertNull(activity.findViewById(R.id.camera_preview));
                assertEquals(xp, Repo.xp);
            });
        }
    }

    @Test public void cameraPracticeSkipAdvancesAndLastSkipEndsWithoutInventingAMatch() throws Exception {
        TestAccounts.student(); Repo.restartPractice(); Repo.startPractice(0, 0);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        int xp = Repo.xp;
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.selectTab(R.id.nav_practice);
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals(activity.getString(R.string.skip_sign), ((TextView) activity.findViewById(R.id.btn_skip)).getText().toString());
                activity.findViewById(R.id.btn_skip).performClick();
                assertEquals(1, Repo.practiceIndex);
                assertEquals(-2, Repo.practiceBest[0]);
                int last = Repo.practiceBest.length - 1;
                Repo.startPractice(0, last);
            });
            screen.recreate();
            screen.onActivity(activity -> {
                int last = Repo.practiceBest.length - 1;
                activity.findViewById(R.id.btn_skip).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals(-2, Repo.practiceBest[last]);
                assertTrue(activity.getSupportFragmentManager().findFragmentById(R.id.nav_host) instanceof PracticeSummaryFragment);
                assertEquals(xp, Repo.xp);
            });
        }
    }
}
