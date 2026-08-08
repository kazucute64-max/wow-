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
 * HONEST CURRENT LIMITATION: this only puts client.jar on the classpath.
 * Real Minecraft also needs every jar under the version's "libraries" array
 * (LWJGL, Guava, Gson, etc.) and the asset index — neither is downloaded
 * yet (that's the next milestone). So right now this will very likely fail
 * fast with a NoClassDefFoundError/ClassNotFoundException once the JVM
 * actually starts loading Minecraft's main class — which is expected and a
 * genuinely useful signal: it means everything up to "the JVM is alive
 * inside our app" is working.
 */
public class GameLauncher {

    public interface LaunchListener {
        void onRuntimeDownloadProgress(long downloaded, long total);
        void onLaunching();
        void onExit(int exitCode);
        void onError(Exception e);
    }

    public static void launch(Context context, VersionEntry entry, File clientJar, LaunchListener listener) {
        new Thread(() -> {
            try {
                VersionManifestFetcher.LaunchInfo meta =
                        VersionManifestFetcher.fetchLaunchInfo(entry.url);

                int javaVersion = RuntimeManager.isVersionAvailable(meta.javaMajorVersion)
                        ? meta.javaMajorVersion
                        : 17; // fall back to a modern runtime for versions we don't have a prebuilt for

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

                // --- Build argv ---
                List<String> args = new ArrayList<>();
                args.add("java"); // argv[0] is the program name per C convention
                args.add("-Djava.home=" + runtimeHome.getAbsolutePath());
                args.add("-Djava.io.tmpdir=" + context.getCacheDir().getAbsolutePath());
                args.add("-Djna.boot.library.path=" + nativeLibDir);
                args.add("-Duser.home=" + gameDir.getAbsolutePath());
                args.add("-Dos.name=Linux");
                args.add("-Xms1024M");
                args.add("-Xmx1024M");
                args.add("-cp");
                args.add(clientJar.getAbsolutePath()); // TODO: append libraries jars once downloaded
                args.add(meta.mainClass);
                // TODO: Minecraft's main() also expects args like --version, --gameDir,
                // --assetsDir, --accessToken, etc. once we have asset/auth support.

                listener.onLaunching();
                int exitCode = VMLauncher.launchJVM(args.toArray(new String[0]));
                listener.onExit(exitCode);
            } catch (Exception e) {
                listener.onError(e);
            }
        }).start();
    }
}
