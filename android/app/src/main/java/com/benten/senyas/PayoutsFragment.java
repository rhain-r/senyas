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

import com.google.android.material.chip.ChipGroup;

public class PayoutsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_payouts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_payouts), requireActivity());
        Ui.hideBack(v);
        ((TextView) v.findViewById(R.id.tv_pending)).setText(Ui.peso(Repo.myPendingPayout()));

        LinearLayout list = v.findViewById(R.id.list);
        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (Repo.Payout p : Repo.PAYOUTS) {
            if (!Repo.accountId().equals(p.tutorId)) continue;
            View row = inf.inflate(R.layout.item_payout, list, false);
            ((TextView) row.findViewById(R.id.tv_title)).setText(p.title);
            ((TextView) row.findViewById(R.id.tv_amount)).setText(Ui.peso(p.amount));
            ChipGroup tags = row.findViewById(R.id.tags);
            Ui.addTag(tags, getString(p.released ? R.string.status_released : R.string.status_pending), p.released ? Ui.Tag.GREEN : Ui.Tag.YELLOW);
            list.addView(row);
        }
    }
}
