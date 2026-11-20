# Tentacle — contributor rules (humans and coding agents)

An open-source, self-contained project. Read `docs/ARCHITECTURE.md` first: it is
the single source of truth for module boundaries and contracts.

## Hard rules

- **No machine-specific values in code, tests or docs** (usernames, home paths,
  IPs, hostnames, phone models, tokens). Every tunable goes through
  `pc/src/tentacle/config.py` + `config.example.toml` (PC) or the app's
  settings (Android). Tests use temp dirs and fake servers.
- **Contracts are fixed.** Do not change the phone HTTP API, the transcript
  JSON shape, the documented cache layout, or `config.py` field names on
  your own. If your module truly needs a change, stop and report it.
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
- Don't `git commit`; the maintainer reviews and commits.

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

App requests arrive through `tentacle requests watch`. When its fixed prompt
invokes a coding agent, read and obey that prompt: implement only the requested
Tentacle change, add or update tests, run them, bump the patch versions, commit
the result, and write the required 1–3 sentence phone-owner summary to
`TENTACLE_RESULT_FILE`. Do not push or release; the watcher performs both after
checking the commit. For a successful no-change result write `Done: ...`; for a
refusal, unclear request, or unfound bug cause write `Failed: ...`.

The computer-side configuration and fixed prompt live in
`pc/src/tentacle/requests_cmd.py` and `pc/src/tentacle/config.py`.
