package com.ourlauncher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    private final List<VersionEntry> allVersions = new ArrayList<>();
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

        // Needed on Android 13+ for the download service's progress notification
        // to actually be visible — the service itself works fine either way,
        // this just affects whether the person sees background progress.
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        FrameLayout contentFrame = findViewById(R.id.content_frame);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);

        LayoutInflater inflater = LayoutInflater.from(this);
        View homeView = inflater.inflate(R.layout.content_home, contentFrame, false);
        View versionsView = inflater.inflate(R.layout.content_versions, contentFrame, false);
        View accountView = inflater.inflate(R.layout.content_account, contentFrame, false);
        View settingsView = inflater.inflate(R.layout.content_settings, contentFrame, false);

        contentFrame.addView(homeView);
        contentFrame.addView(versionsView);
        contentFrame.addView(accountView);
        contentFrame.addView(settingsView);
        versionsView.setVisibility(View.GONE);
        accountView.setVisibility(View.GONE);
        settingsView.setVisibility(View.GONE);

        // Wire up Home tab
        homeSelectedVersion = homeView.findViewById(R.id.home_selected_version);
        homeStatus = homeView.findViewById(R.id.home_status);
        homePlayButton = homeView.findViewById(R.id.home_play_button);
        homePlayButton.setOnClickListener(v -> onPlayClicked());

        MaterialButton diagnosticsButton = homeView.findViewById(R.id.diagnostics_button);
        diagnosticsButton.setOnClickListener(v -> onDiagnosticsClicked(diagnosticsButton));

        // Wire up Versions tab
        versionsStatus = versionsView.findViewById(R.id.versions_status);
        versionsProgress = versionsView.findViewById(R.id.versions_progress);
        versionsList = versionsView.findViewById(R.id.versions_list);
        adapter = new VersionAdapter(this, versions, this::onVersionSelected,
                id -> new File(new File(getFilesDir(), "versions"), id + "/" + id + ".jar").exists());
        versionsList.setAdapter(adapter);

        com.google.android.material.textfield.TextInputEditText versionsSearchInput =
                versionsView.findViewById(R.id.versions_search_input);
        com.google.android.material.switchmaterial.SwitchMaterial releasesOnlySwitch =
                versionsView.findViewById(R.id.versions_releases_only_switch);

        versionsSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString(), releasesOnlySwitch.isChecked());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        releasesOnlySwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyFilter(String.valueOf(versionsSearchInput.getText()), isChecked));

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
                settingsView.setVisibility(View.GONE);
                return true;
            } else if (id == R.id.nav_versions) {
                homeView.setVisibility(View.GONE);
                versionsView.setVisibility(View.VISIBLE);
                accountView.setVisibility(View.GONE);
                settingsView.setVisibility(View.GONE);
                return true;
            } else if (id == R.id.nav_account) {
                homeView.setVisibility(View.GONE);
                versionsView.setVisibility(View.GONE);
                accountView.setVisibility(View.VISIBLE);
                settingsView.setVisibility(View.GONE);
                return true;
            } else if (id == R.id.nav_settings) {
                homeView.setVisibility(View.GONE);
                versionsView.setVisibility(View.GONE);
                accountView.setVisibility(View.GONE);
                settingsView.setVisibility(View.VISIBLE);
                return true;
            }
            return false;
        });

        setUpSettingsTab(settingsView);

        loadManifest();
    }

    private void setUpSettingsTab(View settingsView) {
        com.google.android.material.textfield.TextInputEditText xmsInput =
                settingsView.findViewById(R.id.settings_xms_input);
        com.google.android.material.textfield.TextInputEditText xmxInput =
                settingsView.findViewById(R.id.settings_xmx_input);
        android.widget.RadioGroup javaVersionGroup = settingsView.findViewById(R.id.settings_java_version_group);
        android.widget.RadioButton javaAuto = settingsView.findViewById(R.id.settings_java_auto);
        android.widget.RadioButton java17 = settingsView.findViewById(R.id.settings_java_17);
        android.widget.RadioButton java21 = settingsView.findViewById(R.id.settings_java_21);
        MaterialButton saveButton = settingsView.findViewById(R.id.settings_save_button);
        TextView storageSummary = settingsView.findViewById(R.id.settings_storage_summary);
        MaterialButton clearButton = settingsView.findViewById(R.id.settings_clear_button);

        xmsInput.setText(String.valueOf(LauncherSettings.getXmsMb(this)));
        xmxInput.setText(String.valueOf(LauncherSettings.getXmxMb(this)));
        int javaOverride = LauncherSettings.getJavaVersionOverride(this);
        if (javaOverride == 17) java17.setChecked(true);
        else if (javaOverride == 21) java21.setChecked(true);
        else javaAuto.setChecked(true);

        saveButton.setOnClickListener(v -> {
            int xms, xmx;
            try {
                xms = Integer.parseInt(String.valueOf(xmsInput.getText()));
                xmx = Integer.parseInt(String.valueOf(xmxInput.getText()));
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Enter valid numbers for memory.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (xms <= 0 || xmx <= 0 || xms > xmx) {
                Toast.makeText(this, "Min must be positive and not greater than max.", Toast.LENGTH_SHORT).show();
                return;
            }

            int checkedId = javaVersionGroup.getCheckedRadioButtonId();
            int versionOverride = checkedId == R.id.settings_java_17 ? 17
                    : checkedId == R.id.settings_java_21 ? 21 : 0;

            LauncherSettings.save(this, xms, xmx, versionOverride);
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
        });

        refreshStorageSummary(storageSummary);

        clearButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear all downloaded files?")
                .setMessage("Deletes every downloaded version, library, asset, and installed Java " +
                        "runtime. Your local account and these settings are kept. You'll need to " +
                        "re-download versions before playing again.")
                .setPositiveButton("Clear", (dialog, which) -> clearAllDownloadedFiles(storageSummary))
                .setNegativeButton("Cancel", null)
                .show());

        MaterialButton viewCrashLogButton = settingsView.findViewById(R.id.settings_view_crash_log_button);
        viewCrashLogButton.setOnClickListener(v -> {
            String log = CrashHandler.readLastCrashLog(this);
            if (log == null) {
                Toast.makeText(this, "No crash log found.", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Last crash log")
                    .setMessage(log)
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    private void refreshStorageSummary(TextView storageSummary) {
        executor.submit(() -> {
            long versionsSize = dirSize(new File(getFilesDir(), "versions"));
            long librariesSize = dirSize(new File(getFilesDir(), "libraries"));
            long assetsSize = dirSize(new File(getFilesDir(), "assets"));
            long runtimesSize = dirSize(new File(getFilesDir(), "runtimes"));
            long totalMb = (versionsSize + librariesSize + assetsSize + runtimesSize) / 1024 / 1024;

            String summary = "Versions: " + (versionsSize / 1024 / 1024) + "MB  •  " +
                    "Libraries: " + (librariesSize / 1024 / 1024) + "MB  •  " +
                    "Assets: " + (assetsSize / 1024 / 1024) + "MB  •  " +
                    "Java runtimes: " + (runtimesSize / 1024 / 1024) + "MB\nTotal: " + totalMb + "MB";

            mainHandler.post(() -> storageSummary.setText(summary));
        });
    }

    private long dirSize(File dir) {
        if (!dir.exists()) return 0;
        long size = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            size += child.isDirectory() ? dirSize(child) : child.length();
        }
        return size;
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void clearAllDownloadedFiles(TextView storageSummary) {
        executor.submit(() -> {
            deleteRecursive(new File(getFilesDir(), "versions"));
            deleteRecursive(new File(getFilesDir(), "libraries"));
            deleteRecursive(new File(getFilesDir(), "assets"));
            deleteRecursive(new File(getFilesDir(), "runtimes"));

            mainHandler.post(() -> {
                Toast.makeText(this, "Cleared. Re-download versions from the Versions tab.", Toast.LENGTH_LONG).show();
                refreshStorageSummary(storageSummary);
                selectedJarFile = null;
                homePlayButton.setEnabled(false);
                homeSelectedVersion.setText("None yet — pick one in Versions");
                homeStatus.setText("");
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Live progress while we're in the foreground. The download itself keeps
        // running in DownloadService regardless of whether anyone's listening —
        // that's the whole point: it no longer depends on this Activity being alive.
        DownloadService.setListener(new DownloadService.ProgressListener() {
            @Override
            public void onStage(String stage, String message) {
                mainHandler.post(() -> {
                    versionsStatus.setText(message);
                    homeSelectedVersion.setText(selectedEntry != null
                            ? selectedEntry.id + " — " + stage + "..." : message);
                });
            }

            @Override
            public void onProgress(int current, int total, String detail) {
                mainHandler.post(() -> {
                    versionsProgress.setProgress((int) (100.0 * current / Math.max(total, 1)));
                    versionsStatus.setText(detail + " (" + current + "/" + total + ")");
                });
            }

            @Override
            public void onFinished(boolean success, String summary, File jarFile) {
                mainHandler.post(() -> {
                    versionsList.setEnabled(true);
                    versionsStatus.setText(summary);
                    if (success) {
                        selectedJarFile = jarFile;
                        homeSelectedVersion.setText(
                                (selectedEntry != null ? selectedEntry.id : "") + " — ready to play");
                        homeStatus.setText(summary);
                        homePlayButton.setEnabled(true);
                        adapter.notifyDataSetChanged(); // shows the "Downloaded" indicator immediately
                    } else {
                        homeSelectedVersion.setText(
                                (selectedEntry != null ? selectedEntry.id : "") + " — download failed");
                        homeStatus.setText(summary);
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop updating UI we're about to leave — the service keeps running regardless.
        DownloadService.setListener(null);
    }

    private void loadManifest() {
        executor.submit(() -> {
            try {
                List<VersionEntry> fetched = VersionManifestFetcher.fetch();
                mainHandler.post(() -> {
                    allVersions.clear();
                    allVersions.addAll(fetched);
                    applyFilter("", true); // matches the switch's default checked state
                    versionsStatus.setText("Found " + fetched.size() +
                            " versions. Tap one to download the client jar.");
                });
            } catch (Exception e) {
                mainHandler.post(() -> versionsStatus.setText("Failed to load manifest: " + e.getMessage()));
            }
        });
    }

    private void applyFilter(String query, boolean releasesOnly) {
        String needle = query == null ? "" : query.trim().toLowerCase();
        versions.clear();
        for (VersionEntry entry : allVersions) {
            if (releasesOnly && !"release".equals(entry.type)) continue;
            if (!needle.isEmpty() && !entry.id.toLowerCase().contains(needle)) continue;
            versions.add(entry);
        }
        adapter.notifyDataSetChanged();
    }

    private void onVersionSelected(VersionEntry entry) {
        selectedEntry = entry;
        selectedJarFile = null;
        homePlayButton.setEnabled(false);
        versionsList.setEnabled(false);
        versionsProgress.setVisibility(View.VISIBLE);
        versionsProgress.setProgress(0);
        versionsStatus.setText("Starting download for " + entry.id + "...");
        homeSelectedVersion.setText(entry.id + " (starting...)");

        // Runs in DownloadService from here — survives this Activity being
        // backgrounded, rotated, or killed by the OS mid-download.
        DownloadService.start(this, entry);
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

    /**
     * Safely tests whether the bundled native libraries (exithook,
     * linkerhook, pojavexec, pojavexec_awt) can actually load on this
     * device, without going anywhere near a real launch attempt.
     *
     * Loading them happens in JREUtils's static initializer, so merely
     * referencing the class (Class.forName) is enough to trigger it. This
     * catches Throwable, not just Exception, because a failed
     * System.loadLibrary surfaces as UnsatisfiedLinkError — an Error, not
     * an Exception — and a static-init failure specifically wraps it in
     * ExceptionInInitializerError. Neither would be caught by a plain
     * catch (Exception e).
     *
     * What this can't protect against: a genuine native crash (SIGSEGV
     * from an ABI mismatch, say) kills the process outright and no amount
     * of Java try/catch prevents that. But "library not found" and
     * "missing symbol" — the far more likely failure modes for a first
     * attempt — surface cleanly as catchable errors, which is exactly
     * what this is checking for.
     */
    private void onDiagnosticsClicked(MaterialButton button) {
        button.setEnabled(false);
        button.setText("Testing...");

        executor.submit(() -> {
            String result;
            boolean success;
            try {
                Class.forName("net.kdt.pojavlaunch.utils.JREUtils");
                success = true;
                result = "Native libraries loaded successfully:\n\n" +
                        "libexithook.so\nliblinkerhook.so\nlibpojavexec.so\nlibpojavexec_awt.so\n\n" +
                        "This confirms the JNI bridge is loadable on this specific device/ABI. " +
                        "It does NOT confirm a full launch would work — that needs a JVM, correct " +
                        "classpath, and an EGL surface, none of which this test touches.";
            } catch (Throwable t) {
                success = false;
                result = "Native library load failed:\n\n" +
                        t.getClass().getSimpleName() + ": " + t.getMessage() +
                        (t.getCause() != null ? "\nCaused by: " + t.getCause() : "") +
                        "\n\nThis usually means either a missing .so for this device's actual " +
                        "CPU architecture, or a symbol mismatch between the prebuilt library and " +
                        "the exact class name/package expected (see JREUtils's class comment).";
            }

            final boolean finalSuccess = success;
            final String finalResult = result;
            mainHandler.post(() -> {
                button.setEnabled(true);
                button.setText("Test native libraries");
                new AlertDialog.Builder(this)
                        .setTitle(finalSuccess ? "Native libraries: OK" : "Native libraries: FAILED")
                        .setMessage(finalResult)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private void showLaunchConfigDialog(VersionEntry entry, LaunchConfigBuilder.LaunchConfig config) {
        String message = "Main class:\n" + config.mainClass +
                "\n\nClasspath entries: " + config.classpath.size() +
                "\n\nGame args:\n" + String.join(" ", config.gameArgs) +
                "\n\nThis is a real, valid launch configuration built from everything " +
                "downloaded for " + entry.id + " — client jar, libraries, and assets all " +
                "resolved to real paths on disk.";

        new AlertDialog.Builder(this)
                .setTitle("Launch config ready")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNegativeButton("Attempt real launch (experimental)", (dialog, which) ->
                        confirmRealLaunch(entry))
                .show();
    }

    /**
     * A real launch attempt has never been run on a device before this
     * point in the project — every step here (JRE install, native library
     * loading, the JVM actually starting Minecraft's main class) is a
     * separate, untested point of failure. This confirmation exists so
     * nobody triggers it by accident; it is NOT a substitute for running
     * the Diagnostics "Test native libraries" check first, which is safer
     * to fail than this is.
     */
    private void confirmRealLaunch(VersionEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("This hasn't been tested yet")
                .setMessage("Attempting a real launch runs actual native code (the JVM, the " +
                        "JNI bridge) for the first time. If something goes wrong at the native " +
                        "level rather than the Java level, this could crash the whole app with " +
                        "no dialog or error message — just a crash. Run the Diagnostics \"Test " +
                        "native libraries\" check on the Home tab first if you haven't already; " +
                        "that fails safely, this might not.\n\nContinue anyway?")
                .setPositiveButton("Launch", (dialog, which) -> startRealLaunch(entry))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startRealLaunch(VersionEntry entry) {
        String username = prefs.getString("local_username", null);
        LocalAccount account = LocalAccount.create(username);
        if (account == null || selectedJarFile == null) {
            Toast.makeText(this, "Missing account or downloaded jar — this shouldn't happen here.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        homeStatus.setText("Starting launch for " + entry.id + "...");

        GameLauncher.launch(this, entry, selectedJarFile, account, new GameLauncher.LaunchListener() {
            @Override
            public void onRuntimeDownloadProgress(long downloaded, long total) {
                mainHandler.post(() -> {
                    if (total > 0) {
                        int pct = (int) (100 * downloaded / total);
                        homeStatus.setText("Downloading Java runtime: " + pct + "%");
                    } else {
                        homeStatus.setText("Downloading Java runtime: " + (downloaded / 1024 / 1024) + "MB");
                    }
                });
            }

            @Override
            public void onLaunching() {
                mainHandler.post(() -> homeStatus.setText("Launching JVM for " + entry.id + "..."));
            }

            @Override
            public void onExit(int exitCode) {
                mainHandler.post(() -> {
                    homeStatus.setText("Process exited with code " + exitCode);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(exitCode == 0 ? "Exited normally" : "Exited with an error")
                            .setMessage("The JVM process exited with code " + exitCode + ".\n\n" +
                                    (exitCode == 0
                                            ? "A clean exit this early likely means the JVM started " +
                                              "and stopped quickly (e.g. no display available yet) " +
                                              "rather than a full successful game session — there's " +
                                              "no rendering surface wired in yet."
                                            : "A non-zero exit is expected at this stage — this is " +
                                              "the first real attempt. Check logcat for the actual " +
                                              "stack trace/error if you have Android Studio or adb " +
                                              "available; this dialog only sees the exit code."))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }

            @Override
            public void onError(Exception e) {
                mainHandler.post(() -> {
                    String detail = e.getClass().getSimpleName() + ": " + e.getMessage() +
                            (e.getCause() != null ? "\nCaused by: " + e.getCause() : "");
                    homeStatus.setText("Launch failed: " + e.getMessage());
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Launch failed")
                            .setMessage(detail)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow(); // safe now — this only ever held quick, short-lived work (manifest fetch, launch config build), never the long downloads
    }
}
