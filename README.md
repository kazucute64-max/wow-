# OurLauncher — Milestone 1: real version manifest + real downloads

This is the launcher's "front half," built and buildable independent of
any JVM/NDK toolchain. It talks to Mojang's actual public endpoints:

- `GET https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`
  — the same list of every Minecraft version the official launcher uses.
- For a selected version, fetches its per-version JSON, reads
  `downloads.client.url`/`size`/`sha1`, and streams the real `client.jar`
  into the app's private storage (`filesDir/versions/<id>/<id>.jar`).

No mocked data — run this on a device and it lists genuinely current
Minecraft versions and downloads genuinely real client jars.

## What it deliberately does NOT do yet

- **No account login.** Real Minecraft requires a Microsoft account for
  online play (this is how PojavLauncher does it too — see their FAQ:
  it authenticates directly against Mojang's servers to fetch authentic
  game files). The correct way to add this is the OAuth2 **device code
  flow** against Microsoft's identity endpoints, then exchanging that
  token through Xbox Live → XSTS → Minecraft services. That's a
  self-contained, addable milestone — happy to build it next.
- **No sha1 verification of the download yet** (the field's already
  being fetched — just needs a `MessageDigest` check after download).
- **No asset/library download** — a full launch also needs the
  `libraries` array and the asset index, not just the client jar.
- **No actual game launch.** That's where GLCompatDemo (the GLES2
  translation shim) and a bundled JVM come in — separate, harder
  milestones.

## Build

Same as before: open in Android Studio, let Gradle sync (no NDK needed
for this module), run. Needs network access and `INTERNET` permission,
already declared in the manifest.

## Suggested next milestones, roughly in order

1. **SHA-1 verify** the downloaded jar against the manifest's hash —
   cheap correctness win, teaches you the manifest schema better.
2. **Download the `libraries` array** from the version JSON alongside
   the client jar (each entry has its own `downloads.artifact.url`).
3. **Microsoft OAuth device code flow** for real account login.
4. **Wire in GLCompatDemo's `gl_compat` shim** as the renderer once
   you're ready to attempt an actual launch — this is the point where
   the "front half" (this app) and "back half" (JVM/LWJGL/GL4ES-style
   translation) finally meet.
