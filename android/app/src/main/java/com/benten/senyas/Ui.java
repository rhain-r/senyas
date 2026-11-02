package com.benten.senyas;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import java.util.Locale;

public final class Ui {
    private Ui() {}

    public enum Tag { BLUE, GREEN, GREY, YELLOW }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static String peso(int v) {
        return "₱" + v;
    }

    /** Status is always text, never colour alone. */
    public static TextView addTag(ViewGroup parent, String text, Tag kind) {
        Context c = parent.getContext();
        int bg, fg;
        switch (kind) {
            case GREEN: bg = R.color.success_container; fg = R.color.success_text; break;
            case GREY: bg = R.color.tag_grey; fg = R.color.text_secondary; break;
            case YELLOW: bg = R.color.warning_container; fg = R.color.warning_text; break;
            default: bg = R.color.blue_container; fg = R.color.accent; break;
        }
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        tv.setBackgroundResource(R.drawable.bg_tag);
        tv.setBackgroundTintList(ColorStateList.valueOf(c.getColor(bg)));
        tv.setTextColor(c.getColor(fg));
        parent.addView(tv);
        return tv;
    }

    public static void styleTile(FrameLayout tile, TextView initials, Lesson l) {
        Context c = tile.getContext();
        tile.setBackgroundTintList(ColorStateList.valueOf(l.tileColor));
        initials.setText(l.initials);
        initials.setTextColor(c.getColor(l.darkText ? R.color.navy : R.color.white));
    }

    public static void bindTopBar(View root, String title, FragmentActivity act) {
        ((TextView) root.findViewById(R.id.tv_bar_title)).setText(title);
        root.findViewById(R.id.btn_back).setOnClickListener(
                v -> act.getOnBackPressedDispatcher().onBackPressed());
    }

    public static void addRow(LinearLayout parent, String label, String value) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 52));

        TextView l = new TextView(c);
        l.setText(label);
        l.setTextSize(14);
        l.setTextColor(c.getColor(R.color.text_secondary));
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(14);
        v.setTypeface(null, Typeface.BOLD);
        v.setGravity(Gravity.END);
        v.setTextColor(c.getColor(R.color.text_primary)); // light in dark mode, so it stays readable
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        parent.addView(row);
    }

    public static String clock(int min, boolean suffix) {
        int h = min / 60, m = min % 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        String s = String.format(Locale.US, "%d:%02d", h12, m);
        return suffix ? s + (h < 12 ? " am" : " pm") : s;
    }

    public static String timeRange(int startMin, int durMin) {
        int end = startMin + durMin;
        boolean sameHalf = (startMin / 60 < 12) == (end / 60 < 12);
        return (sameHalf ? clock(startMin, false) : clock(startMin, true)) + "–" + clock(end, true);
    }

    public static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    public static String categoryShort(Context c, String cat) {
        if (Repo.CAT_K12.equals(cat)) return c.getString(R.string.cat_short_k12);
        if (Repo.CAT_TECH.equals(cat)) return c.getString(R.string.cat_short_tech);
        if (Repo.CAT_VOC.equals(cat)) return c.getString(R.string.cat_short_voc);
        return c.getString(R.string.cat_short_life);
    }

    /** Wires the bell icon (view_bell.xml): unread dot + opens the notifications screen. */
    public static void bindBell(View root, MainActivity act) {
        View bell = root.findViewById(R.id.btn_bell);
        if (bell == null) return;
        int unread = Repo.unreadCount();
        root.findViewById(R.id.bell_dot).setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        bell.setContentDescription(unread > 0
                ? act.getString(R.string.notifications_unread_desc, unread)
                : act.getString(R.string.notifications_title));
        bell.setOnClickListener(x -> act.open(new NotificationsFragment()));
    }

    public static String roleLabel(Context c, int role) {
        switch (role) {
            case Repo.ROLE_STUDENT: return c.getString(R.string.role_student);
            case Repo.ROLE_TUTOR: return c.getString(R.string.role_tutor);
            case Repo.ROLE_ADMIN: return c.getString(R.string.role_admin);
            default: return c.getString(R.string.role_learner);
        }
    }

    /** For top bars that sit on a tab's first screen: no back arrow. */
    public static void hideBack(View root) {
        root.findViewById(R.id.btn_back).setVisibility(View.GONE);
        View t = root.findViewById(R.id.tv_bar_title);
        t.setPadding(dp(root.getContext(), 12), t.getPaddingTop(), t.getPaddingRight(), t.getPaddingBottom());
    }

    /** Adds a row holding one or two equal-width stat cards. */
    public static void addStatRow(LinearLayout parent, String v1, String l1, String v2, String l2) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        addStat(row, v1, l1);
        if (v2 != null) addStat(row, v2, l2);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void addStat(LinearLayout row, String value, String label) {
        View card = LayoutInflater.from(row.getContext()).inflate(R.layout.item_stat, row, false);
        ((TextView) card.findViewById(R.id.tv_value)).setText(value);
        ((TextView) card.findViewById(R.id.tv_label)).setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = dp(row.getContext(), 4);
        lp.setMargins(m, m, m, m);
        row.addView(card, lp);
    }

    /** "Mon, 19 October 2026" -> "Mon 19 Oct" */
    public static String shortDay(String full) {
        String[] a = full.split(", ");
        String[] b = a[1].split(" ");
        return a[0] + " " + b[0] + " " + b[1].substring(0, 3);
    }
}
