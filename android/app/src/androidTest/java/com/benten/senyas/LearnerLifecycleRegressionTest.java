package com.benten.senyas;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

/** Recreates saved screens after discarding the process's in-memory identity and practice. */
public class LearnerLifecycleRegressionTest {
    @Test public void dictionaryFindsTheExactEnglishTranslationWithAnApostrophe() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            screen.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.et_search)).setText("You're welcome");
                LinearLayout results = activity.findViewById(R.id.results);
                assertEquals("An exact dictionary translation must find its sign", 1, results.getChildCount());
                assertEquals("Walang anuman", ((TextView) results.getChildAt(0).findViewById(R.id.tv_title)).getText().toString());
                results.getChildAt(0).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("Walang anuman", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
            });
        }
    }

    @Test public void savedPracticeRestoresItsScenarioSignAndSkippedSigns() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        Repo.startPractice(1, 1);
        Repo.skipPracticeSign();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.selectTab(R.id.nav_practice);
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("Estudyante", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
            });
            screen.moveToState(Lifecycle.State.CREATED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Repo.clearIdentity();
                Repo.startPractice(0, 0);
            });
            screen.recreate();
            screen.moveToState(Lifecycle.State.RESUMED);
            screen.onActivity(activity -> {
                assertEquals("A restored camera must keep its original scenario", 1, Repo.practiceScenario);
                assertEquals(2, Repo.practiceIndex);
                assertEquals(-2, Repo.practiceBest[1]);
                assertEquals("Estudyante", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
            });
        }
    }

    @Test public void savedSummaryKeepsItsScenarioAfterProcessStateIsLost() throws Exception {
        TestAccounts.student();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Repo.startPractice(2, 4);
        Repo.skipPracticeSign();
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.open(new PracticeSummaryFragment());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("0 / 5", ((TextView) activity.findViewById(R.id.tv_score)).getText().toString());
            });
            screen.moveToState(Lifecycle.State.CREATED);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Repo.clearIdentity();
                Repo.startPractice(0, 0);
            });
            screen.recreate();
            screen.moveToState(Lifecycle.State.RESUMED);
            screen.onActivity(activity -> {
                assertEquals("A restored summary must describe its completed scenario", 2, Repo.practiceScenario);
                assertEquals(-2, Repo.practiceBest[4]);
                assertEquals("0 / 5", ((TextView) activity.findViewById(R.id.tv_score)).getText().toString());
                activity.findViewById(R.id.btn_again).performClick();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("Tulong", ((TextView) activity.findViewById(R.id.tv_word)).getText().toString());
                assertEquals(2, Repo.practiceScenario);
                assertEquals(-1, Repo.practiceBest[4]);
            });
        }
    }
}
