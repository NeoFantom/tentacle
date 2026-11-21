# Tentacle — Architecture & Contracts

This document is the single source of truth for module boundaries and contracts.
Change an affected contract here before changing an implementation.

## 1. Scope

Tentacle is a manual toolkit for a human or local agent to browse Android shared
storage, pull call recordings, transcribe audio locally on demand, receive
short-lived verification codes when the phone owner enables that feature, and
send a notification from the paired computer to the phone owner. The phone
serves an HTTP API with read-only file access, request status updates, an
in-memory verification-code feed and an in-memory notification-status feed.
File commands do their work and exit; there is no background file analysis,
project filing or file-processing task queue. The optional `requests watch`
process stays running to handle changes requested in the app, as specified
in [REQUESTS.md](REQUESTS.md).

The automation implementation is preserved on `experimental/automation`.
Connectivity options, including the Android SSH reverse tunnel and the embedded
Tailscale node, are documented in [CONNECT.md](CONNECT.md).

## 2. Components and ownership

| Component | Responsibility |
|---|---|
| `android/:core`, `dev.tentacle.core` | Storage roots, confined file access, verification-code parsing and in-memory storage, outbound-notification validation/rate limiting/state, HTTP API and token authentication; pure Kotlin/JVM. |
| `android/:app`, `dev.tentacle` | Foreground service, notification delivery and acknowledgement, notification/SMS code capture, permission guidance, settings, pairing and live service status. |
| `android/tsbridge` (Go, `tsbridge`) | Embedded userspace Tailscale node (`tailscale.com/tsnet`) bound with gomobile into an AAR for `:app` (`dev.tentacle.tailnet`). |
| `pc/src/tentacle/config.py` | All PC settings, loading and masked display. |
| `phone.py`, `phone_cli.py`, `codes_cmd.py`, `notify_cmd.py` | HTTP client, resumable transfers, file commands, verification-code waiting, phone-owner notification, discovery and diagnostics. |
| `paths.py` | Choosing among the phone's candidate base URLs (probe, tailnet-direct check, short cache). |
| `calls.py` | Recording metadata, manual listing/pulling and content-addressed transcription cache. |
| `asr/` | Local ASR backends, transcript validation and rendering. |
| `timeutil.py` | Shared time parsing and conversion. |
| `skills/tentacle/SKILL.md` | Agent workflow for phone files, calls, verification codes and notifying the phone owner. |

The PC core uses Python ≥ 3.11 standard library only. Optional ASR libraries are
lazy imports declared as package extras. Android uses Kotlin;
`com.github.mwiede:jsch` and the in-repository Go bridge (`android/tsbridge`,
built from source with gomobile; it pulls in `tailscale.com`) are the only
runtime dependencies besides the framework, and both are confined to `:app`. The phone records nothing itself; existing recorders write
the files exposed by this toolkit. The PC can update app request status and messages;
file access remains read-only.

## 3. Phone HTTP API v1 (contract between `android/` and `pc/`)

- Direct local base: `http://<host>:<port>` (default port **8765**). An HTTPS
  reverse proxy may add a path prefix, such as `https://example.com/tentacle`.
  All API endpoints remain under `/v1/` relative to the configured base URL.
- Auth: header `Authorization: Bearer <token>` on every request, compared in
  constant time. Missing/wrong → `401 {"error":"unauthorized"}`.
- Token: 32 random bytes, base64url without padding, generated on first launch,
  regenerable in the app.
- Responses: JSON `application/json; charset=utf-8`, except `/v1/file` and
  attached request logs (`text/plain; charset=utf-8`).
- Errors: `{"error": "<code>", "message": "<human text>"}` with status
  400 `bad_request` / 401 `unauthorized` / 403 `forbidden` (outside roots or no
  permission) / 404 `not_found` / 409 `conflict` / 411 `bad_request` (missing
  Content-Length) / 413 `bad_request` (body too large) / 429 `rate_limited` /
  416 `range_not_satisfiable` / 500 `internal`.
- File access is read-only: `GET` (and `HEAD` for `/v1/file`). API writes are
  limited to updating an app request through `POST /v1/requests/<id>` and
  asking the phone to display a notification through `POST /v1/notify`. Other
  methods → 405. See [REQUESTS.md](REQUESTS.md) and [NOTIFY.md](NOTIFY.md).

