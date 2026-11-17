package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.time.LocalDate;
import java.util.List;

public class ScheduleFragment extends Fragment {
    private LocalDate day;
    private List<LocalDate> offeredDays;
    private List<Integer> starts;
    private Lesson lesson;
    private int timeIndex = -1, duration;
    private boolean updating;
    private TextView summary;

    public static ScheduleFragment newInstance(int lessonId) {
        ScheduleFragment fragment = new ScheduleFragment();
        Bundle args = new Bundle(); args.putInt("id", lessonId); fragment.setArguments(args); return fragment;
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_schedule, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        Ui.bindTopBar(view, getString(R.string.title_choose_schedule), requireActivity());
        lesson = Repo.byId(requireArguments().getInt("id"));
        if (lesson == null) {
            view.findViewById(R.id.btn_review).setEnabled(false);
            Toast.makeText(requireContext(), R.string.listing_details_unavailable, Toast.LENGTH_LONG).show();
            view.post(() -> {
                if (isAdded() && getView() == view) requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
            return;
        }
        duration = state == null ? lesson.duration : state.getInt("duration", lesson.duration);
        summary = view.findViewById(R.id.tv_summary);
        ((TextView) view.findViewById(R.id.tv_lesson_line)).setText(lesson.tutor + " · " + lesson.title);
        ((TextView) view.findViewById(R.id.tv_note)).setText(R.string.session_schedule_note);
        offeredDays = LessonAvailability.days(lesson);
        LocalDate restoredDay = state == null ? null : LessonAvailability.parseDay(state.getString("selected_day"));
        day = offeredDays.contains(restoredDay) ? restoredDay : (offeredDays.isEmpty() ? null : offeredDays.get(0));
        starts = LessonAvailability.parseSlots(lesson.slots);
        ChipGroup dayGroup = view.findViewById(R.id.cg_day), timeGroup = view.findViewById(R.id.cg_time);
        dayGroup.setSaveEnabled(false);
        timeGroup.setSaveEnabled(false);
        dayGroup.removeAllViews();
        timeGroup.removeAllViews();
        for (LocalDate date : offeredDays) {
            Chip dayChip = (Chip) getLayoutInflater().inflate(R.layout.item_availability_date, dayGroup, false);
            dayChip.setId(View.generateViewId());
            dayChip.setText(LessonAvailability.formatDay(date));
            dayChip.setTag(date);
            dayChip.setCheckable(true);
            dayChip.setSaveEnabled(false);
            dayGroup.addView(dayChip);
            if (date.equals(day)) dayGroup.check(dayChip.getId());
        }
        for (int index = 0; index < starts.size(); index++) {
            Chip chip = new Chip(requireContext());
            chip.setId(View.generateViewId());
            chip.setText(Ui.clock(starts.get(index), true));
            chip.setCheckable(true);
            chip.setSaveEnabled(false);
            timeGroup.addView(chip);
        }
        timeIndex = state == null ? -1 : starts.indexOf(state.getInt("start_minute", -1));
        dayGroup.setOnCheckedStateChangeListener((group, checked) -> {
            if (!checked.isEmpty()) {
                day = (LocalDate) group.findViewById(checked.get(0)).getTag();
                update();
            }
        });
        timeGroup.setOnCheckedStateChangeListener((group, checked) -> {
            if (!checked.isEmpty()) { timeIndex = group.indexOfChild(group.findViewById(checked.get(0))); update(); }
        });
        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggle_duration);
        toggle.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) { duration = id == R.id.btn_dur30 ? 30 : 60; update(); }
        });
        toggle.check(duration == 30 ? R.id.btn_dur30 : R.id.btn_dur60);
        view.findViewById(R.id.btn_review).setOnClickListener(button -> {
            if (timeIndex < 0 || !available(timeIndex)) {
                update(); Toast.makeText(requireContext(), R.string.session_slot_unavailable, Toast.LENGTH_LONG).show(); return;
            }
            Repo.draftLesson = lesson;
            Repo.draftDay = LessonAvailability.formatDay(day);
            Repo.draftStartMin = starts.get(timeIndex);
            Repo.draftStartAtMillis = SessionTime.startAt(day, starts.get(timeIndex));
            Repo.draftDuration = duration;
            Repo.draftPrice = price();
            ((MainActivity) requireActivity()).open(new ReviewFragment());
        });
        update();
    }
    @Override public void onResume() { super.onResume(); if (summary != null) update(); }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("duration", duration);
        if (day != null) state.putString("selected_day", LessonAvailability.formatDay(day));
        if (starts != null && timeIndex >= 0 && timeIndex < starts.size()) state.putInt("start_minute", starts.get(timeIndex));
    }
    @Override public void onDestroyView() { summary = null; super.onDestroyView(); }
    private int price() {
        if (lesson.duration <= 0 || lesson.price <= 0) return -1;
        long amount = (long) lesson.price * duration / lesson.duration;
        return amount < 0 || amount > Integer.MAX_VALUE ? -1 : (int) amount;
    }
    private boolean available(int index) {
        if (!Repo.isBookable(lesson) || price() < 0 || day == null || index < 0 || index >= starts.size()) return false;
        long start = SessionTime.startAt(day, starts.get(index));
        return start > System.currentTimeMillis() && LessonAvailability.offers(lesson, start, duration)
                && Repo.slotAvailable(lesson.tutorId, start, duration);
    }
    private void update() {
        if (summary == null || updating) return;
        updating = true;
        int firstAvailable = -1;
        ChipGroup group = requireView().findViewById(R.id.cg_time);
        for (int index = 0; index < starts.size(); index++) {
            Chip chip = (Chip) group.getChildAt(index);
            boolean free = available(index);
            chip.setEnabled(free);
            if (free && firstAvailable < 0) firstAvailable = index;
        }
        if (timeIndex < 0 || !available(timeIndex)) timeIndex = firstAvailable;
        if (timeIndex >= 0) group.check(group.getChildAt(timeIndex).getId()); else group.clearCheck();
        requireView().findViewById(R.id.btn_review).setEnabled(timeIndex >= 0);
        summary.setText(timeIndex < 0 ? getString(R.string.session_no_slots)
                : LessonAvailability.formatDay(day) + " · " + Ui.timeRange(starts.get(timeIndex), duration) + " · " + Ui.peso(price()));
        updating = false;
    }
}
