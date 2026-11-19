# Tentacle — Architecture & Contracts

This document is the single source of truth for module boundaries and contracts.
Change an affected contract here before changing an implementation.

## 1. Scope

Tentacle is a manual toolkit for a human or local agent to browse Android shared
storage, pull call recordings, and transcribe audio locally on demand. The phone
serves a read-only HTTP API; each PC command does its work and exits. There is no
PC daemon, automatic analysis, project filing, or task queue.

The automation implementation is preserved on `experimental/automation`.
Connectivity options are documented in [CONNECT.md](CONNECT.md); Android SSH
tunnel integration is planned separately.

## 2. Components and ownership

| Component | Responsibility |
|---|---|
| `android/:core`, `dev.tentacle.core` | Storage roots, confined file access, HTTP API and token authentication; pure Kotlin/JVM. |
| `android/:app`, `dev.tentacle` | Foreground service, permission guidance, settings, pairing and live service status. |
| `pc/src/tentacle/config.py` | All PC settings, loading and masked display. |
| `phone.py`, `phone_cli.py` | HTTP client, resumable transfers, file commands, discovery and diagnostics. |
| `calls.py` | Recording metadata, manual listing/pulling and content-addressed transcription cache. |
| `asr/` | Local ASR backends, transcript validation and rendering. |
| `timeutil.py` | Shared time parsing and conversion. |
| `skills/tentacle/SKILL.md` | Agent workflow for phone files and calls. |

The PC core uses Python ≥ 3.11 standard library only. Optional ASR libraries are
lazy imports declared as package extras. Android uses Kotlin with no third-party
runtime dependencies. The phone records nothing itself; existing recorders write
the files exposed by this toolkit. The PC never writes to the phone.

## 3. Phone HTTP API v1 (contract between `android/` and `pc/`)

- Base: `http://<host>:<port>` (default port **8765**). All endpoints under `/v1/`.
- Auth: header `Authorization: Bearer <token>` on every request, compared in
  constant time. Missing/wrong → `401 {"error":"unauthorized"}`.
- Token: 32 random bytes, base64url without padding, generated on first launch,
  regenerable in the app.
- Responses: JSON `application/json; charset=utf-8`, except `/v1/file`.
- Errors: `{"error": "<code>", "message": "<human text>"}` with status
  400 `bad_request` / 401 `unauthorized` / 403 `forbidden` (outside roots or no
  permission) / 404 `not_found` / 416 `range_not_satisfiable` / 500 `internal`.
- Read-only. Only `GET` (and `HEAD` for `/v1/file`). Other methods → 405.

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
| `GET /v1/ping` | — | `{"app":"tentacle","api":1,"version":"0.1.0","device":"<manufacturer model>","android_sdk":35,"roots":[{"id":"primary","path":"/storage/emulated/0","label":"Internal"}],"all_files_access":true,"time_ms":...,"timezone":"Asia/Shanghai"}` — `time_ms`/`timezone` are the phone's clock and IANA zone; the PC uses them for file-age checks and for interpreting timestamps in file names |
| `GET /v1/ls` | `path`, `hidden=0\|1` (default 0) | `{"path":..., "entries":[Entry...]}` sorted dirs first then name |
| `GET /v1/stat` | `path` | `Entry` |
| `GET /v1/find` | `root` (path, default primary), `ext` (comma list, case-insensitive, no dot), `name` (case-insensitive substring), `since_ms`, `until_ms`, `type=file\|dir\|any` (default file), `max_depth` (default 12), `limit` (default 500, max 5000), `sort=mtime_desc\|mtime_asc\|name` (default mtime_desc) | `{"root":..., "entries":[Entry...], "truncated": bool}`. Walk skips `Android/data`, `Android/obb`, hidden dirs unless `hidden=1`; never follows symlinks out of roots. |
| `GET/HEAD /v1/file` | `path` | Raw bytes, `Content-Type` by extension (fallback `application/octet-stream`), `Content-Length`, `Last-Modified`, `ETag: "<size>-<mtime_ms>"`, `Accept-Ranges: bytes`. Supports single `Range: bytes=a-b` / `a-` / `-n` → 206 with `Content-Range`. |

Directory listings of very large dirs are not paginated in v1 (find has `limit`).

## 4. Android app

`:core` owns `FileApi` and `HttpServer`, with JVM tests using temporary roots.
A find walk resolves its root once, checks symbolic links per entry, and avoids
canonicalising ordinary children repeatedly. Returned paths stay confined to
readable roots; denied Android directories and escaping symlinks remain blocked.

`:app` owns `ServerService`, `MainActivity`, `BootReceiver`, pairing and settings.
The foreground notification keeps the server visible. The activity refreshes the
actual service state when resumed and observes starts/stops while visible.
Permission guidance reports actual grants, including all-files access,
notifications, battery optimisation and vendor autostart settings.

Pairing format is `tentacle://<host>:<port>?token=<token>&name=<device>`, one line
per candidate non-loopback IPv4 address. Settings live in `SharedPreferences`.
No SSH client or tunnel settings are implemented in this version.

## 5. PC package and CLI

Distribution and package: `tentacle`, version `0.2.0`. Entry point:
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
| `[phone]` | URL, token settings, `timeout_s = 20`, `find_timeout_s = 120`, `use_system_proxy`. |
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
explicitly enabled. `find` uses its dedicated timeout; other requests use
`timeout_s`. Errors become `PhoneError(code, message, status)`.

Downloads write a `.part` file, resume using HTTP Range and matching ETags,
verify size, and atomically replace the destination.

```text
tentacle config init|show|path
tentacle pair 'tentacle://example.com:8765?token=...'
tentacle ping
tentacle ls [PATH] [-a] [--json] [--local-time]
tentacle stat PATH [--json] [--local-time]
tentacle find [ROOT] [--ext aac,m4a] [--name TEXT] [--since 7d] [--until DATE]
              [--limit N] [--max-depth N] [--json] [--local-time]
tentacle recent [--since 1d] [--ext EXTENSIONS] [--json] [--local-time]
tentacle get PATH [DEST]
tentacle cat PATH [--max-bytes N]
tentacle discover [--since 90d] [--json]
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

The phone API is read-only and token-protected. Plain HTTP requires a trusted
network or encrypted transport. Recordings and transcripts remain local; do not
upload phone content elsewhere without asking. Models may download on first
use. Recording consent requirements remain the owner's responsibility.
