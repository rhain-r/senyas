package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Shows a booking that was just made. It never books anything itself. */
public class ConfirmedFragment extends Fragment {

    private static final String ARG_REF = "ref";

    public static ConfirmedFragment newInstance(String reference) {
        ConfirmedFragment f = new ConfirmedFragment();
        Bundle a = new Bundle();
        a.putString(ARG_REF, reference);
        f.setArguments(a);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_confirmed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_booking_confirmed), requireActivity());
        // After the app was closed the booking is read back from the account's saved bookings.
        Bundle args = getArguments();
        Repo.Booking bk = Repo.findBooking(args == null ? null : args.getString(ARG_REF));
        if (bk == null) bk = Repo.lastBooking;
        if (bk == null) {
            // Nothing to show (e.g. it was cancelled): go to the bookings list once the screen is restored.
            v.post(() -> {
                if (isAdded()) ((MainActivity) requireActivity()).selectTab(R.id.nav_bookings);
            });
            return;
        }

        ((TextView) v.findViewById(R.id.tv_title)).setText(bk.title + "\n" + bk.tutor);
        ((TextView) v.findViewById(R.id.tv_when)).setText(bk.dayLabel);
        ((TextView) v.findViewById(R.id.tv_when_sub)).setText(
                getString(R.string.confirmed_when_sub, bk.timeRange, bk.duration));
        ((TextView) v.findViewById(R.id.tv_paid)).setText(
                getString(R.string.confirmed_paid, Ui.peso(bk.price), bk.payment));
        ((TextView) v.findViewById(R.id.tv_ref)).setText(getString(R.string.confirmed_ref, bk.reference));

        v.findViewById(R.id.btn_view_bookings).setOnClickListener(x ->
                ((MainActivity) requireActivity()).selectTab(R.id.nav_bookings));
    }
}
