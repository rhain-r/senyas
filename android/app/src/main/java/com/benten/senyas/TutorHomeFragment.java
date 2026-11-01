package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

/** Tutor home: the signed-in tutor's pending payout, next session and recent sessions. */
public class TutorHomeFragment extends Fragment {

    private LinearLayout stats, recent;
    private FrameLayout nextHolder;
    private TextView tvGreeting;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_tutor_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        stats = v.findViewById(R.id.stats);
        recent = v.findViewById(R.id.recent);
        nextHolder = v.findViewById(R.id.next_holder);
        tvGreeting = v.findViewById(R.id.tv_greeting);

        v.findViewById(R.id.btn_new).setOnClickListener(x -> {
            Repo.startDraft();
            ((MainActivity) requireActivity()).open(LessonFormFragment.newInstance(-1));
        });
        v.findViewById(R.id.btn_lessons).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_t_lessons));
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (stats != null) render();
    }

    private void render() {
        stats.removeAllViews();
        nextHolder.removeAllViews();
        recent.removeAllViews();
        tvGreeting.setText(getString(R.string.greeting_hello, Repo.userName));
        Ui.bindBell(requireView(), (MainActivity) requireActivity());

        List<Repo.TutorSession> mine = Repo.mySessions();
        int upcoming = 0;
        Repo.TutorSession next = null;
        for (Repo.TutorSession s : mine) {
            if (!s.past) { upcoming++; if (next == null) next = s; }
        }
        Ui.addStatRow(stats, Ui.peso(Repo.myPendingPayout()), getString(R.string.stat_pending),
                String.valueOf(upcoming), getString(R.string.stat_upcoming));

        LayoutInflater inf = LayoutInflater.from(requireContext());
        if (next == null) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.no_next_session);
            none.setTextSize(14);
            none.setTextColor(requireContext().getColor(R.color.text_secondary));
            none.setPadding(0, Ui.dp(requireContext(), 8), 0, 0);
            nextHolder.addView(none);
        } else {
            View card = inf.inflate(R.layout.item_booking, nextHolder, false);
            TutorSessionsFragment.bind(this, card, next);
            nextHolder.addView(card);
        }

        int shown = 0;
        for (Repo.TutorSession s : mine) {
            if (!s.past || shown >= 3) continue;
            shown++;
            View row = inf.inflate(R.layout.item_menu_row, recent, false);
            ((TextView) row.findViewById(R.id.tv_title)).setText(s.title);
            ((TextView) row.findViewById(R.id.tv_sub)).setText(
                    getString(R.string.learner_line, s.learner) + " · " + Ui.shortDay(s.dayLabel));
            row.setOnClickListener(x -> ((MainActivity) requireActivity()).selectTab(R.id.nav_t_sessions));
            recent.addView(row);
        }
    }
}
