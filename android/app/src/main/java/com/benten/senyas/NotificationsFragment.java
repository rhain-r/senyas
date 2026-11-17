package com.benten.senyas;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

/** Notification list for the current role, plus preferences and a real local test notification. */
public class NotificationsFragment extends Fragment {

    private static final int[] TITLES = {R.string.notif_t0_title, R.string.notif_t1_title, R.string.notif_t2_title,
            R.string.notif_t3_title, R.string.notif_t4_title, R.string.notif_t5_title, R.string.notif_t6_title,
            R.string.notif_t7_title};
    private static final int[] BODIES = {R.string.notif_t0_body, R.string.notif_t1_body, R.string.notif_t2_body,
            R.string.notif_t3_body, R.string.notif_t4_body, R.string.notif_t5_body, R.string.notif_t6_body,
            R.string.notif_t7_body};

    private LinearLayout list;
    private TextView tvSummary, tvEmpty;

    private final ActivityResultLauncher<String> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) sendTest();
                else Toast.makeText(requireContext(), R.string.notif_permission_denied, Toast.LENGTH_LONG).show();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle b) {
        return inflater.inflate(R.layout.fragment_notifications, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        Ui.bindTopBar(v, getString(R.string.notifications_title), requireActivity());
        list = v.findViewById(R.id.list);
        tvSummary = v.findViewById(R.id.tv_summary);
        tvEmpty = v.findViewById(R.id.tv_empty);

        v.findViewById(R.id.tv_mark_all).setOnClickListener(x -> {
            for (Repo.Notif n : Repo.myNotifications()) n.read = true;
            Repo.persist();
            render();
        });

        SharedPreferences sp = requireContext().getSharedPreferences(SenyasApp.PREFS, Context.MODE_PRIVATE);
        bindSwitch(v, R.id.sw_bookings, "notif_bookings", sp);
        bindSwitch(v, R.id.sw_reminders, "notif_reminders", sp);
        bindSwitch(v, R.id.sw_practice, "notif_practice", sp);
        v.findViewById(R.id.sw_practice).setVisibility(
                Repo.role == Repo.ROLE_LEARNER || Repo.role == Repo.ROLE_STUDENT ? View.VISIBLE : View.GONE);

        v.findViewById(R.id.btn_test).setOnClickListener(x -> {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                sendTest();
            }
        });
        render();
    }

    private void bindSwitch(View root, int id, String key, SharedPreferences sp) {
        SwitchMaterial s = root.findViewById(id);
        s.setChecked(sp.getBoolean(key, true));
        s.setOnCheckedChangeListener((btn, checked) -> sp.edit().putBoolean(key, checked).apply());
    }

    private void render() {
        list.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(requireContext());
        int shown = 0, unread = 0;
        for (Repo.Notif n : Repo.myNotifications()) {
            shown++;
            if (!n.read) unread++;
            View card = inf.inflate(R.layout.item_notification, list, false);
            card.findViewById(R.id.dot).setVisibility(n.read ? View.INVISIBLE : View.VISIBLE);
            TextView title = card.findViewById(R.id.tv_title);
            title.setText(TITLES[n.type]);
            title.setTypeface(null, n.read ? Typeface.NORMAL : Typeface.BOLD);
            ((TextView) card.findViewById(R.id.tv_body)).setText(getString(BODIES[n.type], n.a, n.b));
            ((TextView) card.findViewById(R.id.tv_time)).setText(timeAgo(n.minutesAgo));
            card.setOnClickListener(x -> { n.read = true; Repo.persist(); render(); });
            list.addView(card);
        }
        tvSummary.setText(getString(R.string.notif_summary, unread));
        tvEmpty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private String timeAgo(int m) {
        if (m < 1) return getString(R.string.time_now);
        if (m < 60) return getString(R.string.time_min, m);
        if (m < 1440) return getString(R.string.time_hr, m / 60);
        return getString(R.string.time_day, m / 1440);
    }

    @SuppressLint("MissingPermission") // the permission is checked before this is called
    private void sendTest() {
        Context c = requireContext();
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            Toast.makeText(c, R.string.notif_permission_denied, Toast.LENGTH_LONG).show();
            return;
        }
        Notification n = new NotificationCompat.Builder(c, SenyasApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_test_title))
                .setContentText(getString(R.string.notif_test_body))
                .setAutoCancel(true)
                .build();
        NotificationManagerCompat.from(c).notify(1001, n);
    }
}
