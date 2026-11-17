package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ConfirmedFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(available() ? R.layout.fragment_confirmed : R.layout.fragment_session_blocked, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Repo.Booking bk = Repo.lastBooking;
        if (!available()) {
            ((TextView) v.findViewById(R.id.blocked_title)).setText(R.string.data_unavailable_title);
            ((TextView) v.findViewById(R.id.session_blocked_message)).setText(R.string.booking_details_unavailable);
            v.findViewById(R.id.btn_back).setOnClickListener(x -> ((MainActivity) requireActivity()).open(new BookingsFragment()));
            return;
        }
        Ui.bindTopBar(v, getString(R.string.title_booking_confirmed), requireActivity());

        ((TextView) v.findViewById(R.id.tv_title)).setText(bk.title + "\n" + bk.tutor);
        ((TextView) v.findViewById(R.id.tv_when)).setText(bk.dayLabel);
        ((TextView) v.findViewById(R.id.tv_when_sub)).setText(
                getString(R.string.confirmed_when_sub, bk.timeRange, bk.duration));
        ((TextView) v.findViewById(R.id.tv_paid)).setText(
                getString(R.string.confirmed_paid, Ui.peso(bk.price), bk.payment));
        ((TextView) v.findViewById(R.id.tv_ref)).setText(getString(R.string.confirmed_ref, bk.reference));

        v.findViewById(R.id.btn_view_bookings).setOnClickListener(x ->
                ((MainActivity) requireActivity()).open(new BookingsFragment()));
    }

    private boolean available() {
        return Repo.lastBooking != null && !Repo.lastBooking.cancelled && Repo.accountId().equals(Repo.lastBooking.learnerId);
    }
}
