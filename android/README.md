# Android application and core

`:app` is a framework-only Android shell for the JVM `:core` file server.
It uses Kotlin and Android framework views, follows the system light/dark
appearance through DeviceDefault themes, and has no AndroidX or Material
dependency. Its runtime dependencies are `com.github.mwiede:jsch` (SSH reverse
tunnel) and the Go bridge in `tsbridge/` (embedded Tailscale, built from source
into an AAR); `:core` remains pure JVM. The application id and label are
`dev.tentacle` and `Tentacle`; the version is declared in `app/build.gradle.kts`.
Minimum API is 26, compile/target API is 35. JDK 21 builds Java 17 bytecode;
JUnit is test-only. Release APKs contain only `arm64-v8a` (phones); debug APKs
contain both `arm64-v8a` and `x86_64` (emulator). Set
`-Ptentacle.allAbis=true` to include both ABIs in a release APK for testing.

## Build

Install Android SDK platform 35 and the build-tools requested by AGP (currently
34.0.0), using the SDK's `cmdline-tools/latest/bin/sdkmanager`. Point
`ANDROID_HOME` at the SDK, or put `sdk.dir` in the gitignored `local.properties`.
Do not commit local SDK paths, APKs, signing material or build directories.

The embedded Tailscale bridge also needs:

- **Go** at the version named in `tsbridge/go.mod` (the `tailscale.com` module
  sets the minimum), on `PATH` or given with `-Ptentacle.goBin=<dir>`;
- **gomobile/gobind**: `go install golang.org/x/mobile/cmd/gomobile@latest
  golang.org/x/mobile/cmd/gobind@latest` (found on `PATH`, in
  `$(go env GOPATH)/bin`, or via `-Ptentacle.gomobileBin=<dir>`);
- **the NDK**: `sdkmanager "ndk;29.0.14206865"` (any recent NDK works; the
  newest `<sdk>/ndk/<version>` is used unless `ANDROID_NDK_HOME` is set).

Gradle builds the bridge only for the target set required by each build type:

- `:app:buildTsbridgeArm64` runs `gomobile bind -target=android/arm64` into
  `app/build/tsbridge/arm64/tsbridge.aar` for a normal release.
- `:app:buildTsbridgeAll` runs with `-target=android/arm64,android/amd64` into
  `app/build/tsbridge/all/tsbridge.aar` for debug and all-ABI release builds.

Each AAR has its own task inputs and up-to-date checks for the target set,
Go sources, `go.mod`/`go.sum`, feature tags and Tailscale version stamps.
Build-type dependencies select the AAR; NDK ABI filters also constrain APK
packaging. A debug build does not require the arm64-only AAR, and a normal
release does not compile amd64. Missing tools produce a clear error. Go modules
download on the first build (set `GOPROXY` if needed). The bridge is stripped,
uses 16 KB page alignment, and native libraries are compressed inside the APK.

Release builds enable R8 code optimization/obfuscation and resource shrinking
with `proguard-android-optimize.txt` and `app/proguard-rules.pro`. The rules
preserve the gomobile JNI runtime (`go.**`), generated bridge bindings
(`dev.tentacle.tailnet.bridge.tsbridge.**`), and all JSch classes/members because
JSch loads algorithms and authentication implementations by config class name.
Only missing optional JSch integration dependencies are suppressed. AAPT supplies
keep rules for Manifest components. Inspect `app/build/outputs/mapping/release/`
for the R8 mapping, effective configuration and removed-code report. Kotlin
compiler/debug metadata and unused OSGi manifests are excluded from packaging;
third-party license and notice texts are retained, merging duplicate names.

