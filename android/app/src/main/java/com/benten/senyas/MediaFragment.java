package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Step 2 of New lesson (publish), or media/availability editing (id = lesson id). */
public class MediaFragment extends Fragment {

    private static final String[] DAYS = {"Friday, 23 October 2026", "Saturday, 24 October 2026",
            "Sunday, 25 October 2026", "Monday, 26 October 2026"};

    private boolean editing, hasCover, hasPreview;
    private Lesson lesson;
    private TextView tvCover, tvPreview;

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
        TextInputLayout tilSlots = v.findViewById(R.id.til_slots);
        TextInputEditText etCity = v.findViewById(R.id.et_city);
        TextInputEditText etSlots = v.findViewById(R.id.et_slots);
        MaterialAutoCompleteTextView actvDay = v.findViewById(R.id.actv_day);
        MaterialButton publish = v.findViewById(R.id.btn_publish);

        etCity.setText(lesson.city);
        etSlots.setText(lesson.slots);
        actvDay.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, DAYS));
        actvDay.setText(lesson.day, false);
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
            tilSlots.setError(null);
            String city = etCity.getText() == null ? "" : etCity.getText().toString().trim();
            String slots = etSlots.getText() == null ? "" : etSlots.getText().toString().trim();
            boolean ok = true;
            if (city.isEmpty()) { tilCity.setError(getString(R.string.err_city)); ok = false; }
            if (slots.isEmpty()) { tilSlots.setError(getString(R.string.err_slots)); ok = false; }
            if (!ok) {
                // On a short (landscape) screen the field can be out of view: scroll it in.
                (city.isEmpty() ? etCity : etSlots).requestFocus();
                return;
            }

            lesson.city = city;
            lesson.slots = slots;
            lesson.day = actvDay.getText().toString();
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

    private void backToStepOne() {
        Toast.makeText(requireContext(), R.string.toast_draft_incomplete, Toast.LENGTH_LONG).show();
        getParentFragmentManager().popBackStack();
    }

    private void updateTiles() {
        tvCover.setText(hasCover ? R.string.state_added : R.string.state_not_added);
        tvPreview.setText(hasPreview ? R.string.state_added : R.string.state_not_added);
    }
}
