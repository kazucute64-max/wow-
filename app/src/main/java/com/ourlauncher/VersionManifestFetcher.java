package com.ourlauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches Mojang's real, public version manifest — the same endpoint the
 * official launcher and PojavLauncher both use to know what versions
 * exist and where to download them. No auth required for this part;
 * it's public metadata.
 */
public class VersionManifestFetcher {

    private static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public static List<VersionEntry> fetch() throws IOException {
        String json = httpGetString(MANIFEST_URL);
        List<VersionEntry> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray versions = root.getJSONArray("versions");
            for (int i = 0; i < versions.length(); i++) {
                JSONObject v = versions.getJSONObject(i);
                result.add(new VersionEntry(
                        v.getString("id"),
                        v.getString("type"),
                        v.getString("url")
                ));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse version manifest", e);
        }
        return result;
    }

    /** Reads the per-version JSON and pulls out the client jar download URL + size. */
    public static ClientDownloadInfo fetchClientDownloadInfo(String versionUrl) throws IOException {
        String json = httpGetString(versionUrl);
        try {
            JSONObject root = new JSONObject(json);
            JSONObject client = root.getJSONObject("downloads").getJSONObject("client");
            return new ClientDownloadInfo(
                    client.getString("url"),
                    client.getLong("size"),
                    client.getString("sha1")
            );
        } catch (Exception e) {
            throw new IOException("Failed to parse version metadata", e);
        }
    }

    /**
     * Reads the per-version JSON's "libraries" array — everything besides the
     * client jar itself that needs to be on the classpath to actually run the
     * game (Gson, Netty, Guava, etc). Desktop-only native library entries
     * (Windows/Linux/macOS-specific LWJGL natives) are filtered out here,
     * since none of them apply to Android — those get supplied separately by
     * whatever Android-native LWJGL/GL4ES build the launcher bundles instead.
     */
    public static List<LibraryEntry> fetchLibraries(String versionUrl) throws IOException {
        String json = httpGetString(versionUrl);
        List<LibraryEntry> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray libraries = root.getJSONArray("libraries");
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject lib = libraries.getJSONObject(i);

                if (!isAllowedOnThisPlatform(lib)) continue;
                if (!lib.has("downloads")) continue;

                JSONObject downloads = lib.getJSONObject("downloads");
                if (!downloads.has("artifact")) continue; // classifiers-only (native) entries — skip

                JSONObject artifact = downloads.getJSONObject("artifact");
                result.add(new LibraryEntry(
                        artifact.getString("url"),
                        artifact.getString("path"),
                        artifact.optString("sha1", ""),
                        artifact.optLong("size", -1)
                ));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse libraries list", e);
        }
        return result;
    }

    /**
     * Evaluates a library's "rules" array (if present) against our platform.
     * Mojang's manifest format uses these rules for OS-gated entries — e.g.
     * a library entry that should only be included on Windows or macOS.
     * Per Mojang's own evaluation order: with no rules array, a library is
     * always included. With a rules array present, the default becomes
     * "not allowed" until a matching rule says otherwise, and later matching
     * rules override earlier ones. Since we report as neither "windows",
     * "linux", nor "osx", any rule scoped to one of those simply never
     * matches on Android — which correctly excludes desktop-only entries.
     */
    private static boolean isAllowedOnThisPlatform(JSONObject lib) throws org.json.JSONException {
        if (!lib.has("rules")) return true;

        JSONArray rules = lib.getJSONArray("rules");
        boolean allowed = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            boolean matches = true;

            if (rule.has("os")) {
                JSONObject os = rule.getJSONObject("os");
                if (os.has("name")) {
                    String osName = os.getString("name");
                    if (osName.equals("windows") || osName.equals("linux") || osName.equals("osx")) {
                        matches = false; // none of Mojang's desktop OS names are us
                    }
                }
            }

            if (matches) {
                allowed = rule.getString("action").equals("allow");
            }
        }
        return allowed;
    }

    /**
     * Reads the per-version JSON's "assetIndex" pointer, then fetches that
     * index itself and returns every asset object it lists (sounds,
     * textures, language files, etc — everything under Minecraft's
     * "resources" system). Each object's own hash doubles as its SHA-1,
     * which is also how Mojang's CDN addresses it:
     * https://resources.download.minecraft.net/<hash[0:2]>/<hash>
     */
    public static List<AssetObject> fetchAssetObjects(String versionUrl) throws IOException {
        String versionJson = httpGetString(versionUrl);
        String assetIndexUrl;
        try {
            JSONObject root = new JSONObject(versionJson);
            assetIndexUrl = root.getJSONObject("assetIndex").getString("url");
        } catch (Exception e) {
            throw new IOException("Failed to find assetIndex in version metadata", e);
        }

        String indexJson = httpGetString(assetIndexUrl);
        List<AssetObject> result = new ArrayList<>();
        try {
            JSONObject indexRoot = new JSONObject(indexJson);
            JSONObject objects = indexRoot.getJSONObject("objects");
            java.util.Iterator<String> keys = objects.keys();
            while (keys.hasNext()) {
                String virtualPath = keys.next();
                JSONObject obj = objects.getJSONObject(virtualPath);
                result.add(new AssetObject(
                        obj.getString("hash"),
                        obj.getLong("size")
                ));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse asset index", e);
        }
        return result;
    }


    /**
     * Reads the per-version JSON for everything needed to actually construct
     * a launch: which class to run (mainClass — almost always
     * net.minecraft.client.main.Main for anything reasonably modern), the
     * asset index's own id (used for the game's --assetIndex argument,
     * distinct from the asset index's download URL used in
     * fetchAssetObjects), and which major Java version the version needs
     * (used to pick a matching prebuilt runtime in RuntimeManager).
     */
    public static LaunchInfo fetchLaunchInfo(String versionUrl) throws IOException {
        String json = httpGetString(versionUrl);
        try {
            JSONObject root = new JSONObject(json);
            String mainClass = root.getString("mainClass");
            String assetIndexId = root.getJSONObject("assetIndex").getString("id");
            // Very old versions (pre-1.6ish) have no "javaVersion" field at all — default to 8.
            int javaMajorVersion = root.has("javaVersion")
                    ? root.getJSONObject("javaVersion").getInt("majorVersion")
                    : 8;
            return new LaunchInfo(mainClass, assetIndexId, javaMajorVersion);
        } catch (Exception e) {
            throw new IOException("Failed to parse launch info", e);
        }
    }


    private static String httpGetString(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    public static class LaunchInfo {
        public final String mainClass;
        public final String assetIndexId;
        public final int javaMajorVersion;

        LaunchInfo(String mainClass, String assetIndexId, int javaMajorVersion) {
            this.mainClass = mainClass;
            this.assetIndexId = assetIndexId;
            this.javaMajorVersion = javaMajorVersion;
        }
    }

    public static class ClientDownloadInfo {
        public final String url;
        public final long size;
        public final String sha1;

        ClientDownloadInfo(String url, long size, String sha1) {
            this.url = url;
            this.size = size;
            this.sha1 = sha1;
        }
    }
}
