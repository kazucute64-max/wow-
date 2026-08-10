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
 * Downloads every asset object a version needs (sounds, textures, language
 * files — everything under Minecraft's "resources" system) into app-private
 * storage under filesDir/assets/objects/<hash[0:2]>/<hash>, matching the
 * same layout the official launcher and PojavLauncher use. Each object's
 * hash doubles as its own SHA-1, so verification works the same way it did
 * for the client jar and libraries.
 *
 * There are often several thousand of these per version, so progress is
 * reported as "how many objects so far" rather than per-file byte counts —
 * more useful for something this numerous.
 */
public class AssetDownloader {

    public interface ProgressListener {
        void onAssetProgress(int current, int total, String hash);
        void onComplete(int downloadedCount, int skippedCount, int failedCount);
        /** Called for each asset that ultimately fails after retries — download continues regardless. */
        void onAssetFailed(Exception e, String hash);
    }

    private static final int MAX_ATTEMPTS_PER_ASSET = 3;

    public static void downloadAll(Context context, List<AssetObject> assets, ProgressListener listener) {
        File objectsRoot = new File(new File(context.getFilesDir(), "assets"), "objects");
        int downloaded = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < assets.size(); i++) {
            AssetObject asset = assets.get(i);
            boolean succeeded = false;
            Exception lastError = null;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_ASSET && !succeeded; attempt++) {
                try {
                    File outFile = new File(objectsRoot, asset.relativeStoragePath());
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Could not create " + parent);
                    }

                    // Already have a verified copy? Skip re-downloading it — this
                    // matters a lot here since asset counts run into the thousands.
                    if (outFile.exists() && asset.hash.equalsIgnoreCase(computeSha1(outFile))) {
                        skipped++;
                        succeeded = true;
                        break;
                    }

                    HttpURLConnection conn = (HttpURLConnection) new URL(asset.downloadUrl()).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    } finally {
                        conn.disconnect();
                    }

                    String actual = computeSha1(outFile);
                    if (!actual.equalsIgnoreCase(asset.hash)) {
                        outFile.delete();
                        throw new IOException("SHA-1 mismatch for asset " + asset.hash +
                                ": got " + actual);
                    }

                    downloaded++;
                    succeeded = true;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < MAX_ATTEMPTS_PER_ASSET) {
                        try {
                            Thread.sleep(500L * attempt); // brief backoff before retrying
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (!succeeded) {
                failed++;
                listener.onAssetFailed(lastError, asset.hash);
            }

            listener.onAssetProgress(i + 1, assets.size(), asset.hash);
        }

        listener.onComplete(downloaded, skipped, failed);
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