### Paths

- A *root* is a readable storage volume. v1 roots: primary shared storage
  (`/storage/emulated/0`, id `primary`) plus any mounted removable volumes.
- Requests may use an absolute path (`/storage/emulated/0/Download`) or a path
  relative to the primary root (`Download`, `MIUI/sound_recorder`). `""`, `/`
  relative form `.` mean the primary root.
- The server canonicalises (resolving `..` and symlinks) and refuses anything
  that ends outside a root → 403. `Android/data` and `Android/obb` are not
  readable on Android 11+ and are reported as 403, never crash.
- Every entry returned carries its absolute canonical `path`.

### Entry object

```json
{"name": "a.aac", "path": "/storage/emulated/0/x/a.aac", "type": "file",
 "size": 17229188, "mtime_ms": 1763371721000}
```
`type` ∈ `file` | `dir` | `other`. `size` is 0 for dirs.

### Endpoints

| Method & path | Query | Response |
|---|---|---|
| `GET /v1/ping` | — | `{"app":"tentacle","api":1,"version":"0.1.0","device":"<manufacturer model>","android_sdk":35,"roots":[{"id":"primary","path":"/storage/emulated/0","label":"Internal"}],"all_files_access":true,"time_ms":...,"timezone":"Asia/Shanghai"}` — `time_ms`/`timezone` are the phone's clock and IANA zone; the PC uses them for file-age checks and for interpreting timestamps in file names. Optional additive field `"tailnet":{"state":"running","ip":"100.64.0.1"}` while the embedded Tailscale node is enabled: `state` ∈ `starting` \| `needs_login` \| `running` \| `error` \| `stopped`, `ip` only while running. It never carries keys or login URLs. Optional additive field `"lan":["http://192.168.0.5:8765"]`: the phone's current Wi-Fi/Ethernet base URLs (no VPN, tailnet or cellular addresses), used by the PC to switch to a same-network path. Clients must ignore unknown fields. |
| `GET /v1/ls` | `path`, `hidden=0\|1` (default 0) | `{"path":..., "entries":[Entry...]}` sorted dirs first then name |
| `GET /v1/stat` | `path` | `Entry` |
| `GET /v1/find` | `root` (path, default primary), `ext` (comma list, case-insensitive, no dot), `name` (case-insensitive substring), `since_ms`, `until_ms`, `type=file\|dir\|any` (default file), `max_depth` (default 12), `limit` (default 500, max 5000), `sort=mtime_desc\|mtime_asc\|name` (default mtime_desc) | `{"root":..., "entries":[Entry...], "truncated": bool}`. Walk skips `Android/data`, `Android/obb`, hidden dirs unless `hidden=1`; never follows symlinks out of roots. |
| `GET/HEAD /v1/file` | `path` | Raw bytes, `Content-Type` by extension (fallback `application/octet-stream`), `Content-Length`, `Last-Modified`, `ETag: "<size>-<mtime_ms>"`, `Accept-Ranges: bytes`. Supports single `Range: bytes=a-b` / `a-` / `-n` → 206 with `Content-Range`. |
| `GET /v1/codes` | `since` = phone epoch milliseconds; `wait=0..60` seconds | `{"codes":[{"id","code","sender","source":"notification"\|"sms","snippet","received_ms"}...],"now_ms":...,"enabled":true}`. Codes are oldest first and only entries with `received_ms > since` are returned. With `wait > 0`, the call blocks until such an entry exists or the timeout elapses. When relay is off, it returns `{"codes":[],"now_ms":...,"enabled":false}` immediately. See [CODES.md](CODES.md). |
| `GET /v1/requests` | `wait=0..60` seconds | Newest-first requests and `now_ms`; optional long-poll for a `new` request, recording the last poll time. See [REQUESTS.md](REQUESTS.md). |
| `POST /v1/requests/<id>` | JSON status and message, ≤ 16 KiB | Updated Request; invalid input → 400, unknown id → 404, disallowed transition → 409. See [REQUESTS.md](REQUESTS.md). |
| `GET /v1/requests/<id>/log` | — | Attached snapshot as `text/plain; charset=utf-8`, or 404. See [REQUESTS.md](REQUESTS.md). |
| `POST /v1/notify` | JSON body, ≤ 16 KiB | Accepts `{"title"?: string, "text": string, "level"?: "normal"\|"urgent", "id"?: string}` and returns `{"id", "level", "downgraded", "reason"?, "shown"}`. `title` is at most 100 characters, `text` is 1–1000 characters, and `id` is 1–64 characters of `[A-Za-z0-9_-]`; the phone generates an id when absent. `level` defaults to `normal`. Control characters except newline are invalid. Reusing an id with identical content returns the original result; different content returns 409. See [NOTIFY.md](NOTIFY.md). |
| `GET /v1/notify/<id>` | `wait=0..60` seconds | Returns `{"id","level","state":"shown"\|"acknowledged"\|"dismissed"\|"expired","acknowledged_ms"?}`. With `wait > 0`, blocks until the state leaves `shown` or the timeout elapses; unknown ids return 404. See [NOTIFY.md](NOTIFY.md). |

