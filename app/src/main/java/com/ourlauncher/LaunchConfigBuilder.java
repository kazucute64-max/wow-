package com.ourlauncher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles what a real JVM launch would actually need, from everything
 * this launcher has already downloaded and verified: the full classpath
 * (client jar + every downloaded library jar), the main class to run, and
 * the game's command-line arguments (account info, version, asset
 * locations).
 *
 * This doesn't execute anything yet — there's no JVM wired in on Android
 * to hand this off to. What it does do is prove the pieces already
 * downloaded and verified (client jar, libraries, assets) genuinely fit
 * together into a coherent launch configuration, which is the direct
 * prerequisite for wiring in a real JVM later.
 *
 * NOTE: rebuilt from the interface MainActivity and VersionManifestFetcher
 * already expected (LaunchConfig.mainClass/classpath/gameArgs, and the
 * build(...) signature below) after the original copy of this file was
 * accidentally deleted mid-session. If anything here doesn't match
 * behavior you remember from before, that's why — treat this version as
 * freshly reconstructed and worth double-checking rather than assumed
 * identical to whatever came before.
 */
public class LaunchConfigBuilder {

    public static class LaunchConfig {
        public final List<String> classpath;
        public final String mainClass;
        public final List<String> gameArgs;

        LaunchConfig(List<String> classpath, String mainClass, List<String> gameArgs) {
            this.classpath = classpath;
            this.mainClass = mainClass;
            this.gameArgs = gameArgs;
        }
    }

    public static LaunchConfig build(Context context, VersionEntry entry, File clientJarFile,
                                      VersionManifestFetcher.LaunchInfo launchInfo,
                                      LocalAccount account) throws Exception {

        List<String> classpath = new ArrayList<>();
        classpath.add(clientJarFile.getAbsolutePath());

        File librariesRoot = new File(context.getFilesDir(), "libraries");
        collectJars(librariesRoot, classpath);

        File assetsDir = new File(context.getFilesDir(), "assets");
        File gameDir = new File(context.getFilesDir(), "gamedir");
        if (!gameDir.exists()) //noinspection ResultOfMethodCallIgnored
            gameDir.mkdirs();

        List<String> gameArgs = new ArrayList<>();
        gameArgs.add("--username"); gameArgs.add(account.username);
        gameArgs.add("--version"); gameArgs.add(entry.id);
        gameArgs.add("--gameDir"); gameArgs.add(gameDir.getAbsolutePath());
        gameArgs.add("--assetsDir"); gameArgs.add(assetsDir.getAbsolutePath());
        gameArgs.add("--assetIndex"); gameArgs.add(launchInfo.assetIndexId);
        gameArgs.add("--uuid"); gameArgs.add(account.uuid);
        // Local/offline accounts have no real Microsoft session — these are
        // conventional placeholder values, same approach every offline-mode
        // launcher uses; this is not a substitute for real auth where real
        // auth is actually required.
        gameArgs.add("--accessToken"); gameArgs.add("0");
        gameArgs.add("--userType"); gameArgs.add("legacy");
        gameArgs.add("--versionType"); gameArgs.add(entry.type);

        return new LaunchConfig(classpath, launchInfo.mainClass, gameArgs);
    }

    private static void collectJars(File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJars(child, out);
            } else if (child.getName().endsWith(".jar")) {
                out.add(child.getAbsolutePath());
            }
        }
    }
}
