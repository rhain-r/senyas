package com.benten.senyas;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/** Shared by every role: name, role, a small activity summary, settings and log out. */
public class ProfileFragment extends Fragment {

    private static final int[] MODES = {AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES};

    private LinearLayout info, settings;
    private View rootView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        rootView = v;
        info = v.findViewById(R.id.menu_list);
        settings = v.findViewById(R.id.settings_list);
        ((TextView) v.findViewById(R.id.tv_avatar)).setText(Repo.initialsOf(Repo.userName));
        ((TextView) v.findViewById(R.id.tv_name)).setText(Repo.userName);
        ((TextView) v.findViewById(R.id.tv_role)).setText(Ui.roleLabel(requireContext(), Repo.role));
        v.findViewById(R.id.btn_logout).setOnClickListener(x -> {
            new AccountStore(requireContext()).logout();
            Repo.clearIdentity();
            Intent i = new Intent(requireContext(), LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        });
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (info != null) render();
    }

    private void render() {
        info.removeAllViews();
        settings.removeAllViews();
        bindCharts(Repo.role == Repo.ROLE_LEARNER || Repo.role == Repo.ROLE_STUDENT);

        if (Repo.role == Repo.ROLE_TUTOR) {
            int n = Repo.myLessons().size();
            addInfo(getResources().getQuantityString(R.plurals.lessons_count, n, n));
            addInfo(getString(R.string.pending_payout_sub, Ui.peso(Repo.myPendingPayout())));
        } else if (Repo.role == Repo.ROLE_ADMIN) {
            addInfo(getString(R.string.profile_admin_note));
        } else {
            int count = 0, sum = 0;
            if (Repo.practiceBest != null) {
                for (int s : Repo.practiceBest) if (s >= 0) { count++; sum += s; }
            }
            addInfo(getString(R.string.profile_learner_stat, count == 0 ? 0 : Math.round(sum / (float) count), count));
        }

        if (Repo.role == Repo.ROLE_LEARNER || Repo.role == Repo.ROLE_STUDENT) {
            addRow(getString(R.string.nav_bookings), getString(R.string.profile_bookings_sub),
                    () -> ((MainActivity) requireActivity()).open(new BookingsFragment()));
        }
        addRow(getString(R.string.notifications_title), getString(R.string.notif_summary, Repo.unreadCount()),
                () -> ((MainActivity) requireActivity()).open(new NotificationsFragment()));

        String[] modeNames = {getString(R.string.appearance_system), getString(R.string.appearance_light),
                getString(R.string.appearance_dark)};
        addRow(getString(R.string.appearance), modeNames[currentModeIndex()], this::pickAppearance);
        addRow(getString(R.string.language),
                getString(isFilipino() ? R.string.lang_filipino : R.string.lang_english), this::pickLanguage);
        addRow(getString(R.string.report_user_title), getString(R.string.report_user_sub), () -> showReportUserDialog(null));
        addRow(getString(R.string.share_camera_diagnostics), getString(R.string.camera_diagnostics_description),
                () -> CameraDiagnostics.share(requireActivity()));
    }

    /** Learner charts: best score per sign (real practice results) and a sample weekly activity chart. */
    private void bindCharts(boolean show) {
        View section = rootView.findViewById(R.id.chart_section);
        section.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        int n = Repo.practiceBest == null ? 0 : Repo.practiceBest.length;
        int[] scores = new int[n];
        String[] numbers = new String[n];
        for (int i = 0; i < n; i++) {
            scores[i] = Repo.practiceBest[i] >= 0 ? Repo.practiceBest[i] : -1; // -1 = not tried or skipped
            numbers[i] = String.valueOf(i + 1);
        }
        ((BarChartView) rootView.findViewById(R.id.chart_scores)).setData(scores, numbers, 100, 85);
        ((BarChartView) rootView.findViewById(R.id.chart_week)).setData(
                new int[]{5, 12, 0, 8, 15, 10, 6}, getResources().getStringArray(R.array.week_days_short), 20, -1);
    }

