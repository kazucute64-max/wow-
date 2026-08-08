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
  (release + snapshot).
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
- **Material 3 UI** with three tabs: Home (selected version + Play
  button), Versions (the real manifest list as cards), Account (local
  account management).
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

1. **Verify `RuntimeManager`'s JRE download URLs actually resolve** —
   cheap to check, and everything downstream depends on it.
2. **Trace and test whether `JREUtils`/`VMLauncher` can load the
   bundled native libraries at all**, independent of actually launching
   anything — a "does `System.loadLibrary` succeed" smoke test.
3. **Wire `GameLauncher` to build a real classpath** from what's
   already downloaded (client jar + libraries) and attempt a JVM
   argument dry run (print what it would launch, don't launch yet).
4. Only after 1-3 are individually confirmed working: attempt an
   actual launch, expect it to fail the first several times, debug from
   there.
5. **Swap in `MicrosoftAuth`** for real account login once launching
   itself is closer to working — no urgency before then, since local
   accounts are sufficient for testing the launch pipeline itself.
