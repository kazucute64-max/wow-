# Third-party native libraries

The `.so` files in `arm64-v8a/` and `armeabi-v7a/` come from two sources,
both from the actively-maintained PojavLauncher fork (LGPLv3 + mixed
third-party licenses — see PojavLauncher's own README license table):

https://github.com/AngelAuraMC/Amethyst-Android (successor to the now
archived https://github.com/PojavLauncherTeam/PojavLauncher)

1. **Third-party libs, checked into the PojavLauncher source repo as-is:**
   libgl4es_114.so (GL4ES, MIT), liblwjgl*.so (LWJGL native bindings, BSD-3),
   libopenal.so, libfreetype.so, libOSMesa.so, libjnidispatch.so,
   libunpack200.so, plus arm64-only libvulkan_freedreno.so /
   libVkLayer_khronos_timeline_semaphore.so.

2. **Pojav/Amethyst's own JVM-launch bridge, extracted from a published
   release APK (Amethyst-Android v1.1.5, Amethyst.apk — an APK is just a
   zip, so `unzip Amethyst.apk` and copy from `lib/<abi>/`):**
   libpojavexec.so, libpojavexec_awt.so, libexithook.so, liblinkerhook.so,
   libawt_headless.so, libawt_xawt.so.
   We used this route instead of compiling from source because that native
   code (~20 C files under app_pojavlauncher/src/main/jni/, e.g.
   jre_launcher.c, egl_bridge.c, awt_bridge.c, input_bridge_v3.c) needs the
   Android NDK toolchain, which this environment doesn't have. The upstream
   README lists this bridge's license as "Boardwalk (JVM Launcher): Unknown
   License/Apache License 2.0 or GNU GPLv2" — unresolved. Fine for personal/
   learning use; revisit before any public distribution of OurLauncher.

We did not modify these binaries. Per LGPLv3 we keep this notice and the
upstream license text (see /LICENSE-POJAVLAUNCHER at repo root) alongside
them. If OurLauncher is ever distributed publicly, keep this file and the
license together with the app.

Important: because libpojavexec.so was compiled with standard JNI static
linking, its exported symbols are hard-bound to specific Java class names
(net.kdt.pojavlaunch.utils.JREUtils and com.oracle.dalvik.VMLauncher — see
those classes under app/src/main/java/ for the full explanation). Those two
classes must keep that exact package/name or native calls will throw
UnsatisfiedLinkError.
