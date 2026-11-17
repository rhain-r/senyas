package com.benten.senyas;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;

public class VerifiedFeedbackRegressionTest {
    private boolean record(int scenario, int index, int score) throws Exception {
        try {
            Method method = Repo.class.getDeclaredMethod("recordVerifiedMatch", int.class, int.class, int.class);
            method.setAccessible(true); return (boolean) method.invoke(null, scenario, index, score);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Verified recognition must reach saved practice feedback", missing);
        }
    }

    @Test public void verifiedMatchSavesBestScoreAndAwardsOnlyOncePerAccountAndSign() throws Exception {
        AccountStore.Account first = TestAccounts.student();
        Repo.startPractice(0, 0);
        assertTrue(record(0, 0, 90));
        assertEquals(90, Repo.practiceBest[0]); assertEquals(10, Repo.xp);
        assertTrue(record(0, 0, 95));
        assertEquals(95, Repo.practiceBest[0]); assertEquals(10, Repo.xp);
        Repo.restartPractice();
        assertTrue(record(0, 0, 90)); assertEquals(10, Repo.xp);
        TestAccounts.student();
        assertEquals(0, Repo.xp); assertEquals(-1, Repo.practiceBest[0]);
        TestAccounts.signIn(first);
        assertEquals(90, Repo.practiceBest[0]); assertEquals(10, Repo.xp);
        assertTrue(record(0, 0, 96)); assertEquals(10, Repo.xp);
    }

    @Test public void rejectedAndStaleSignResultsCannotAwardXp() throws Exception {
        TestAccounts.student(); Repo.startPractice(0, 0);
        assertFalse(record(0, 0, 84));
        assertFalse(record(1, 0, 95));
        assertFalse(record(0, 1, 95));
        assertEquals(-1, Repo.practiceBest[0]); assertEquals(0, Repo.xp);
    }
}
