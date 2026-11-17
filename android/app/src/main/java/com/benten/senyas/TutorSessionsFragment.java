package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;

/** Tutor's Sessions tab: sessions that learners booked with this tutor. */
public class TutorSessionsFragment extends Fragment {

    private boolean upcoming = true;
    private LinearLayout list;
    private View emptyView;
    private TextView tvEmptyTitle, tvEmptyBody;
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshSessions = new Runnable() {
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
        ((TextView) v.findViewById(R.id.tv_page_title)).setText(R.string.title_sessions);
        v.findViewById(R.id.btn_find).setVisibility(View.GONE);
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
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.removeCallbacks(refreshSessions);
        if (list != null) refreshHandler.post(refreshSessions);
    }

    @Override public void onPause() { refreshHandler.removeCallbacks(refreshSessions); super.onPause(); }
    @Override public void onDestroyView() { refreshHandler.removeCallbacks(refreshSessions); list = null; super.onDestroyView(); }

    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int count = 0;
        for (Repo.TutorSession s : Repo.sessionsFor(Repo.ownerName)) {
            if (Repo.isPast(s, System.currentTimeMillis()) == upcoming) continue;
            count++;
            View card = inf.inflate(R.layout.item_booking, list, false);
            bind(this, card, s);
            list.addView(card);
        }
        emptyView.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        if (count == 0) {
            tvEmptyTitle.setText(upcoming ? R.string.sessions_empty_upcoming_title : R.string.bookings_empty_past_title);
            tvEmptyBody.setText(upcoming ? R.string.sessions_empty_upcoming_body : R.string.bookings_empty_past_body);
        }
    }

    /** Fills an item_booking card with a session (also used by the tutor home screen). */
    static void bind(Fragment f, View card, Repo.TutorSession s) {
        ChipGroup tags = card.findViewById(R.id.tags);
        tags.removeAllViews();
        if (s.cancelled) Ui.addTag(tags, f.getString(R.string.session_cancelled_label), Ui.Tag.GREY);
        else if (Repo.isPast(s, System.currentTimeMillis())) Ui.addTag(tags, f.getString(R.string.tag_completed_on, Ui.shortDay(s.dayLabel)), Ui.Tag.GREY);
        else Ui.addTag(tags, f.getString(R.string.tag_booked_on, Ui.shortDay(s.dayLabel)), Ui.Tag.BLUE);

        ((TextView) card.findViewById(R.id.tv_title)).setText(s.title);
        ((TextView) card.findViewById(R.id.tv_tutor)).setText(f.getString(R.string.learner_line, s.learner));
        ((TextView) card.findViewById(R.id.tv_time)).setText(
                f.getString(R.string.booking_time_line, s.timeRange, s.duration, Ui.peso(s.price)));

        View join = card.findViewById(R.id.btn_join);
        card.findViewById(R.id.btn_cancel).setVisibility(View.GONE);
        TextView note = card.findViewById(R.id.tv_note);
        if (Repo.isPast(s, System.currentTimeMillis())) {
            join.setVisibility(View.GONE);
            note.setText(s.cancelled ? R.string.session_cancelled_note : R.string.booking_note_past);
        } else {
            boolean open = Repo.canJoinSession(s.reference, System.currentTimeMillis());
            join.setEnabled(open);
            note.setText(open ? R.string.session_ready : R.string.session_not_open);
            join.setOnClickListener(x -> ((MainActivity) f.requireActivity())
                    .open(LiveClassroomFragment.newInstance(s.reference, s.learner)));
        }
    }
}
