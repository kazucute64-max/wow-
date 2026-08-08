package com.ourlauncher;

import android.os.Build;

/**
 * Detects the device's CPU architecture and maps it to the naming scheme
 * used by AngelAuraMC's prebuilt JRE releases (arm64 / arm / x86_64 / x86),
 * as well as to the Android ABI folder names we use under jniLibs/.
 *
 * Reference: PojavLauncher/Amethyst-Android's Architecture.java does the
 * same mapping; this is our own trimmed-down version.
 */
public class Architecture {

    /** @return "arm64", "arm", "x86_64", or "x86" — matches jreNN-android-<arch>.tar.xz */
    public static String getJreArchString() {
        boolean is64Bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        boolean isX86 = isX86Device();
        if (isX86) {
            return is64Bit ? "x86_64" : "x86";
        }
        return is64Bit ? "arm64" : "arm";
    }

    /** @return the jniLibs/ folder name matching this device's ABI, e.g. "arm64-v8a" */
    public static String getAndroidAbiString() {
        boolean is64Bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        boolean isX86 = isX86Device();
        if (isX86) {
            return is64Bit ? "x86_64" : "x86";
        }
        return is64Bit ? "arm64-v8a" : "armeabi-v7a";
    }

    private static boolean isX86Device() {
        String[] abis = Build.SUPPORTED_64_BIT_ABIS.length > 0
                ? Build.SUPPORTED_64_BIT_ABIS
                : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            String a = abi.toLowerCase().trim();
            if (a.contains("x86")) return true;
        }
        return false;
    }
}
