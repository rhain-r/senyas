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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The learner picks a session from the tutor's own availability: the next open dates in the
 * coming two weeks and the start times the tutor offers. A time shows as booked only when a real
 * session (by any learner) already takes it.
 */
public class ScheduleFragment extends Fragment {
    private static final int HORIZON_DAYS = 14, MAX_DATES = 8;
    /** A time today must start at least this far ahead to be bookable. */
    private static final int LEAD_MIN = 60;

    private Lesson lesson;
    private final List<LocalDate> dates = new ArrayList<>();
    private int dateIndex, startMin = -1, duration;
    private ChipGroup cgTime;
    private TextView tvSummary, tvNote;
    private View btnReview;
    private DateTimeFormatter shortDate, fullDate;

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
        duration = b != null ? b.getInt("duration", lesson.duration) : lesson.duration;
        dateIndex = b != null ? b.getInt("date", 0) : 0;
        startMin = b != null ? b.getInt("start", -1) : -1;
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        shortDate = DateTimeFormatter.ofPattern("EEE d MMM", locale);
        fullDate = DateTimeFormatter.ofPattern("EEE, d MMMM yyyy", locale);

        tvSummary = v.findViewById(R.id.tv_summary);
        tvNote = v.findViewById(R.id.tv_note);
        cgTime = v.findViewById(R.id.cg_time);
        btnReview = v.findViewById(R.id.btn_review);
        ((TextView) v.findViewById(R.id.tv_lesson_line)).setText(lesson.tutor + " · " + lesson.title);

        dates.addAll(Availability.nextDates(lesson.days, LocalDate.now(), HORIZON_DAYS, MAX_DATES));
        if (dateIndex >= dates.size()) dateIndex = 0;
        ChipGroup cgDay = v.findViewById(R.id.cg_day);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int i = 0; i < dates.size(); i++) {
            Chip c = (Chip) inf.inflate(R.layout.item_choice_chip, cgDay, false);
            c.setId(View.generateViewId());
            c.setText(dates.get(i).format(shortDate));
            final int index = i;
            c.setOnClickListener(x -> {
                dateIndex = index;
                startMin = -1;
                showTimes();
            });
            cgDay.addView(c);
            if (i == dateIndex) c.setChecked(true);
        }

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_duration);
        toggle.check(duration == 30 ? R.id.btn_dur30 : R.id.btn_dur60);
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            duration = id == R.id.btn_dur30 ? 30 : 60;
            showTimes(); // a longer session can clash with a booking the shorter one missed
        });

        btnReview.setOnClickListener(x -> {
            if (dates.isEmpty() || startMin < 0) return;
            LocalDate d = dates.get(dateIndex);
            Repo.draftLesson = lesson;
            Repo.draftDay = d.format(fullDate);
            Repo.draftDate = d.toString();
            Repo.draftStartMin = startMin;
            Repo.draftDuration = duration;
            Repo.draftPrice = price();
            ((MainActivity) requireActivity()).open(ReviewFragment.newInstance(lesson.id,
                    Repo.draftDay, Repo.draftDate, Repo.draftStartMin, Repo.draftDuration, Repo.draftPrice));
        });
        showTimes();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("duration", duration);
        out.putInt("date", dateIndex);
        out.putInt("start", startMin);
    }

    /** The tutor's times on the chosen date: past or booked ones are shown but cannot be picked. */
    private void showTimes() {
        if (cgTime == null) return;
        cgTime.removeAllViews();
        if (dates.isEmpty() || lesson.times.isEmpty()) {
            tvNote.setText(R.string.schedule_no_times);
            tvNote.setVisibility(View.VISIBLE);
            update();
            return;
        }
        LocalDate d = dates.get(dateIndex);
        boolean today = d.equals(LocalDate.now());
        int now = LocalTime.now().toSecondOfDay() / 60;
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int firstFree = -1, booked = 0;
        boolean keep = false;
        for (int t : lesson.times) {
            boolean past = today && t < now + LEAD_MIN;
            boolean taken = !past && Repo.isBooked(lesson, d.toString(), t, duration);
            Chip c = (Chip) inf.inflate(R.layout.item_choice_chip, cgTime, false);
            c.setId(View.generateViewId());
            c.setText(taken ? getString(R.string.slot_booked_chip, Ui.clock(t, true)) : Ui.clock(t, true));
            c.setEnabled(!past && !taken);
            if (taken) booked++;
            if (past || taken) {
                if (past) c.setVisibility(View.GONE);
            } else {
                if (firstFree < 0) firstFree = t;
                if (t == startMin) keep = true;
                c.setOnClickListener(x -> {
                    startMin = t;
                    update();
                });
            }
            c.setTag(t);
            cgTime.addView(c);
        }
        if (!keep) startMin = firstFree;
        for (int i = 0; i < cgTime.getChildCount(); i++) {
            Chip c = (Chip) cgTime.getChildAt(i);
            if ((int) c.getTag() == startMin) c.setChecked(true);
        }
        tvNote.setText(startMin < 0 ? getString(R.string.schedule_day_full)
                : booked > 0 ? getString(R.string.schedule_booked_note) : "");
        tvNote.setVisibility(tvNote.getText().length() == 0 ? View.GONE : View.VISIBLE);
        update();
    }

    private int price() {
        return lesson.price * duration / lesson.duration;
    }

    private void update() {
        if (tvSummary == null) return;
        boolean ready = !dates.isEmpty() && startMin >= 0;
        btnReview.setEnabled(ready);
        tvSummary.setText(ready ? dates.get(dateIndex).format(shortDate) + " · "
                + Ui.timeRange(startMin, duration) + " · " + Ui.peso(price()) : getString(R.string.schedule_pick));
    }
}
