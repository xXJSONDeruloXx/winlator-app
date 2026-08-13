# SteamDroid implementation status

SteamDroid is the ARM64-native Linux product surface built on Winlator's
Android, X11, renderer, audio, and input substrate. It intentionally does not
start Winlator's Wine or Box64 execution path. Steam is expected to own its
ARM64 Steam Runtime, Proton, FEX, and game processes.

## Current milestone boundary

M0–M4 are implemented:

- the application installs as `com.xjsonderulo.steamdroid` beside the legacy
  Winlator sources;
- `SteamSessionService` owns the session independently of the Activity;
- an extracted ARM64 PIE supervisor is hash-checked against the APK before
  root execution;
- the supervisor creates and verifies a private mount namespace, binds the
  Holo substrate, runs as a child subreaper, and performs deterministic
  descendant cleanup;
- Holo identity views use the live Android UID, primary GID, and supplementary
  groups while retaining the logical `steam` account name;
- the Android control socket is an app-private filesystem socket and is never
  exposed in the Holo chroot; the guest proxy is a separate app-private socket
  bind-mounted at Holo-visible `/tmp/steamdroid-runtime-bwrap.sock`;
- Steam client seeding is ARM64-only and versioned by the channel descriptor.

M4 is qualified through native client startup on the attached AYN Thor:

- native ARM64 Steam reaches its GLX update UI;
- GLX legacy visual/FBConfig replies, context creation, drawable attributes,
  and `IsDirect` framing are implemented;
- XFixes, RandR, DRI3, Present, Composite, MIT-SHM, Sync, and GLX are exposed
  through the existing X server core;
- the service starts and tears down the X/Pulse/SysV substrate without an
  Activity-owned session leak.

The current public-beta client still exits with its known
`Bootstrapper HTTP Client` frame-function assertion before SteamUI/webhelper.
That is an open native-client compatibility boundary, not acceptance of Big
Picture. The M7 root proxy now preserves the complete inherited-FD table and
Bubblewrap `--args FD` stream, performs the identity transition after the
command boundary, and passes a service-owned root-owned fixture bwrap on Thor.
The fixture does not yet qualify the live Steam-installed srt-bwrap,
Runtime 4, Proton, input/uinput, login, or game launch.

## Build and device smoke test

The current Gradle wrapper/Android Gradle Plugin requires JDK 17:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  bash gradlew :app:assembleDebug --console=plain
adb -s d234a848 install -r app/build/outputs/apk/debug/app-debug.apk
```

The attached-device service actions are deliberately explicit:

```sh
adb -s d234a848 shell am start -n \
  com.xjsonderulo.steamdroid/com.winlator.SteamDroidActivity
adb -s d234a848 shell su -c \
  'am start-foreground-service -n com.xjsonderulo.steamdroid/com.winlator.SteamSessionService \
   -a com.xjsonderulo.steamdroid.action.START'
adb -s d234a848 shell su -c \
  'am start-foreground-service -n com.xjsonderulo.steamdroid/com.winlator.SteamSessionService \
   -a com.xjsonderulo.steamdroid.action.STOP'
adb -s d234a848 shell su -c \
  'am start-foreground-service -n com.xjsonderulo.steamdroid/com.winlator.SteamSessionService \
   -a com.xjsonderulo.steamdroid.action.RUNTIME_BWRAP_SELF_TEST'
```

The real Runtime-4 operation remains acceptance-gated until an authentic
Steam-installed srt-bwrap invocation and a real Proton launch pass through the
same proxy. Malformed requests, unknown channels, missing descriptors, and
unvalidated tool versions remain fail-closed.
