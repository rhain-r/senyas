package com.benten.senyas;

import androidx.annotation.Nullable;

import com.senyas.cv.SignSample;
import com.senyas.ml.SignScorer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One account's practice progress: the best result per sign in every scenario, where the learner
 * left off in each, and the days a sign got a new best score (for the streak and the weekly chart).
 * Results are keyed by scenario id and sign label, so changing a scenario's sign list never mixes
 * scores up. Days are epoch days (LocalDate.toEpochDay).
 */
public final class Progress {
    public static final int NOT_TRIED = -1, SKIPPED = -2;
    public static final int PASS = SignScorer.PASS;

    private final Map<String, Integer> best = new HashMap<>();
    private final Map<Integer, Integer> position = new HashMap<>();
    /** Epoch day -> how many signs got a new best score that day. */
    private final TreeMap<Long, Integer> days = new TreeMap<>();
    /** Sign key -> the last day it got a new best, so each sign counts once per day. */
    private final Map<String, Long> improvedOn = new HashMap<>();
    private int lastScenario;

    static String key(int scenario, String word) {
        return scenario + ":" + SignSample.normalizeLabel(word);
    }

    public int best(int scenario, String word) {
        Integer v = best.get(key(scenario, word));
        return v == null ? NOT_TRIED : v;
    }

    /** The scenario's results in sign order: -1 not tried, -2 skipped, else the best score 0-100. */
    public int[] scores(Scenario sc) {
        int[] out = new int[sc.signs.length];
        for (int i = 0; i < out.length; i++) out[i] = best(sc.id, sc.signs[i][0]);
        return out;
    }

    /**
     * Keeps each sign's better result (any score beats a skip, a skip beats not tried) and counts
     * today as a practice day for every sign that gets a new or higher score. True if anything changed.
     */
    public boolean store(Scenario sc, int[] scores, long today) {
        boolean changed = false;
        for (int i = 0; i < Math.min(sc.signs.length, scores.length); i++) {
            String k = key(sc.id, sc.signs[i][0]);
            Integer old = best.get(k);
            int v = Math.min(scores[i], 100);
            if (rank(v) <= rank(old == null ? NOT_TRIED : old)) continue;
            best.put(k, v);
            changed = true;
            if (v >= 0) markDay(k, today);
        }
        return changed;
    }

    private static int rank(int result) {
        return result >= 0 ? result + 2 : result == SKIPPED ? 1 : 0;
    }

    private void markDay(String key, long today) {
        Long last = improvedOn.get(key);
        if (last != null && last == today) return;
        improvedOn.put(key, today);
        Integer n = days.get(today);
        days.put(today, n == null ? 1 : n + 1);
    }

    public void setPosition(int scenario, int index) {
        position.put(scenario, Math.max(index, 0));
    }

    /** The sign the learner was on when they last left this scenario. */
    public int position(int scenario) {
        Integer p = position.get(scenario);
        return p == null ? 0 : p;
    }

    public void setLastScenario(int scenario) {
        lastScenario = Math.max(scenario, 0);
    }

    public int lastScenario() {
        return lastScenario;
    }

    public int passed(Scenario sc) {
        int n = 0;
        for (int s : scores(sc)) if (s >= PASS) n++;
        return n;
    }

    /** Any sign tried or skipped. */
    public boolean started(Scenario sc) {
        for (int s : scores(sc)) if (s != NOT_TRIED) return true;
        return false;
    }

    public boolean completed(Scenario sc) {
        return sc.signs.length > 0 && passed(sc) == sc.signs.length;
    }

    /** Share of the scenario's signs passed, 0-100. */
    public int percent(Scenario sc) {
        return sc.signs.length == 0 ? 0 : passed(sc) * 100 / sc.signs.length;
    }

    /** The first sign not passed yet; 0 for a completed scenario, to go over it again. */
    public int resumeIndex(Scenario sc) {
        int[] s = scores(sc);
        for (int i = 0; i < s.length; i++) if (s[i] < PASS) return i;
        return 0;
    }

