package com.benten.senyas;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Records stages and system exit evidence locally, without frames or account details. */
final class CameraDiagnostics {
    private static final String TAG = "SenyasDiagnostics";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static DiagnosticJournal journal;
    private static ActivityManager activityManager;

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        activityManager = app.getSystemService(ActivityManager.class);
        journal = new DiagnosticJournal(new File(app.getFilesDir(), "camera-diagnostics"));
        try {
            journal.start();
            event("application_started version=" + app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName
                    + " sdk=" + Build.VERSION.SDK_INT + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + " manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL);
        } catch (Exception error) {
            Log.w(TAG, "Cannot start diagnostic journal", error);
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                PrintWriter writer = new PrintWriter(trace);
                // Exception messages can contain user input; keep classes and stack frames only.
                Throwable cause = error;
                for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                    writer.println(cause.getClass().getName());
                    StackTraceElement[] frames = cause.getStackTrace();
                    for (int index = 0; index < Math.min(frames.length, 256); index++) writer.println("    at " + frames[index]);
                    if (cause.getCause() == cause) break;
                }
                journal.save("java-crash.txt", trace.toString().getBytes(StandardCharsets.UTF_8), 65_536);
                event("uncaught_java_exception thread=" + thread.getName() + " type=" + error.getClass().getName());
            } catch (Exception diagnosticError) {
                Log.w(TAG, "Cannot preserve Java crash", diagnosticError);
            } finally {
                // Preserve Android's crash behavior and existing crash handlers.
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
        WORKER.execute(() -> captureSystemExits(app));
    }

    static void event(String message) {
        if (journal == null) return;
        try { journal.event(message); }
        catch (IOException error) { Log.w(TAG, "Cannot record camera stage", error); }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                if (activityManager != null) activityManager
                        .setProcessStateSummary(message.substring(0, Math.min(message.length(), 100))
                                .getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot update process stage", error);
            }
        }
    }

    private static void captureSystemExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            saveText("exit-info.txt", "System exit history requires Android 11 or newer.\n");
            return;
        }
        try {
            List<ApplicationExitInfo> exits = context.getSystemService(ActivityManager.class)
                    .getHistoricalProcessExitReasons(null, 0, 5);
            StringBuilder report = new StringBuilder("System process exit history. Numeric reasons follow ApplicationExitInfo.\n");
            new File(context.getFilesDir(), "camera-diagnostics/native-tombstone.pb").delete();
            boolean copiedNativeTrace = false;
            for (ApplicationExitInfo exit : exits) {
                report.append("timestamp=").append(exit.getTimestamp()).append(" reason=")
                        .append(exit.getReason()).append(" status=").append(exit.getStatus())
                        .append(" importance=").append(exit.getImportance()).append(" pssKb=")
                        .append(exit.getPss()).append(" pid=").append(exit.getPid()).append(" rssKb=").append(exit.getRss())
                        .append(" description=").append(exit.getDescription()).append('\n');
                byte[] stage = exit.getProcessStateSummary();
                if (stage != null) report.append("last_stage=").append(new String(stage, StandardCharsets.UTF_8)).append('\n');
                if (!copiedNativeTrace && exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try (InputStream input = exit.getTraceInputStream()) {
                        if (input != null) {
                            journal.save("native-tombstone.pb", DiagnosticJournal.readBounded(input, 2_097_152), 2_097_152);
                            report.append("native_tombstone_timestamp=").append(exit.getTimestamp()).append('\n');
                            copiedNativeTrace = true;
                        }
                    } catch (IOException error) {
                        report.append("native_tombstone_unavailable=").append(error.getClass().getSimpleName()).append('\n');
                    }
                }
            }
            if (exits.isEmpty()) report.append("No previous exit records available.\n");
            saveText("exit-info.txt", report.toString());
        } catch (RuntimeException error) {
            saveText("exit-info.txt", "System exit history unavailable: " + error.getClass().getName() + "\n");
        }
    }

    private static void saveText(String name, String text) {
        try { journal.save(name, text.getBytes(StandardCharsets.UTF_8), 65_536); }
        catch (IOException error) { Log.w(TAG, "Cannot save system exit evidence", error); }
    }

    static void share(Activity activity) {
        WORKER.execute(() -> {
            try {
                File directory = new File(activity.getCacheDir(), "diagnostics");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create export folder");
                File zip = new File(directory, "senyas-camera-diagnostics.zip");
                journal.export(zip);
                Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(activity,
                                activity.getPackageName() + ".diagnostics", zip))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed())
                        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_camera_diagnostics)));
                });
            } catch (Exception error) {
                Log.w(TAG, "Cannot share diagnostics", error);
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.diagnostics_export_failed, Toast.LENGTH_LONG).show());
            }
        });
    }
}
