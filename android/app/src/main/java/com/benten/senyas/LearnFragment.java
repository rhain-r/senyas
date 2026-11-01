package com.benten.senyas;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/** Learner home: browse everyday scenarios, or search the sign dictionary by typing or speaking. */
public class LearnFragment extends Fragment {

    private TextInputEditText etSearch;
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

        TextInputLayout til = v.findViewById(R.id.til_search);
        til.setEndIconOnClickListener(x -> startVoiceSearch());
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

    /** Header (greeting, streak, lessons completed) and one card per scenario, from the account's progress. */
    private void renderProgress(View v) {
        Progress pr = Repo.progress();
        ((TextView) v.findViewById(R.id.tv_greeting)).setText(
                getString(R.string.learn_greeting, Repo.firstName(Repo.userName)));
        int streak = pr.streak(Repo.today());
        ((TextView) v.findViewById(R.id.tv_streak)).setText(streak == 0 ? getString(R.string.streak_none)
                : getResources().getQuantityString(R.plurals.streak_days, streak, streak));
        int done = pr.completedCount(Repo.SCENARIOS);
        ((TextView) v.findViewById(R.id.tv_completed)).setText(done == 0 ? getString(R.string.completed_none)
                : getResources().getQuantityString(R.plurals.lessons_completed, done, done));

        LinearLayout list = v.findViewById(R.id.scenario_list);
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        Scenario next = null; // the first scenario not started yet
        boolean anyStarted = false;
        for (Scenario sc : Repo.SCENARIOS) {
            int total = sc.signs.length, passed = pr.passed(sc), pct = pr.percent(sc);
            boolean started = pr.started(sc), completed = pr.completed(sc);
            if (next == null && !started) next = sc;
            anyStarted |= started;

            View card = inf.inflate(R.layout.item_scenario, list, false);
            ((TextView) card.findViewById(R.id.tv_title)).setText(sc.title(requireContext()));
            String count = started
                    ? getResources().getQuantityString(R.plurals.signs_passed, total, passed, total)
                    : getResources().getQuantityString(R.plurals.scenario_signs, total, total);
            ((TextView) card.findViewById(R.id.tv_meta)).setText(
                    getString(R.string.scenario_meta, getString(sc.descriptionRes), count));
            LinearProgressIndicator p = card.findViewById(R.id.progress);
            if (pct > 0) {
                p.setProgress(pct);
                p.setContentDescription(getString(R.string.progress_desc, pct));
            } else {
                p.setVisibility(View.GONE);
            }
            ((TextView) card.findViewById(R.id.tv_action)).setText(completed ? R.string.action_review
                    : started ? R.string.action_continue : R.string.action_start);
            int resume = pr.resumeIndex(sc);
            card.setOnClickListener(x -> ((MainActivity) requireActivity())
                    .open(SignLessonFragment.newInstance(sc.id, resume)));
            list.addView(card);
        }

        TextView tvNext = v.findViewById(R.id.tv_next);
        tvNext.setVisibility(next != null && anyStarted ? View.VISIBLE : View.GONE);
        if (next != null) tvNext.setText(getString(R.string.learn_next, next.title(requireContext())));
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