    public int completedCount(List<Scenario> all) {
        int n = 0;
        for (Scenario sc : all) if (completed(sc)) n++;
        return n;
    }

    /** {signs with a score, their average score} over the given scenarios. */
    public int[] scoreSummary(List<Scenario> all) {
        int count = 0, sum = 0;
        for (Scenario sc : all) {
            for (int s : scores(sc)) if (s >= 0) { count++; sum += s; }
        }
        return new int[]{count, count == 0 ? 0 : Math.round(sum / (float) count)};
    }

    /** Consecutive practice days ending today, or yesterday when today has none yet. */
    public int streak(long today) {
        long day = days.containsKey(today) ? today : today - 1;
        int n = 0;
        while (days.containsKey(day)) { n++; day--; }
        return n;
    }

    /** New best scores per day, Monday to Sunday of today's week; -1 for days still to come. */
    public int[] week(long today) {
        long monday = today - Math.floorMod(today + 3, 7); // epoch day 0 was a Thursday
        int[] out = new int[7];
        for (int i = 0; i < 7; i++) {
            long d = monday + i;
            Integer n = days.get(d);
            out[i] = d > today ? -1 : n == null ? 0 : n;
        }
        return out;
    }

    // ------------------------------------------------------------------ JSON

    JSONObject toJsonObject() throws JSONException {
        JSONObject b = new JSONObject(), p = new JSONObject(), d = new JSONObject(), im = new JSONObject();
        for (Map.Entry<String, Integer> e : best.entrySet()) b.put(e.getKey(), (int) e.getValue());
        for (Map.Entry<Integer, Integer> e : position.entrySet()) p.put(String.valueOf(e.getKey()), (int) e.getValue());
        for (Map.Entry<Long, Integer> e : days.entrySet()) d.put(String.valueOf(e.getKey()), (int) e.getValue());
        for (Map.Entry<String, Long> e : improvedOn.entrySet()) im.put(e.getKey(), (long) e.getValue());
        return new JSONObject().put("v", 1).put("last", lastScenario)
                .put("best", b).put("pos", p).put("days", d).put("improved", im);
    }

    public String toJson() {
        try {
            return toJsonObject().toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empty progress when there is nothing saved; unreadable entries are skipped. */
    static Progress from(@Nullable JSONObject o) {
        Progress p = new Progress();
        if (o == null) return p;
        p.lastScenario = Math.max(o.optInt("last", 0), 0);
        JSONObject b = o.optJSONObject("best");
        if (b != null) {
            for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                String k = it.next();
                int v = b.optInt(k, NOT_TRIED);
                if (v >= 0 || v == SKIPPED) p.best.put(k, Math.min(v, 100));
            }
        }
        JSONObject pos = o.optJSONObject("pos");
        if (pos != null) {
            for (Iterator<String> it = pos.keys(); it.hasNext(); ) {
                String k = it.next();
                try { p.position.put(Integer.parseInt(k), Math.max(pos.optInt(k, 0), 0)); } catch (NumberFormatException ignored) {}
            }
        }
        JSONObject d = o.optJSONObject("days");
        if (d != null) {
            for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                String k = it.next();
                int n = d.optInt(k, 0);
                try { if (n > 0) p.days.put(Long.parseLong(k), n); } catch (NumberFormatException ignored) {}
            }
        }
        JSONObject im = o.optJSONObject("improved");
        if (im != null) {
            for (Iterator<String> it = im.keys(); it.hasNext(); ) {
                String k = it.next();
                p.improvedOn.put(k, im.optLong(k, 0));
            }
        }
        return p;
    }

    public static Progress fromJson(@Nullable String json) {
        if (json == null) return new Progress();
        try {
            return from(new JSONObject(json));
        } catch (JSONException e) {
            return new Progress();
        }
    }
}
