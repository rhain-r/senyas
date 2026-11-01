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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;

/** Admin: review new listings and remove ones that break the rules. */
public class AdminListingsFragment extends Fragment {

    private LinearLayout list;
    private TextView tvSub;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_admin_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        ((TextView) v.findViewById(R.id.tv_title)).setText(R.string.nav_listings);
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
        int review = 0;
        for (Lesson l : Repo.LESSONS) if (!l.reviewed) review++;
        tvSub.setText(getString(R.string.admin_listings_sub, Repo.LESSONS.size(), review));

        LayoutInflater inf = LayoutInflater.from(requireContext());
        for (Lesson l : new ArrayList<>(Repo.LESSONS)) {
            View card = inf.inflate(R.layout.item_admin_listing, list, false);
            ((TextView) card.findViewById(R.id.tv_title)).setText(l.title);
            ((TextView) card.findViewById(R.id.tv_by)).setText(
                    getString(R.string.by_tutor, l.tutor) + " · " + Ui.categoryShort(requireContext(), l.category));
            ChipGroup tags = card.findViewById(R.id.tags);
            if (l.reviewed) Ui.addTag(tags, getString(R.string.tag_approved), Ui.Tag.GREEN);
            else Ui.addTag(tags, getString(R.string.tag_needs_review), Ui.Tag.YELLOW);
            ((TextView) card.findViewById(R.id.tv_price)).setText(
                    getString(R.string.price_line, Ui.peso(l.price), l.duration));

            View approve = card.findViewById(R.id.btn_approve);
            approve.setVisibility(l.reviewed ? View.GONE : View.VISIBLE);
            approve.setOnClickListener(x -> {
                l.reviewed = true; // TODO (Firebase batch): update the listing in Firestore
                Repo.notifyOwner(l, Repo.N_LISTING_APPROVED, l.title, "");
                Toast.makeText(requireContext(), R.string.toast_approved, Toast.LENGTH_SHORT).show();
                render();
            });
            card.findViewById(R.id.btn_remove).setOnClickListener(x -> new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.remove_title)
                    .setMessage(getString(R.string.remove_message, l.title, l.tutor))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.btn_remove, (d, w) -> {
                        Repo.LESSONS.remove(l);
                        Toast.makeText(requireContext(), R.string.toast_removed, Toast.LENGTH_SHORT).show();
                        render();
                    })
                    .show());
            list.addView(card);
        }
    }
}