Directory listings of very large dirs are not paginated in v1 (find has `limit`).

## 4. Android app

`:core` owns `FileApi` and `HttpServer`, with JVM tests using temporary roots.
A find walk resolves its root once, checks symbolic links per entry, and avoids
canonicalising ordinary children repeatedly. Returned paths stay confined to
readable roots; denied Android directories and escaping symlinks remain blocked.

`:core` also owns `ServerInfoParser`, which recognises own-server settings in
pasted free text, literal-IP / Cloudflare-range checks (`IpAddresses`), and the
pure-JVM verification-code parser and store. The store is memory-only, keeps at
most ten entries, expires each entry ten minutes after receipt, and deduplicates
the same code and sender within two minutes. Disabling relay clears it. Code,
request and notification-status long-polls share the two HTTP-worker waiting
slots so file requests retain capacity. See [CODES.md](CODES.md) and
[NOTIFY.md](NOTIFY.md).

The pure-JVM notification store validates content, supplies an id when needed,
enforces idempotency and keeps the last 50 records in memory. It limits normal
notifications to 10 per sliding minute and urgent notifications to 3 per
sliding 10 minutes. A requested urgent notification downgraded by the app
counts against the normal limit. A displayed notification starts as `shown` and
can become `acknowledged`, `dismissed` or `expired`; a failed delivery starts as
`expired`. After display, `expired` is an explicit app-reported terminal state
for a notification that is no longer active without a user tap or swipe, not
an automatic time-to-live. The two-minute urgent alarm cap stops sound and
vibration but leaves both the notification and state as `shown`.

`:app` owns `ServerService`, `MainActivity`, `BootReceiver`, pairing and settings.
The main screen puts one status card (plain-language state, a single
Start/Stop action, the state of each way in) first, shows a "needs attention"
card only while a permission is missing, and keeps requests, pairing, connection
configuration and app settings collapsed by default. Disclosure state and the
request draft survive activity recreation. Connection status shortcuts expand
the connection configuration before scrolling to the relevant option.
The request card shows a compact latest-status summary while collapsed and
creates feature requests and bug requests with a redacted log snapshot when
expanded. History is a separate collapsed disclosure; rows show short previews
with full text and results available in a scrollable details dialog.
`:core` owns request length validation, atomic app-private request
storage (newest 20 with logs), and the request endpoints; no endpoint creates
requests. A process-wide store lets the UI queue requests while the server is
stopped and observes status updates while visible. At most two HTTP workers
wait in long-polls so normal file access retains capacity.

The app-private log (`filesDir/logs`, two files of 512 KiB) records service, HTTP,
tunnel, Tailscale, network, update and crash events. Redaction happens before
writing; HTTP access logs omit query strings and headers. Snapshots include app
and Android versions, device information, time zone and current time. Settings
can view, copy or clear the log. The application crash handler writes synchronously
before delegating to the previous handler. See [REQUESTS.md](REQUESTS.md).

Verification-code relay is off by default. While enabled, a `RECEIVE_SMS`
receiver is the primary source on every Android version and concatenates
multipart messages. A separately enabled notification listener is an optional
source, mainly for Android 14 and older or codes sent inside apps; Android 15
and newer redact one-time codes in other apps' notifications from ordinary
listeners. Empty and platform-redacted notifications are ignored. Disabling
relay makes both sources inert and clears captured entries. Tentacle ignores
its own, ongoing and group-summary notifications. Payment, purchase, transfer
and withdrawal messages are never relayed. The app log records only sender and
relayed/blocked status, while redaction masks any captured code accidentally
passed to it.

