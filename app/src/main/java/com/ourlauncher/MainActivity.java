package com.ourlauncher;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // Home tab views
    private TextView homeSelectedVersion;
    private TextView homeStatus;
    private MaterialButton homePlayButton;

    // Versions tab views
    private TextView versionsStatus;
    private ProgressBar versionsProgress;
    private ListView versionsList;

    private final List<VersionEntry> versions = new ArrayList<>();
    private VersionAdapter adapter;
    private VersionEntry selectedEntry;
    private File selectedJarFile;

    private android.content.SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ourlauncher_prefs", MODE_PRIVATE);

        FrameLayout contentFrame = findViewById(R.id.content_frame);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);

        LayoutInflater inflater = LayoutInflater.from(this);
        View homeView = inflater.inflate(R.layout.content_home, contentFrame, false);
        View versionsView = inflater.inflate(R.layout.content_versions, contentFrame, false);
        View accountView = inflater.inflate(R.layout.content_account, contentFrame, false);

        contentFrame.addView(homeView);
        contentFrame.addView(versionsView);
        contentFrame.addView(accountView);
        versionsView.setVisibility(View.GONE);
        accountView.setVisibility(View.GONE);

        // Wire up Home tab
        homeSelectedVersion = homeView.findViewById(R.id.home_selected_version);
        homeStatus = homeView.findViewById(R.id.home_status);
        homePlayButton = homeView.findViewById(R.id.home_play_button);
        homePlayButton.setOnClickListener(v -> onPlayClicked());

        // Wire up Versions tab
        versionsStatus = versionsView.findViewById(R.id.versions_status);
        versionsProgress = versionsView.findViewById(R.id.versions_progress);
        versionsList = versionsView.findViewById(R.id.versions_list);
        adapter = new VersionAdapter(this, versions, this::onVersionSelected);
        versionsList.setAdapter(adapter);

        // Wire up Account tab
        TextView accountStatus = accountView.findViewById(R.id.account_status);
        com.google.android.material.textfield.TextInputEditText accountUsernameInput =
                accountView.findViewById(R.id.account_username_input);
        MaterialButton accountSaveButton = accountView.findViewById(R.id.account_save_button);

        String savedUsername = prefs.getString("local_username", null);
        String savedUuid = prefs.getString("local_uuid", null);
        if (savedUsername != null && savedUuid != null) {
            accountUsernameInput.setText(savedUsername);
            accountStatus.setText("Using local account: " + savedUsername + "\nUUID: " + savedUuid);
        }

        accountSaveButton.setOnClickListener(v -> {
            String typed = accountUsernameInput.getText() != null ? accountUsernameInput.getText().toString() : "";
            LocalAccount account = LocalAccount.create(typed);
            if (account == null) {
                accountStatus.setText("Invalid username — use 3-16 letters, numbers, or underscores.");
                return;
            }
            prefs.edit()
                    .putString("local_username", account.username)
                    .putString("local_uuid", account.uuid)
                    .apply();
            accountStatus.setText("Using local account: " + account.username + "\nUUID: " + account.uuid);
        });

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                homeView.setVisibility(View.VISIBLE);
                versionsView.setVisibility(View.GONE);
                accountView.setVisibility(View.GONE);
                return true;
            } else if (id == R.id.nav_versions) {
                homeView.setVisibility(View.GONE);
                versionsView.setVisibility(View.VISIBLE);
                accountView.setVisibility(View.GONE);
                return true;
            } else if (id == R.id.nav_account) {
                homeView.setVisibility(View.GONE);
                versionsView.setVisibility(View.GONE);
                accountView.setVisibility(View.VISIBLE);
                return true;
            }
            return false;
        });

        loadManifest();
    }

    private void loadManifest() {
        executor.submit(() -> {
            try {
                List<VersionEntry> fetched = VersionManifestFetcher.fetch();
                mainHandler.post(() -> {
                    versions.clear();
                    versions.addAll(fetched);
                    adapter.notifyDataSetChanged();
                    versionsStatus.setText("Found " + fetched.size() +
                            " versions. Tap one to download the client jar.");
                });
            } catch (Exception e) {
                mainHandler.post(() -> versionsStatus.setText("Failed to load manifest: " + e.getMessage()));
            }
        });
    }

    private void onVersionSelected(VersionEntry entry) {
        selectedEntry = entry;
        versionsList.setEnabled(false);
        versionsProgress.setVisibility(View.VISIBLE);
        versionsProgress.setProgress(0);
        versionsStatus.setText("Fetching metadata for " + entry.id + "...");

        homeSelectedVersion.setText(entry.id + " (downloading...)");

        executor.submit(() -> ClientDownloader.download(this, entry, new ClientDownloader.ProgressListener() {
            @Override
            public void onProgress(long bytesDownloaded, long totalBytes) {
                mainHandler.post(() -> {
                    if (totalBytes > 0) {
                        int pct = (int) (100 * bytesDownloaded / totalBytes);
                        versionsProgress.setProgress(pct);
                        versionsStatus.setText("Downloading " + entry.id + ": " +
                                (bytesDownloaded / 1024 / 1024) + "MB / " +
                                (totalBytes / 1024 / 1024) + "MB");
                    } else {
                        versionsStatus.setText("Downloading " + entry.id + ": " +
                                (bytesDownloaded / 1024 / 1024) + "MB");
                    }
                });
            }

            @Override
            public void onComplete(File jarFile) {
                selectedJarFile = jarFile;
                mainHandler.post(() -> {
                    versionsStatus.setText("Client jar verified for " + entry.id +
                            ". Fetching library list...");
                    homeSelectedVersion.setText(entry.id + " — fetching libraries...");
                    homeStatus.setText("Client jar saved at:\n" + jarFile.getAbsolutePath());
                });
                downloadLibraries(entry, jarFile);
            }

            @Override
            public void onError(Exception e) {
                mainHandler.post(() -> {
                    versionsList.setEnabled(true);
                    versionsStatus.setText("Download failed for " + entry.id + ": " + e.getMessage());
                    homeSelectedVersion.setText(entry.id + " — download failed");
                });
            }
        }));
    }

    private void downloadLibraries(VersionEntry entry, File jarFile) {
        executor.submit(() -> {
            try {
                List<LibraryEntry> libraries = VersionManifestFetcher.fetchLibraries(entry.url);

                LibraryDownloader.downloadAll(this, libraries, new LibraryDownloader.ProgressListener() {
                    @Override
                    public void onLibraryProgress(int current, int total, String libraryPath,
                                                   long bytesDownloaded, long bytesTotal) {
                        mainHandler.post(() -> {
                            versionsProgress.setProgress((int) (100.0 * current / Math.max(total, 1)));
                            versionsStatus.setText("Library " + current + "/" + total + ": " + libraryPath);
                        });
                    }

                    @Override
                    public void onComplete(int downloadedCount) {
                        mainHandler.post(() -> {
                            versionsStatus.setText("Libraries verified for " + entry.id +
                                    " (" + downloadedCount + "). Fetching asset list...");
                            homeSelectedVersion.setText(entry.id + " — fetching assets...");
                        });
                        downloadAssets(entry, jarFile, downloadedCount);
                    }

                    @Override
                    public void onError(Exception e, String libraryPath) {
                        mainHandler.post(() -> {
                            versionsList.setEnabled(true);
                            versionsStatus.setText("Library download failed on " + libraryPath +
                                    ": " + e.getMessage());
                            homeSelectedVersion.setText(entry.id + " — library download failed");
                        });
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    versionsList.setEnabled(true);
                    versionsStatus.setText("Failed to fetch library list for " + entry.id + ": " + e.getMessage());
                });
            }
        });
    }

    private void downloadAssets(VersionEntry entry, File jarFile, int libraryCount) {
        executor.submit(() -> {
            try {
                List<AssetObject> assets = VersionManifestFetcher.fetchAssetObjects(entry.url);

                AssetDownloader.downloadAll(this, assets, new AssetDownloader.ProgressListener() {
                    @Override
                    public void onAssetProgress(int current, int total, String hash) {
                        mainHandler.post(() -> {
                            versionsProgress.setProgress((int) (100.0 * current / Math.max(total, 1)));
                            versionsStatus.setText("Asset " + current + "/" + total);
                        });
                    }

                    @Override
                    public void onComplete(int downloadedCount, int skippedCount) {
                        mainHandler.post(() -> {
                            versionsList.setEnabled(true);
                            versionsStatus.setText("Ready: " + entry.id + " — client jar, " +
                                    libraryCount + " libraries, " +
                                    (downloadedCount + skippedCount) + " assets, all verified.");

                            homeSelectedVersion.setText(entry.id + " — ready to play");
                            homeStatus.setText("Client jar saved at:\n" + jarFile.getAbsolutePath());
                            homePlayButton.setEnabled(true);
                        });
                    }

                    @Override
                    public void onError(Exception e, String hash) {
                        mainHandler.post(() -> {
                            versionsList.setEnabled(true);
                            versionsStatus.setText("Asset download failed on " + hash + ": " + e.getMessage());
                            homeSelectedVersion.setText(entry.id + " — asset download failed");
                        });
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    versionsList.setEnabled(true);
                    versionsStatus.setText("Failed to fetch asset list for " + entry.id + ": " + e.getMessage());
                });
            }
        });
    }

    /**
     * Builds the actual classpath + main class + game args from everything
     * downloaded so far, and shows them in a dialog. This doesn't launch
     * anything yet — there's no JVM wired in to hand this off to — but it
     * proves the downloaded pieces genuinely fit together into a coherent
     * launch configuration, which is the direct prerequisite for that.
     */
    private void onPlayClicked() {
        if (selectedEntry == null || selectedJarFile == null) {
            Toast.makeText(this, "Pick and fully download a version first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String username = prefs.getString("local_username", null);
        String uuid = prefs.getString("local_uuid", null);
        if (username == null || uuid == null) {
            Toast.makeText(this, "Set a local account in the Account tab first.", Toast.LENGTH_SHORT).show();
            return;
        }

        VersionEntry entry = selectedEntry;
        File jarFile = selectedJarFile;
        LocalAccount account = LocalAccount.create(username);

        homePlayButton.setEnabled(false);
        homeStatus.setText("Building launch configuration...");

        executor.submit(() -> {
            try {
                VersionManifestFetcher.LaunchInfo launchInfo = VersionManifestFetcher.fetchLaunchInfo(entry.url);
                LaunchConfigBuilder.LaunchConfig config =
                        LaunchConfigBuilder.build(this, entry, jarFile, launchInfo, account);

                mainHandler.post(() -> {
                    homePlayButton.setEnabled(true);
                    showLaunchConfigDialog(entry, config);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    homePlayButton.setEnabled(true);
                    homeStatus.setText("Failed to build launch config: " + e.getMessage());
                });
            }
        });
    }

    private void showLaunchConfigDialog(VersionEntry entry, LaunchConfigBuilder.LaunchConfig config) {
        String message = "Main class:\n" + config.mainClass +
                "\n\nClasspath entries: " + config.classpath.size() +
                "\n\nGame args:\n" + String.join(" ", config.gameArgs) +
                "\n\nThis is a real, valid launch configuration built from everything " +
                "downloaded for " + entry.id + " — client jar, libraries, and assets all " +
                "resolved to real paths on disk. Actually executing it needs a JVM built " +
                "for Android, which isn't wired in yet.";

        new AlertDialog.Builder(this)
                .setTitle("Launch config ready")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
