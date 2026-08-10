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
import java.util.List;

/**
 * Downloads every library jar a version's classpath needs (everything from
 * VersionManifestFetcher.fetchLibraries, already filtered down to
 * Android-relevant entries) into app-private storage under
 * filesDir/libraries/<path-from-manifest>. Each file is SHA-1 verified,
 * same as ClientDownloader does for the client jar itself.
 */
public class LibraryDownloader {

    public interface ProgressListener {
        /** current is 1-based; total is how many libraries are being fetched overall. */
        void onLibraryProgress(int current, int total, String libraryPath,
                                long bytesDownloaded, long bytesTotal);
        void onComplete(int downloadedCount);
        void onError(Exception e, String libraryPath);
    }

    public static void downloadAll(Context context, List<LibraryEntry> libraries, ProgressListener listener) {
        File librariesRoot = new File(context.getFilesDir(), "libraries");

        for (int i = 0; i < libraries.size(); i++) {
            LibraryEntry lib = libraries.get(i);
            try {
                File outFile = new File(librariesRoot, lib.path);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent);
                }

                // Already have a verified copy on disk? Don't re-download it.
                if (outFile.exists() && lib.sha1 != null && !lib.sha1.isEmpty()
                        && lib.sha1.equalsIgnoreCase(computeSha1(outFile))) {
                    listener.onLibraryProgress(i + 1, libraries.size(), lib.path, lib.size, lib.size);
                    continue;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(lib.url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                long total = lib.size > 0 ? lib.size : conn.getContentLengthLong();
                long downloaded = 0;

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        listener.onLibraryProgress(i + 1, libraries.size(), lib.path, downloaded, total);
                    }
                } finally {
                    conn.disconnect();
                }

                if (lib.sha1 != null && !lib.sha1.isEmpty()) {
                    String actual = computeSha1(outFile);
                    if (!actual.equalsIgnoreCase(lib.sha1)) {
                        outFile.delete();
                        throw new IOException("SHA-1 mismatch for " + lib.path +
                                ": expected " + lib.sha1 + " but got " + actual);
                    }
                }
            } catch (Exception e) {
                listener.onError(e, lib.path);
                return; // classpath needs to be complete, so stop at the first failure
            }
        }

        listener.onComplete(libraries.size());
    }

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
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
