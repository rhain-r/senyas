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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Step 2 of New lesson (publish), or media/availability editing (id = lesson id). The tutor sets
 * the weekdays they teach and the session start times; learners book only from these.
 */
public class MediaFragment extends Fragment {

    private boolean editing, hasCover, hasPreview;
    private Lesson lesson;
    private TextView tvCover, tvPreview, tvDaysError, tvTimesError;
    private ChipGroup cgTimes;
    private int days;
    private final List<Integer> times = new ArrayList<>();

    public static MediaFragment newInstance(int lessonId) {
        MediaFragment f = new MediaFragment();
        Bundle a = new Bundle();
        a.putInt("id", lessonId);
        f.setArguments(a);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_media, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        int id = requireArguments().getInt("id", -1);
        editing = id != -1;
        if (!editing && Repo.draft == null) {
            // Step 1 was lost when the app was closed: go back to it instead of starting blank.
            v.post(() -> {
                if (isAdded()) backToStepOne();
            });
            return;
        }
        lesson = editing ? Repo.byId(id) : Repo.draft;
        hasCover = lesson.hasCover;
        hasPreview = lesson.hasPreview;

        Ui.bindTopBar(v, getString(R.string.title_media), requireActivity());
        tvCover = v.findViewById(R.id.tv_cover_state);
        tvPreview = v.findViewById(R.id.tv_preview_state);
        TextInputLayout tilCity = v.findViewById(R.id.til_city);
        TextInputEditText etCity = v.findViewById(R.id.et_city);
        MaterialButton publish = v.findViewById(R.id.btn_publish);
        tvDaysError = v.findViewById(R.id.tv_days_error);
        tvTimesError = v.findViewById(R.id.tv_times_error);
        cgTimes = v.findViewById(R.id.cg_times);

        if (b == null) etCity.setText(lesson.city);
        etCity.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int bf, int c) { tilCity.setError(null); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        days = b != null ? b.getInt("days", lesson.days) : lesson.days;
        times.clear();
        if (b != null && b.getIntegerArrayList("times") != null) times.addAll(b.getIntegerArrayList("times"));
        else times.addAll(lesson.times);
        bindDays(v.findViewById(R.id.cg_days));
        showTimes();
        v.findViewById(R.id.btn_add_time).setOnClickListener(x -> pickTime());
        updateTiles();

        v.findViewById(R.id.btn_add_media).setOnClickListener(x -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_media_title)
                .setItems(R.array.media_options,
                        (d, which) -> {
                            if (which < 2) hasCover = true; else hasPreview = true;
                            updateTiles();
                            // TODO (Firebase batch): camera/gallery/video picker + upload to Firebase Storage
                            Toast.makeText(requireContext(), R.string.toast_picker, Toast.LENGTH_SHORT).show();
                        })
                .show());
        v.findViewById(R.id.btn_gps).setOnClickListener(x ->
                Toast.makeText(requireContext(), R.string.toast_gps, Toast.LENGTH_SHORT).show());

