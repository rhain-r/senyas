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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * HOME (Duolingo-style). Mascot + streak/XP header, daily goal, continue / camera buttons,
 * sign of the day. The sign-dictionary search (typed or spoken) is kept at the top of the sheet.
 */
public class LearnFragment extends Fragment implements StatusTint {

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
    public int statusColor() { return R.color.home_pattern_a; }

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

        // Header
        String first = Repo.userName == null ? "" : Repo.userName.trim().split("\\s+")[0];
        ((TextView) v.findViewById(R.id.tv_bubble)).setText(getString(R.string.home_greeting, first));

        // Daily goal card
        int goalPct = Math.min(100, Repo.dailyXp * 100 / Math.max(1, Repo.dailyGoalXp));
        ((LinearProgressIndicator) v.findViewById(R.id.goal_progress)).setProgress(goalPct);
        ((TextView) v.findViewById(R.id.tv_goal_xp)).setText(
                getString(R.string.xp_progress, Repo.dailyXp, Repo.dailyGoalXp));
        ((TextView) v.findViewById(R.id.tv_streak)).setText(getResources().getQuantityString(
                R.plurals.streak_days, Repo.streakDays, Repo.streakDays));
        ((TextView) v.findViewById(R.id.tv_xp)).setText(getString(R.string.xp_total, Repo.xp));

        // Continue: the first scenario that is in progress, otherwise the first one
        Scenario current = Repo.SCENARIOS.get(0);
        for (Scenario sc : Repo.SCENARIOS) {
            int progress = Repo.scenarioProgressPct(sc.id);
            if (progress > 0 && progress < 100) { current = sc; break; }
        }
        ((TextView) v.findViewById(R.id.btn_continue)).setText(getString(R.string.home_continue, current.title));
        v.findViewById(R.id.btn_continue).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_path));

        v.findViewById(R.id.btn_camera).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_practice));

        // Sign of the day (changes every day)
        String[] sotd = Repo.SIGNS[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % Repo.SIGNS.length];
        ((TextView) v.findViewById(R.id.tv_sotd)).setText(sotd[0]);
        v.findViewById(R.id.sotd).setOnClickListener(x -> ((MainActivity) requireActivity())
                .open(SignLessonFragment.newInstanceForWord(sotd[0], sotd[1])));

        // Search
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
        if (getView() != null) Ui.bindBell(getView(), (MainActivity) requireActivity());
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
