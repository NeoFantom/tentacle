# Android application and core

`:app` is a framework-only Android shell for the JVM `:core` file server.
It uses Kotlin and Android framework views, follows the system light/dark
appearance through DeviceDefault themes, and has no AndroidX, Material or
third-party runtime libraries. Kotlin's standard library is the only runtime
library beyond `:core` and Android. The application id and label are
`dev.tentacle` and `Tentacle`; version is `0.2.0` (code 2), minimum API 26,
compile/target API 35. JDK 21 builds Java 17 bytecode; JUnit is test-only.

## Build

Install Android SDK platform 35 and the build-tools requested by AGP (currently
34.0.0), using the SDK's `cmdline-tools/latest/bin/sdkmanager`. Point
`ANDROID_HOME` at the SDK, or put `sdk.dir` in the gitignored `local.properties`.
Do not commit local SDK paths, APKs, signing material or build directories.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease
```

APK outputs are `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/app-release-unsigned.apk` (or `app-release.apk`
when signed). Release is unsigned
when signing settings are absent; debug uses normal Android debug signing.
To sign a release, provide all four of `TENTACLE_KEYSTORE`,
`TENTACLE_KEYSTORE_PASSWORD`, `TENTACLE_KEY_ALIAS`, and `TENTACLE_KEY_PASSWORD` through Gradle
properties (prefer private user Gradle properties) or environment variables.
Properties take precedence. Partial signing configuration fails with a message;
secrets are never printed by the build script.

The core tests need no Android SDK. After dependencies have been cached they
also run offline with `./gradlew :core:test --offline`.

## On the phone

Start the server from the app and grant shared storage access. On Android 11+
this is All files access; API 26–29 requests READ_EXTERNAL_STORAGE and uses
legacy shared storage. Notification permission is requested on API 33+.
Permission labels are read from Android again on resume; opening a settings
page never marks a permission as granted. The Xiaomi/Redmi/POCO autostart link
is best-effort with an app-details fallback; its state cannot be detected.

The foreground service listens on all interfaces, default port 8765, with a
persistent low-importance notification containing its best address and a Stop
action. The screen lists IPv4 pairing links, preferring Wi-Fi/Ethernet, then
addresses in the Tailscale CGNAT range, then other interfaces. Copy a link and
run `tentacle pair '<copied link>'` on the computer. Tokens are masked until Show;
clipboard copies contain the complete token and are marked sensitive on API
33+. Regeneration requires confirmation and invalidates the previous token
immediately, including while the server runs. Preferences are private and
backup is disabled, including explicit preference exclusions for Android 12+
cloud backup and device transfer.

Port accepts 1024–65535 and restarts a running server when changed. Start on
boot defaults to enabled after the first successful start; an explicit switch
choice persists. Boot and package replacement can start the `specialUse`
foreground service. Android or OEM background restrictions can still prevent
startup; errors appear on the app screen. Keep awake defaults off; enabling it
holds a partial CPU wake lock and Wi-Fi lock until the service stops. API 29+
uses low-latency Wi-Fi mode, older versions high-performance mode. Android may
ignore low-latency requests while the screen is off or the app is backgrounded;
this setting is not a connectivity guarantee.

Read-only does not mean private on an untrusted network: anyone with the token
and network reachability can read shared files. Plain HTTP is unencrypted;
prefer Tailscale or trusted Wi-Fi. Android/data and Android/obb remain forbidden.

## Desktop development server

The isolated `dev` source set does not enter the main core jar or APK. Supply a
readable directory, port (0 chooses an ephemeral port), and a nonempty test token:

```sh
./gradlew :core:devServer --args="--root $ROOT --port $PORT --token $TOKEN"
```

The process prints a `tentacle://` pairing link for localhost and blocks until stopped
with Ctrl-C. Use it with the normal PC CLI (`pair`, `ping`, `ls`, `find`, `get`,
`discover`); it exposes only the supplied root and the same read-only API.

## Emulator smoke test

With an API 35 emulator running, install the debug APK and grant storage access:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set dev.tentacle MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant dev.tentacle android.permission.POST_NOTIFICATIONS
adb shell mkdir -p /sdcard/MIUI/sound_recorder/call_rec
adb push "$FIXTURE" /sdcard/MIUI/sound_recorder/call_rec/
adb shell am start -n dev.tentacle/.MainActivity --ez autostart true
adb forward tcp:18765 tcp:8765
```

`autostart` is honoured only by debug builds. The service remains unexported;
release builds ignore that extra. For a disposable emulator only, read its token
with `adb shell run-as dev.tentacle cat shared_prefs/tentacle.xml`, without
logging or publishing it. Configure the PC client URL to `http://127.0.0.1:18765`
and token through its `token_env` setting. Check `tentacle ping`, `tentacle ls`,
`tentacle find --ext mp3`, `tentacle get`, and `tentacle discover`; `tentacle ls Android/data` must
fail cleanly with 403. Inspect the screen using `adb exec-out screencap -p`, keep
screenshots locally outside tracked files, then remove the forward and stop
the emulator. Also exercise Start/Stop, token regeneration, permissions after
returning from settings, port restart, and dark mode.

## Integration

The application supplies `StorageRoot` objects, a live token provider, and a
`PingInfo` provider to `HttpServer`. Call `start()` and `stop()` with the service
lifecycle. Both calls are idempotent; the server can be restarted. Port zero
selects an ephemeral port, exposed by `boundPort` while running. A stopped server
reports `boundPort == -1`. Set `logger` to receive status messages without
request paths, headers, or tokens.

`FileApi` can also be used directly through `resolve`, `ls`, `stat`, `find`, and
`openFile`. `openFile` returns a `Closeable` bounded file view; always close it
with `use`, including when only reading metadata. `writeTo` streams the selected
range through a fixed-size buffer. Model `toJson()` methods expose the wire
field names to the small `Json.stringify` writer.

## Boundary choices

- The root with id `primary` is used for relative paths, with the first root as
  a fallback. Empty paths, `.` and `/` select it. Absolute aliases for configured
  roots are accepted when their canonical targets remain within a root.
- Names sort in natural, case-sensitive string order, with canonical paths as
  tie breakers. `ls` places directories before other entries.
- `find` excludes the requested root itself. Its children have depth one, so
  `max_depth=0` produces no entries. Time bounds are inclusive. Hidden entries
  and hidden directory trees are skipped unless `hidden=1`.
- `find` retains the first `limit` entries in the requested global sort order;
  later subtrees can replace earlier matches. Limits outside 1 through 5000
  and malformed extension lists produce 400.
  `truncated` means at least one matching entry was omitted. An explicit
  unreadable root or a directory that cannot be opened during traversal
  produces 403. Forbidden paths are skipped. Symbolic directory links are
  never traversed during a walk.
- Malformed, multiple, empty-file, or unsatisfiable byte ranges produce 416
  with the total size. Range ends beyond EOF and suffixes longer than the file
  are clamped to the file size. HEAD uses the same metadata and range handling
  as GET and writes no response body, including on errors.

The transport closes each connection after one request. Eight workers and a
bounded queue handle clients, with a five-second socket read timeout and an
8 KiB limit for each request line and the aggregate header block. Query values
use strict UTF-8 percent decoding, and `+` represents a space.
