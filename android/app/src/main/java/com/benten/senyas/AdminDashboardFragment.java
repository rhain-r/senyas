package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

public class AdminDashboardFragment extends Fragment {

    private LinearLayout stats, bars, attention;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_admin_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        stats = v.findViewById(R.id.stats);
        bars = v.findViewById(R.id.bars);
        attention = v.findViewById(R.id.attention);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (stats != null) render();
    }

    private void render() {
        stats.removeAllViews();
        bars.removeAllViews();
        attention.removeAllViews();
        Ui.bindBell(requireView(), (MainActivity) requireActivity());

        int tutors = 0, needReview = 0, pendingCount = 0;
        for (Repo.User u : Repo.USERS) if (u.role == Repo.ROLE_TUTOR && u.active) tutors++;
        for (Lesson l : Repo.LESSONS) if (!l.reviewed) needReview++;
        for (Repo.Payout p : Repo.PAYOUTS) if (!p.released) pendingCount++;

        Ui.addStatRow(stats, String.valueOf(Repo.USERS.size()), getString(R.string.stat_users),
                String.valueOf(tutors), getString(R.string.stat_tutors));
        Ui.addStatRow(stats, String.valueOf(Repo.LESSONS.size()), getString(R.string.stat_lessons),
                String.valueOf(Repo.TUTOR_SESSIONS.size()), getString(R.string.stat_sessions));
        Ui.addStatRow(stats, String.valueOf(needReview), getString(R.string.stat_needs_review),
                Ui.peso(Repo.pendingPayout(null)), getString(R.string.stat_payouts_pending));

        int[] counts = new int[Repo.CATEGORIES.length];
        int max = 1;
        for (int i = 0; i < counts.length; i++) {
            for (Repo.TutorSession s : Repo.TUTOR_SESSIONS) if (Repo.CATEGORIES[i].equals(s.category)) counts[i]++;
            max = Math.max(max, counts[i]);
        }
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int i = 0; i < counts.length; i++) {
            View row = inf.inflate(R.layout.item_bar, bars, false);
            ((TextView) row.findViewById(R.id.tv_label)).setText(Ui.categoryShort(requireContext(), Repo.CATEGORIES[i]));
            ((TextView) row.findViewById(R.id.tv_count)).setText(String.valueOf(counts[i]));
            LinearProgressIndicator p = row.findViewById(R.id.progress);
            p.setProgress(counts[i] * 100 / max);
            bars.addView(row);
        }

        if (needReview > 0) addAction(getString(R.string.attention_review, needReview), R.id.nav_a_listings);
        if (pendingCount > 0) addAction(getString(R.string.attention_payouts, pendingCount), R.id.nav_a_payouts);
        if (needReview == 0 && pendingCount == 0) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.attention_none);
            none.setTextSize(14);
            none.setTextColor(requireContext().getColor(R.color.text_secondary));
            attention.addView(none);
        }
    }

    private void addAction(String text, int tabId) {
        MaterialButton b = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setMinHeight(Ui.dp(requireContext(), 52));
        b.setCornerRadius(Ui.dp(requireContext(), 16));
        b.setOnClickListener(x -> ((MainActivity) requireActivity()).selectTab(tabId));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(requireContext(), 8);
        attention.addView(b, lp);
    }
}
