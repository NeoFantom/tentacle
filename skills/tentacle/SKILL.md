---
name: tentacle
description: Browse and fetch files on the user's Android phone, transcribe call recordings, and receive verification codes on demand with the local Tentacle CLI. Use for phone photos, downloads, documents, recordings, verification codes, or questions such as "what did X say on the call?".
---

# Tentacle

Use `tentacle` to access the phone's read-only file API, short-lived
verification-code feed and local transcription cache. Phone paths can be
relative to shared storage (`Download`, `DCIM/Camera`)
or absolute (`/storage/emulated/0/...`). Configuration is discoverable through
`tentacle config path` and `tentacle config show` (secrets masked).
Run `tentacle requests list` to see what the phone owner has asked the computer
to change; do not start the watcher or act on a request unless explicitly asked.

## Verification codes

When a login or verification flow needs a code:

1. Note the current time before triggering the website's **Send code** action.
2. Trigger **Send code**.
3. Run `tentacle code --from '<brand>' --wait 120` immediately.
4. Enter the returned code only in the intended verification field.

The default accepts only codes arriving after the command starts. If the code
could have arrived between the website action and command startup, use the
smallest suitable look-back, such as `--since 30s` or `--since 2m`. `--from`
matches the sender or message snippet without case sensitivity; use it whenever
the expected brand is known. Use `--json` only when source metadata is needed.

Treat the returned code as an ephemeral secret. Do not repeat it in chat, logs,
summaries or any other command output, and do not save it to a file, clipboard,
agent memory or transcript. Use it only for the verification that requested it.
Phone codes are kept only in memory and expire ten minutes after receipt.
Tentacle never relays messages about payments, purchases, transfers or
withdrawals; never try to bypass that safety rule.

Direct SMS reception is the primary Android source. If relay is on but SMS
permission is missing, tell the owner to open Tentacle's app info and grant it.
On Android 13 and newer, a sideloaded build may first require **App info → ⋮ →
Allow restricted settings**. Xiaomi, Redmi and POCO phones may additionally
require **App info → Permissions → Notification SMS (通知类短信) → Allow**.
The optional **Also read codes from app notifications** source mainly helps on
Android 14 and older or for codes sent inside apps: Android 15 and newer hide
one-time codes in other apps' notifications from ordinary listeners.

Exit 1 means no matching code arrived before the timeout or another operational
error occurred. Exit 2 with `Code relay is off in the app (Settings → Relay
verification codes)` means the owner must enable relay in the Tentacle app;
tell the owner that, without asking them to send or type the code into chat.

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

When the phone has several addresses (`phone.urls`, e.g. built-in Tailscale,
Wi-Fi, own server), every command picks a working one automatically;
`tentacle ping` lists each address with its result and the chosen path.

Exit codes: `0` success (including an empty listing), `1` operational failure,
`2` usage/configuration error, missing pairing or disabled code relay, `130`
interrupted. If ping
fails, report the error and relevant connection/service state; stop repeated
requests until reachability changes.

Phone content is private. Never upload files, audio or transcripts elsewhere
without asking. Keep work within the user's request; Tentacle has no automatic
analysis, project filing or task queue.
