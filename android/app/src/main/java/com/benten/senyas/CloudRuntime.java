package com.benten.senyas;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.MemoryCacheSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Explicit build-selected Firebase startup. A configuration failure never enables local auth. */
public final class CloudRuntime {
    private static boolean initialized, enabled;
    private static FirebaseAuth auth;
    private static FirebaseFirestore db;
    private static RuntimeException initializationFailure;
    private CloudRuntime() { }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        Context app = context.getApplicationContext();
        enabled = app.getResources().getBoolean(R.bool.firebase_backend_enabled);
        if (!enabled) return;
        try {
            boolean emulator = app.getResources().getBoolean(R.bool.firebase_emulators_enabled);
            if (emulator && (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
                throw new IllegalStateException("Firebase emulators require a test build");
            JSONObject config;
            try (InputStream stream = app.getAssets().open("google-services.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                config = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
            JSONObject project = config.getJSONObject("project_info");
            if (!"senyas-4d7f2".equals(project.getString("project_id")))
                throw new IllegalArgumentException("Firebase project does not match this build");
            JSONArray clients = config.getJSONArray("client");
            JSONObject client = null;
            for (int i = 0; i < clients.length(); i++) {
                JSONObject candidate = clients.getJSONObject(i);
                if (app.getPackageName().equals(candidate.getJSONObject("client_info")
                        .getJSONObject("android_client_info").getString("package_name"))) {
                    client = candidate;
                    break;
                }
            }
            if (client == null) throw new IllegalArgumentException("No Firebase client matches this Android package");
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApplicationId(client.getJSONObject("client_info").getString("mobilesdk_app_id"))
                    .setApiKey(client.getJSONArray("api_key").getJSONObject(0).getString("current_key"))
                    .setProjectId(emulator ? "demo-senyas" : project.getString("project_id"))
                    .setGcmSenderId(project.getString("project_number"))
                    .setStorageBucket(project.getString("storage_bucket"))
                    .build();
            // Separate app names also separate persisted emulator and hosted Auth sessions.
            FirebaseApp firebase = FirebaseApp.initializeApp(app, options, emulator ? "SenyasEmulator" : "SenyasCloud");
            auth = FirebaseAuth.getInstance(firebase);
            db = FirebaseFirestore.getInstance(firebase);
            db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());
            if (emulator) {
                auth.useEmulator("10.0.2.2", 9199);
                db.useEmulator("10.0.2.2", 8180);
            }
            CloudAccounts.initialize(app, emulator);
        } catch (Exception invalidConfiguration) {
            initializationFailure = new IllegalStateException("Shared account service is not configured for this build");
            auth = null;
            db = null;
        }
    }

    /** True for connected builds even if startup failed; failures must stay fail-closed. */
    public static synchronized boolean enabled() { return enabled; }
    public static synchronized FirebaseAuth auth() { requireReady(); return auth; }
    public static synchronized FirebaseFirestore db() { requireReady(); return db; }
    private static void requireReady() {
        if (initializationFailure != null) throw initializationFailure;
        if (!enabled || auth == null || db == null) throw new IllegalStateException("Shared account service is unavailable");
    }
}
