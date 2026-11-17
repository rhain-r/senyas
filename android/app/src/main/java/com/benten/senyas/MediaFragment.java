package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Step 2 of New lesson (publish), or media/availability editing (id = lesson id). */
public class MediaFragment extends Fragment {

    private boolean editing, hasCover, hasPreview;
    private Lesson lesson;
    private TextView tvCover, tvPreview;
    private final ArrayList<String> selectedDays = new ArrayList<>();
    private ChipGroup selectedDateChips;
    private TextInputEditText dateField;

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
        int id = requireArguments().getInt("id", -1);
        boolean allowed = id < 0 ? (Repo.accountRole() == Repo.ROLE_TUTOR && (Repo.draft == null || Repo.ownsLesson(Repo.draft)))
                : Repo.ownedLesson(id) != null;
        return inflater.inflate(allowed ? R.layout.fragment_media : R.layout.fragment_session_blocked, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        int id = requireArguments().getInt("id", -1);
        editing = id != -1;
        if (!editing && Repo.draft == null && Repo.accountRole() == Repo.ROLE_TUTOR) Repo.startDraft();
        lesson = editing ? Repo.ownedLesson(id) : Repo.draft;
        if (!Repo.ownsLesson(lesson)) {
            ((TextView) v.findViewById(R.id.blocked_title)).setText(R.string.data_unavailable_title);
            ((TextView) v.findViewById(R.id.session_blocked_message)).setText(R.string.listing_details_unavailable);
            v.findViewById(R.id.btn_back).setOnClickListener(x -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            return;
        }
        hasCover = b == null ? lesson.hasCover : b.getBoolean("has_cover", lesson.hasCover);
        hasPreview = b == null ? lesson.hasPreview : b.getBoolean("has_preview", lesson.hasPreview);

        Ui.bindTopBar(v, getString(R.string.title_media), requireActivity());
        tvCover = v.findViewById(R.id.tv_cover_state);
        tvPreview = v.findViewById(R.id.tv_preview_state);
        TextInputLayout tilCity = v.findViewById(R.id.til_city);
        TextInputLayout tilSlots = v.findViewById(R.id.til_slots);
        TextInputLayout tilDay = v.findViewById(R.id.til_day);
        TextInputEditText etCity = v.findViewById(R.id.et_city);
        TextInputEditText etSlots = v.findViewById(R.id.et_slots);
        TextInputEditText actvDay = v.findViewById(R.id.actv_day);
        dateField = actvDay;
        selectedDateChips = v.findViewById(R.id.cg_selected_days);
        selectedDays.clear();
        ArrayList<String> restoredDays = b == null ? null : b.getStringArrayList("selected_days");
        selectedDays.addAll(restoredDays == null ? LessonAvailability.dateLabels(lesson) : restoredDays);
        MaterialButton publish = v.findViewById(R.id.btn_publish);

        etCity.setText(lesson.city);
        etSlots.setText(lesson.slots);
        showSelectedDays();
        actvDay.setKeyListener(null);
        etSlots.setKeyListener(null);
        actvDay.setOnClickListener(x -> chooseDay(actvDay, tilDay));
        tilDay.setEndIconOnClickListener(x -> chooseDay(actvDay, tilDay));
        etSlots.setOnClickListener(x -> chooseSlots(etSlots, tilSlots));
        tilSlots.setEndIconOnClickListener(x -> chooseSlots(etSlots, tilSlots));
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
            if (!Repo.ownsLesson(lesson)) return;
            tilCity.setError(null);
            tilSlots.setError(null);
            tilDay.setError(null);
            String city = etCity.getText() == null ? "" : etCity.getText().toString().trim();
            String slots = etSlots.getText() == null ? "" : etSlots.getText().toString().trim();
            boolean ok = true;
            if (city.isEmpty()) { tilCity.setError(getString(R.string.err_city)); ok = false; }
            if (city.length() > 200) { tilCity.setError(getString(R.string.listing_city_length)); ok = false; }
            LessonAvailability.Error availability = LessonAvailability.validate(selectedDays, slots, lesson.duration, System.currentTimeMillis());
            if (availability == LessonAvailability.Error.DATE) { tilDay.setError(getString(R.string.availability_date_error)); ok = false; }
            if (availability == LessonAvailability.Error.TIME) { tilSlots.setError(getString(R.string.availability_time_error)); ok = false; }
            if (availability == LessonAvailability.Error.PAST) { tilSlots.setError(getString(R.string.availability_past_error)); ok = false; }
            if (!ok) return;

            String oldCity = lesson.city, oldSlots = lesson.slots, oldDay = lesson.day;
            List<String> oldDays = new ArrayList<>(lesson.days);
            boolean oldCover = lesson.hasCover, oldPreview = lesson.hasPreview;
            int oldPreviewSeconds = lesson.previewSeconds;
            lesson.city = city;
            lesson.slots = LessonAvailability.formatSlots(LessonAvailability.parseSlots(slots));
            LessonAvailability.setDays(lesson, selectedDays);
            lesson.hasCover = hasCover;
            lesson.hasPreview = hasPreview;
            if (CloudRuntime.enabled()) {
                lesson.previewSeconds = hasPreview ? Math.max(lesson.previewSeconds, 30) : 0;
                CloudUi.run(this, publish, CloudData.saveLesson(lesson, !editing), confirmed -> {
                    Toast.makeText(requireContext(), editing ? R.string.toast_changes_saved : R.string.toast_published,
                            Toast.LENGTH_SHORT).show();
                    if (editing) requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    else ((MainActivity) requireActivity()).selectTab(R.id.nav_t_lessons);
                });
                return;
            }
            if (editing) {
                lesson.previewSeconds = hasPreview ? Math.max(lesson.previewSeconds, 30) : 0;
                if (!Repo.persist()) {
                    lesson.city = oldCity; lesson.slots = oldSlots; lesson.day = oldDay;
                    lesson.days.clear(); lesson.days.addAll(oldDays);
                    lesson.hasCover = oldCover; lesson.hasPreview = oldPreview; lesson.previewSeconds = oldPreviewSeconds;
                    Toast.makeText(requireContext(), R.string.local_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(requireContext(), R.string.toast_changes_saved, Toast.LENGTH_SHORT).show();
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            } else {
                try { Repo.publishDraft(); }
                catch (IllegalStateException | SecurityException invalid) {
                    Toast.makeText(requireContext(), invalid instanceof Repo.StorageException
                            ? R.string.local_save_failed : R.string.listing_details_required, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(requireContext(), R.string.toast_published, Toast.LENGTH_SHORT).show();
                ((MainActivity) requireActivity()).selectTab(R.id.nav_t_lessons);
            }
        });
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("has_cover", hasCover);
        state.putBoolean("has_preview", hasPreview);
        state.putStringArrayList("selected_days", new ArrayList<>(selectedDays));
    }

    private void chooseDay(TextInputEditText field, TextInputLayout layout) {
        LocalDate today = LocalDate.now(SessionTime.ZONE);
        LocalDate selected = LessonAvailability.parseDay(field.getText() == null ? "" : field.getText().toString());
        if (selected == null || selected.isBefore(today)) selected = today;
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (view, year, month, day) -> {
            String label = LessonAvailability.formatDay(LocalDate.of(year, month + 1, day));
            if (!selectedDays.contains(label)) {
                if (selectedDays.size() >= LessonAvailability.MAX_DAYS) {
                    layout.setError(getString(R.string.availability_dates_limit));
                    return;
                }
                selectedDays.add(label);
                List<LocalDate> dates = LessonAvailability.parseDays(selectedDays);
                selectedDays.clear();
                for (LocalDate date : dates) selectedDays.add(LessonAvailability.formatDay(date));
            }
            showSelectedDays();
            field.setText(label);
            layout.setError(null);
        }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
        picker.getDatePicker().setMinDate(today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        picker.show();
    }

    private void showSelectedDays() {
        selectedDateChips.removeAllViews();
        dateField.setText(selectedDays.isEmpty() ? "" : selectedDays.get(selectedDays.size() - 1));
        for (String label : new ArrayList<>(selectedDays)) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_availability_date, selectedDateChips, false);
            chip.setId(View.generateViewId());
            chip.setText(label);
            chip.setCheckable(false);
            chip.setSaveEnabled(false);
            chip.setCloseIconResource(R.drawable.ic_close);
            chip.setCloseIconVisible(true);
            chip.setCloseIconContentDescription(getString(R.string.availability_remove_date, label));
            chip.setOnCloseIconClickListener(view -> {
                selectedDays.remove(label);
                showSelectedDays();
            });
            selectedDateChips.addView(chip);
        }
    }

    @Override public void onDestroyView() {
        selectedDateChips = null;
        dateField = null;
        super.onDestroyView();
    }

    private void chooseSlots(TextInputEditText field, TextInputLayout layout) {
        List<Integer> times = new ArrayList<>(LessonAvailability.parseSlots(field.getText() == null ? "" : field.getText().toString()));
        if (times.isEmpty()) { addTime(field, layout, times); return; }
        String[] labels = new String[times.size()];
        for (int index = 0; index < times.size(); index++) labels[index] = getString(R.string.availability_remove_time, Ui.clock(times.get(index), true));
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.time_slots_pht)
                .setItems(labels, (dialog, which) -> {
                    times.remove(which);
                    field.setText(LessonAvailability.formatSlots(times));
                    layout.setError(null);
                })
                .setPositiveButton(R.string.availability_add_time, (dialog, which) -> addTime(field, layout, times))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void addTime(TextInputEditText field, TextInputLayout layout, List<Integer> times) {
        int initial = times.isEmpty() ? 15 * 60 : (times.get(times.size() - 1) + 60) % 1440;
        new TimePickerDialog(requireContext(), (view, hour, minute) -> {
            int value = hour * 60 + minute;
            if (!times.contains(value)) times.add(value);
            field.setText(LessonAvailability.formatSlots(times));
            layout.setError(null);
        }, initial / 60, initial % 60, false).show();
    }

    private void updateTiles() {
        tvCover.setText(hasCover ? R.string.state_added : R.string.state_not_added);
        tvPreview.setText(hasPreview ? R.string.state_added : R.string.state_not_added);
    }
}
