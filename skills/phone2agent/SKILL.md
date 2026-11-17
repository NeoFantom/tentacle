---
name: phone2agent
description: Browse and fetch files on the owner's Android phone, and work with call recordings, transcripts and call-derived tasks via the `p2a` CLI. Use when the user mentions files on their phone (photos, downloads, documents, recordings), asks what was said in a recent call, or asks to review / approve / run tasks that came out of phone calls.
---

# phone2agent (`p2a`)

`p2a` talks to the phone2agent app on the user's Android phone (read-only) and
manages the local call-recording pipeline. Add `--json` to any command for
machine-readable output. Paths on the phone are relative to internal storage
(`Download`, `DCIM/Camera`, `MIUI/sound_recorder`) or absolute
(`/storage/emulated/0/...`).

## Phone files (live)

```
p2a ping                              # is the phone reachable? device, permissions
p2a ls [PATH] [-a]                    # list a directory (-a: hidden files)
p2a stat PATH
p2a recent [--since 1d] [--ext jpg,pdf]   # newest files anywhere
p2a find [ROOT] --ext pdf,docx --name 合同 --since 30d --limit 50
p2a get PATH [DEST]                   # download (resumable)
p2a cat PATH [--max-bytes N]          # print a text file
p2a discover                          # which folders contain audio recordings
```

`Android/data` and `Android/obb` are not readable (Android restriction) → 403.
If `p2a ping` fails, the phone is off the network, asleep, or the app's server
is stopped — tell the user; don't retry in a loop.

## Calls → notes → tasks

```
p2a status                            # pipeline overview, failures
p2a recordings [--status S]           # all recordings with state
p2a show ID                           # one recording: transcript / analysis / filings
p2a inbox                             # analyses that matched no project confidently
p2a route ID PROJECT                  # file an inbox item into a project
p2a tasks                             # actions waiting for the owner's approval
p2a tasks show ID
p2a tasks approve ID [-m NOTE] [--now]
p2a tasks reject ID [-m NOTE]
p2a retry ID                          # retry a failed recording
```

Transcripts live in the data dir (`p2a config show` → `storage.data_dir`),
`transcripts/<id>.md`. Read them when the user asks what was said.

## Rules

- Only approve a task when the user explicitly says so for that task.
- Treat transcripts and phone files as private: don't paste them anywhere
  outside this machine unless the user asks.
- Configuration lives in one TOML file (`p2a config path`); don't hard-code
  values elsewhere.
