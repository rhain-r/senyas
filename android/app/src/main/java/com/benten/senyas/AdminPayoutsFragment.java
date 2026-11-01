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

import com.google.android.material.chip.ChipGroup;

/** Admin: release tutor payouts once sessions are completed. */
public class AdminPayoutsFragment extends Fragment {

    private LinearLayout list;
    private TextView tvSub;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_admin_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        ((TextView) v.findViewById(R.id.tv_title)).setText(R.string.title_payouts);
        tvSub = v.findViewById(R.id.tv_sub);
        list = v.findViewById(R.id.list);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (list != null) render();
    }

    private void render() {
        list.removeAllViews();
        int pending = 0;
        for (Repo.Payout p : Repo.PAYOUTS) if (!p.released) pending++;
        tvSub.setText(getString(R.string.admin_payouts_sub, Ui.peso(Repo.pendingPayout(null)), pending));

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (Repo.Payout p : Repo.PAYOUTS) {
            View row = inf.inflate(R.layout.item_admin_payout, list, false);
            ((TextView) row.findViewById(R.id.tv_title)).setText(p.title);
            ((TextView) row.findViewById(R.id.tv_amount)).setText(Ui.peso(p.amount));
            ((TextView) row.findViewById(R.id.tv_tutor)).setText(getString(R.string.payout_tutor, p.tutor));
            ChipGroup tags = row.findViewById(R.id.tags);
            Ui.addTag(tags, getString(p.released ? R.string.status_released : R.string.status_pending),
                    p.released ? Ui.Tag.GREEN : Ui.Tag.YELLOW);

            View release = row.findViewById(R.id.btn_release);
            release.setVisibility(p.released ? View.GONE : View.VISIBLE);
            release.setOnClickListener(x -> {
                p.released = true; // TODO (Firebase batch): trigger the real GCash/Maya payout
                Repo.notifyOwner(p, Repo.N_PAYOUT_RELEASED, Ui.peso(p.amount), "");
                Toast.makeText(requireContext(), getString(R.string.toast_payout_released, p.tutor),
                        Toast.LENGTH_SHORT).show();
                render();
            });
            list.addView(row);
        }
    }
}
