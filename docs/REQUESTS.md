# Requests from the app

The phone owner types a change request (or taps "Upload log & fix") in the
Tentacle app. The computer that uses this phone picks it up, runs a configured
AI coding agent in this repository, checks the result, releases a new version,
and the app's in-app updater offers it. The phone shows the progress.

This document is the contract between the app (`android/`) and the computer
tool (`pc/`, `tentacle requests …`). Setup and security notes for users are in
the README ("Requests from the app").

## Request object

```json
{"id": "r-1763371721000-ab12", "kind": "feature", "scope": "tentacle-requests/1",
 "text": "Add a dark mode switch", "created_ms": 1763371721000,
 "updated_ms": 1763371725000, "status": "new", "message": "", "has_log": false}
```

- `id`: 1–64 characters of `[A-Za-z0-9_-]`, assigned by the phone.
- `kind`: `feature` or `bug` (bug requests come from "Upload log & fix").
- `scope`: fixed scope version added by the phone, never user-editable. The
  current value is `tentacle-requests/1`; the computer refuses requests with a
  missing or different scope.
- `text`: the owner's words, at most 1000 units (see below), no control
  characters except `\n` and `\t`, at most 8000 UTF-16 code units in total.
  May be empty only for `bug`.
- `status`: `new` → `working` → `done` | `failed`. `done` and `failed` are
  final. Allowed transitions: `new → working | failed`, `working → done |
  failed | new` (a computer may give a claimed request back), nothing from a
  final state.
- `message`: short plain text for the phone, at most 1000 characters, set by
  the computer (result summary, failure reason).
- `has_log`: whether a log snapshot is attached.

The phone keeps the newest 20 requests (and their logs) in app-private storage.
Requests are created only by the app UI, never through the API.

## Length limit (shared by app and computer)

`units(text)`, scanning code points left to right:

- a **CJK character** counts 1. CJK means a code point in one of these ranges
  (Han, kana, Hangul): U+1100–11FF, U+2E80–2FDF, U+3005, U+3007, U+3021–3029,
  U+3038–303B, U+3041–309F, U+30A0–30FF, U+3130–318F, U+31F0–31FF,
  U+3400–4DBF, U+4E00–9FFF, U+A960–A97F, U+AC00–D7AF, U+D7B0–D7FF,
  U+F900–FAFF, U+FF66–FF9F, U+20000–2FA1F, U+30000–3134F;
- every maximal **run** of other letters/digits (Unicode general category L* or
  N*) counts 1; a combining mark (M*) directly after a run continues it;
- everything else (whitespace, punctuation, symbols, emoji) counts 0 and ends a run.

A request is valid when `units(text) ≤ 1000` (and the other text rules above hold).

Test vectors used by both implementations: `docs/request-length-vectors.json`.

## Phone API (additive to API v1, token-protected)

| Method & path | Body / query | Response |
|---|---|---|
| `GET /v1/requests` | `wait` = 0–60 s (default 0) | `{"requests": [Request…] (newest first), "now_ms": …}`. With `wait > 0` the call blocks until a request with status `new` exists or the time is up. Every call records the computer's last poll time, which the app shows. |
| `POST /v1/requests/<id>` | `{"status": "working"\|"done"\|"failed"\|"new", "message": "…"}` (JSON, ≤ 16 KiB) | The updated Request. `404 not_found` for an unknown id, `400 bad_request` for an invalid status/message/body, `409 conflict` for a transition that is not allowed. |
| `GET /v1/requests/<id>/log` | — | The attached log snapshot, `text/plain; charset=utf-8`; `404 not_found` if none. |

This is the only write in the API, and it only changes a request's status and
message. File access stays read-only.

## App log

The app keeps a rotating log in app-private storage (2 files × 512 KiB):
service start/stop, HTTP requests (method, path, status, bytes, duration),
tunnel and Tailscale state changes and errors, network changes, update checks
and crashes. Tokens, auth keys, login URLs, passwords and query strings are
redacted before anything is written. "Upload log & fix" attaches a snapshot
(header: app version, Android version, device model) to a new `bug` request.

## Computer side

`tentacle requests list [--json]` displays the newest requests. `setup` writes
only the `[requests]` configuration, including the required agent command,
shell, repository, optional test/release/push commands, shared timeout and
protected paths. `watch` long-polls with `wait=55`, processes the oldest `new`
request one at a time, and uses an exclusive lock in the data directory.
`watch --once` performs one poll and handles at most one request; `run-once` is
an alias. There is no default agent command.

Each run is recorded in `<data_dir>/requests/<id>/`: `request.json`,
`prompt.txt`, `agent.log`, `result.txt`, `watcher.log`, and `log.txt` for a bug
request. The unchanged phone object is kept in `request.json`; a separate local
`state.json` records this computer's claim and any completion waiting to be
reported. At startup the watcher fails only stale `working` requests that have
such a local claim, with
the message "The computer restarted while working on this; send it again."
It does not take over a request claimed by another computer.

The watcher validates the complete request before running any command. It then
requires a clean Git work tree, records `HEAD`, claims the request, downloads a
bug log, and builds the agent prompt. Fixed non-overridable rules appear both
before and after the randomly delimited request data. A configured
`prompt_template` replaces only the middle instructions. The fixed rules allow
the agent to access the explicitly named request artifacts (`prompt.txt`,
`result.txt`, and a bug's `log.txt`) for input and output; all other access
outside the repository remains forbidden. This prompt is defense in depth and
does not replace sandboxing the configured agent.

The agent must implement and test the change, bump the patch versions, commit,
and write a 1–3 sentence result. It must not push or release. With a new commit,
the result is an ordinary summary. With no new commit, `result.txt` must start
with `Done:` for a successful no-code-needed outcome or `Failed:` for a refusal,
unclear request, impossible request, or a bug whose cause was not found. The
watcher removes this metadata prefix before sending the remaining text to the
phone; without a recognized prefix, a no-commit run fails conservatively.

After the agent exits, the watcher requires a clean work tree, verifies that
`HEAD` descends from the recorded base, and checks changed paths. It rejects
`.github/**`, keystores, PKCS #12 files, update download/verification code,
`Tokens.kt`, and signing-related changes in `android/app/build.gradle.kts`.
The watcher then runs the configured test, push, and release commands itself.
Agent, test, push, and release share one timeout budget (two hours by default).
Commits remain local until the watcher reaches the push step; a release failure
reports that the commits have already been pushed. An empty release command
finishes successfully and reports that no release command was configured.

When a request creates commits but fails before push, the watcher records its
rejected `HEAD` in `<data_dir>/requests-blocked.json`. A later request for the
same repository fails while that commit is an ancestor of the current `HEAD`;
this prevents a later successful request from pushing previously rejected
changes. The watcher does not reset or rewrite Git history. The maintainer must
review the checkout and remove the failed history manually.
If recovery cannot inspect interrupted history, it records an unconditional
blocker instead. The maintainer must review that checkout and marker before
accepting more requests; the watcher stops if it cannot persist either blocker.
