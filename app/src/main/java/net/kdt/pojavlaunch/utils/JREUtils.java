package net.kdt.pojavlaunch.utils;

import android.content.Context;

/**
 * IMPORTANT — why this class lives in this exact package:
 *
 * The native bridge libraries we bundle (libpojavexec.so, libpojavexec_awt.so,
 * libexithook.so — pulled from a real AngelAuraMC/Amethyst-Android release
 * APK, see /app/src/main/jniLibs/NOTICE.md) were compiled with the standard
 * JNI static-linking convention, which bakes the calling Java class's package
 * and name directly into each native symbol, e.g.:
 *
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_dlopen
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_chdir
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_setLdLibraryPath
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_initializeHooks
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow / releaseBridgeWindow
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeSurfaceAWT
 *   Java_net_kdt_pojavlaunch_utils_JREUtils_renderAWTScreenFrame (in libpojavexec_awt.so)
 *
 * Since we didn't compile these binaries ourselves (compiling ~20 C files
 * would need the Android NDK, which this environment doesn't have), we
 * can't rename them. This class's package/name has to match exactly, or the
 * JVM will throw UnsatisfiedLinkError at runtime. Everything else in
 * OurLauncher (RuntimeManager, ClientDownloader, our future GameLauncher)
 * stays under com.ourlauncher as normal — only this one class is pinned.
 */
public class JREUtils {

    static {
        // Load order matters: exithook/linkerhook patch libc behavior that
        // pojavexec relies on, so they must be loaded first.
        System.loadLibrary("exithook");
        System.loadLibrary("linkerhook");
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeHooks();
    public static native void setupExitMethod(Context context);
    public static native void setupBridgeSurfaceAWT();
    public static native int[] renderAWTScreenFrame();
}
