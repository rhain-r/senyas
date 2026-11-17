package com.benten.senyas;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.appcompat.app.AppCompatDelegate;

public class SenyasApp extends Application {
    public static final String PREFS = "senyas_prefs";
    public static final String KEY_THEME = "theme_mode";
    public static final String CHANNEL_ID = "senyas_updates";

    @Override
    public void onCreate() {
        super.onCreate();
        CameraDiagnostics.initialize(this);
        CloudRuntime.initialize(this);
        Repo.initialize(this);
        int mode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mode);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
