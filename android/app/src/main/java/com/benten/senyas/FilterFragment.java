package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class FilterFragment extends Fragment {

    private static final int[] RB_IDS = {R.id.rb_all, R.id.rb_k12, R.id.rb_tech, R.id.rb_voc, R.id.rb_life};

    private String category;
    private int distance;
    private MaterialButton btnApply;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_filter, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_filter_lessons), requireActivity());
        btnApply = v.findViewById(R.id.btn_apply);
        RadioGroup rg = v.findViewById(R.id.rg_category);
        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_distance);

        category = Repo.filterCategory;
        distance = Repo.filterDistanceKm;

        rg.setOnCheckedChangeListener((g, id) -> {
            int i = Ui.indexOf(RB_IDS, id);
            category = i <= 0 ? null : Repo.CATEGORIES[i - 1];
            updateCount();
        });
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            distance = id == R.id.btn_d2 ? 2 : id == R.id.btn_d5 ? 5 : 10;
            updateCount();
        });

        int idx = 0;
        if (category != null) {
            for (int i = 0; i < Repo.CATEGORIES.length; i++)
                if (Repo.CATEGORIES[i].equals(category)) idx = i + 1;
        }
        rg.check(RB_IDS[idx]);
        toggle.check(distance == 2 ? R.id.btn_d2 : distance == 10 ? R.id.btn_d10 : R.id.btn_d5);

        btnApply.setOnClickListener(x -> {
            Repo.filterCategory = category;
            Repo.filterDistanceKm = distance;
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
        updateCount();
    }

    private void updateCount() {
        int n = Repo.query(category, distance, Repo.filterQuery).size();
        btnApply.setText(getResources().getQuantityString(R.plurals.show_lessons, n, n));
    }
}
