package com.benten.senyas;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.ChipGroup;

/** Listings the signed-in tutor published; a new tutor starts with none. */
public class MyListingsFragment extends Fragment {

    private LinearLayout list;
    private View emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_my_listings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.title_my_listings), requireActivity());
        Ui.hideBack(v); // this screen is the Lessons tab, so there is nothing to go back to
        list = v.findViewById(R.id.list);
        emptyView = v.findViewById(R.id.empty_view);
        v.findViewById(R.id.btn_new).setOnClickListener(x -> {
            Repo.startDraft();
            ((MainActivity) requireActivity()).open(LessonFormFragment.newInstance(-1));
        });
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (list == null) return;
        Repo.save(); // keeps edits made on the media screen
        render();
    }

    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int n = 0;
        for (Lesson l : Repo.myLessons()) {
            n++;
            View card = inf.inflate(R.layout.item_listing, list, false);
            Ui.styleTile((FrameLayout) card.findViewById(R.id.tile), card.findViewById(R.id.tv_initials), l);
            ((TextView) card.findViewById(R.id.tv_title)).setText(l.title);
            ((TextView) card.findViewById(R.id.tv_created)).setText(getString(R.string.created_on, l.created));
            ChipGroup tags = card.findViewById(R.id.tags);
            Ui.addTag(tags, Ui.categoryShort(requireContext(), l.category), Ui.Tag.BLUE);
            Ui.addTag(tags, getString(l.available ? R.string.status_available : R.string.status_booked), l.available ? Ui.Tag.GREEN : Ui.Tag.GREY);
            ((TextView) card.findViewById(R.id.tv_price)).setText(getString(R.string.price_line, Ui.peso(l.price), l.duration));
            card.findViewById(R.id.btn_edit).setOnClickListener(x ->
                    ((MainActivity) requireActivity()).open(LessonFormFragment.newInstance(l.id)));
            list.addView(card);
        }
        emptyView.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
    }
}
