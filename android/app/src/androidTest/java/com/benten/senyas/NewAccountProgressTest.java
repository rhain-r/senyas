package com.benten.senyas;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

/** New identities must not inherit catalogue demo progress or another learner's path. */
public class NewAccountProgressTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void newAccountStartsAtTheFirstSignWithNoCompletedNodes() throws Exception {
        TestAccounts.student();
        assertEquals(0, Repo.xp);
        assertEquals(0, Repo.dailyXp);
        assertEquals(0, Repo.streakDays);
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.selectTab(R.id.nav_path);
                activity.getSupportFragmentManager().executePendingTransactions();
                ViewGroup units = activity.findViewById(R.id.units);
                for (int unit = 0; unit < 3; unit++) {
                    ViewGroup nodes = units.getChildAt(unit).findViewById(R.id.nodes);
                    for (int index = 0; index < nodes.getChildCount(); index++) {
                        String word = Repo.SCENARIOS.get(unit).signs[index][0];
                        int description = index == 0 ? R.string.path_node_current : R.string.path_node_locked;
                        assertEquals("A new account has never completed " + word,
                                activity.getString(description, word), node(units, unit, index).getContentDescription());
                    }
                }
            });
        }
    }

    @Test public void earnedPathSurvivesScenarioChangesAndAccountSwitching() throws Exception {
        AccountStore.Account first = TestAccounts.student();
        Repo.startPractice(0, 0);
        for (int i = 0; i < 3; i++) assertTrue(Repo.skipPracticeSign());
        assertTrue(Repo.recordVerifiedMatch(0, 3, 91));
        assertTrue(Repo.skipPracticeSign());
        Repo.startPractice(1, 0);
        assertTrue(Repo.skipPracticeSign());
        AccountStore.Account second = TestAccounts.student();
        assertEquals(0, Repo.xp);
        TestAccounts.signIn(first);
        assertEquals(10, Repo.xp);
        assertPathNode(0, 3, R.string.path_node_done);
        assertPathNode(0, 4, R.string.path_node_current);
        assertPathNode(1, 1, R.string.path_node_current);
        TestAccounts.signIn(second);
        assertEquals(0, Repo.xp);
        assertPathNode(0, 0, R.string.path_node_current);
        assertPathNode(0, 3, R.string.path_node_locked);
    }

    @Test public void legacySavedScoresMigrateWithoutInventingEarlierCompletions() throws Exception {
        AccountStore.Account learner = TestAccounts.student();
        Repo.clearIdentity();
        org.json.JSONArray scores = new org.json.JSONArray();
        for (int i = 0; i < 10; i++) scores.put(i == 3 ? 91 : i == 1 ? -2 : -1);
        org.json.JSONObject legacy = new org.json.JSONObject().put("scenario", 0).put("index", 3)
                .put("scores", scores).put("xp", 10).put("dailyXp", 10).put("streak", 0)
                .put("day", java.time.LocalDate.now(SessionTime.ZONE).toString());
        assertTrue(context().getSharedPreferences("senyas_learning_progress", Context.MODE_PRIVATE).edit()
                .putString(learner.id, legacy.toString()).commit());
        TestAccounts.signIn(learner);
        assertEquals(10, Repo.xp);
        assertEquals(10, Repo.scenarioProgressPct(0));
        assertPathNode(0, 0, R.string.path_node_current);
        assertPathNode(0, 3, R.string.path_node_done);
    }

    private void assertPathNode(int unit, int index, int description) {
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(new Intent(context(), MainActivity.class))) {
            screen.onActivity(activity -> {
                activity.selectTab(R.id.nav_path);
                activity.getSupportFragmentManager().executePendingTransactions();
                ViewGroup units = activity.findViewById(R.id.units);
                assertEquals(activity.getString(description, Repo.SCENARIOS.get(unit).signs[index][0]),
                        node(units, unit, index).getContentDescription());
            });
        }
    }

    private View node(ViewGroup units, int unit, int index) {
        ViewGroup nodes = units.getChildAt(unit).findViewById(R.id.nodes);
        return nodes.getChildAt(index).findViewById(R.id.node);
    }
}
