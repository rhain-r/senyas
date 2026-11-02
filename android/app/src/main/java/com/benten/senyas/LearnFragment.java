package com.benten.senyas;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Learner HOME (Duolingo-style): mascot greeting, streak and XP chips, daily goal, Continue,
 * Practice with camera and the sign of the day, plus the sign dictionary search (typed or spoken).
 * Every number comes from the signed-in account's saved practice progress:
 * <ul>
 *   <li>XP: 10 for every sign with a score in a scenario, and 10 more once it is passed (85+),
 *       so at most 20 per sign per scenario.</li>
 *   <li>Daily goal: 10 XP for each sign that got a new best score today (Progress counts a sign
 *       once a day), out of 50 XP, i.e. five signs improved.</li>
 * </ul>
 */
public class LearnFragment extends Fragment implements StatusTint {

    static final int XP_PER_SIGN = 10, DAILY_GOAL_XP = 50;

    private EditText etSearch;
    private View browse, searchSection;
    private LinearLayout results;
    private TextView tvResultsTitle, tvResultsBody;

    // The phone's own speech recogniser: no extra permission needed, and nothing is recorded by the app.
    private final ActivityResultLauncher<Intent> voiceLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null || etSearch == null) return;
                ArrayList<String> heard = r.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (heard == null || heard.isEmpty()) return;
                Toast.makeText(requireContext(), getString(R.string.voice_heard, heard.get(0)),
                        Toast.LENGTH_SHORT).show();
                String q = Repo.normalizeQuery(heard.get(0));
                etSearch.setText(q);
                etSearch.setSelection(q.length());
            });

    @Override
    public int statusColor() {
        return R.color.home_pattern_a;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_learn, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        etSearch = v.findViewById(R.id.et_search);
        browse = v.findViewById(R.id.browse_section);
        searchSection = v.findViewById(R.id.search_section);
        results = v.findViewById(R.id.results);
        tvResultsTitle = v.findViewById(R.id.tv_results_title);
        tvResultsBody = v.findViewById(R.id.tv_results_body);

        // The bell sits on the blue header, so make it white.
        ((ImageButton) v.findViewById(R.id.btn_bell)).setColorFilter(Color.WHITE);

        v.findViewById(R.id.btn_camera).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_practice));

        v.findViewById(R.id.btn_mic).setOnClickListener(x -> startVoiceSearch());
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { updateSearch(); }
        });
        updateSearch();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getView() == null) return;
        Ui.bindBell(getView(), (MainActivity) requireActivity());
        Repo.save(); // keeps the Practice Room's latest scores before showing progress
        renderProgress(getView());
    }

    /** Greeting, streak, XP, daily goal, Continue and the sign of the day, from the account's progress. */
    private void renderProgress(View v) {
        Progress pr = Repo.progress();
        long today = Repo.today();

        ((TextView) v.findViewById(R.id.tv_bubble)).setText(
                getString(R.string.home_greeting, Repo.firstName(Repo.userName)));

        int streak = pr.streak(today);
        TextView tvStreak = v.findViewById(R.id.tv_streak);
        tvStreak.setText(getResources().getQuantityString(R.plurals.home_streak_days, streak, streak));
        tvStreak.setContentDescription(streak == 0 ? getString(R.string.streak_none)
                : getResources().getQuantityString(R.plurals.streak_days, streak, streak));

        int xp = totalXp(pr);
        TextView tvXp = v.findViewById(R.id.tv_xp);
        tvXp.setText(getString(R.string.xp_total, xp));
        tvXp.setContentDescription(getString(R.string.home_xp_desc, xp));

        int todayXp = xpToday(pr, today);
        ((TextView) v.findViewById(R.id.tv_goal_xp)).setText(
                getString(R.string.xp_progress, todayXp, DAILY_GOAL_XP));
        ((LinearProgressIndicator) v.findViewById(R.id.goal_progress))
                .setProgress(Math.min(100, todayXp * 100 / DAILY_GOAL_XP));
        v.findViewById(R.id.goal_card).setContentDescription(
                getString(R.string.home_goal_desc, todayXp, DAILY_GOAL_XP));

        // Continue: the sign the learner is on, in the scenario they are on.
        Scenario sc = resumeScenario(pr);
        int index = pr.resumeIndex(sc);
        int label = pr.completed(sc) ? R.string.home_review
                : pr.started(sc) ? R.string.home_continue : R.string.home_start;
        TextView btnContinue = v.findViewById(R.id.btn_continue);
        btnContinue.setText(getString(label, sc.title(requireContext())));
        btnContinue.setOnClickListener(x -> ((MainActivity) requireActivity())
                .open(SignLessonFragment.newInstance(sc.id, index)));

        // Sign of the day: changes daily, always a sign with a bundled video (Repo.SIGNS).
        String[] sign = Repo.SIGNS[(int) (today % Repo.SIGNS.length)];
        ((TextView) v.findViewById(R.id.tv_sotd)).setText(sign[0]);
        ((TextView) v.findViewById(R.id.tv_sotd_meaning)).setText(sign[1]);
        View sotd = v.findViewById(R.id.sotd);
        sotd.setContentDescription(getString(R.string.home_sotd_desc, sign[0], sign[1]));
        sotd.setOnClickListener(x -> ((MainActivity) requireActivity())
                .open(SignLessonFragment.newInstanceForWord(sign[0], sign[1])));
    }

    /** 10 XP for every sign with a score, 10 more once it is passed (85+). */
    static int totalXp(Progress pr) {
        int scored = pr.scoreSummary(Repo.SCENARIOS)[0], passed = 0;
        for (Scenario sc : Repo.SCENARIOS) passed += pr.passed(sc);
        return XP_PER_SIGN * (scored + passed);
    }

    /** 10 XP for each sign that got a new best score today. */
    static int xpToday(Progress pr, long today) {
        int[] week = pr.week(today); // Monday first
        int day = LocalDate.ofEpochDay(today).getDayOfWeek().getValue() - 1;
        return XP_PER_SIGN * Math.max(0, week[day]);
    }

    /**
     * The scenario the learner is on: the one practised last, unless every sign in it is passed;
     * then the first scenario that still has signs to pass (or the last one again, to review).
     */
    static Scenario resumeScenario(Progress pr) {
        int last = Math.max(0, Math.min(pr.lastScenario(), Repo.SCENARIOS.size() - 1));
        Scenario sc = Repo.SCENARIOS.get(last);
        if (pr.completed(sc)) {
            for (Scenario other : Repo.SCENARIOS) if (!pr.completed(other)) return other;
        }
        return sc;
    }

    private void startVoiceSearch() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt));
        try {
            voiceLauncher.launch(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void updateSearch() {
        String raw = etSearch.getText() == null ? "" : etSearch.getText().toString().trim();
        boolean searching = !raw.isEmpty();
        browse.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchSection.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (!searching) return;

        results.removeAllViews();
        List<String[]> found = Repo.searchSigns(raw);
        if (found.isEmpty()) {
            tvResultsTitle.setText(getString(R.string.search_no_results_title, raw));
            tvResultsBody.setVisibility(View.VISIBLE);
            tvResultsBody.setText(R.string.search_no_results_body);
            return;
        }
        tvResultsBody.setVisibility(View.GONE);
        tvResultsTitle.setText(getResources().getQuantityString(R.plurals.search_results_count, found.size(), found.size()));
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (String[] s : found) {
            View row = inf.inflate(R.layout.item_menu_row, results, false);
            ((TextView) row.findViewById(R.id.tv_title)).setText(s[0]);
            ((TextView) row.findViewById(R.id.tv_sub)).setText(s[1]);
            row.setOnClickListener(x -> ((MainActivity) requireActivity())
                    .open(SignLessonFragment.newInstanceForWord(s[0], s[1])));
            results.addView(row);
        }
    }
}
