# phone2agent — Architecture & Contracts

This document is the single source of truth for module boundaries and the
contracts between them. Every module implementation must conform to it; if a
contract has to change, change this file first.

## 1. What the system does

1. A local coding agent (Codex, Claude Code, ...) can browse the phone's shared
   storage in real time with one-line commands: `p2a ls`, `p2a find`, `p2a get`.
2. Call recordings on the phone (system call recorder, Xiaomi "AI 录音纪要" for
   WeChat/Feishu/QQ calls, any app that writes audio to shared storage) are
   picked up automatically.
3. The PC transcribes them locally (pluggable ASR).
4. An agent analyses each transcript into structured items (todos, project
   progress, approach, handoff info, decisions), routes them to the matching
   local project folder, writes the key points into that project following the
   project's own conventions, commits and pushes.
5. Actions that need the owner's go-ahead become *tasks*; once the owner
   approves one (`p2a tasks approve <id>`), an agent starts working on it with
   the call context attached.

Non-goals (v1): writing to the phone, cloud relay server, iOS, recording calls
itself (the phone's own recorders do that; we only collect their files).

## 2. Components

```
┌──────────── Android phone ────────────┐        ┌──────────────────── PC ─────────────────────┐
│ phone2agent app                       │  HTTP  │ p2a (Python package)                         │
│  :core  FileApi + HttpServer (JVM)    │◄───────┤  phone.py   client  ── p2a ls/find/get/...  │
│  :app   ForegroundService, pairing UI │ Bearer │  sync.py    sources → data_dir/recordings    │
│         permissions, boot start       │ token  │  asr/       audio → transcript.json          │
└───────────────────────────────────────┘        │  analyze.py transcript → analysis.json       │
  read-only access to shared storage             │  dispatch.py analysis → project notes, git   │
  (MANAGE_EXTERNAL_STORAGE)                      │  tasks      approval queue → agent run       │
                                                 │  pipeline.py daemon loop, notify hook        │
                                                 │  store.py   SQLite state                     │
                                                 └──────────────────────────────────────────────┘
```

Transport: the phone is the server, the PC pulls. Reachability (same Wi-Fi,
Tailscale, USB `adb forward`) is the user's choice; the PC only needs a URL.
Pull is idempotent: files stay on the phone, the PC remembers what it fetched.

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
| `GET /v1/ping` | — | `{"app":"phone2agent","api":1,"version":"0.1.0","device":"<manufacturer model>","android_sdk":35,"roots":[{"id":"primary","path":"/storage/emulated/0","label":"Internal"}],"all_files_access":true,"time_ms":...}` |
| `GET /v1/ls` | `path`, `hidden=0\|1` (default 0) | `{"path":..., "entries":[Entry...]}` sorted dirs first then name |
| `GET /v1/stat` | `path` | `Entry` |
| `GET /v1/find` | `root` (path, default primary), `ext` (comma list, case-insensitive, no dot), `name` (case-insensitive substring), `since_ms`, `until_ms`, `type=file\|dir\|any` (default file), `max_depth` (default 12), `limit` (default 500, max 5000), `sort=mtime_desc\|mtime_asc\|name` (default mtime_desc) | `{"root":..., "entries":[Entry...], "truncated": bool}`. Walk skips `Android/data`, `Android/obb`, hidden dirs unless `hidden=1`; never follows symlinks out of roots. |
| `GET/HEAD /v1/file` | `path` | Raw bytes, `Content-Type` by extension (fallback `application/octet-stream`), `Content-Length`, `Last-Modified`, `ETag: "<size>-<mtime_ms>"`, `Accept-Ranges: bytes`. Supports single `Range: bytes=a-b` / `a-` / `-n` → 206 with `Content-Range`. |

Directory listings of very large dirs are not paginated in v1 (find has `limit`).

## 4. Android app (`android/`)

Gradle project, Kotlin, minSdk 26, targetSdk 35, **no third-party runtime
dependencies**.

- `:core` — pure Kotlin/JVM library, no Android imports. `FileApi` (roots,
  path resolution, listing, find, stat, range reads) and `HttpServer` (minimal
  HTTP/1.1 on `ServerSocket`, thread pool, keep-alive optional, request size
  limits, Bearer auth). Fully unit-tested on the JVM with temp directories.
- `:app` — Android shell: `ServerService` foreground service (type
  `dataSync`/`specialUse`, persistent notification showing address), `BootReceiver`
  (restart if enabled), `MainActivity` (status, permission guidance, start/stop,
  port, token show/copy/regenerate, pair string). Settings in
  `SharedPreferences`. Pair string format:
  `p2a://<host>:<port>?token=<token>&name=<device>` — one line per candidate
  address (Wi-Fi IPv4, Tailscale `100.x`, other non-loopback IPv4).
- Permission guidance: All files access (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`),
  notifications (API 33+), ignore battery optimisations, and a best-effort link
  to vendor autostart settings (Xiaomi) with fallback to app details. The UI
  reports the *actual* permission state, never "granted" because a settings
  page was opened.

## 5. PC package (`pc/`, Python ≥ 3.11)

Core uses the standard library only. ASR backends are optional extras.
Entry point: `p2a` (`phone2agent.cli:main`).

### 5.1 Configuration — `config.py` (owner: architecture)

All tunables live in ONE TOML file; nothing machine-specific is hard-coded.
Lookup: `--config PATH` > `$P2A_CONFIG` > `$XDG_CONFIG_HOME/phone2agent/config.toml`
(default `~/.config/phone2agent/config.toml`). `p2a config init` writes the
commented template `pc/src/phone2agent/config.example.toml`; `p2a config show`
prints the effective config with secrets masked. Secrets (phone token) may be
given inline, via `token_file`, or `token_env`. `~` and `$VARS` in path values
are expanded. Modules receive a `Config` object; they never read env vars or
files for settings themselves.

### 5.2 State store — `store.py`

SQLite at `<data_dir>/state.db`, WAL mode, schema versioned with
`PRAGMA user_version` and forward-only migrations.

```sql
CREATE TABLE recordings (
  id            INTEGER PRIMARY KEY,
  source        TEXT NOT NULL,          -- source name from config
  phone_path    TEXT NOT NULL,
  size          INTEGER NOT NULL,
  mtime_ms      INTEGER NOT NULL,
  sha256        TEXT,                   -- of the downloaded file
  local_path    TEXT,                   -- under <data_dir>/recordings/
  app           TEXT,                   -- parsed: wechat / phone / feishu / ...
  counterpart   TEXT,                   -- parsed contact name / number
  started_at    TEXT,                   -- ISO-8601 local time, parsed from name or mtime
  duration_s    REAL,
  status        TEXT NOT NULL,          -- discovered|downloaded|transcribed|analyzed|filed|skipped|failed
  failed_stage  TEXT,
  error         TEXT,
  attempts      INTEGER NOT NULL DEFAULT 0,
  next_try_at   TEXT,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  UNIQUE(phone_path, size, mtime_ms)
);
CREATE TABLE artifacts (               -- transcript / analysis / filing results
  id INTEGER PRIMARY KEY,
  recording_id INTEGER NOT NULL REFERENCES recordings(id),
  kind TEXT NOT NULL,                    -- transcript|analysis|filing
  path TEXT NOT NULL,                    -- file under data_dir
  meta TEXT,                             -- JSON
  created_at TEXT NOT NULL
);
CREATE TABLE tasks (
  id INTEGER PRIMARY KEY,
  recording_id INTEGER REFERENCES recordings(id),
  project TEXT,                          -- project id or NULL
  title TEXT NOT NULL,
  instructions TEXT NOT NULL,
  reason TEXT,
  status TEXT NOT NULL,                  -- pending|approved|running|done|failed|rejected
  owner_note TEXT,                       -- extra instruction given at approval
  result_path TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE events (id INTEGER PRIMARY KEY, at TEXT NOT NULL, level TEXT NOT NULL,
  recording_id INTEGER, task_id INTEGER, message TEXT NOT NULL);
```

Stage functions transition `status` forward; on error they set `failed`,
`failed_stage`, `error`, increment `attempts`, and set `next_try_at` with
exponential backoff (5 min × 2^n, cap 24 h, give up after `pipeline.max_attempts`).
`p2a retry <id>` resets a failed recording to the stage before `failed_stage`.

Data layout under `data_dir`:
```
recordings/<YYYY-MM>/<id>-<sanitised original name>
transcripts/<id>.json   transcripts/<id>.md
analyses/<id>.json
filings/<id>-<project>.json
tasks/<task id>.md      (agent final report)
logs/agent/<timestamp>-<kind>-<id>.log
inbox/<id>.md           (analysis with no confident project match)
```

### 5.3 Phone client — `phone.py` (+ file commands in CLI)

`PhoneClient(cfg.phone)` with methods `ping()`, `ls(path, hidden=False)`,
`stat(path)`, `find(**filters)`, `download(path, dest, resume=True)` (atomic:
write `.part`, verify size, rename; resumes with Range), `read_bytes(path,
limit)`. Bypasses system proxies unless `phone.use_system_proxy = true`
(phones on LAN/Tailscale are usually not reachable through a proxy). Errors map
to `PhoneError(code, message, status)`.

CLI (human-readable by default, `--json` for machine output):
```
p2a pair 'p2a://192.168.1.5:8765?token=...'   # writes phone.url/token into config
p2a ping
p2a ls [PATH] [-a]
p2a stat PATH
p2a find [ROOT] [--ext aac,m4a] [--name STR] [--since 2d|2026-11-01] [--limit N]
p2a recent [--since 1d] [--ext ...]            # find sorted by mtime, default since 1d
p2a get PATH [DEST]                            # download (default ./<name>)
p2a cat PATH [--max-bytes N]                   # print text file
p2a discover                                   # list dirs that contain audio files, to help configure sources
```

### 5.4 Sync — `sync.py`

For each `[[sources]]` entry: `find(root=path, ext=extensions, since_ms=...)`;
skip files younger than `min_age_s` (still being written); insert unseen
`(phone_path,size,mtime_ms)` as `discovered`; download to
`recordings/<YYYY-MM>/`, compute sha256, parse metadata with the source's
`name_pattern` regex (named groups `app`, `counterpart`, `ts` with `ts_format`),
→ `downloaded`. Duplicate content (same sha256 already stored) → `skipped`.
`sync.backfill_days` bounds the very first scan. Phone unreachable is not an
error for recordings: log an event and return.

### 5.5 ASR — `asr/`

Interface: `transcribe(audio_path, cfg.asr) -> Transcript` where the JSON on disk is
```json
{"backend":"faster-whisper","model":"large-v3","language":"zh","duration_s":1234.5,
 "segments":[{"id":0,"start":0.0,"end":3.2,"speaker":"S1","text":"..."}],
 "text":"full text"}
```
`speaker` may be null when the backend has no diarisation. Backends:
`funasr` (default: paraformer-zh + fsmn-vad + ct-punc + cam++ speaker clustering), `faster-whisper`, `command` (external program: template with
`{input}` and `{output}` that must write the JSON above), `none`. Heavy imports
happen lazily inside the backend; models are cached per process.
`transcripts/<id>.md` is a readable rendering (`[mm:ss] S1: text`).

### 5.6 Agent runner — `agent.py`

One function: `run_agent(cfg.agent, prompt, *, cwd, mode, output_schema=None,
log_path) -> AgentResult(ok, final_message, json, returncode, log_path)`.
`mode` ∈ `read-only` | `workspace-write`. `cfg.agent.kind = "codex"` builds
`codex exec --skip-git-repo-check --sandbox <mode> [--output-schema F]
--output-last-message OUT -C <cwd> -` with `-c` overrides from
`cfg.agent.extra_args`; `kind = "command"` uses `cfg.agent.command` template with
`{prompt_file} {output_file} {schema_file} {cwd} {mode}` placeholders, so any
agent CLI can be plugged in. Prompt is passed on stdin or via file, never in
argv. Timeout `cfg.agent.timeout_s`; full stdout/stderr go to `log_path`.

### 5.7 Project registry — `projects.py`

`list_projects(cfg.projects) -> [Project(id, path, description, has_git,
has_remote)]`. Projects = immediate subdirectories of each `projects.roots`
entry (plus explicit `projects.extra`), minus `projects.exclude` globs.
Description: if `projects.index_file` is set, the paragraph under the matching
`### <id>` heading; else first ~400 chars of `AGENTS.md`/`README.md`.

### 5.8 Analysis — `analyze.py`

Builds a prompt from `prompts/analyze.md` (+ owner name, call metadata,
transcript markdown, project list) and runs the agent in `read-only` mode in
`data_dir` with `schemas/analysis.schema.json`. Validates the JSON (stdlib
validator for the subset of JSON Schema we use), saves `analyses/<id>.json`.

Analysis JSON contract (`schemas/analysis.schema.json`):
```json
{
  "title": "short title of the call",
  "summary": "3-6 sentence summary",
  "participants": [{"name": "Neo", "role": "owner"}],
  "items": [{"kind": "todo|progress|approach|handoff|decision|info|risk",
             "text": "...", "owner": "who is responsible or null",
             "due": "free text or null", "project": "project id or null",
             "quote": "short supporting quote from transcript"}],
  "routes": [{"project": "id", "confidence": 0.0, "reason": "...",
              "notes_markdown": "the key content to record in this project"}],
  "actions": [{"title": "...", "project": "id or null",
               "instructions": "what an agent should do, self-contained",
               "reason": "why this needs the owner's go-ahead"}]
}
```

### 5.9 Dispatch — `dispatch.py` and tasks

For each route with `confidence >= projects.min_confidence`: run the agent in
`workspace-write` mode with `cwd = project.path` using `prompts/file_notes.md`:
read the project's AGENTS.md/CLAUDE.md/README conventions, write the notes
where that project keeps such records (fallback `projects.default_notes_dir`,
e.g. `notes/calls/<date>-<slug>.md`), never include the raw transcript unless
`projects.include_transcript`, touch nothing else, and **not run git**. Its
final message is JSON (`schemas/filing.schema.json`: `{files, commit_message,
report}`). **p2a itself does the git work deterministically** (agents' sandboxes
usually forbid network and `.git` writes): verify each listed file is inside the
project and actually changed, then if `projects.auto_commit` run
`git add -- <files>` + `git commit -m <msg> -- <files>` (never touching other
uncommitted changes), and if `projects.auto_push` and the branch has an
upstream, `git push` (no force; failure is recorded, not retried blindly).
The result (`files, commit, pushed, report, error`) is saved to
`filings/<id>-<project>.json`. Routes below threshold go to
`inbox/<id>.md` and are listed by `p2a inbox`; `p2a route <rec-id> <project>`
files them manually.

Each `actions[]` entry becomes a `pending` task (or auto-runs if
`tasks.require_approval = false`). CLI:
```
p2a tasks [--all]                 # list pending (or all)
p2a tasks show ID
p2a tasks approve ID [-m NOTE]    # → runs agent in project dir with prompts/run_task.md,
                                  #   same JSON result + deterministic commit/push as filing
p2a tasks reject ID [-m NOTE]
```

### 5.10 Pipeline & daemon — `pipeline.py`, `notify.py`

`p2a run` = one pass: sync → for each recording due: transcribe → analyze →
dispatch; then run approved tasks. `p2a daemon` loops every
`sync.interval_s`, single instance via a lock file in `data_dir`. Stages run
sequentially (one GPU, one agent at a time, configurable later). `p2a status`
shows counts per status, last sync, failures. `p2a retry ID`.
`notify.command` (argv template with `{title}`, `{body}`) is called for: new
pending task, filing done, recording failed permanently. Systemd user unit in
`pc/contrib/systemd/p2a.service`.

## 6. Privacy & safety defaults

- Phone server is read-only, token-protected, binds all interfaces; recommend
  Tailscale or a trusted LAN (HTTP is not encrypted on plain Wi-Fi).
- Audio, transcripts and analyses stay in `data_dir`. Only `notes_markdown`
  (key points) goes into project repos unless configured otherwise.
- The analysis agent runs read-only; filing agents are confined to one project
  directory; tasks run only after explicit approval by default.
- Recording calls may require consent of all parties in your jurisdiction.
