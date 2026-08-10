# OurLauncher

An Android launcher for Minecraft: Java Edition, built from scratch —
inspired by PojavLauncher's approach, not a fork of it. This README is
kept honest and current: it reflects what actually works today, not the
eventual vision.

**Everything — the working launcher pieces, the local account system,
and the PojavLauncher-derived runtime scaffolding — now lives together
in this one project/zip.** Earlier in development some of this was
split across separate uploads; that's no longer the case.

## What's real and working right now

- **Live version manifest.** Fetches Mojang's actual public
  `version_manifest_v2.json` and lists every current Minecraft version
  (release + snapshot). Search box filters by name; "Releases only"
  switch (on by default) hides the couple hundred snapshot entries
  that otherwise dominate the list.
- **Real, verified client jar downloads.** Downloads the selected
  version's `client.jar`, computes its SHA-1, and compares it against
  the hash Mojang's manifest publishes. Corrupted/truncated downloads
  are deleted automatically instead of silently kept.
- **Real, verified library downloads.** Parses the version JSON's
  `libraries` array, filters out Windows/Linux/macOS-only native
  entries (irrelevant on Android — see "Known gaps" below), downloads
  every remaining jar, and SHA-1 verifies each one. Already-verified
  files on disk are skipped on repeat downloads.
- **Real, verified asset downloads.** Fetches the version's asset index
  and downloads every sound/texture/language file it lists into
  `filesDir/assets/objects/<hash prefix>/<hash>`, verified the same way.
  This is the largest step (thousands of small files per version) and
  the slowest to run.
- **A local (offline-mode) account system.** Type a username, get a
  deterministic UUID generated the same way Minecraft's own offline
  mode does (`UUID.nameUUIDFromBytes("OfflinePlayer:" + username)`).
  Persisted across app restarts via `SharedPreferences`. This is *not*
  a piracy bypass — it's the same offline-account mechanism vanilla
  Minecraft and every major third-party launcher support for local/LAN
  testing. It doesn't grant access to anything requiring a real,
  paid account.
- **Material 3 UI** with four tabs: Home (selected version + Play
  button + native library diagnostics), Versions (the real manifest
  list as cards, with a "Downloaded" indicator on versions you already
  have — search box; "Releases only" switch, on by default), Account (local account management), Settings (JVM
  memory allocation, Java runtime override, storage management, crash
  log viewer).
- **App-wide crash handler.** Catches uncaught Java exceptions anywhere
  in the app and saves the full stack trace to
  `filesDir/crash_logs/latest.txt`, viewable from Settings → "View
  last crash log." Added specifically because the "attempt real
  launch" path runs genuinely untested native code — this exists to
  leave a trail if it goes wrong. Can't catch a true native crash
  (SIGSEGV) — only `adb logcat` sees those.
- **CI build pipeline** on GitHub Actions, building a debug APK on
  every push. Stable on AGP 8.7.2 + Gradle 8.9 — see git history for
  the AGP/Gradle version mismatches that had to be fixed to get here;
  don't casually bump either without checking compatibility first.

Run this on a real device and it will list genuinely current Minecraft
versions, and download and verify genuinely real, complete game files
for a selected version — client jar, every needed library, every asset.

## What's built but NOT wired in yet

- **`MicrosoftAuth.java`** — a complete, real OAuth2 device-code-flow
  implementation (Microsoft login → Xbox Live → XSTS → Minecraft
  Services → profile fetch). It's sitting in the codebase, but the
  Account tab currently uses the local-account system instead.
  Swapping it in requires registering a free Azure AD app (see the
  comment at the top of that file for exact steps) and rewiring the
  Account tab's button to call `MicrosoftAuth.login(...)` instead of
  `LocalAccount.create(...)`.
- **PojavLauncher-derived runtime scaffolding** — `GameLauncher.java`,
  `RuntimeManager.java`, `VMLauncher.java`, `JREUtils.java`, and a set
  of real PojavLauncher native `.so` libraries (`libpojavexec.so`,
  `liblwjgl.so`, `libgl4es_114.so`, etc.) exist in this repo from an
  earlier session. **None of it is currently called from
  `MainActivity`.** It's reference material / a head start, not
  functioning code yet — treat any claim that "the launcher can start
  a JRE" or similar as unverified until someone actually traces through
  and tests that path.
  `RuntimeManager.java` has had one concrete fix applied: its JRE
  download URL is no longer hard-coded (the original guessed filename
  pattern didn't match the real release's tag name), it's now resolved
  dynamically via GitHub's Releases API at runtime, with a
  self-diagnosing error message if the matching logic still needs
  adjustment.

