package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

public class ScheduleFragment extends Fragment {

    private static final int[] DAY_IDS = {R.id.chip_d0, R.id.chip_d1, R.id.chip_d2, R.id.chip_d3};
    private static final String[] DAY_SHORT = {"Mon 19 Oct", "Tue 20 Oct", "Wed 21 Oct", "Thu 22 Oct"};
    private static final String[] DAY_FULL = {"Mon, 19 October 2026", "Tue, 20 October 2026",
            "Wed, 21 October 2026", "Thu, 22 October 2026"};
    private static final int[] TIME_IDS = {R.id.chip_t0, R.id.chip_t1, R.id.chip_t2, R.id.chip_t3};
    private static final int[] TIME_START = {14 * 60, 15 * 60, 16 * 60, 18 * 60};
    private static final int BOOKED_INDEX = 0; // dummy: first slot is already taken

    private Lesson lesson;
    private int dayIndex = 0, timeIndex = 1, duration;
    private TextView tvSummary;

    public static ScheduleFragment newInstance(int lessonId) {
        ScheduleFragment f = new ScheduleFragment();
        Bundle a = new Bundle();
        a.putInt("id", lessonId);
        f.setArguments(a);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_schedule, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_choose_schedule), requireActivity());
        lesson = Repo.byId(requireArguments().getInt("id"));
        duration = lesson.duration;
        tvSummary = v.findViewById(R.id.tv_summary);

        ((TextView) v.findViewById(R.id.tv_lesson_line)).setText(lesson.tutor + " · " + lesson.title);
        ((TextView) v.findViewById(R.id.tv_note)).setText(
                getString(R.string.slot_booked_note, Ui.clock(TIME_START[BOOKED_INDEX], true)));

        for (int i = 0; i < DAY_IDS.length; i++) ((Chip) v.findViewById(DAY_IDS[i])).setText(DAY_SHORT[i]);
        for (int i = 0; i < TIME_IDS.length; i++) {
            Chip c = v.findViewById(TIME_IDS[i]);
            boolean booked = i == BOOKED_INDEX;
            c.setText(booked ? getString(R.string.slot_booked_chip, Ui.clock(TIME_START[i], true)) : Ui.clock(TIME_START[i], true));
            c.setEnabled(!booked);
        }

        ChipGroup cgDay = v.findViewById(R.id.cg_day);
        ChipGroup cgTime = v.findViewById(R.id.cg_time);
        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_duration);

        cgDay.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            dayIndex = Ui.indexOf(DAY_IDS, ids.get(0));
            update();
        });
        cgTime.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) return;
            timeIndex = Ui.indexOf(TIME_IDS, ids.get(0));
            update();
        });
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            duration = id == R.id.btn_dur30 ? 30 : 60;
            update();
        });

        cgDay.check(DAY_IDS[dayIndex]);
        cgTime.check(TIME_IDS[timeIndex]);
        toggle.check(duration == 30 ? R.id.btn_dur30 : R.id.btn_dur60);

        v.findViewById(R.id.btn_review).setOnClickListener(x -> {
            Repo.draftLesson = lesson;
            Repo.draftDay = DAY_FULL[dayIndex];
            Repo.draftStartMin = TIME_START[timeIndex];
            Repo.draftDuration = duration;
            Repo.draftPrice = price();
            ((MainActivity) requireActivity()).open(ReviewFragment.newInstance(lesson.id,
                    Repo.draftDay, Repo.draftStartMin, Repo.draftDuration, Repo.draftPrice));
        });
        update();
    }

    private int price() {
        return lesson.price * duration / lesson.duration;
    }

    private void update() {
        if (tvSummary == null) return;
        tvSummary.setText(DAY_SHORT[dayIndex] + " · " + Ui.timeRange(TIME_START[timeIndex], duration)
                + " · " + Ui.peso(price()));
    }
}