The foreground notification keeps the server visible. The activity refreshes the
actual service state when resumed and observes starts/stops while visible.
Permission guidance reports actual grants, including all-files access,
notifications, battery optimisation and vendor autostart settings.

Computer notifications use two channels. Normal messages use default system
sound and vibration behavior, open Tentacle when tapped, and become
acknowledged on a tap or dismissed on a swipe. Urgent alerts are allowed only
when the owner has explicitly enabled them; otherwise the app displays a normal
notification and reports `urgent_not_allowed`. An allowed urgent alert uses
alarm audio and repeating vibration, a lock-screen full-screen activity and
acknowledgement actions, stopping after acknowledgement or two minutes. If
Android does not allow full-screen intents, the app still posts the
high-priority urgent notification and reports `full_screen_not_allowed`.
Missing notification permission or a blocked channel returns `shown: false`
with a reason. See [NOTIFY.md](NOTIFY.md).

Pairing format is
`tentacle://pair?name=<urlencoded-device>&token=<token>&url=<urlencoded-base-url>`,
one line per candidate non-loopback IPv4 address. When configured, the public
HTTPS URL contributes another line, and a running embedded Tailscale node
contributes `http://<100.x>:<port>`. The "all addresses" link repeats `url=` in
preference order: embedded tailnet, Wi-Fi/Ethernet, other interfaces, public
URL. A link with one `url=` is the original format. Settings live in
`SharedPreferences`.

The optional embedded Tailscale node (`dev.tentacle.tailnet`, Go bridge
`android/tsbridge`) runs inside `ServerService`, independently of the SSH
tunnel. It is a userspace tsnet node (no VpnService), so it works while a
VPN/proxy app is active. It listens on the tailnet at the server port and
proxies each TCP connection to the local server on `127.0.0.1`; the phone token
still protects every request. Its state directory is app-private
(`no_backup/tailnet`), log upload to Tailscale is disabled, and an optional
pre-auth key is kept only until the first successful login. The app supplies
network interfaces and network changes to the node because Android 11+ hides
them from Go.

The app can maintain an ECDSA-authenticated SSH reverse tunnel while the local
server runs. Its private key remains in app-private storage; its host-key trust,
connection settings, public URL and status follow [CONNECT.md](CONNECT.md).
The app also checks a configured GitHub-compatible latest-release endpoint and
installs only APKs signed by the running application's certificate.

## 5. PC package and CLI

Distribution and package: `tentacle`, version `0.3.0`. Entry point:
`tentacle` → `tentacle.cli:main`; feature modules register via
`add_commands(subparsers)`. There are no legacy command aliases.

### 5.1 Configuration

Lookup: `--config PATH` > `$TENTACLE_CONFIG` >
`$XDG_CONFIG_HOME/tentacle/config.toml` (default
`~/.config/tentacle/config.toml`). `config init` writes the bundled commented
`config.example.toml`; `config show` masks secrets. Path settings expand `~` and
`$VARS`. Tokens can be inline, in `token_file`, or supplied by `token_env`.

| Section | Settings |
|---|---|
| `[phone]` | URL, ordered candidate `urls`, token settings, `timeout_s = 20`, `find_timeout_s = 120`, `use_system_proxy`. |
| `[storage]` | `data_dir`, default `~/.local/share/tentacle`. |
| `[[sources]]` | Unique name, phone path, extensions, optional filename regex and timestamp format, `min_age_s`. |
| `[calls]` | `timezone`, an optional IANA zone override for recording filenames. |
| `[asr]` | Backend, language, model, diarisation, device, compute type, initial prompt, hotwords and command template. |

Modules receive `Config`; credentials and machine-specific settings stay out of
source code. `TENTACLE_ASR_VERBOSE` enables verbose optional-backend output.

### 5.2 Phone client and file commands

`PhoneClient(cfg.phone)` provides `ping`, `ls`, `stat`, `find`, `download` and
`read_bytes`. It accepts only `app = "tentacle"` from ping. Requests carry
`User-Agent: tentacle/<version>` and a Bearer token. Base URL path prefixes are
preserved: `https://example.com/tentacle` →
`https://example.com/tentacle/v1/ping`. System proxies are bypassed unless
explicitly enabled; loopback, LAN, link-local and tailnet (100.64.0.0/10)
addresses always bypass them.