The `tsbridgeTags` list in `app/build.gradle.kts` omits features outside the
embedded node's listener, login, status and network-change paths, including
log upload, metrics, CLI tooling, Taildrop, routing and exit-node
features. CLI is opt-in upstream and is never enabled here. Preserve netstack,
NAT traversal, direct UDP, port mapping, DNS (including fallback resolution),
health, Tailnet Lock, fallback certificate roots and DNS-cache support. Retain
ACE, netmap caching and system proxy support for control connectivity and
reconnect behavior. ACME and Serve remain enabled because the pinned tsnet
version references their methods unconditionally; omitting either fails to
compile, even though the app exposes neither feature. Standalone Android
binary/DNS fallbacks are omitted: the app supplies interfaces/routes and
gomobile uses Android's cgo DNS resolver.
The app polls status, so it needs no IPN notification bus; C2N admin/debug and
captive-portal features are also omitted. Recheck the pinned Tailscale source
and `go run tailscale.com/cmd/featuretags --list` / `--remove=<features>` when
changing tags or upgrading; validate dependency closure, host tests with and
without production tags, and gomobile builds for both target sets. Bridge host
checks are `cd tsbridge && go vet ./... && go test ./...`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
./gradlew :app:lintDebug :app:lintRelease
./gradlew :app:assembleRelease -Ptentacle.allAbis=true
./gradlew :app:assembleRelease -Ptentacle.updateRepo=<owner>/<repo>
```

APK outputs are `app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/release/tentacle-<versionName>.apk`. The release build
also writes `tentacle-<versionName>.apk.sha256` beside it. Release is unsigned
when signing settings are absent; debug uses normal Android debug signing.
To sign a release, provide all four of `TENTACLE_KEYSTORE`,
`TENTACLE_KEYSTORE_PASSWORD`, `TENTACLE_KEY_ALIAS`, and
`TENTACLE_KEY_PASSWORD` through Gradle properties (prefer private user Gradle
properties) or environment variables. Properties take precedence. Partial
signing configuration fails with a message; secrets are never printed by the
build script. The `tentacle.updateRepo` property is an `owner/repo` pair used
to construct the default GitHub Releases update source. Omitting it disables
updates by default, which keeps fork builds independent.

From the repository root, `scripts/release.sh` performs the signed build and
creates `v<version>` with the APK and checksum through an authenticated `gh`.
It requires the four signing environment variables above and refuses to run
from a dirty worktree. It derives `owner/repo` from the current GitHub remote.

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
action. The screen lists IPv4 pairing links: the built-in Tailscale address
when connected, then Wi-Fi/Ethernet, addresses in the Tailscale CGNAT range,
other interfaces and the public URL. "Copy pairing link (all addresses)"
copies one link with all of them in that order. Copy a link and
run `tentacle pair '<copied link>'` on the computer. Tokens are masked until Show;
clipboard copies contain the complete token and are marked sensitive on API
33+. Regeneration requires confirmation and invalidates the previous token
immediately, including while the server runs. Preferences are private and
backup is disabled, including explicit preference exclusions for Android 12+
cloud backup and device transfer.

The optional SSH mode keeps an outbound reverse tunnel to the owner's server
while the file server runs. It uses an app-private ECDSA key and pins the SSH
host key on first use. See `docs/CONNECT.md` for the server-side authorized key
and nginx setup. The optional public HTTPS URL is included as another pairing
link; the app does not infer it from the SSH host.

The optional built-in Tailscale node (`dev.tentacle.tailnet`) runs a
userspace tsnet node inside the service, next to and independent of the SSH
tunnel. It needs no VPN slot, so it works while a VPN/proxy app is on; only the
server port is reachable on the tailnet and is proxied to `127.0.0.1:<port>`.
Settings: enable switch (default off), control server URL (empty = Tailscale,
or a Headscale URL), optional auth key (app-private, never shown, deleted after
the first login), and hostname (default `tentacle-<model>`). Changing them
restarts the service; changing the control URL starts a new node identity. The
node state lives in `no_backup/tailnet`. The app enumerates interfaces with
`java.net.NetworkInterface` and pushes them, the default interface and
gateway to Go before start and on every default-network change, because Android
11+ denies Go's netlink access; the same callback triggers an immediate
rebind/re-STUN. Logs go to logcat (tag `GoLog`), never to Tailscale's log
service. `/v1/ping` reports `{"tailnet":{"state":…,"ip":…}}` while enabled.

The app can check GitHub Releases for signed APK updates. Release builds use
the repository supplied by `tentacle.updateRepo`; users can override the update
source in settings. Android always asks the user to confirm installation, and
may first require the app to be allowed to install unknown apps.
With an update source configured, the running service periodically checks for
published releases in the background. The owner starts download and installation
from the update prompt or notification; the updater verifies that the APK belongs
to Tentacle and has the installed app's signing certificate. If Android requests
installation confirmation while the app is in the background, a notification
opens that system dialog. Completing a code request does not install an APK:
the change must first pass maintainer review and be published as a signed release.
The phone API provides no remote or silent-install command.

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

`autostart` is honoured only by debug builds, as are the test extras
`--ez tailnet_enabled true`, `--es tailnet_control_url URL`,
`--es tailnet_auth_key KEY` and `--es tailnet_hostname NAME` (they save the
built-in Tailscale settings and restart the service). The service remains unexported;
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
