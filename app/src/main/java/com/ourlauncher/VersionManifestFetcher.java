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
