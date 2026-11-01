package com.benten.senyas;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LessonAdapter extends RecyclerView.Adapter<LessonAdapter.VH> {

    public interface OnLessonClick { void onClick(Lesson lesson); }

    private final List<Lesson> items = new ArrayList<>();
    private final OnLessonClick listener;

    public LessonAdapter(OnLessonClick listener) { this.listener = listener; }

    public void setItems(List<Lesson> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lesson, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Lesson l = items.get(position);
        Ui.styleTile(h.tile, h.initials, l);
        h.title.setText(l.title);
        h.tutor.setText(h.itemView.getContext().getString(R.string.tutor_distance_away, l.tutor, l.distanceKm));

        h.tags.removeAllViews();
        Ui.addTag(h.tags, Ui.categoryShort(h.itemView.getContext(), l.category), Ui.Tag.BLUE);
        Ui.addTag(h.tags, h.itemView.getContext().getString(l.available ? R.string.status_available : R.string.status_booked), l.available ? Ui.Tag.GREEN : Ui.Tag.GREY);

        String peso = Ui.peso(l.price);
        SpannableString price = new SpannableString(h.itemView.getContext().getString(R.string.price_for, peso, l.duration));
        price.setSpan(new StyleSpan(Typeface.BOLD), 0, peso.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        h.price.setText(price);

        h.itemView.setOnClickListener(v -> listener.onClick(l));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final FrameLayout tile;
        final TextView initials, title, tutor, price;
        final ChipGroup tags;

        VH(@NonNull View v) {
            super(v);
            tile = v.findViewById(R.id.tile);
            initials = v.findViewById(R.id.tv_initials);
            title = v.findViewById(R.id.tv_title);
            tutor = v.findViewById(R.id.tv_tutor);
            tags = v.findViewById(R.id.tags);
            price = v.findViewById(R.id.tv_price);
        }
    }
}
