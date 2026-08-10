package com.ourlauncher;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Catches uncaught exceptions app-wide and writes the full stack trace to
 * filesDir/crash_logs/latest.txt before letting the system's default
 * handler take over (so the normal "app has stopped" behavior still
 * happens — this doesn't try to keep the app running after a crash, just
 * makes sure the crash information survives it).
 *
 * This matters specifically because of GameLauncher's "attempt real
 * launch" path: that runs genuinely untested native code, and a failure
 * there could crash the whole process with no Java-catchable exception
 * and no dialog — exactly the scenario this exists to leave a trail for.
 *
 * HONEST LIMITATION: this only catches Java-level uncaught exceptions on
 * threads that go through the normal JVM exception mechanism. A true
 * native crash (SIGSEGV, e.g. from an ABI mismatch in the bundled .so
 * files) terminates the process directly and never reaches this handler
 * at all — for that class of failure, `adb logcat` after the fact is the
 * only way to see what happened, this can't help.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previousHandler;

    private CrashHandler(Context appContext, Thread.UncaughtExceptionHandler previousHandler) {
        this.appContext = appContext.getApplicationContext();
        this.previousHandler = previousHandler;
    }

    /** Call once, as early as possible (Application.onCreate is ideal). */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler existing = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, existing));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            File logDir = new File(appContext.getFilesDir(), "crash_logs");
            if (!logDir.exists()) //noinspection ResultOfMethodCallIgnored
                logDir.mkdirs();

            File logFile = new File(logDir, "latest.txt");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("Crash at " + timestamp);
            pw.println("Thread: " + thread.getName());
            pw.println();
            throwable.printStackTrace(pw);
            pw.flush();

            try (FileWriter writer = new FileWriter(logFile, false)) {
                writer.write(sw.toString());
            }
        } catch (Exception loggingFailure) {
            // If even crash logging fails, there's nothing more we can safely
            // do here — fall through to the previous/default handler regardless.
        }

        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable);
        } else {
            System.exit(1);
        }
    }

    /** Returns the last saved crash log's text, or null if none exists yet. */
    public static String readLastCrashLog(Context context) {
        File logFile = new File(new File(context.getFilesDir(), "crash_logs"), "latest.txt");
        if (!logFile.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to read crash log: " + e.getMessage();
        }
    }
}
