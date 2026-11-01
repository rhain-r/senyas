package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** End of a scenario in the Practice Room: the account's best results for its signs. */
public class PracticeSummaryFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_practice_summary, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_practice_summary), requireActivity());
        Repo.save(); // the round's best scores go to the account now, not only when the app pauses
        Scenario sc = Repo.SCENARIOS.get(Repo.practiceScenario);
        int total = sc.signs.length, practiced = 0, sum = 0, worst = -1, worstScore = 101;
        for (int i = 0; i < total; i++) {
            int s = Repo.practiceBest[i];
            if (s < 0) continue;
            practiced++;
            sum += s;
            if (s < 85 && s < worstScore) { worstScore = s; worst = i; }
        }
        int avg = practiced == 0 ? 0 : Math.round(sum / (float) practiced);

        ((TextView) v.findViewById(R.id.tv_score)).setText(practiced + " / " + total);
        ((TextView) v.findViewById(R.id.tv_avg)).setText(getString(R.string.avg_score, avg));

        TextView w1 = v.findViewById(R.id.tv_work1);
        TextView w2 = v.findViewById(R.id.tv_work2);
        if (worst >= 0) {
            w1.setText(getString(R.string.work_position, sc.signs[worst][0]));
            w2.setText(R.string.work_guide);
        } else if (practiced == 0) {
            w1.setText(R.string.work_none_scored);
            w2.setVisibility(View.GONE);
        } else {
            w1.setText(R.string.work_none);
            w2.setVisibility(View.GONE);
        }

        v.findViewById(R.id.btn_again).setOnClickListener(x -> {
            Repo.restartPractice();
            ((MainActivity) requireActivity()).selectTab(R.id.nav_practice);
        });
        v.findViewById(R.id.btn_learn).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_learn));
    }
}
