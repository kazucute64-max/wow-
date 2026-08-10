package com.ourlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralizes the settings the Settings tab exposes (JVM memory allocation,
 * Java runtime override) so GameLauncher reads from one place instead of
 * either duplicating SharedPreferences keys or having hardcoded values that
 * the Settings screen can't actually affect.
 */
public class LauncherSettings {

    private static final String PREFS_NAME = "ourlauncher_prefs";
    private static final String KEY_XMS = "jvm_xms_mb";
    private static final String KEY_XMX = "jvm_xmx_mb";
    private static final String KEY_JAVA_OVERRIDE = "java_version_override"; // 0 = auto

    public static final int DEFAULT_XMS_MB = 512;
    public static final int DEFAULT_XMX_MB = 1024;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getXmsMb(Context context) {
        return prefs(context).getInt(KEY_XMS, DEFAULT_XMS_MB);
    }

    public static int getXmxMb(Context context) {
        return prefs(context).getInt(KEY_XMX, DEFAULT_XMX_MB);
    }

    /** 0 means "auto" — pick based on what the version's manifest requires. */
    public static int getJavaVersionOverride(Context context) {
        return prefs(context).getInt(KEY_JAVA_OVERRIDE, 0);
    }

    public static void save(Context context, int xmsMb, int xmxMb, int javaVersionOverride) {
        prefs(context).edit()
                .putInt(KEY_XMS, xmsMb)
                .putInt(KEY_XMX, xmxMb)
                .putInt(KEY_JAVA_OVERRIDE, javaVersionOverride)
                .apply();
    }
}
