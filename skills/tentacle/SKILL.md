---
name: tentacle
description: Browse and fetch files on the user's Android phone and transcribe call recordings on demand with the local Tentacle CLI. Use for phone photos, downloads, documents, recordings, or questions such as "what did X say on the call?".
---

# Tentacle

Use `tentacle` to access the phone's read-only file API and local transcription
cache. Phone paths can be relative to shared storage (`Download`, `DCIM/Camera`)
or absolute (`/storage/emulated/0/...`). Configuration is discoverable through
`tentacle config path` and `tentacle config show` (secrets masked).

## Questions about calls

1. Run `tentacle ping` to check reachability and permissions.
2. Run `tentacle calls --with NAME --since 7d --json`, adjusting contact and time
   window to the request. `--app wechat` or `--source NAME` narrows the results.
3. Run `tentacle transcribe '<path from calls>'` and answer from the transcript.
   Use `--json` when segment timestamps or speaker labels matter.

`calls` lists current phone recordings, newest first, with app, counterpart,
start time, size, source, phone path and any cached local path. The default
window is seven days. `--limit 50`
applies per source. `recording: true` means the file may still be growing;
prefer a completed recording when available. `--until` bounds the search.
Human times use the phone's timezone; `--local-time` selects the PC timezone.

Transcription pulls phone paths first and reuses the audio SHA-256 cache.
`--force` regenerates a transcript; `-o PREFIX` exports `.json` and `.txt`.
`tentacle pull PHONE_PATH... [--to DIR]` downloads without transcription.
The default cache is `recordings/<source or other>/<filename>` and
`transcripts/<audio sha256>.json` / `.txt` beneath `storage.data_dir`.

## Phone files

```text
tentacle ls [PATH] [-a] [--json]
tentacle stat PATH [--json]
tentacle find [ROOT] --ext jpg,pdf --name TEXT --since 7d --limit 50 --json
tentacle recent --since 1d --ext jpg --json
tentacle get PATH [DEST]
tentacle cat PATH --max-bytes 1048576
tentacle discover --json
tentacle doctor --json
```

Start with a likely folder and narrow filters for large storage trees.
`get` and `pull` support resumable transfers. `discover` probes known recorder
locations and suggests source settings. Android blocks `Android/data` and
`Android/obb`; a 403 does not imply those directories are empty.

Exit codes: `0` success (including an empty listing), `1` operational failure,
`2` usage/configuration error or missing pairing, `130` interrupted. If ping
fails, report the error and relevant connection/service state; stop repeated
requests until reachability changes.

Phone content is private. Never upload files, audio or transcripts elsewhere
without asking. Keep work within the user's request; Tentacle has no automatic
analysis, project filing or task queue.
