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

public class BookingsFragment extends Fragment {

    private boolean upcoming = true;
    private LinearLayout list;
    private View emptyView;
    private TextView tvEmptyTitle, tvEmptyBody;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshBookings = new Runnable() {
        @Override public void run() { if (list != null && isResumed()) { render(); refreshHandler.postDelayed(this, 1000); } }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_bookings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        v.findViewById(R.id.btn_camera_check).setOnClickListener(x ->
                ((MainActivity) requireActivity()).open(new CameraCheckFragment()));
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
        refreshHandler.removeCallbacks(refreshBookings);
        if (list != null) refreshHandler.post(refreshBookings);
    }

    @Override public void onPause() { refreshHandler.removeCallbacks(refreshBookings); super.onPause(); }
    @Override public void onDestroyView() { refreshHandler.removeCallbacks(refreshBookings); list = null; super.onDestroyView(); }

    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int count = 0;
        for (Repo.Booking bk : Repo.myBookings()) {
            if (Repo.isPast(bk, System.currentTimeMillis()) == upcoming) continue;
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
        if (Repo.isPast(bk, System.currentTimeMillis())) Ui.addTag(tags, getString(R.string.tag_completed_on, Ui.shortDay(bk.dayLabel)), Ui.Tag.GREY);
        else Ui.addTag(tags, getString(R.string.tag_booked_on, Ui.shortDay(bk.dayLabel)), Ui.Tag.BLUE);

        ((TextView) card.findViewById(R.id.tv_title)).setText(bk.title);
        ((TextView) card.findViewById(R.id.tv_tutor)).setText(bk.tutor);
        ((TextView) card.findViewById(R.id.tv_time)).setText(
                getString(R.string.booking_time_line, bk.timeRange, bk.duration, Ui.peso(bk.price)));

        View join = card.findViewById(R.id.btn_join);
        View cancel = card.findViewById(R.id.btn_cancel);
        TextView note = card.findViewById(R.id.tv_note);
        if (Repo.isPast(bk, System.currentTimeMillis())) {
            join.setVisibility(View.GONE);
            cancel.setVisibility(View.GONE);
            note.setText(R.string.booking_note_past);
        } else {
            boolean open = Repo.canJoinSession(bk.reference, System.currentTimeMillis());
            join.setEnabled(open);
            note.setText(open ? R.string.session_ready : R.string.session_not_open);
            join.setOnClickListener(x -> ((MainActivity) requireActivity())
                    .open(LiveClassroomFragment.newInstance(bk.reference)));
            cancel.setOnClickListener(x -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.cancel_dialog_title)
                    .setMessage(getString(R.string.cancel_dialog_message, bk.title, bk.tutor, bk.dayLabel))
                    .setNegativeButton(R.string.keep_booking, null)
                    .setPositiveButton(R.string.cancel_booking, (d, w) -> {
                        if (!Repo.cancelBooking(bk)) {
                            Toast.makeText(requireContext(), R.string.local_save_failed, Toast.LENGTH_LONG).show();
                            return;
                        }
                        render();
                        Toast.makeText(requireContext(), R.string.toast_booking_cancelled, Toast.LENGTH_SHORT).show();
                    })
                    .show());
        }
    }
}
