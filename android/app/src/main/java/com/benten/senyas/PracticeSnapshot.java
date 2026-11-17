package com.benten.senyas;

import android.os.Bundle;

/** A copied, validated practice state for restoring the screen Android saved. */
final class PracticeSnapshot {
    private PracticeSnapshot() {}

    static Bundle capture() {
        Bundle snapshot = new Bundle();
        snapshot.putInt("scenario", Repo.practiceScenario);
        snapshot.putInt("index", Repo.practiceIndex);
        snapshot.putIntArray("best", Repo.practiceBest == null ? null : Repo.practiceBest.clone());
        return snapshot;
    }

    static void restore(Bundle snapshot) {
        if (snapshot == null) return;
        int scenario = snapshot.getInt("scenario", -1);
        int[] best = snapshot.getIntArray("best");
        if (scenario < 0 || scenario >= Repo.SCENARIOS.size() || best == null
                || best.length != Repo.SCENARIOS.get(scenario).signs.length) return;
        for (int score : best) if (score < -2 || score > 100) return;
        Repo.startPractice(scenario, snapshot.getInt("index", 0));
        Repo.practiceBest = best.clone();
    }
}
