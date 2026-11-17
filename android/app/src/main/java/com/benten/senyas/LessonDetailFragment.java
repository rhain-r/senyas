package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

import java.util.Locale;

public class LessonDetailFragment extends Fragment {

    public static LessonDetailFragment newInstance(int lessonId) {
        LessonDetailFragment f = new LessonDetailFragment();
        Bundle a = new Bundle();
        a.putInt("id", lessonId);
        f.setArguments(a);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_lesson_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_lesson_details), requireActivity());
        Lesson l = Repo.byId(requireArguments().getInt("id"));
        if (l == null || !l.reviewed) {
            v.findViewById(R.id.btn_schedule).setEnabled(false);
            android.widget.Toast.makeText(requireContext(), R.string.listing_details_unavailable, android.widget.Toast.LENGTH_LONG).show();
            v.post(() -> {
                if (isAdded() && getView() == v) requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
            return;
        }

        ((TextView) v.findViewById(R.id.tv_title)).setText(l.title);
        Ui.styleTile(v.findViewById(R.id.tile), v.findViewById(R.id.tv_initials), l);
        ((TextView) v.findViewById(R.id.tv_tutor)).setText(l.tutor);
        ((TextView) v.findViewById(R.id.tv_tutor_sub)).setText(
                getString(R.string.deaf_tutor_distance, l.distanceKm));
        ((TextView) v.findViewById(R.id.tv_desc)).setText(l.description);
        ((TextView) v.findViewById(R.id.tv_preview_len)).setText(
                l.previewSeconds == 0 ? getString(R.string.preview_none) : getString(R.string.preview_len, l.previewSeconds));
        ((TextView) v.findViewById(R.id.tv_price)).setText(
                getString(R.string.price_detail, Ui.peso(l.price), l.duration));

        ChipGroup tags = v.findViewById(R.id.tags);
        Ui.addTag(tags, Ui.categoryShort(requireContext(), l.category), Ui.Tag.BLUE);
        Ui.addTag(tags, getString(l.available ? R.string.status_available : R.string.status_booked), l.available ? Ui.Tag.GREEN : Ui.Tag.GREY);

        v.findViewById(R.id.card_preview).setOnClickListener(x ->
                Toast.makeText(requireContext(), getString(R.string.toast_preview), Toast.LENGTH_SHORT).show());

        View tvReport = v.findViewById(R.id.tv_report_tutor);
        if (tvReport != null) {
            final String tutorName = l.tutor;
            tvReport.setOnClickListener(x -> new ProfileFragment().showReportUserDialog(tutorName));
        }

        MaterialButton btn = v.findViewById(R.id.btn_schedule);
        TextView note = v.findViewById(R.id.tv_unavailable);
        if (Repo.offline) {
            btn.setEnabled(false);
            btn.setText(R.string.btn_booking_needs_connection);
            note.setVisibility(View.VISIBLE);
            note.setText(R.string.note_offline_book);
        } else if (!l.available) {
            btn.setEnabled(false);
            btn.setText(R.string.btn_currently_booked);
            note.setVisibility(View.VISIBLE);
            note.setText(R.string.note_booked_now);
        } else {
            btn.setOnClickListener(x -> {
                Repo.draftLesson = l;
                ((MainActivity) requireActivity()).open(ScheduleFragment.newInstance(l.id));
            });
        }
    }
}
