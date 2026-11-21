# Requests from the app

The phone owner types a change request (or taps "Upload log & fix") in the
Tentacle app. The computer that uses this phone picks it up and runs a configured
implementer agent. A generally useful change becomes an upstream pull request;
a personal change may run in a separately configured fork checkout. The
maintainer's computer reviews upstream pull requests and releases approved
changes. The phone shows the progress.

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
- `status`: `new` → `working` → `done` | `failed`. Allowed transitions are
  `new → working | failed`, `working → done | failed | new` (a computer may
  give a claimed request back), `done | failed → done | failed` (a later PR
  outcome updates the message and time while the request stays final), and
  `failed → new` (the owner retries it).
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

`GET /v1/ping` may include `"requests":{"followup":true,"retry":true}`.
`followup` advertises final-to-final message updates and `retry` advertises
`failed → new`. An older app omits the whole field. The computer uses these
flags when present. For compatibility, it may still try a follow-up when the
field is absent: a 409 is treated as an old phone, retried on later checks for
up to seven days, then dropped with a log entry. An explicit retry on an old
phone fails with a plain message asking the owner to update the app.

| Method & path | Body / query | Response |
|---|---|---|
| `GET /v1/requests` | `wait` = 0–60 s (default 0) | `{"requests": [Request…] (newest first), "now_ms": …}`. With `wait > 0` the call blocks until a request with status `new` exists or the time is up. Every call records the computer's last poll time, which the app shows. |
| `POST /v1/requests/<id>` | `{"status": "working"\|"done"\|"failed"\|"new", "message": "…"}` (JSON, ≤ 16 KiB) | The updated Request. `404 not_found` for an unknown id, `400 bad_request` for an invalid status/message/body, `409 conflict` for a transition that is not allowed. |
| `GET /v1/requests/<id>/log` | — | The attached log snapshot, `text/plain; charset=utf-8`; `404 not_found` if none. |

This write changes only a request's status and message. The separate
`POST /v1/notify` write asks the phone to display a notification; file access
stays read-only. See [NOTIFY.md](NOTIFY.md).

## App log

The app keeps a rotating log in app-private storage (2 files × 512 KiB):
service start/stop, HTTP requests (method, path, status, bytes, duration),
tunnel and Tailscale state changes and errors, network changes, update checks
and crashes. Tokens, auth keys, login URLs, passwords and query strings are
redacted before anything is written. "Upload log & fix" attaches a snapshot
(header: app version, Android version, device model) to a new `bug` request.

## Computer side

`tentacle requests list [--json]` displays the newest requests. `setup` writes
only `[requests]`, including the implementer command, shell, repository, base,
push remote and PR repository; optional personal-fork checkout and release;
optional maintainer review command; tests, release, timeout and protected
paths. `watch` long-polls with `wait=55`, processes the oldest `new` request
one at a time and uses an exclusive lock in the data directory. `watch --once`
performs one poll and handles at most one request; `run-once` is an alias.
There is no default implementer or reviewer command. Maintainer review from
`watch` is disabled unless `review_command` is configured.

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

The implementer first classifies the target: `upstream` when the change is
useful to most Tentacle users, or `personal` when it fits only this owner. The
first result line is `Target: upstream` or `Target: personal` (case-insensitive),
followed by a 1–3 sentence summary. Missing or invalid target metadata fails
with "agent did not state a target". With no new commit, the summary begins
with `Done:` for a successful no-code-needed outcome or `Failed:` for a refusal,
unclear request, impossible request or bug whose cause was not found. The agent
must not bump versions on an upstream PR branch, push, open a PR or release.

After the agent exits, the watcher requires a clean work tree, verifies that
`HEAD` descends from the recorded base, and checks changed paths. It rejects
`.github/**`, keystores, PKCS #12 files, update download/verification code,
`Tokens.kt`, and signing-related changes in `android/app/build.gradle.kts`.
Before the implementer runs, the watcher fetches and creates
`tentacle-request/<id>` from the configured `base`, or the push remote's default
branch when `base` is empty. If that branch name already exists during retry,
the watcher appends a random suffix so earlier history is not overwritten. For
`upstream`, it runs tests, pushes the branch to
`push_remote` (`origin` by default) and opens a PR on `pr_repo`. When `pr_repo`
is empty it resolves the push repository, using its GitHub parent when it is a
fork. The PR contains the summary, quoted untrusted request data, request
id/kind and marker `<!-- tentacle-request:v1 id=<id> -->`. Missing or
unauthenticated `gh` fails with a plain message.

For `personal`, the upstream checkout remains unchanged. If `fork_repo` is set,
the watcher reruns the implementer there with the target fixed to personal. The
personal prompt requires patch version bumps for changed code, after which the
watcher uses the direct test, push and `fork_release` flow. Otherwise the
request fails with a plain explanation and the setup command needed to
configure a personal fork.

The watcher stores open PRs atomically in `<data_dir>/requests-prs.json` and
checks them at most once every five minutes while idle. A merged PR updates the
phone with the merge outcome; a closed PR reports the maintainer's last plain
comment, or a localized default. Use `tentacle requests retry <id>` to return a
failed request to `new` when the phone advertises retry support. Both computer
and app-UI retries supersede older PR follow-ups for the same id so an old
decision cannot overwrite the new attempt.

When a request creates commits but fails before push, the watcher records its
rejected `HEAD` in `<data_dir>/requests-blocked.json`. A later request for the
same repository fails while that commit is an ancestor of the current `HEAD`;
this prevents a later successful request from pushing previously rejected
changes. The watcher does not reset or rewrite Git history. The maintainer must
review the checkout and remove the failed history manually.
If recovery cannot inspect interrupted history, it records an unconditional
blocker instead. The maintainer must review that checkout and marker before
accepting more requests; the watcher stops if it cannot persist either blocker.

## Maintainer review

`tentacle requests review [--once]` reviews marked open PRs in oldest-first
order. `watch` performs the same work between requests only when
`review_command` is configured. Decisions at the same PR head SHA are recorded
atomically in `<data_dir>/reviews.json` and skipped. The decision is recorded
before its action, so an operational failure on a decided head is reported
once; a new head is reviewed again.

The reviewer runs in a fresh worktree under the data directory. Its separate,
fixed prompt sandwiches the untrusted PR title, body, diff, comments and request
text between random boundaries. It judges general usefulness and product fit;
security, including authentication, update verification, signing, telemetry,
secrets, third-party network access and malicious build code; major-version API
compatibility; tests; and code quality. It writes `Decision: merge` or
`Decision: close`, followed by a 1–3 sentence requester-facing note. It may only
read files and `git diff`: it must not edit, run code, push, merge or release.

For close, the watcher comments and closes the PR. A merge requires a configured
release command; without one the approved PR stays open. For merge, it first
applies the protected-path check and runs tests in the worktree, then squash-merges
(falling back to a merge commit), removes the worktree, fetches the default
branch, bumps Android and PC patch versions in one maintainer commit, pushes,
runs the release command and comments the released version. A failure before
merge comments the error, leaves the PR open and does not release. A failure
after merge reports the incomplete version-bump, push or release step without
claiming that a release succeeded. Review worktrees are removed even after
failure.
