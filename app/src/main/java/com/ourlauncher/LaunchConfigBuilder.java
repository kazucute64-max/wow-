package com.ourlauncher;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles what a real JVM launch would actually need, from everything
 * this launcher has already downloaded and verified: the full classpath
 * (client jar + every library jar), the main class to run, and the game's
 * command-line arguments (account info, version, asset locations).
 *
 * This doesn't execute anything yet — there's no JVM wired in on Android
 * to hand this to. What it does do is prove the pieces we've downloaded
 * actually fit together into a coherent launch, which is the direct
 * prerequisite for wiring in a real JVM later.
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

    public static LaunchConfig build(Context context, VersionEntry entry, File clientJar,
                                      VersionManifestFetcher.LaunchInfo launchInfo,
                                      LocalAccount account) {
        List<String> classpath = new ArrayList<>();
        classpath.add(clientJar.getAbsolutePath());

        File librariesRoot = new File(context.getFilesDir(), "libraries");
        collectJarsRecursively(librariesRoot, classpath);

        File gameDir = new File(context.getFilesDir(), "gamedir/" + entry.id);
        File assetsDir = new File(context.getFilesDir(), "assets");

        List<String> gameArgs = new ArrayList<>();
        gameArgs.add("--username"); gameArgs.add(account.username);
        gameArgs.add("--uuid"); gameArgs.add(account.uuid);
        gameArgs.add("--accessToken"); gameArgs.add("0"); // placeholder — real accounts need a real token here
        gameArgs.add("--version"); gameArgs.add(entry.id);
        gameArgs.add("--gameDir"); gameArgs.add(gameDir.getAbsolutePath());
        gameArgs.add("--assetsDir"); gameArgs.add(assetsDir.getAbsolutePath());
        gameArgs.add("--assetIndex"); gameArgs.add(launchInfo.assetIndexId);
        gameArgs.add("--userType"); gameArgs.add("legacy"); // "legacy" = non-Microsoft/offline-style session

        return new LaunchConfig(classpath, launchInfo.mainClass, gameArgs);
    }

    private static void collectJarsRecursively(File dir, List<String> out) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectJarsRecursively(child, out);
            } else if (child.getName().endsWith(".jar")) {
                out.add(child.getAbsolutePath());
            }
        }
    }
}
