package com.benten.senyas;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;

/** Local progress belongs to an account, including optional skipped signs. */
final class LearningProgressStore {
    private final SharedPreferences preferences;
    LearningProgressStore(SharedPreferences preferences) { this.preferences = preferences; }

    boolean save(String accountId) {
        if (accountId == null || accountId.isEmpty() || Repo.practiceBest == null) return false;
        try {
            JSONArray scores = new JSONArray();
            for (int score : Repo.practiceBest) scores.put(score);
            JSONArray path = new JSONArray();
            for (int s = 0; s < Repo.pathBest.length; s++) {
                int[] values = Repo.pathBest[s].clone();
                if (s == Repo.practiceScenario) for (int i = 0; i < values.length; i++) {
                    int attempted = Repo.practiceBest[i];
                    if (attempted >= 0) values[i] = Math.max(values[i], attempted);
                    else if (attempted == -2 && values[i] == -1) values[i] = -2;
                }
                JSONArray row = new JSONArray();
                for (int value : values) row.put(value);
                path.put(row);
            }
            JSONObject state = new JSONObject().put("scenario", Repo.practiceScenario)
                    .put("index", Repo.practiceIndex).put("scores", scores)
                    .put("path", path)
                    .put("xp", Repo.xp).put("dailyXp", Repo.dailyXp).put("streak", Repo.streakDays)
                    .put("rewarded", new JSONArray(Repo.rewardedSigns))
                    .put("day", LocalDate.now(SessionTime.ZONE).toString());
            return preferences.edit().putString(accountId, state.toString()).commit();
        } catch (Exception invalid) { return false; }
    }

    boolean load(String accountId) {
        try {
            String json = preferences.getString(accountId, null);
            if (json == null) return false;
            JSONObject state = new JSONObject(json);
            int scenario = state.getInt("scenario"), index = state.getInt("index");
            if (scenario < 0 || scenario >= Repo.SCENARIOS.size()) return false;
            JSONArray scores = state.getJSONArray("scores");
            int total = Repo.SCENARIOS.get(scenario).signs.length;
            if (index < 0 || index >= total || scores.length() != total) return false;
            int[] best = new int[total];
            for (int i = 0; i < total; i++) {
                best[i] = scores.getInt(i);
                if (best[i] < -2 || best[i] > 100) return false;
            }
            int xp = state.getInt("xp"), daily = state.getInt("dailyXp"), streak = state.getInt("streak");
            if (xp < 0 || daily < 0 || daily > xp || streak < 0) return false;
            boolean today = LocalDate.now(SessionTime.ZONE).equals(LocalDate.parse(state.getString("day")));
            java.util.Set<String> rewarded = new java.util.HashSet<>();
            JSONArray savedRewards = state.optJSONArray("rewarded");
            if (savedRewards != null) {
                if (savedRewards.length() > 1000) return false;
                for (int i = 0; i < savedRewards.length(); i++) {
                    String word = savedRewards.getString(i);
                    if (FslSigns.label(word) < 0) return false;
                    rewarded.add(word);
                }
            }
            int[][] path = new int[Repo.SCENARIOS.size()][];
            JSONArray savedPath = state.optJSONArray("path");
            if (state.has("path") && (savedPath == null || savedPath.length() != path.length)) return false;
            for (int s = 0; s < path.length; s++) {
                path[s] = new int[Repo.SCENARIOS.get(s).signs.length];
                java.util.Arrays.fill(path[s], -1);
                if (savedPath == null) {
                    if (s == scenario) path[s] = best.clone(); // migrate actual legacy results only
                } else {
                    JSONArray row = savedPath.getJSONArray(s);
                    if (row.length() != path[s].length) return false;
                    for (int i = 0; i < path[s].length; i++) {
                        path[s][i] = row.getInt(i);
                        if (path[s][i] < -2 || path[s][i] > 100) return false;
                    }
                }
            }
            Repo.pathBest = path;
            Repo.startPractice(scenario, index);
            Repo.practiceBest = best;
            Repo.xp = xp; Repo.dailyXp = today ? daily : 0; Repo.streakDays = streak;
            Repo.rewardedSigns.clear(); Repo.rewardedSigns.addAll(rewarded);
            return true;
        } catch (Exception invalid) { return false; }
    }
}
