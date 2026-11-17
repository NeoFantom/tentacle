# phone2agent — contributor rules (humans and coding agents)

An open-source, self-contained project. Read `docs/ARCHITECTURE.md` first: it is
the single source of truth for module boundaries and contracts.

## Hard rules

- **No machine-specific values in code, tests or docs** (usernames, home paths,
  IPs, hostnames, phone models, tokens). Every tunable goes through
  `pc/src/phone2agent/config.py` + `config.example.toml` (PC) or the app's
  settings (Android). Tests use temp dirs and fake servers.
- **Contracts are fixed.** Do not change the phone HTTP API, the store schema,
  the transcript / analysis / filing JSON shapes, or `config.py` field names on
  your own. If your module truly needs a change, stop and report it.
- **Stay inside your module's files.** A task names the files it owns; don't
  edit others (shared files like `cli.py` already load modules via
  `add_commands(subparsers)`).
- PC core: Python ≥ 3.11 standard library only. Optional heavy deps (ASR) only
  inside their backend, imported lazily, declared as extras in `pyproject.toml`.
- Android: Kotlin, no third-party runtime dependencies (JUnit for tests is ok).
- Every module ships tests that run offline and fast:
  `cd pc && .venv/bin/python -m unittest discover -s tests` and
  `cd android && ./gradlew test`.
- English for code, comments, CLI messages, and docs.
- Never commit secrets, recordings, transcripts, or build outputs.
- Don't `git commit`; the maintainer reviews and commits.
