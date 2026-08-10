package com.ourlauncher;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Downloads a version's client.jar into app-private storage:
 * filesDir/versions/<id>/<id>.jar — same layout convention the
 * official launcher and Pojav use under the hood. After downloading,
 * verifies the file's SHA-1 against the hash Mojang's manifest
 * published for it, so a truncated or corrupted download is caught
 * immediately instead of silently producing a jar that won't run.
 */
public class ClientDownloader {

    public interface ProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
        void onComplete(File jarFile);
        void onError(Exception e);
    }

    public static void download(Context context, VersionEntry entry, ProgressListener listener) {
        try {
            VersionManifestFetcher.ClientDownloadInfo info =
                    VersionManifestFetcher.fetchClientDownloadInfo(entry.url);

            File versionDir = new File(new File(context.getFilesDir(), "versions"), entry.id);
            if (!versionDir.exists() && !versionDir.mkdirs()) {
                throw new IOException("Could not create " + versionDir);
            }
            File outFile = new File(versionDir, entry.id + ".jar");

            HttpURLConnection conn = (HttpURLConnection) new URL(info.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            long total = info.size > 0 ? info.size : conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outFile)) {
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

            // Verify integrity before handing the file back as "done."
            if (info.sha1 != null && !info.sha1.isEmpty()) {
                String actualSha1 = computeSha1(outFile);
                if (!actualSha1.equalsIgnoreCase(info.sha1)) {
                    outFile.delete(); // don't leave a corrupt jar sitting around
                    throw new IOException("SHA-1 mismatch for " + entry.id +
                            ": expected " + info.sha1 + " but got " + actualSha1 +
                            " — download was likely corrupted or truncated.");
                }
            }

            listener.onComplete(outFile);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /** Computes the SHA-1 hash of a file's contents as a lowercase hex string. */
    private static String computeSha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
