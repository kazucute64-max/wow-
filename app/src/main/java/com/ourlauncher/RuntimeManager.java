package com.ourlauncher;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Downloads and unpacks a Java runtime for actually launching the game jar.
 *
 * Source: AngelAuraMC's prebuilt Android JRE builds (the actively maintained
 * PojavLauncher fork), from the "angelauramc-openjdk-build" repo's GitHub
 * Releases (the "download" tag).
 *
 * IMPORTANT: the exact asset filenames on that release were NOT hand-verified
 * against a live browser check (automated access hit both a GitHub API rate
 * limit and a robots.txt block while building this). Rather than hard-code a
 * guessed filename pattern that could silently 404, this resolves the real
 * download URL at runtime by querying GitHub's Releases API and pattern
 * matching against whatever assets actually exist — self-correcting if the
 * exact naming scheme differs from what was assumed, and the error message
 * lists every available asset name if nothing matches, so a failure here is
 * immediately diagnosable instead of a mysterious 404.
 *
 * Layout convention (matches Pojav's MultiRT so we can reuse their mental
 * model): filesDir/runtimes/<name>/... with a "bin/java" executable inside.
 *
 * NOTE: only Java 17 and 21 are currently exposed via
 * {@link #isVersionAvailable(int)}. x86 (32-bit) devices are not supported
 * for Java 21+, matching upstream's own restriction.
 */
public class RuntimeManager {

    public interface ProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
        void onComplete(File runtimeHome);
        void onError(Exception e);
    }

    private static final int[] AVAILABLE_JAVA_VERSIONS = {17, 21};
    private static final String RELEASE_API_URL =
            "https://api.github.com/repos/AngelAuraMC/angelauramc-openjdk-build/releases/tags/download";

    public static boolean isVersionAvailable(int majorVersion) {
        for (int v : AVAILABLE_JAVA_VERSIONS) if (v == majorVersion) return true;
        return false;
    }

    /**
     * Queries GitHub's Releases API for the "download" release and finds the
     * asset whose filename mentions both this Java major version and this
     * device's architecture string. Throws with the full list of available
     * asset names if nothing matches, so a naming-scheme mismatch is
     * immediately visible instead of a bare 404.
     */
    private static String resolveJreDownloadUrl(int majorVersion, String archString) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASE_API_URL).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "OurLauncher-App");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        String json;
        try (InputStream in = conn.getInputStream()) {
            json = readAll(in);
        } catch (IOException e) {
            throw new IOException("Could not reach GitHub to look up the JRE release " +
                    "(rate-limited, offline, or the release moved): " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }

        JSONObject release;
        JSONArray assets;
        try {
            release = new JSONObject(json);
            assets = release.getJSONArray("assets");
        } catch (Exception e) {
            throw new IOException("Unexpected response looking up the JRE release: " + json, e);
        }

        String needleVersion = "jre" + majorVersion;
        String needleArch = archString.toLowerCase();
        StringBuilder availableNames = new StringBuilder();

        for (int i = 0; i < assets.length(); i++) {
            try {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                availableNames.append(name).append(", ");
                if (name.toLowerCase().contains(needleVersion) && name.toLowerCase().contains(needleArch)) {
                    return asset.getString("browser_download_url");
                }
            } catch (Exception ignored) {
                // malformed single asset entry — skip it, keep checking the rest
            }
        }

        throw new IOException("No JRE " + majorVersion + " build found for architecture \"" + archString +
                "\" in the release. Available assets were: " + availableNames);
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }


    /** @return the folder a runtime named e.g. "External-17" would live in, or null if not installed. */
    public static File getInstalledRuntimeHome(Context context, int majorVersion) {
        File dir = new File(runtimesRoot(context), "External-" + majorVersion);
        return dir.exists() ? dir : null;
    }

    /** @return the java executable inside an installed runtime, or null if missing. */
    public static File getJavaBinary(File runtimeHome) {
        File bin = new File(runtimeHome, "bin/java");
        return bin.exists() ? bin : null;
    }

    private static File runtimesRoot(Context context) {
        return new File(context.getFilesDir(), "runtimes");
    }

    /**
     * Downloads + extracts the given Java major version's runtime for this
     * device's architecture. Safe to call even if a version is already
     * installed elsewhere — this always re-downloads into a fresh folder
     * (call getInstalledRuntimeHome first if you want to skip re-downloading).
     */
    public static void install(Context context, int majorVersion, ProgressListener listener) {
        if (!isVersionAvailable(majorVersion)) {
            listener.onError(new IllegalArgumentException(
                    "Java " + majorVersion + " is not one of the available prebuilt runtimes " +
                    "(available: 17, 21)."));
            return;
        }

        String archString = Architecture.getJreArchString();
        if (archString.equals("x86") && majorVersion >= 21) {
            listener.onError(new IllegalArgumentException(
                    "Java " + majorVersion + " has no 32-bit x86 build available."));
            return;
        }

        try {
            File runtimesRoot = runtimesRoot(context);
            if (!runtimesRoot.exists() && !runtimesRoot.mkdirs()) {
                throw new IOException("Could not create " + runtimesRoot);
            }

            File cacheFile = new File(context.getCacheDir(),
                    "jre" + majorVersion + "-android-" + archString + ".tar.xz");

            String url = resolveJreDownloadUrl(majorVersion, archString);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            long total = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloaded += read;
                    listener.onProgress(downloaded, total);
                }
            } finally {
                conn.disconnect();
            }

            File destDir = new File(runtimesRoot, "External-" + majorVersion);
            if (destDir.exists()) deleteRecursive(destDir);
            extractTarXz(cacheFile, destDir);
            cacheFile.delete();

            // Old JRE8-style builds ship some libraries as .pack files that need
            // libunpack200.so to restore into real .jar files. 17/21 builds
            // generally don't need this, but we check just in case — it's a
            // no-op if there's nothing to unpack.
            unpack200IfNeeded(context, destDir);

            makeJavaBinaryExecutable(destDir);

            listener.onComplete(destDir);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private static void extractTarXz(File tarXzFile, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Could not create " + destDir);
        }
        try (InputStream fileIn = new java.io.FileInputStream(tarXzFile);
             XZCompressorInputStream xzIn = new XZCompressorInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                // Guard against zip-slip style path traversal from a malformed archive.
                if (!outFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)
                        && !outFile.getCanonicalPath().equals(destDir.getCanonicalPath())) {
                    throw new IOException("Archive entry escapes destination: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Could not create dir " + outFile);
                    }
                    continue;
                }

                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create dir " + parent);
                }

                try (FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = tarIn.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                // Preserve the executable bit for things like bin/java.
                if ((entry.getMode() & 0111) != 0) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.setExecutable(true, false);
                }
            }
        }
    }

    private static void unpack200IfNeeded(Context context, File runtimeHome) {
        File[] packFiles = findPackFiles(runtimeHome);
        if (packFiles.length == 0) return;

        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File unpacker = new File(nativeLibDir, "libunpack200.so");
        if (!unpacker.exists()) return; // nothing we can do without it, silently skip

        for (File packFile : packFiles) {
            try {
                String jarPath = packFile.getAbsolutePath().replace(".pack", "");
                Process process = new ProcessBuilder()
                        .directory(new File(nativeLibDir))
                        .command("./libunpack200.so", "-r", packFile.getAbsolutePath(), jarPath)
                        .start();
                process.waitFor();
            } catch (IOException | InterruptedException ignored) {
                // Best-effort — a failed unpack here just means that one jar
                // stays packed, which will surface as a normal launch error later.
            }
        }
    }

    private static File[] findPackFiles(File root) {
        java.util.ArrayList<File> found = new java.util.ArrayList<>();
        collectPackFiles(root, found);
        return found.toArray(new File[0]);
    }

    private static void collectPackFiles(File dir, java.util.List<File> found) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectPackFiles(child, found);
            else if (child.getName().endsWith(".pack")) found.add(child);
        }
    }

    private static void makeJavaBinaryExecutable(File runtimeHome) {
        File javaBin = new File(runtimeHome, "bin/java");
        if (javaBin.exists()) {
            //noinspection ResultOfMethodCallIgnored
            javaBin.setExecutable(true, false);
        }
    }

    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
