package com.benten.senyas;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class TutorsFragment extends Fragment {

    private enum State { LOADING, LIST, EMPTY, OFFLINE }

    private static final int[] CHIP_IDS = {R.id.chip_all, R.id.chip_k12, R.id.chip_tech, R.id.chip_voc, R.id.chip_life};

    private RecyclerView rv;
    private View skeleton, emptyView, offlineBanner, offlineNote;
    private TextView tvCount, tvLocation;
    private Chip chipFilters;
    private ChipGroup chipGroup;
    private TextInputEditText etSearch;
    private LessonAdapter adapter;
    private boolean syncing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_tutors, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        rv = v.findViewById(R.id.rv_lessons);
        skeleton = v.findViewById(R.id.skeleton);
        emptyView = v.findViewById(R.id.empty_view);
        offlineBanner = v.findViewById(R.id.offline_banner);
        offlineNote = v.findViewById(R.id.offline_note);
        tvCount = v.findViewById(R.id.tv_count);
        tvLocation = v.findViewById(R.id.tv_location);
        chipFilters = v.findViewById(R.id.chip_filters);
        chipGroup = v.findViewById(R.id.chip_group);
        etSearch = v.findViewById(R.id.et_search);

        adapter = new LessonAdapter(l ->
                ((MainActivity) requireActivity()).open(LessonDetailFragment.newInstance(l.id)));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
            if (syncing || ids.isEmpty()) return;
            int idx = Ui.indexOf(CHIP_IDS, ids.get(0));
            Repo.filterCategory = idx <= 0 ? null : Repo.CATEGORIES[idx - 1];
            refresh();
        });
        chipFilters.setOnClickListener(x -> openFilters());
        v.findViewById(R.id.tv_change).setOnClickListener(x -> openFilters());

        etSearch.setText(Repo.filterQuery);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) {
                Repo.filterQuery = s.toString().trim();
                if (Repo.loadedOnce) refresh();
            }
        });

        // Demo: long-press the title to toggle the offline state.
        v.findViewById(R.id.tv_title).setOnLongClickListener(x -> {
            Repo.offline = !Repo.offline;
            Toast.makeText(requireContext(), Repo.offline ? R.string.toast_offline_on : R.string.toast_offline_off,
                    Toast.LENGTH_SHORT).show();
            refresh();
            return true;
        });

        v.findViewById(R.id.btn_clear).setOnClickListener(x -> {
            Repo.clearFilters();
            etSearch.setText("");
            syncChips();
            refresh();
        });
        v.findViewById(R.id.btn_retry).setOnClickListener(x -> {
            Repo.offline = false;
            show(State.LOADING);
            handler.postDelayed(this::refresh, 800);
        });
        v.findViewById(R.id.fab_new).setOnClickListener(x -> {
            Repo.startDraft();
            ((MainActivity) requireActivity()).open(LessonFormFragment.newInstance(-1));
        });
        v.findViewById(R.id.fab_new).setVisibility(Repo.role == Repo.ROLE_TUTOR ? View.VISIBLE : View.GONE);

        syncChips();
        if (!Repo.loadedOnce) {
            show(State.LOADING);
            handler.postDelayed(() -> { Repo.loadedOnce = true; refresh(); }, 1200);
        } else {
            refresh();
        }
        refreshFromCloud();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Repo.loadedOnce) { syncChips(); refresh(); }
        refreshFromCloud();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void openFilters() {
        ((MainActivity) requireActivity()).open(new FilterFragment());
    }

    private void syncChips() {
        syncing = true;
        int idx = 0;
        if (Repo.filterCategory != null) {
            for (int i = 0; i < Repo.CATEGORIES.length; i++)
                if (Repo.CATEGORIES[i].equals(Repo.filterCategory)) idx = i + 1;
        }
        chipGroup.check(CHIP_IDS[idx]);
        syncing = false;
    }

    private void refresh() {
        int n = Repo.activeFilterCount();
        chipFilters.setText(n > 0 ? getString(R.string.filters_count, n) : getString(R.string.filters));
        tvLocation.setText(getString(R.string.location_line, Repo.filterDistanceKm));

        if (Repo.offline) {
            List<Lesson> saved = new ArrayList<>(Repo.LESSONS.subList(0, 1));
            adapter.setItems(saved);
            tvCount.setText(R.string.saved_updated);
            show(State.OFFLINE);
            return;
        }
        List<Lesson> r = Repo.query(Repo.filterCategory, Repo.filterDistanceKm, Repo.filterQuery);
        adapter.setItems(r);
        if (r.isEmpty()) {
            show(State.EMPTY);
        } else {
            tvCount.setText(getResources().getQuantityString(R.plurals.lessons_nearby, r.size(), r.size()));
            show(State.LIST);
        }
    }

    private void refreshFromCloud() {
        if (!CloudRuntime.enabled()) return;
        CloudData.refresh().addOnCompleteListener(task -> {
            if (task.isSuccessful() && isAdded() && adapter != null) refresh();
        });
    }

    private void show(State s) {
        rv.setVisibility(s == State.LIST || s == State.OFFLINE ? View.VISIBLE : View.GONE);
        skeleton.setVisibility(s == State.LOADING ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(s == State.EMPTY ? View.VISIBLE : View.GONE);
        offlineBanner.setVisibility(s == State.OFFLINE ? View.VISIBLE : View.GONE);
        offlineNote.setVisibility(s == State.OFFLINE ? View.VISIBLE : View.GONE);
        tvCount.setVisibility(s == State.EMPTY ? View.GONE : View.VISIBLE);
        if (s == State.LOADING) tvCount.setText(R.string.loading_lessons_text);
    }
}