## What's NOT done at all — the actual hard part

Getting from "downloads real game files" to "actually launches
Minecraft" needs, roughly in order of when you'd hit them:

1. **A real JRE for Android**, downloaded and extracted somewhere
   `RuntimeManager` can find it. Whether `RuntimeManager`'s current URLs
   even point at valid, non-expired sources hasn't been verified.
2. **Native library loading in the right order** with the right
   environment variables — this is usually the single trickiest part
   of the whole project; real PojavLauncher's own `JREUtils` has years
   of accumulated edge-case handling for exactly this.
3. **Correct JVM argument construction** — classpath (client jar +
   every downloaded library, now that we actually have them),
   natives path, main class, `--username`/`--uuid`/`--version`/etc.
   `GameLauncher` sketches this but it's unverified.
4. **An actual rendering surface.** See the separate `GLCompatDemo`
   project in this same workspace — it's a small, working, *verified*
   proof of the core GL4ES trick (translating legacy immediate-mode
   desktop GL calls into real OpenGL ES 2.0 draw calls). Wiring that
   in as the actual renderer for a real LWJGL-driven Minecraft session
   is a substantially bigger step than the demo itself.
5. Surviving first contact with a real device without crashing —
   expect this to take multiple iterations once steps 1-4 exist.

None of this is impossible — PojavLauncher itself proves it's
achievable — but each step is a genuine, multi-session undertaking on
its own. Treat estimates otherwise with skepticism.

## Build

Open in Android Studio, let Gradle sync (no NDK needed for the parts
that currently run — the `.so` libraries are present but unused),
run. Needs network access; `INTERNET` permission is already declared.

CI: GitHub Actions builds a debug APK automatically on every push to
`main`. Grab it from the run's Artifacts section.

## Suggested next milestones, roughly in order

1. ~~Verify `RuntimeManager`'s JRE download URLs actually resolve~~ —
   **done.** The original hard-coded URL guessed a wrong release tag
   naming scheme; `RuntimeManager` now resolves the real download URL
   dynamically via GitHub's Releases API instead, with a
   self-diagnosing error message if the matching logic ever needs
   further adjustment.
2. ~~Trace and test whether `JREUtils` can load the bundled native
   libraries at all~~ — **built, not yet confirmed on-device.** Home
   tab → Diagnostics card → "Test native libraries" safely triggers
   `JREUtils`'s static initializer (where the four `System.loadLibrary`
   calls live) and reports success/failure without attempting a real
   launch. Catches `UnsatisfiedLinkError`/`ExceptionInInitializerError`
   cleanly; can't protect against a genuine native crash (SIGSEGV from
   an ABI mismatch), but that's a much less likely first failure mode
   than "library not found." **Needs an actual device test to know
   the real answer** — this only confirms the mechanism is wired up
   correctly, not that it passes.
3. ~~Wire a dry-run classpath + launch args build~~ — **done.**
   Home tab → Play button → `LaunchConfigBuilder` assembles the real
   classpath (client jar + every downloaded library jar), main class,
   and game arguments, shown in a dialog. Confirms the downloaded
   pieces genuinely cohere into a valid launch configuration.
   `GameLauncher.java` now uses `LaunchConfigBuilder` too (previously
   it built its own incomplete classpath — client jar only, no
   libraries, no game args — duplicating and diverging from the
   dry-run logic). Both paths share one source of truth now.
4. Once the diagnostics test above is confirmed passing on a real
   device: attempt an actual launch via `GameLauncher`/`VMLauncher`.
   Expect this to fail the first several times — a JRE that installs
   correctly, natives that load cleanly, and a coherent classpath are
   all necessary but not sufficient; getting a real EGL surface for
   LWJGL to render into (see `GLCompatDemo`) is the remaining large
   piece with no work started yet.
5. **Swap in `MicrosoftAuth`** for real account login once launching
   itself is closer to working — no urgency before then, since local
   accounts are sufficient for testing the launch pipeline itself.
6. **Background download reliability** — downloads now run in
   `DownloadService`, a real foreground service with a wake lock and
   persistent notification, specifically because the previous
   Activity-owned executor got killed by `onDestroy()` mid-download
   whenever the app was backgrounded — likely the cause of asset
   downloads "randomly" failing before. `AssetDownloader` also now
   retries each file up to 3 times and tolerates individual failures
   instead of aborting the whole batch. Worth confirming with a real
   test: start a large download, background the app, come back later.