Path selection (`paths.py`): when `phone.urls` is non-empty it replaces
`phone.url`. The candidates are probed concurrently with `GET /v1/ping`
(2.5 s timeout) as a race decided as early as possible: a reachable LAN
address wins the moment it answers; otherwise the first good candidate in list
order wins after a 0.25 s grace for LAN answers; unfinished probes are left on
daemon threads. A tailnet candidate is checked with `tailscale status --json`
(run in parallel) when that CLI exists; if the peer owning that address is
relayed (no `CurAddr`) it is used only when nothing else works. `ping`, `pair`
and `doctor` wait for all probes to print the full table. The winner is cached for
60 s in `<data_dir>/path-cache.json` (keyed by the candidate list). A transport
error invalidates the cache and retries the request once on another candidate.
The global `--url URL` option overrides `phone.url` and `phone.urls`. `find` uses its dedicated timeout; other requests use
`timeout_s`. Errors become `PhoneError(code, message, status)`.

Downloads write a `.part` file, resume using HTTP Range and matching ETags,
verify size, and atomically replace the destination.

```text
tentacle config init|show|path
tentacle [--url URL] COMMAND ...
tentacle pair 'tentacle://pair?name=...&token=...&url=...&url=...'
tentacle ping
tentacle ls [PATH] [-a] [--json] [--local-time]
tentacle stat PATH [--json] [--local-time]
tentacle find [ROOT] [--ext aac,m4a] [--name TEXT] [--since 7d] [--until DATE]
              [--limit N] [--max-depth N] [--json] [--local-time]
tentacle recent [--since 1d] [--ext EXTENSIONS] [--json] [--local-time]
tentacle get PATH [DEST]
tentacle cat PATH [--max-bytes N]
tentacle discover [--since 90d] [--json]
tentacle code [--wait 120] [--from TEXT] [--since now|30s|2m] [--json]
tentacle notify TEXT [--title T] [--urgent] [--wait-ack [SECONDS]] [--json]
tentacle doctor [--json]
```

Human file timestamps use the phone's ping timezone, cached for the process;
`--local-time` chooses the PC's zone. JSON file entries retain epoch milliseconds.
Relative dates accept `m/h/d/w`; absolute dates accept `YYYY-MM-DD` or
`YYYY-MM-DDTHH:MM` and are interpreted in the selected zone.

`discover` searches these paths relative to the primary root, each with
`max_depth = 4`: `MIUI/sound_recorder`, `Recordings`, `Music/Recordings`,
`Recorder`, `Sounds`, `Download`, `Documents`, `Android/media/com.tencent.mm`,
`Android/media/com.ss.android.lark`, `Call`, `CallRecordings`. Missing locations
are skipped. Results are aggregated by parent directory with source suggestions.

`code` first makes a non-waiting code request to learn the phone clock. By
default it then accepts only codes received after that request; `--since 30s`
or `--since 2m` includes a recent code when the website was triggered first.
It long-polls in calls of at most 55 seconds until the overall `--wait` deadline
and optionally matches `--from` case-insensitively against sender or snippet.
Plain output contains only the code; `--json` returns its code object. A timeout
exits 1. Disabled relay exits 2 and tells the owner where to enable it.

`notify` generates the id on the computer, posts a normal notification by
default and retries a transport failure with the same id. `--urgent` requests
an urgent alert; the response says whether it was downgraded or could not be
shown. `--wait-ack` waits 300 seconds by default, or the supplied duration, in
long-polls of at most 55 seconds. Acknowledged and dismissed are successful;
not shown, rate limited or no response before the deadline exits 1. Usage and
pairing errors exit 2. Without `--wait-ack`, `--json` emits the delivery object
as one JSON value. With both options, stdout is JSON Lines: the delivery object
first and the terminal acknowledgement or dismissal object when received.

`pair` stores every `url=` of a link in `phone.urls` (and the first one in
`phone.url`; a single-address link clears `phone.urls`), then selects a path.
With several candidates, `ping` and `pair` print each candidate with its result
(`ok`, `direct`, `relayed` or `unreachable`) and round trip, and mark the chosen
one; JSON output adds a `paths` list.

