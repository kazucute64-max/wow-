package com.ourlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.List;

/**
 * Runs the full client-jar + libraries + assets download pipeline as a real
 * Android foreground service, so it survives the thing that was actually
 * causing "downloads keep failing": MainActivity being destroyed mid-download
 * (screen lock, switching apps, the OS reclaiming memory during a
 * multi-thousand-file asset download that can easily run several minutes).
 * The previous approach ran everything on an executor owned by the Activity,
 * which got forcibly shut down in onDestroy() — killing in-flight downloads
 * outright. A foreground service, backed by a wake lock and a persistent
 * notification, is Android's actual intended mechanism for this.
 *
 * MainActivity still gets live progress updates while it's in the
 * foreground, via {@link #setListener}, but the download itself no longer
 * depends on the Activity being alive at all.
 */
public class DownloadService extends Service {

    public interface ProgressListener {
        void onStage(String stage, String message);
        void onProgress(int current, int total, String detail);
        /** jarFile is non-null only when success is true. */
        void onFinished(boolean success, String summary, File jarFile);
    }

    private static volatile ProgressListener listener;

    /** MainActivity calls this in onResume/unsets in onPause — the service runs regardless of whether anyone's listening. */
    public static void setListener(ProgressListener l) {
        listener = l;
    }

    public static void start(Context context, VersionEntry entry) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.putExtra("id", entry.id);
        intent.putExtra("type", entry.type);
        intent.putExtra("url", entry.url);
        ContextCompat.startForegroundService(context, intent);
    }

    private static final String CHANNEL_ID = "ourlauncher_downloads";
    private static final int NOTIF_ID = 1001;

    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Minecraft version file downloads");
            notificationManager.createNotificationChannel(channel);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OurLauncher:DownloadWakeLock");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra("id") == null || intent.getStringExtra("url") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        VersionEntry entry = new VersionEntry(
                intent.getStringExtra("id"),
                intent.getStringExtra("type"),
                intent.getStringExtra("url"));

        startForeground(NOTIF_ID, buildNotification("Starting download for " + entry.id, 0, 0, true));

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(30 * 60 * 1000L); // 30-minute safety cap, released properly on completion regardless
        }

        if (worker == null || !worker.isAlive()) {
            worker = new Thread(() -> runPipeline(entry));
            worker.start();
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service — MainActivity just registers a static listener
    }

    private void runPipeline(VersionEntry entry) {
        try {
            // ---- Client jar ----
            postStage("client", "Downloading client jar: " + entry.id);
            File jarFile = downloadClientBlocking(entry);

            // ---- Libraries ----
            postStage("libraries", "Fetching library list: " + entry.id);
            List<LibraryEntry> libraries = VersionManifestFetcher.fetchLibraries(entry.url);
            int libraryCount = downloadLibrariesBlocking(entry, libraries);

            // ---- Assets ----
            postStage("assets", "Fetching asset list: " + entry.id);
            List<AssetObject> assets = VersionManifestFetcher.fetchAssetObjects(entry.url);
            int[] assetResult = downloadAssetsBlocking(entry, assets); // {downloaded, skipped, failed}

            String summary = "Ready: " + entry.id + " — client jar, " + libraryCount + " libraries, " +
                    (assetResult[0] + assetResult[1]) + " assets verified" +
                    (assetResult[2] > 0 ? " (" + assetResult[2] + " assets failed after retries)" : "") + ".";

            updateNotification(summary, 100, 100, false);
            postFinished(true, summary, jarFile);
        } catch (Exception e) {
            String message = "Download failed: " + e.getMessage();
            updateNotification(message, 0, 0, false);
            postFinished(false, message, null);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(false); // leave the final "Ready"/"Failed" notification visible
            stopSelf();
        }
    }

    private File downloadClientBlocking(VersionEntry entry) throws Exception {
        Object lock = new Object();
        File[] result = new File[1];
        Exception[] error = new Exception[1];

        ClientDownloader.download(this, entry, new ClientDownloader.ProgressListener() {
            @Override
            public void onProgress(long bytesDownloaded, long totalBytes) {
                int pct = totalBytes > 0 ? (int) (100 * bytesDownloaded / totalBytes) : 0;
                updateNotification("Client jar: " + entry.id + " (" + pct + "%)", pct, 100, false);
                postProgress(pct, 100, "Client jar");
            }

            @Override
            public void onComplete(File jarFile) {
                synchronized (lock) { result[0] = jarFile; lock.notifyAll(); }
            }

            @Override
            public void onError(Exception e) {
                synchronized (lock) { error[0] = e; lock.notifyAll(); }
            }
        });

        synchronized (lock) {
            while (result[0] == null && error[0] == null) lock.wait();
        }
        if (error[0] != null) throw error[0];
        return result[0];
    }

    private int downloadLibrariesBlocking(VersionEntry entry, List<LibraryEntry> libraries) throws Exception {
        Object lock = new Object();
        int[] result = new int[1];
        boolean[] done = new boolean[1];
        Exception[] error = new Exception[1];

        LibraryDownloader.downloadAll(this, libraries, new LibraryDownloader.ProgressListener() {
            @Override
            public void onLibraryProgress(int current, int total, String libraryPath, long bd, long bt) {
                updateNotification("Library " + current + "/" + total, current, Math.max(total, 1), false);
                postProgress(current, total, "Library: " + libraryPath);
            }

            @Override
            public void onComplete(int downloadedCount) {
                synchronized (lock) { result[0] = downloadedCount; done[0] = true; lock.notifyAll(); }
            }

            @Override
            public void onError(Exception e, String libraryPath) {
                synchronized (lock) { error[0] = e; done[0] = true; lock.notifyAll(); }
            }
        });

        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        if (error[0] != null) throw error[0];
        return result[0];
    }

    /** Returns {downloadedCount, skippedCount, failedCount}. Individual asset failures don't abort the batch. */
    private int[] downloadAssetsBlocking(VersionEntry entry, List<AssetObject> assets) throws InterruptedException {
        Object lock = new Object();
        int[] result = new int[3];
        boolean[] done = new boolean[1];

        AssetDownloader.downloadAll(this, assets, new AssetDownloader.ProgressListener() {
            @Override
            public void onAssetProgress(int current, int total, String hash) {
                updateNotification("Asset " + current + "/" + total, current, Math.max(total, 1), false);
                postProgress(current, total, "Asset " + current + "/" + total);
            }

            @Override
            public void onComplete(int downloadedCount, int skippedCount, int failedCount) {
                synchronized (lock) {
                    result[0] = downloadedCount;
                    result[1] = skippedCount;
                    result[2] = failedCount;
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override
            public void onAssetFailed(Exception e, String hash) {
                // Individual failures are tolerated (retried internally by AssetDownloader);
                // the aggregate failedCount is surfaced in the final summary instead of
                // aborting the whole batch over one flaky file.
            }
        });

        synchronized (lock) {
            while (!done[0]) lock.wait();
        }
        return result;
    }

    // ---- notification + listener plumbing ----

    private Notification buildNotification(String text, int progress, int max, boolean indeterminate) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("OurLauncher")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        builder.setProgress(max, progress, indeterminate);
        return builder.build();
    }

    private void updateNotification(String text, int progress, int max, boolean indeterminate) {
        notificationManager.notify(NOTIF_ID, buildNotification(text, progress, max, indeterminate));
    }

    private void postStage(String stage, String message) {
        mainHandler.post(() -> { if (listener != null) listener.onStage(stage, message); });
    }

    private void postProgress(int current, int total, String detail) {
        mainHandler.post(() -> { if (listener != null) listener.onProgress(current, total, detail); });
    }

    private void postFinished(boolean success, String summary, File jarFile) {
        mainHandler.post(() -> { if (listener != null) listener.onFinished(success, summary, jarFile); });
    }
}
