package com.lasrecomp;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes a small startup/crash breadcrumb log before SDL loads native libraries.
 * The report is placed in Download/Clustertruck so it remains accessible even
 * when the Activity crashes before the in-app logger can initialize.
 */
public final class ClustertruckApplication extends Application {
    private static final String TAG = "ClustertruckStartup";
    private static final String LOG_NAME = "clustertruck-startup-log.txt";
    private static final String PREVIOUS_NAME = "clustertruck-startup-previous.txt";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/Clustertruck/";
    private static final Object LOCK = new Object();

    private static ClustertruckApplication instance;
    private static Uri logUri;
    private static File fallbackLog;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        synchronized (LOCK) {
            try {
                rotateLog();
                writeLocked("==================================================");
                writeLocked("Application.onCreate; package=" + getPackageName()
                        + "; Android=" + Build.VERSION.RELEASE
                        + "; API=" + Build.VERSION.SDK_INT
                        + "; ABI=" + Build.SUPPORTED_ABIS[0]);
                writePreviousExitReasonsLocked();
            } catch (Throwable t) {
                Log.e(TAG, "Could not initialize public startup log", t);
                initFallback();
                writeLocked("Startup logger fallback: " + Log.getStackTraceString(t));
            }
        }

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record("UNCAUGHT JAVA EXCEPTION on " + thread.getName() + ": " + Log.getStackTraceString(error));
            if (previous != null) previous.uncaughtException(thread, error);
        });
        record("Java uncaught-exception handler installed");
    }

    /** Safe to call from the Activity before SDL/native loading starts. */
    public static void record(String message) {
        synchronized (LOCK) {
            if (instance == null) {
                Log.i(TAG, message);
                return;
            }
            writeLocked(message);
        }
    }

    private static void rotateLog() throws Exception {
        Uri current = findLog(LOG_NAME);
        Uri previous = findLog(PREVIOUS_NAME);
        if (previous != null) instance.getContentResolver().delete(previous, null, null);
        if (current != null) {
            ContentValues rename = new ContentValues();
            rename.put(MediaStore.MediaColumns.DISPLAY_NAME, PREVIOUS_NAME);
            instance.getContentResolver().update(current, rename, null, null);
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
        logUri = instance.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (logUri == null) throw new IllegalStateException("MediaStore did not create " + RELATIVE_PATH + LOG_NAME);
    }

    private static Uri findLog(String name) {
        String[] columns = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor cursor = instance.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns, selection,
                new String[]{name, RELATIVE_PATH}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not find existing startup log " + name, t);
        }
        return null;
    }

    private static void initFallback() {
        try {
            File dir = instance.getExternalFilesDir("logs");
            if (dir == null) dir = new File(instance.getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) return;
            fallbackLog = new File(dir, LOG_NAME);
        } catch (Throwable t) {
            Log.e(TAG, "Could not initialize fallback log", t);
        }
    }

    private static void writeLocked(String message) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        byte[] bytes = (stamp + " " + message + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (logUri != null) {
                try (OutputStream out = instance.getContentResolver().openOutputStream(logUri, "wa")) {
                    if (out == null) throw new IllegalStateException("Could not open startup log for append");
                    out.write(bytes);
                    out.flush();
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not append public startup log", t);
            logUri = null;
            if (fallbackLog == null) initFallback();
        }

        try {
            if (fallbackLog != null) {
                try (FileOutputStream out = new FileOutputStream(fallbackLog, true)) {
                    out.write(bytes);
                    out.flush();
                    out.getFD().sync();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not append fallback startup log", t);
        }
        Log.i(TAG, message);
    }

    private static void writePreviousExitReasonsLocked() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager = (ActivityManager) instance.getSystemService(ACTIVITY_SERVICE);
            if (manager == null) {
                writeLocked("Previous process exit information unavailable: no ActivityManager");
                return;
            }
            List<ApplicationExitInfo> exits =
                    manager.getHistoricalProcessExitReasons(instance.getPackageName(), 0, 5);
            if (exits == null || exits.isEmpty()) {
                writeLocked("No previous process exit record reported by Android");
                return;
            }
            for (ApplicationExitInfo info : exits) {
                writeLocked("PREVIOUS PROCESS EXIT: reason=" + reasonName(info.getReason())
                        + " (" + info.getReason() + ")"
                        + "; timestamp=" + info.getTimestamp()
                        + "; status=" + info.getStatus()
                        + "; pssKB=" + info.getPss()
                        + "; rssKB=" + info.getRss()
                        + "; description=" + String.valueOf(info.getDescription()));
                // Android 12+ may expose a native crash trace. Android 11 does not.
                if (Build.VERSION.SDK_INT >= 31) appendExitTraceLocked(info);
            }
        } catch (Throwable t) {
            writeLocked("Could not read previous process exit information: " + Log.getStackTraceString(t));
        }
    }

    private static void appendExitTraceLocked(ApplicationExitInfo info) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) return;
            byte[] buffer = new byte[4096];
            int total = 0;
            int n;
            writeLocked("PREVIOUS PROCESS TRACE BEGIN");
            while (total < 65536 && (n = in.read(buffer, 0, Math.min(buffer.length, 65536 - total))) > 0) {
                String part = new String(buffer, 0, n, StandardCharsets.UTF_8);
                writeLocked(part);
                total += n;
            }
            writeLocked("PREVIOUS PROCESS TRACE END; bytes=" + total);
        } catch (Throwable t) {
            writeLocked("Could not read previous process trace: " + t);
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }
}