`doctor` checks config, writable data directory, phone route/ping, configured
source paths, ASR backend availability and CUDA. It has no agent, project,
queue, database or daemon checks.

### 5.3 Manual recordings — `calls.py`

```text
tentacle calls [--since 7d] [--until DATE] [--source NAME] [--with TEXT]
               [--app APP] [--limit 50] [--json] [--local-time]
tentacle pull PHONE_PATH... [--to DIR]
tentacle transcribe PHONE_PATH_OR_LOCAL_FILE [--json] [--force] [-o PREFIX]
```

`calls` makes one `/v1/find` request per selected source root, with its extensions,
phone-side `since_ms`/`until_ms` bounds (last seven days by default) and limit
(50 per source by default).
Rows are merged newest first. Fields are `app`, `counterpart`, `started_at`,
`size`, `source`, absolute phone `path`, `local` (cached path or null), and
`recording`. `--with` matches a case-insensitive counterpart substring; `--app`
matches the parsed app exactly. Human output aligns one recording per line;
JSON is a list of objects.

Filename parsing preserves the recorder conventions: source `name_pattern` has
optional named groups `app`, `counterpart`, `ts`; `ts_format` parses the latter.
App names normalise 微信 → wechat, 企业微信 → wecom, 飞书 → feishu, QQ → qq,
钉钉 → dingtalk, 腾讯会议 → tencent-meeting. A source named `call` without an app
group defaults to `phone`. Invalid or absent filename timestamps fall back to
mtime. `started_at` is ISO-8601 with offset, using `[calls].timezone`, then ping
`timezone`, then local time. An invalid nonempty timezone override is an error.
`--local-time` selects the PC zone for display.
Files within `min_age_s` of ping `time_ms` are retained with `recording = true`;
this uses the phone clock even when PC and phone clocks differ.

`pull` saves to `recordings/<source or other>/<original filename>` beneath the
data directory, or to `--to`. It resumes partial downloads and skips a complete
file of the same size. It prints the downloaded local paths.

`transcribe` pulls paths starting with `/storage/`, and relative paths that are
not existing local files. Other paths identify local files. SHA-256 of the
complete audio selects the transcript cache; a hit avoids ASR unless `--force`.
Stdout contains transcript text by default, or the unchanged JSON contract with
`--json`. `-o PREFIX` also exports `PREFIX.json` and `PREFIX.txt`.

### 5.4 ASR contract and cache

`asr.transcribe(audio_path, cfg.asr)` returns this fixed shape:

```json
{"backend":"faster-whisper","model":"large-v3","language":"zh","duration_s":1234.5,
 "segments":[{"id":0,"start":0.0,"end":3.2,"speaker":"S1","text":"..."}],
 "text":"full text"}
```

`speaker` may be null. Backends: `funasr` (default, optional speaker clustering),
`faster-whisper`, `command`, `none`. The command backend uses argv placeholders
`{input}` and `{output}`; it must write the JSON above. Heavy libraries load
lazily and models are cached per process. Text rendering uses
`[mm:ss] SPEAKER: text` lines, omitting the speaker label when unavailable.

```text
<data_dir>/
  recordings/<source or other>/<filename>
  transcripts/<audio sha256>.json
  transcripts/<audio sha256>.txt
```

There is no database. Cache files are local artifacts and must not be committed.

### 5.5 Exit codes

`0` means success; `1` means an operation failed (phone, transfer, ASR or
diagnostics); `2` means usage/configuration error, including missing pairing;
`130` means interrupted. An empty listing is successful.

## 6. Privacy

The phone API is token-protected. Anyone who has the phone token and can reach
the server can read codes held for their ten-minute lifetime. Keep code relay
off when it is not needed. Payment, purchase, transfer and withdrawal codes are
never relayed, regardless of settings. Files stay read-only; API writes can
update an app request status or ask the phone to display a notification (see
[REQUESTS.md](REQUESTS.md) and [NOTIFY.md](NOTIFY.md)). Plain HTTP requires a trusted
network or encrypted transport. Recordings and transcripts remain local; do not
upload phone content elsewhere without asking. Models may download on first
use. Recording consent requirements remain the owner's responsibility.