    private void addInfo(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(requireContext().getColor(R.color.text_primary));
        tv.setPadding(0, Ui.dp(requireContext(), 4), 0, Ui.dp(requireContext(), 4));
        info.addView(tv);
    }

    private void addRow(String title, String sub, Runnable onClick) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_menu_row, settings, false);
        ((TextView) row.findViewById(R.id.tv_title)).setText(title);
        ((TextView) row.findViewById(R.id.tv_sub)).setText(sub);
        row.setOnClickListener(x -> onClick.run());
        settings.addView(row);
    }

    private int currentModeIndex() {
        int m = AppCompatDelegate.getDefaultNightMode();
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == m) return i;
        return 0;
    }

    private void pickAppearance() {
        String[] items = {getString(R.string.appearance_system), getString(R.string.appearance_light),
                getString(R.string.appearance_dark)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.appearance)
                .setSingleChoiceItems(items, currentModeIndex(), (d, which) -> {
                    requireContext().getSharedPreferences(SenyasApp.PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(SenyasApp.KEY_THEME, MODES[which]).apply();
                    d.dismiss();
                    AppCompatDelegate.setDefaultNightMode(MODES[which]); // recreates the activity
                })
                .show();
    }

    private boolean isFilipino() {
        LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        Locale loc = l.isEmpty() ? Locale.getDefault() : l.get(0);
        String lang = loc == null ? "" : loc.getLanguage();
        return "fil".equals(lang) || "tl".equals(lang);
    }

    private void pickLanguage() {
        String[] items = {getString(R.string.lang_english), getString(R.string.lang_filipino)};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.language)
                .setSingleChoiceItems(items, isFilipino() ? 1 : 0, (d, which) -> {
                    d.dismiss();
                    AppCompatDelegate.setApplicationLocales(
                            LocaleListCompat.forLanguageTags(which == 1 ? "fil" : "en")); // recreates the activity
                })
                .show();
    }

    public void showReportUserDialog(@Nullable String initialTarget) {
        Context context = requireContext();
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_report_user, null, false);
        com.google.android.material.textfield.TextInputEditText etTarget = dialogView.findViewById(R.id.et_report_target);
        com.google.android.material.textfield.TextInputEditText etDetails = dialogView.findViewById(R.id.et_report_details);
        com.google.android.material.textfield.TextInputLayout tilTarget = dialogView.findViewById(R.id.til_report_target);
        android.widget.RadioGroup rgReasons = dialogView.findViewById(R.id.rg_report_reasons);

        if (initialTarget != null && !initialTarget.trim().isEmpty()) {
            etTarget.setText(initialTarget.trim());
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.report_dialog_title)
                .setView(dialogView)
                .setNegativeButton(R.string.close, (d, which) -> d.dismiss())
                .setPositiveButton(R.string.btn_submit_report, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String target = etTarget.getText() == null ? "" : etTarget.getText().toString().trim();
                String details = etDetails.getText() == null ? "" : etDetails.getText().toString().trim();
                if (target.isEmpty()) {
                    tilTarget.setError(getString(R.string.report_err_target));
                    return;
                }
                tilTarget.setError(null);
                int selectedId = rgReasons.getCheckedRadioButtonId();
                String reason = "Other";
                if (selectedId == R.id.rb_inappropriate) reason = getString(R.string.report_reason_inappropriate);
                else if (selectedId == R.id.rb_spam) reason = getString(R.string.report_reason_spam);
                else if (selectedId == R.id.rb_noshow) reason = getString(R.string.report_reason_noshow);

                CameraDiagnostics.event("user_report target=" + target + " reason=" + reason + " details=" + details);
                android.widget.Toast.makeText(context, R.string.report_submitted_toast, android.widget.Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        });

        dialog.show();
    }
}
