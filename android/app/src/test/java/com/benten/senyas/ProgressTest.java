package com.benten.senyas;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ProgressTest {
    private static final int NT = Progress.NOT_TRIED, SK = Progress.SKIPPED;
    /** 2026-11-02, a Monday. */
    private static final long MONDAY = 20759;

    private static final Scenario STORE = new Scenario(0, "At the sari-sari store", "Store", 0, new String[][]{
            {"Kumusta", "Hello"}, {"Tinapay", "Bread"}, {"Salamat", "Thank you"}, {"Walang anuman", "You're welcome"}});
    private static final Scenario JEEPNEY = new Scenario(1, "Riding a jeepney", "Jeepney", 0, new String[][]{
            {"Dalawa", "Two"}, {"Salamat", "Thank you"}});
    private static final List<Scenario> ALL = Arrays.asList(STORE, JEEPNEY);

    @Test
    public void newAccountStartsEmpty() {
        Progress p = new Progress();
        assertArrayEquals(new int[]{NT, NT, NT, NT}, p.scores(STORE));
        assertFalse(p.started(STORE));
        assertEquals(0, p.passed(STORE));
        assertEquals(0, p.resumeIndex(STORE));
        assertEquals(0, p.completedCount(ALL));
        assertEquals(0, p.streak(MONDAY));
        assertArrayEquals(new int[]{0, 0}, p.scoreSummary(ALL));
        assertEquals(0, p.lastScenario());
        assertEquals(0, p.position(2));
    }

    @Test
    public void keepsTheBetterResultForEachSign() {
        Progress p = new Progress();
        p.store(STORE, new int[]{70, SK, NT, 90}, MONDAY);
        p.store(STORE, new int[]{60, NT, SK, SK}, MONDAY); // lower, not tried and skips never win
        assertArrayEquals(new int[]{70, SK, SK, 90}, p.scores(STORE));
        p.store(STORE, new int[]{88, 40, 0, 95}, MONDAY);
        assertArrayEquals(new int[]{88, 40, 0, 95}, p.scores(STORE));
        assertFalse(p.store(STORE, new int[]{88, 40, 0, 95}, MONDAY));
    }

    @Test
    public void scoresBelongToTheirScenarioAndSign() {
        Progress p = new Progress();
        p.store(STORE, new int[]{NT, NT, 92, NT}, MONDAY);
        assertArrayEquals(new int[]{NT, NT}, p.scores(JEEPNEY)); // "Salamat" in another scenario is separate
        // The same scenario with its signs in another order still finds each sign's score.
        Scenario reordered = new Scenario(0, "At the sari-sari store", "Store", 0, new String[][]{
                {"Salamat", "Thank you"}, {"Itlog", "Egg"}, {"Kumusta", "Hello"}});
        assertArrayEquals(new int[]{92, NT, NT}, p.scores(reordered));
    }

    @Test
    public void scenarioProgressAndWhereToResume() {
        Progress p = new Progress();
        p.store(STORE, new int[]{90, 60, NT, NT}, MONDAY);
        assertTrue(p.started(STORE));
        assertEquals(1, p.passed(STORE));
        assertEquals(25, p.percent(STORE));
        assertEquals(1, p.resumeIndex(STORE)); // the first sign not passed yet
        assertFalse(p.completed(STORE));

        p.store(STORE, new int[]{90, 85, 99, 86}, MONDAY);
        assertTrue(p.completed(STORE));
        assertEquals(100, p.percent(STORE));
        assertEquals(0, p.resumeIndex(STORE)); // completed: go over it again from the start
        assertEquals(1, p.completedCount(ALL));
        assertArrayEquals(new int[]{4, 90}, p.scoreSummary(ALL));
    }

    @Test
    public void skippingAloneIsNotProgressOrAPracticeDay() {
        Progress p = new Progress();
        p.store(STORE, new int[]{SK, NT, NT, NT}, MONDAY);
        assertTrue(p.started(STORE));
        assertEquals(0, p.percent(STORE));
        assertEquals(0, p.resumeIndex(STORE));
        assertEquals(0, p.streak(MONDAY));
    }

    @Test
    public void streakCountsConsecutiveDaysEndingTodayOrYesterday() {
        Progress p = new Progress();
        p.store(STORE, new int[]{10, NT, NT, NT}, MONDAY - 3);
        p.store(STORE, new int[]{20, NT, NT, NT}, MONDAY - 2);
        p.store(STORE, new int[]{30, NT, NT, NT}, MONDAY - 1);
        assertEquals(3, p.streak(MONDAY));     // nothing yet today: the streak still stands
        assertEquals(3, p.streak(MONDAY - 1));
        assertEquals(0, p.streak(MONDAY + 1)); // a whole day missed

        p.store(STORE, new int[]{40, NT, NT, NT}, MONDAY);
        assertEquals(4, p.streak(MONDAY));

        Progress gap = new Progress();
        gap.store(STORE, new int[]{10, NT, NT, NT}, MONDAY - 5);
        gap.store(STORE, new int[]{20, NT, NT, NT}, MONDAY);
        assertEquals(1, gap.streak(MONDAY));
    }

    @Test
    public void onlyNewOrHigherScoresMakeAPracticeDay() {
        Progress p = new Progress();
        p.store(STORE, new int[]{80, NT, NT, NT}, MONDAY - 1);
        p.store(STORE, new int[]{70, NT, NT, NT}, MONDAY); // lower than before
        assertEquals(1, p.streak(MONDAY));
        assertEquals(0, p.week(MONDAY)[0]);
    }

    @Test
    public void weekCountsEachImprovedSignOncePerDay() {
        Progress p = new Progress();
        p.store(STORE, new int[]{50, NT, NT, NT}, MONDAY);
        p.store(STORE, new int[]{60, 70, NT, NT}, MONDAY); // "Kumusta" again the same day counts once
        p.store(JEEPNEY, new int[]{90, NT}, MONDAY + 2);
        assertArrayEquals(new int[]{2, 0, 1, -1, -1, -1, -1}, p.week(MONDAY + 2));
        assertArrayEquals(new int[]{2, 0, 1, 0, 0, 0, 0}, p.week(MONDAY + 6));   // Sunday
        assertArrayEquals(new int[]{0, -1, -1, -1, -1, -1, -1}, p.week(MONDAY + 7)); // next Monday
    }

    @Test
    public void jsonRoundTripKeepsEverything() {
        Progress p = new Progress();
        p.store(STORE, new int[]{90, SK, 40, NT}, MONDAY - 1);
        p.store(JEEPNEY, new int[]{NT, 80}, MONDAY);
        p.setPosition(0, 3);
        p.setPosition(1, 1);
        p.setLastScenario(1);

        Progress back = Progress.fromJson(p.toJson());
        assertArrayEquals(new int[]{90, SK, 40, NT}, back.scores(STORE));
        assertArrayEquals(new int[]{NT, 80}, back.scores(JEEPNEY));
        assertEquals(3, back.position(0));
        assertEquals(1, back.position(1));
        assertEquals(1, back.lastScenario());
        assertEquals(2, back.streak(MONDAY));
        assertArrayEquals(p.week(MONDAY), back.week(MONDAY));
        // A sign that improves again the same day still counts once after reloading.
        back.store(JEEPNEY, new int[]{NT, 100}, MONDAY);
        assertEquals(100, back.best(1, "Salamat"));
        assertEquals(1, back.week(MONDAY)[0]);
    }

    @Test
    public void unreadableJsonGivesEmptyProgress() {
        assertArrayEquals(new int[]{NT, NT, NT, NT}, Progress.fromJson(null).scores(STORE));
        assertArrayEquals(new int[]{NT, NT, NT, NT}, Progress.fromJson("not json").scores(STORE));
        assertArrayEquals(new int[]{NT, NT, NT, NT},
                Progress.fromJson("{\"best\":{\"0:kumusta\":-7},\"days\":{\"x\":2}}").scores(STORE));
    }
}
