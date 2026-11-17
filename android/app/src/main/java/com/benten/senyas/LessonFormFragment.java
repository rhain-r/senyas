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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Step 1 of New lesson, or the Edit lesson form (id = -1 means new). */
public class LessonFormFragment extends Fragment {

    private boolean editing;
    private Lesson lesson;
    private int duration, rate;
    private TextInputLayout tilTitle, tilRate, tilDesc;
    private TextInputEditText etTitle, etDesc, etRate;
    private MaterialAutoCompleteTextView actvCat;
    private View banner;

    public static LessonFormFragment newInstance(int lessonId) {
        LessonFormFragment f = new LessonFormFragment();
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
        return inflater.inflate(allowed ? R.layout.fragment_lesson_form : R.layout.fragment_session_blocked, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        int id = requireArguments().getInt("id", -1);
        editing = id != -1;
        if (!editing && Repo.draft == null) Repo.startDraft();
        lesson = editing ? Repo.ownedLesson(id) : Repo.draft;
        if (!Repo.ownsLesson(lesson)) {
            ((TextView) v.findViewById(R.id.blocked_title)).setText(R.string.data_unavailable_title);
            ((TextView) v.findViewById(R.id.session_blocked_message)).setText(R.string.listing_details_unavailable);
            v.findViewById(R.id.btn_back).setOnClickListener(x -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
            return;
        }
        duration = lesson.duration;

        Ui.bindTopBar(v, getString(editing ? R.string.title_edit_lesson : R.string.title_new_lesson), requireActivity());
        tilTitle = v.findViewById(R.id.til_title);
        tilRate = v.findViewById(R.id.til_rate);
        tilDesc = v.findViewById(R.id.til_desc);
        etTitle = v.findViewById(R.id.et_title);
        etDesc = v.findViewById(R.id.et_desc);
        etRate = v.findViewById(R.id.et_rate);
        actvCat = v.findViewById(R.id.actv_cat);
        banner = v.findViewById(R.id.tv_banner);
        MaterialButton primary = v.findViewById(R.id.btn_primary);
        MaterialButton media = v.findViewById(R.id.btn_media);
        TextView delete = v.findViewById(R.id.btn_delete);
        TextView hint = v.findViewById(R.id.tv_hint);

        etTitle.setText(lesson.title);
        etDesc.setText(lesson.description);
        etRate.setText(lesson.price > 0 ? String.valueOf(lesson.price) : "");
        actvCat.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, Repo.CATEGORIES));
        actvCat.setText(lesson.category, false);

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_duration);
        toggle.addOnButtonCheckedListener((g, bid, checked) -> {
            if (checked) duration = bid == R.id.btn_dur30 ? 30 : 60;
        });
        toggle.check(duration == 30 ? R.id.btn_dur30 : R.id.btn_dur60);

        if (editing) {
            primary.setText(R.string.save_changes);
            hint.setText(R.string.hint_edit);
            media.setVisibility(View.VISIBLE);
            delete.setVisibility(View.VISIBLE);
            primary.setOnClickListener(x -> {
                if (!validate()) return;
                String oldTitle = lesson.title, oldDescription = lesson.description, oldCategory = lesson.category;
                int oldDuration = lesson.duration, oldPrice = lesson.price;
                apply(lesson);
                if (!Repo.persist()) {
                    lesson.title = oldTitle; lesson.description = oldDescription; lesson.category = oldCategory;
                    lesson.duration = oldDuration; lesson.price = oldPrice;
                    Toast.makeText(requireContext(), R.string.local_save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(requireContext(), R.string.toast_changes_saved, Toast.LENGTH_SHORT).show();
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
            media.setOnClickListener(x ->
                    ((MainActivity) requireActivity()).open(MediaFragment.newInstance(lesson.id)));
            delete.setOnClickListener(x -> confirmDelete());
        } else {
            primary.setText(R.string.next_media);
            hint.setText(R.string.hint_new);
            primary.setOnClickListener(x -> {
                if (!validate()) return;
                apply(Repo.draft);
                ((MainActivity) requireActivity()).open(MediaFragment.newInstance(-1));
            });
        }
    }

    private String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private boolean validate() {
        if (!Repo.ownsLesson(lesson)) return false;
        tilTitle.setError(null);
        tilRate.setError(null);
        tilDesc.setError(null);
        boolean ok = true;
        if (text(etTitle).isEmpty()) {
            tilTitle.setError(getString(R.string.err_title));
            ok = false;
        }
        if (text(etTitle).length() > 200) {
            tilTitle.setError(getString(R.string.listing_title_length)); ok = false;
        }
        if (text(etDesc).length() > 10000) {
            tilDesc.setError(getString(R.string.listing_description_length)); ok = false;
        }
        String r = text(etRate);
        rate = 0;
        if (r.isEmpty()) {
            tilRate.setError(getString(R.string.err_rate_empty));
            ok = false;
        } else {
            try { rate = Integer.parseInt(r); } catch (NumberFormatException e) { rate = 0; }
            if (rate <= 0) {
                tilRate.setError(getString(R.string.err_rate_zero));
                ok = false;
            }
        }
        banner.setVisibility(ok ? View.GONE : View.VISIBLE);
        return ok;
    }

    private void apply(Lesson l) {
        l.title = text(etTitle);
        l.description = text(etDesc);
        String c = actvCat.getText().toString();
        l.category = Repo.CAT_VOC;
        for (String k : Repo.CATEGORIES) if (k.equals(c)) l.category = k;
        l.duration = duration;
        l.price = rate;
    }

    private void confirmDelete() {
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, lesson.title))
                .setNegativeButton(R.string.keep_lesson, null)
                .setPositiveButton(R.string.action_delete, (dlg, w) -> {
                    if (!Repo.ownsLesson(lesson)) return;
                    int index = Repo.LESSONS.indexOf(lesson);
                    if (index < 0) return;
                    Repo.LESSONS.remove(index);
                    if (!Repo.persist()) {
                        Repo.LESSONS.add(index, lesson);
                        Toast.makeText(requireContext(), R.string.local_save_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.toast_lesson_deleted, Toast.LENGTH_SHORT).show();
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                })
                .show();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(requireContext().getColor(R.color.error));
    }
}
