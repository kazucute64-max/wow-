package com.ourlauncher;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        homePlayButton.setOnClickListener(v -> Toast.makeText(this,
                "Launching isn't implemented yet — this is where the JVM + " +
                        "LWJGL + GL translation pieces plug in next.",
                Toast.LENGTH_LONG).show());

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

        android.content.SharedPreferences prefs = getSharedPreferences("ourlauncher_prefs", MODE_PRIVATE);
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
                            versionsList.setEnabled(true);
                            versionsStatus.setText("Downloaded and verified " + entry.id +
                                    ": client jar + " + downloadedCount + " libraries. Ready.");

                            homeSelectedVersion.setText(entry.id + " — verified (" +
                                    downloadedCount + " libraries)");
                            homeStatus.setText("Client jar saved at:\n" + jarFile.getAbsolutePath());
                            homePlayButton.setEnabled(true);
                        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
