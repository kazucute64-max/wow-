package com.ourlauncher;

import android.content.Context;

import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.oracle.dalvik.VMLauncher;

/**
 * Orchestrates an actual launch attempt: makes sure a runtime is installed,
 * sets up the native environment (native library path, working directory,
 * exit hook), builds the JVM argv, and hands off to VMLauncher.launchJVM.
 *
 * Classpath, main class, and game arguments are no longer built here —
 * they come from LaunchConfigBuilder, the same code the Home tab's "Play"
 * dry-run dialog already uses, so both paths stay consistent by
 * construction instead of maintaining classpath-assembly logic twice.
 * That also means this now genuinely includes every downloaded library
 * jar, not just client.jar, and passes the full set of arguments
 * Minecraft's main() actually expects (--username, --version, --gameDir,
 * --assetsDir, --assetIndex, --uuid, etc).
 *
 * STILL UNTESTED on a real device: the JRE install, native library
 * loading, and the JVM actually starting are each their own point of
 * failure, and this hasn't been run yet. Expect the first several
 * attempts to fail somewhere in this chain — each failure narrows down
 * exactly which piece needs attention next.
 */
public class GameLauncher {

    public interface LaunchListener {
        void onRuntimeDownloadProgress(long downloaded, long total);
        void onLaunching();
        void onExit(int exitCode);
        void onError(Exception e);
    }

    public static void launch(Context context, VersionEntry entry, File clientJar,
                               LocalAccount account, LaunchListener listener) {
        new Thread(() -> {
            try {
                VersionManifestFetcher.LaunchInfo meta =
                        VersionManifestFetcher.fetchLaunchInfo(entry.url);

                LaunchConfigBuilder.LaunchConfig config =
                        LaunchConfigBuilder.build(context, entry, clientJar, meta, account);

                int javaOverride = LauncherSettings.getJavaVersionOverride(context);
                int javaVersion = javaOverride != 0
                        ? javaOverride
                        : (RuntimeManager.isVersionAvailable(meta.javaMajorVersion)
                                ? meta.javaMajorVersion
                                : 17); // fall back to a modern runtime for versions we don't have a prebuilt for

                File runtimeHome = RuntimeManager.getInstalledRuntimeHome(context, javaVersion);
                if (runtimeHome == null) {
                    final Exception[] installError = {null};
                    final File[] installedHome = {null};
                    final Object lock = new Object();
                    RuntimeManager.install(context, javaVersion, new RuntimeManager.ProgressListener() {
                        @Override
                        public void onProgress(long bytesDownloaded, long totalBytes) {
                            listener.onRuntimeDownloadProgress(bytesDownloaded, totalBytes);
                        }
                        @Override
                        public void onComplete(File home) {
                            synchronized (lock) {
                                installedHome[0] = home;
                                lock.notifyAll();
                            }
                        }
                        @Override
                        public void onError(Exception e) {
                            synchronized (lock) {
                                installError[0] = e;
                                lock.notifyAll();
                            }
                        }
                    });
                    synchronized (lock) {
                        while (installedHome[0] == null && installError[0] == null) {
                            lock.wait();
                        }
                    }
                    if (installError[0] != null) throw installError[0];
                    runtimeHome = installedHome[0];
                }

                File javaBinary = RuntimeManager.getJavaBinary(runtimeHome);
                if (javaBinary == null) {
                    throw new IllegalStateException("Runtime installed but bin/java is missing at " + runtimeHome);
                }

                File gameDir = new File(context.getFilesDir(), "game");
                if (!gameDir.exists() && !gameDir.mkdirs()) {
                    throw new java.io.IOException("Could not create " + gameDir);
                }

                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;

                // --- Native environment setup (mirrors JREUtils.launchJavaVM in Pojav/Amethyst) ---
                JREUtils.setLdLibraryPath(runtimeHome.getAbsolutePath() + "/lib:" + nativeLibDir);
                JREUtils.setupExitMethod(context.getApplicationContext());
                JREUtils.initializeHooks();
                JREUtils.chdir(gameDir.getAbsolutePath());

                // --- Build argv: JVM flags, then -cp with the FULL classpath
                //     (client jar + every downloaded library), then main
                //     class, then Minecraft's own game arguments — all from
                //     LaunchConfigBuilder, not duplicated here. ---
                List<String> args = new ArrayList<>();
                args.add("java"); // argv[0] is the program name per C convention
                args.add("-Djava.home=" + runtimeHome.getAbsolutePath());
                args.add("-Djava.io.tmpdir=" + context.getCacheDir().getAbsolutePath());
                args.add("-Djna.boot.library.path=" + nativeLibDir);
                args.add("-Duser.home=" + gameDir.getAbsolutePath());
                args.add("-Dos.name=Linux");
                args.add("-Xms" + LauncherSettings.getXmsMb(context) + "M");
                args.add("-Xmx" + LauncherSettings.getXmxMb(context) + "M");
                args.add("-cp");
                args.add(String.join(File.pathSeparator, config.classpath));
                args.add(config.mainClass);
                args.addAll(config.gameArgs);

                listener.onLaunching();
                int exitCode = VMLauncher.launchJVM(args.toArray(new String[0]));
                listener.onExit(exitCode);
            } catch (Exception e) {
                listener.onError(e);
            }
        }).start();
    }
}
