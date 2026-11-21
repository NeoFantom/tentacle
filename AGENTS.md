# Tentacle — contributor rules (humans and coding agents)

An open-source, self-contained project. Read `docs/ARCHITECTURE.md` first: it is
the single source of truth for module boundaries and contracts.

## Hard rules

- **No machine-specific values in code, tests or docs** (usernames, home paths,
  IPs, hostnames, phone models, tokens). Every tunable goes through
  `pc/src/tentacle/config.py` + `config.example.toml` (PC) or the app's
  settings (Android). Tests use temp dirs and fake servers.
- **Contracts stay compatible within a major version.** The phone HTTP API,
  transcript JSON, documented cache layout and `config.py` field names may grow
  additively: new endpoints and optional fields or values are allowed, but
  existing items must not be removed, renamed, retyped or given a new meaning.
  Old app + new computer and new app + old computer must keep working. Clients
  ignore unknown fields; the computer detects optional support through `ping`
  fields or 404/409 responses and degrades with a plain message. Breaking
  changes require a new major version and a maintainer decision, never an app
  request. Document every API addition in `docs/ARCHITECTURE.md` and its
  feature contract (`docs/REQUESTS.md` or `docs/NOTIFY.md`) in the same change.
- **Stay inside your module's files.** A task names the files it owns; don't
  edit others (shared files like `cli.py` already load modules via
  `add_commands(subparsers)`).
- PC core: Python ≥ 3.11 standard library only. Optional heavy deps (ASR) only
  inside their backend, imported lazily, declared as extras in `pyproject.toml`.
- Android: Kotlin. Allowed runtime dependencies, both only in `:app`:
  `com.github.mwiede:jsch` and the in-repository Go bridge `android/tsbridge`
  (gomobile AAR built from source by Gradle; its Go dependency is
  `tailscale.com`). `:core` stays pure JVM with no runtime dependencies. JUnit
  is test-only. Never commit the generated AAR.
- Every module ships tests that run offline and fast:
  `cd pc && .venv/bin/python -m unittest discover -s tests` and
  `cd android && ./gradlew test`.
- English for code, comments, CLI messages, and docs. Android user-visible text
  is localized in English and Simplified Chinese string resources.
- Never commit secrets, recordings, transcripts, or build outputs.
- Don't `git commit`; the maintainer reviews and commits. The exception is a
  coding agent invoked by a request run, whose fixed prompt requires a commit
  on the request branch.

## Product scope

- `main` is a manual toolkit: phone file browsing, recording listing, pulling
  and local transcription on demand. Automation lives on
  `experimental/automation`; do not reintroduce a PC daemon, automatic analysis,
  project filing, state database or task queue.
- PC package and console command: `tentacle`. Android packages:
  `dev.tentacle` and `dev.tentacle.core`. The agent skill lives in
  `skills/tentacle/SKILL.md`.
- Keep connection guidance in `docs/CONNECT.md`; it is the contract for the
  Android SSH-tunnel and embedded-Tailscale implementations and for the PC's
  path selection.

## Requests from the app

App requests arrive through `tentacle requests watch`. Its implementer agent
decides whether the change belongs upstream (useful to most Tentacle users) or
in the phone owner's personal fork. It implements only the requested change,
adds or updates tests, runs them, commits on the request branch and writes
`Target: upstream` or `Target: personal` as the first line of
`TENTACLE_RESULT_FILE`, followed by the required 1–3 sentence phone-owner
summary. It does not bump versions on an upstream PR branch. When the watcher
reruns a personal request in the configured fork checkout, it bumps the patch
versions before committing. It never pushes, opens a PR or releases. For a
successful no-change result the summary starts with `Done:`; for a refusal,
unclear request or unfound bug cause it starts with `Failed:`.

### Two roles

- The **implementer** handles one owner's request in a writable request branch.
  The watcher checks it and either opens an upstream PR or uses a configured
  separate personal-fork checkout.
- The **maintainer** reviews upstream PRs in a fresh read-only worktree with a
  separate fixed prompt. The reviewer may only inspect files and diffs and
  write its decision; the watcher owns tests, merge, version bump, push and
  release after approval.

Computer-side CLI and implementer prompting live in
`pc/src/tentacle/requests_cmd.py`; Git/GitHub helpers, PR follow-up and
maintainer review live in `requests_git.py`, `requests_pr.py` and
`requests_review.py`. Configuration lives in `pc/src/tentacle/config.py`.
