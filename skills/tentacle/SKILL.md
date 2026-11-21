---
name: tentacle
description: Browse and fetch files on the user's Android phone, read relevant phone notifications, transcribe call recordings, receive verification codes, and notify the phone owner on demand with the local Tentacle CLI. Use for phone photos, downloads, documents, recordings, verification codes, phone notifications, phone-owner notifications, or questions such as "what arrived on my phone?" and "what did X say on the call?".
---

# Tentacle

Use `tentacle` to access the phone's read-only file API, short-lived
verification-code feed, owner-enabled phone notification feed, phone-owner
notifications and local transcription cache. Phone paths can be
relative to shared storage (`Download`, `DCIM/Camera`)
or absolute (`/storage/emulated/0/...`). Configuration is discoverable through
`tentacle config path` and `tentacle config show` (secrets masked).
Run `tentacle requests list` to see what the phone owner has asked the computer
to change; do not start the watcher or act on a request unless explicitly asked.

## Requests from the app

`tentacle requests watch` runs the configured implementer command for one phone
request at a time. The implementer writes `Target: upstream` for work useful to
most Tentacle users or `Target: personal` for owner-specific work, then a short
result. It commits only on the request branch and does not bump versions for an
upstream pull request. In a separately configured personal-fork checkout, the
rerun bumps patch versions for changed code. The implementer never pushes,
opens pull requests or releases; the watcher owns those actions.

`tentacle requests review [--once]` is the separate maintainer path. Its
read-only reviewer may inspect the pull request and write a merge/close decision
but must not edit, run code, push or merge. The watcher performs protected-path
checks, tests, merge, version bump, push and release only after approval.
Automatic maintainer review is disabled unless `[requests].review_command` is
configured. `tentacle requests retry ID` retries a failed phone request when
the app supports it. Follow-up PR outcomes may update a final phone request;
old apps that return 409 are retried for up to seven days.

## Notifications received by the phone

Use `tentacle notifications` when the user asks about something that arrived
as a phone notification, such as a message preview, delivery update or recent
app alert. Narrow the read to the task before inspecting content:

```text
tentacle notifications --since 30m --app '<app or package>' --grep '<term>' --json
```

Choose the shortest useful `--since` window and apply `--app` or `--grep` when
the request supplies that context. Use `--follow` only when the user needs you
to wait for a new notification. JSON output is JSON Lines, one notification per
line. Exit 2 means sharing is off or the phone app is too old; tell the owner to
enable **Share notifications with the computer** or update the app as the error
directs. Exit 1 means the phone is unreachable or another operational error
occurred.

Notification content is private. Read only the entries needed for the current
task. Do not dump the full feed into the conversation, and do not forward,
upload or paste notification content into another service. Summarize only the
minimum content needed to answer. Payment-message text is deliberately hidden;
never try to recover it. Android 15 may redact OTP content from third-party
listeners; do not seek a bypass.

`tentacle gui` is the owner's local visual view of the same feed. It is not an
agent scraping surface and does not broaden permission to read notifications.

## Notify the phone owner

Use a normal notification when a long task finishes, when work needs the
owner's decision, or when work is blocked and the owner is unlikely to be
watching the computer:

```text
tentacle notify 'Short message for the owner'
```

Keep the title and text short and send one useful update rather than repeated
progress messages. **Normal alert** is the default; **Ring alert** and **Forced
alert** share a stricter rate limit. If delivery says `not shown`, report the
reason when the owner next sees the current output; do not retry repeatedly.

Use `--level ring` only when the owner asked to be alerted for this particular
event. Use `--level forced` only when the owner explicitly asked to be woken or
forced for this particular event. General phone opt-in does not replace that
event-specific request. The app calls these 普通提醒 / 响铃提醒 / 强制提醒 in
Chinese. The legacy `--urgent` flag aliases ring; its wire value stays `urgent`.

Ring has a two-minute sound cap and an Acknowledge action. Forced temporarily
sets alarm volume, keeps ringing until a correct general-knowledge answer or
its configured sound cap, and cannot be acknowledged by a notification tap.
Forced and ring have independent opt-ins. If forced is off, delivery falls
back to ring when allowed, otherwise normal; ring off falls back to normal.
An old phone rejecting forced is retried as ring and the command reports it.
Read the delivered level/reason before assuming the requested strength worked.

Use `--wait-ack` only when work cannot safely continue until the owner has seen
the message. It defaults to 300 seconds; supply seconds for another deadline.
Acknowledged and dismissed confirm handling. Forced `timeout` means its sound
cap ended without a correct answer and exits 1. No response or timeout is never
approval. Without waiting, `--json` prints one delivery object; with waiting it
prints JSON Lines containing delivery then the terminal response.

When asked to manage the question bank:

```text
tentacle notify questions [show]
tentacle notify questions push FILE
tentacle notify questions clear
tentacle notify questions generate [--count N] [--out FILE] [--no-push]
```

Generate uses the explicitly configured `[notify] question_command`, with no
default, to request English/Chinese high-school general-knowledge questions.
It validates and deduplicates the JSON and requires ten valid questions in
each language before pushing. `--no-push` keeps output local. Push accepts the
question object or a bare list; clear restores built-in questions. A language
missing from a custom set uses its built-in bank. Old phones report that the
app is too old for custom questions. A notification whose entire text is the
single word `questions` is reserved for these commands. Full contracts:
[Alerts](../../docs/NOTIFY.md).

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
`2` usage/configuration error, missing pairing or a disabled optional feed, `130`
interrupted. If ping
fails, report the error and relevant connection/service state; stop repeated
requests until reachability changes.

Phone content is private. Never upload files, audio or transcripts elsewhere
without asking. Keep work within the user's request; Tentacle has no automatic
analysis, project filing or task queue.