        publish.setText(editing ? R.string.publish_save : R.string.publish_lesson);
        publish.setOnClickListener(x -> {
            tilCity.setError(null);
            String city = etCity.getText() == null ? "" : etCity.getText().toString().trim().replaceAll("\\s+", " ");
            boolean cityOk = InputRules.isCity(city);
            if (!cityOk) tilCity.setError(getString(city.isEmpty() ? R.string.err_city : R.string.err_city_format));
            tvDaysError.setVisibility(days == 0 ? View.VISIBLE : View.GONE);
            tvTimesError.setVisibility(times.isEmpty() ? View.VISIBLE : View.GONE);
            if (!cityOk || days == 0 || times.isEmpty()) {
                // On a short (landscape) screen the problem can be out of view: scroll it in.
                View problem = !cityOk ? etCity : days == 0 ? tvDaysError : tvTimesError;
                problem.getParent().requestChildFocus(problem, problem);
                if (!cityOk) etCity.requestFocus();
                return;
            }

            lesson.city = city;
            lesson.days = days;
            lesson.times = Availability.normalize(times);
            lesson.hasCover = hasCover;
            lesson.hasPreview = hasPreview;
            if (editing) {
                lesson.previewSeconds = hasPreview ? Math.max(lesson.previewSeconds, 30) : 0;
                Toast.makeText(requireContext(), R.string.toast_changes_saved, Toast.LENGTH_SHORT).show();
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            } else if (Repo.publishDraft() == null) { // TODO (Firebase batch): write the listing to Firestore
                backToStepOne(); // no title or rate: never publish a blank listing
            } else {
                Toast.makeText(requireContext(), R.string.toast_published, Toast.LENGTH_SHORT).show();
                ((MainActivity) requireActivity()).selectTab(R.id.nav_t_lessons);
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("days", days);
        out.putIntegerArrayList("times", new ArrayList<>(times));
    }

    /** Monday to Sunday chips; each toggles that weekday. */
    private void bindDays(ChipGroup group) {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (DayOfWeek d : DayOfWeek.values()) {
            Chip c = (Chip) inf.inflate(R.layout.item_choice_chip, group, false);
            c.setId(View.generateViewId());
            c.setText(d.getDisplayName(TextStyle.SHORT, locale));
            c.setChecked((days & Availability.bit(d)) != 0);
            c.setOnCheckedChangeListener((chip, on) -> {
                days = on ? days | Availability.bit(d) : days & ~Availability.bit(d);
                if (days != 0) tvDaysError.setVisibility(View.GONE);
            });
            group.addView(c);
        }
    }

    /** The chosen start times as removable chips, earliest first. */
    private void showTimes() {
        cgTimes.removeAllViews();
        List<Integer> sorted = Availability.normalize(times);
        times.clear();
        times.addAll(sorted);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (int t : times) {
            Chip c = (Chip) inf.inflate(R.layout.item_choice_chip, cgTimes, false);
            c.setId(View.generateViewId());
            c.setText(Ui.clock(t, true));
            c.setCheckable(false);
            c.setCloseIconVisible(true);
            c.setCloseIconContentDescription(getString(R.string.remove_time, Ui.clock(t, true)));
            c.setOnCloseIconClickListener(x -> {
                times.remove(Integer.valueOf(t));
                showTimes();
            });
            cgTimes.addView(c);
        }
    }

    /** A clock picker, so a time slot can only ever be a real time. */
    private void pickTime() {
        if (times.size() >= Availability.MAX_TIMES) {
            Toast.makeText(requireContext(), getString(R.string.err_time_max, Availability.MAX_TIMES),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int start = times.isEmpty() ? 15 * 60 : Math.min(times.get(times.size() - 1) + 60, Availability.LATEST);
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(start / 60)
                .setMinute(start % 60)
                .setTitleText(R.string.time_picker_title)
                .build();
        picker.addOnPositiveButtonClickListener(x -> {
            int t = picker.getHour() * 60 + picker.getMinute();
            if (t < Availability.EARLIEST || t > Availability.LATEST) {
                Toast.makeText(requireContext(), R.string.err_time_range, Toast.LENGTH_LONG).show();
            } else if (times.contains(t)) {
                Toast.makeText(requireContext(), getString(R.string.err_time_duplicate, Ui.clock(t, true)),
                        Toast.LENGTH_SHORT).show();
            } else {
                times.add(t);
                tvTimesError.setVisibility(View.GONE);
                showTimes();
            }
        });
        picker.show(getParentFragmentManager(), "time");
    }

    private void backToStepOne() {
        Toast.makeText(requireContext(), R.string.toast_draft_incomplete, Toast.LENGTH_LONG).show();
        getParentFragmentManager().popBackStack();
    }

    private void updateTiles() {
        tvCover.setText(hasCover ? R.string.state_added : R.string.state_not_added);
        tvPreview.setText(hasPreview ? R.string.state_added : R.string.state_not_added);
    }
}
