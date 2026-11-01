package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** The signed-in learner's own bookings, upcoming or past. */
public class BookingsFragment extends Fragment {

    private boolean upcoming = true;
    private LinearLayout list;
    private View emptyView;
    private TextView tvEmptyTitle, tvEmptyBody;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_bookings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        list = v.findViewById(R.id.list);
        emptyView = v.findViewById(R.id.empty_view);
        tvEmptyTitle = v.findViewById(R.id.tv_empty_title);
        tvEmptyBody = v.findViewById(R.id.tv_empty_body);

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_when);
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            upcoming = id == R.id.btn_upcoming;
            render();
        });
        toggle.check(R.id.btn_upcoming);

        v.findViewById(R.id.btn_find).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_tutors));
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (list != null) render();
    }

    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int count = 0;
        for (Repo.Booking bk : Repo.BOOKINGS) {
            if (bk.past == upcoming) continue;
            count++;
            View card = inf.inflate(R.layout.item_booking, list, false);
            bind(card, bk);
            list.addView(card);
        }
        emptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        if (count == 0) {
            tvEmptyTitle.setText(upcoming ? R.string.bookings_empty_upcoming_title : R.string.bookings_empty_past_title);
            tvEmptyBody.setText(upcoming ? R.string.bookings_empty_upcoming_body : R.string.bookings_empty_past_body);
        }
    }

    private void bind(View card, Repo.Booking bk) {
        ChipGroup tags = card.findViewById(R.id.tags);
        if (bk.past) Ui.addTag(tags, getString(R.string.tag_completed_on, Ui.shortDay(bk.dayLabel)), Ui.Tag.GREY);
        else Ui.addTag(tags, getString(R.string.tag_booked_on, Ui.shortDay(bk.dayLabel)), Ui.Tag.BLUE);

        ((TextView) card.findViewById(R.id.tv_title)).setText(bk.title);
        ((TextView) card.findViewById(R.id.tv_tutor)).setText(bk.tutor);
        ((TextView) card.findViewById(R.id.tv_time)).setText(
                getString(R.string.booking_time_line, bk.timeRange, bk.duration, Ui.peso(bk.price)));

        View join = card.findViewById(R.id.btn_join);
        View cancel = card.findViewById(R.id.btn_cancel);
        TextView note = card.findViewById(R.id.tv_note);
        if (bk.past) {
            join.setVisibility(View.GONE);
            cancel.setVisibility(View.GONE);
            note.setText(R.string.booking_note_past);
        } else {
            note.setText(R.string.booking_note_upcoming);
            join.setOnClickListener(x -> ((MainActivity) requireActivity())
                    .open(LiveClassroomFragment.newInstance(bk.reference)));
            cancel.setOnClickListener(x -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.cancel_dialog_title)
                    .setMessage(getString(R.string.cancel_dialog_message, bk.title, bk.tutor, bk.dayLabel))
                    .setNegativeButton(R.string.keep_booking, null)
                    .setPositiveButton(R.string.cancel_booking, (d, w) -> {
                        Repo.cancelBooking(bk); // also drops the tutor's session and pending payout
                        render();
                        Toast.makeText(requireContext(), R.string.toast_booking_cancelled, Toast.LENGTH_SHORT).show();
                    })
                    .show());
        }
    }
}
