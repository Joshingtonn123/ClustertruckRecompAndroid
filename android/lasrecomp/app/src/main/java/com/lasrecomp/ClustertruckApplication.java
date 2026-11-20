package com.lasrecomp;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes startup/crash breadcrumbs before SDL loads native libraries.
 * Keep logs beside the app's existing logs under Android/data/.../files/logs.
 */
public final class ClustertruckApplication extends Application {
    private static final String TAG = "ClustertruckStartup";
    private static final String LOG_NAME = "clustertruck-startup-log.txt";
    private static final String PREVIOUS_NAME = "clustertruck-startup-previous.txt";
    private static final Object LOCK = new Object();

    private static ClustertruckApplication instance;
    private static File logFile;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        synchronized (LOCK) {
            try {
                initLog();
                writeLocked("==================================================");
                writeLocked("Application.onCreate; package=" + getPackageName()
                        + "; Android=" + Build.VERSION.RELEASE
                        + "; API=" + Build.VERSION.SDK_INT
                        + "; ABI=" + Build.SUPPORTED_ABIS[0]);
                writePreviousExitReasonsLocked();
            } catch (Throwable t) {
                Log.e(TAG, "Could not initialize startup log", t);
                Log.i(TAG, "Startup logger initialization failed: " + Log.getStackTraceString(t));
            }
        }

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            record("UNCAUGHT JAVA EXCEPTION on " + thread.getName() + ": " + Log.getStackTraceString(error));
            if (previous != null) previous.uncaughtException(thread, error);
        });
        record("Java uncaught-exception handler installed");
    }

    private static void initLog() throws Exception {
        File dir = instance.getExternalFilesDir("logs");
        if (dir == null) dir = new File(instance.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create log directory: " + dir);
        }

        File current = new File(dir, LOG_NAME);
        File previous = new File(dir, PREVIOUS_NAME);
        if (previous.exists() && !previous.delete()) {
            Log.w(TAG, "Could not delete previous startup log");
        }
        if (current.exists() && !current.renameTo(previous)) {
            Log.w(TAG, "Could not rotate previous startup log");
        }
        logFile = current;
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

    private static void writeLocked(String message) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = stamp + " " + message + "\n";
        try {
            if (logFile == null) initLog();
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(line.getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not append startup log", t);
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
                writeLocked(new String(buffer, 0, n, "UTF-8"));
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
