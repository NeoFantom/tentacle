# Phone notification sharing

Tentacle can share notifications that arrived on the phone with its paired
computer. This inbound feed is distinct from `tentacle notify`, which sends an
outbound notification from the computer to the phone. Sharing is off by default
and requires both the app switch and Android notification access.

The computer exposes the same feed in two forms:

- `tentacle notifications` provides filtered text or JSON Lines for a person or
  local agent.
- `tentacle gui` provides a local, live browser view. Its server uses the same
  `notifications.py` data path as the CLI, so the phone token never reaches the
  page.

## Capture, filtering and retention

For each notification, the phone records only its key, package name, app label,
title, text, sub-text, category, post time, ongoing state, optional removal
time and last-change time (`updated_ms`). It does not copy icons, images, actions
or arbitrary notification extras. Expanded text or the latest messaging-style
lines are preferred when Android provides them.

Tentacle skips its own notifications and group summaries. Ongoing and
foreground-service notifications are skipped unless **Include ongoing** is
enabled. The owner can also exclude individual apps from the set of apps seen
by Tentacle.

The store is app-private, survives app restarts and retains at most the newest
500 entries by `updated_ms`, expiring each entry three days after `posted_ms`.
Turning sharing off or using **Clear** deletes the retained feed.
A removed notification remains in the feed with
`removed_ms` set until normal retention removes it.

Messages that look like payment or bank-verification messages have their text
replaced with `[hidden: payment message]`, and their title and sub-text are
cleared. Android 15 and newer can hide OTP content from third-party notification
listeners; Tentacle accepts that platform
redaction and does not try to bypass it.

## Phone API

Every request uses the normal phone bearer token:

```text
GET /v1/notifications?since=<phone ms>&wait=<0..60>&app=<package>&limit=<1..500>
Authorization: Bearer <phone token>
```

All query parameters are optional. `since` defaults to zero and selects entries
whose `updated_ms` is strictly greater than that phone epoch-millisecond cursor.
`wait` defaults to zero; a positive value long-polls until a matching entry has
a newer `updated_ms` or the timeout expires. Posts, updates to an existing key
and removals all advance `updated_ms` and wake matching long-polls. `app` is an
exact package-name filter. `limit` bounds the newest-`updated_ms`-first result
and defaults to 500.

An enabled response has this shape:

```json
{
  "notifications": [
    {
      "key": "example-key",
      "package": "com.example.messages",
      "app": "Messages",
      "title": "Alice",
      "text": "Are you free this afternoon?",
      "sub_text": "Personal",
      "category": "msg",
      "posted_ms": 1763371721000,
      "ongoing": false,
      "removed_ms": null,
      "updated_ms": 1763371721000
    }
  ],
  "now_ms": 1763371722000,
  "enabled": true
}
```

Entries are ordered by descending `updated_ms`, with one current version per
`key`. `key`, `package`, `app`, `title`, `text`, `sub_text` and `category` are
strings (`category` is empty when unavailable); `posted_ms` and `updated_ms` are
nonnegative integers; `ongoing` is a boolean; and `removed_ms` is a nonnegative
integer or null. `posted_ms` is Android's post time; `removed_ms` is the removal
time, and `updated_ms` is the last post/update/removal time. The phone makes
change cursors strictly increasing even when changes share a millisecond.
Clients ignore unknown fields. For robustness, the computer accepts a missing
`updated_ms` and defaults it to `max(posted_ms, removed_ms or 0)`.

Consumers merge by `key`, keeping the version with the largest `updated_ms`,
and advance the long-poll cursor to the largest received `updated_ms`, including
entries hidden by local filters. An empty initial snapshot starts at cursor zero
(or the CLI's posting threshold), so a post in the same millisecond is not
skipped. `now_ms` supplies the phone clock, not a replacement for the change
cursor. A removal updates the existing key with both `removed_ms`
and `updated_ms`; it does not delete the retained entry.

When sharing is off, the phone immediately returns HTTP 200, including during
a long-poll:

```json
{"notifications": [], "now_ms": 1763371722000, "enabled": false}
```

Computer clients also treat an HTTP 403 body with
`{"error":"disabled"}` as the same state. A 404 means the installed phone app
predates this feature; the computer reports that the app must be updated. Ping
may advertise the optional additive field
`"phone_notifications":{"enabled":true|false}`.

## Computer CLI

```text
tentacle notifications [--since 10m|ISO] [--app NAME-or-package]
                       [--grep TEXT] [--follow] [--json] [--limit N]
```

`--since` means **posted since**: it accepts a duration such as `10m` or `2h`,
or an ISO time, and filters `posted_ms` strictly after that threshold on the
computer. The API's numeric `since` instead filters `updated_ms`; an older
notification edited or removed recently must not pass the CLI's posting-time
filter. A listing sends that threshold as the API's change cursor, then applies
the posting-time check locally. Relative durations first make a non-waiting
request to learn the phone clock. In follow mode the posting threshold stays
fixed while the API cursor advances with every received change. `--app` matches
either the app label or package name without case sensitivity. `--grep`
matches app, title or text without case sensitivity. `--limit` is between 1 and
500 (default 50). The shared computer data module reads the retained window,
applies the app, text and time filters, then limits the result. Relative windows
use the phone clock; ISO times without an offset use the computer's local zone.

Plain output is a terminal-width table containing local post time, app, title
and truncated text, newest `updated_ms` first. `--json` writes one notification
object per line, including `updated_ms`. `--follow` prints an initial filtered
snapshot, then each new matching key once. It suppresses unchanged duplicates
and updates that do not change title or text. A title/text change prints the
notification again with an `[updated]` marker; removal of an already printed
key prints a short `[removed]` line. JSON follow emits the corresponding full
notification objects, with the timestamps indicating the change. Sharing off or
an old phone app exits 2 with a direct explanation; an unreachable phone exits
1.

## Local GUI

Run `tentacle gui` to open the notification feed in the default browser. The
server binds only to `127.0.0.1` and chooses a free port by default; `--port N`
selects one. `--no-open` prints the URL instead of opening it. On WSL the
launcher prefers `wslview` and falls back to `cmd.exe /c start`; other systems
use the Python default-browser integration.

Each launch creates a random secret. The initial URL carries it as `?k=...`, and
the page sends it in `X-Tentacle-Key` on subsequent requests. Missing or wrong
secrets receive 403. The page calls only the local JSON endpoints; the local
server calls the phone and never places the phone token in page source,
response bodies or response headers.

`GET /api/status` reports the selected phone base URL and path kind, sharing
state, or a reachable error with guidance. `GET /api/notifications` accepts
`since`, `wait`, `app`, `grep` and `limit`; `since` is an `updated_ms` cursor
and `wait=0..60` makes it the live long-poll endpoint. Unlike the CLI's
`--since`, this numeric cursor does not filter by post time. The local endpoint
accepts app labels as well as packages and applies the same case-insensitive
search as the CLI. Both require
`X-Tentacle-Key`.

The GUI leaves a tab bar for future views with only **Notifications** active.
It groups entries by Today, Yesterday and Earlier (or by hour), supports app and
text filters, and keeps live through a long-poll endpoint using `updated_ms`.
It updates existing rows by key without duplicating them, dims removed rows
and labels them **Removed**. New keys appear at the top and count as unread;
ordinary updates do not create another unread notification. The page uses no
external assets, follows the system light/dark preference, remains
keyboard-accessible and usable in a phone-width window, and
renders notification strings with `textContent` so they cannot become HTML.
Its status identifies the selected base URL/path kind, an unreachable phone,
disabled sharing with enablement guidance, or an old app that needs updating.

The local secret protects against unrelated local web pages making requests; it
does not make the GUI a network service. Do not proxy or rebind it to a public
interface.

## Enabling and privacy

In the app, turn on **Share notifications with the computer**, acknowledge the
privacy warning and grant Android notification access. A sideloaded app may
require **App info → ⋮ → Allow restricted settings** before Android permits
notification access, especially on Xiaomi/HyperOS devices. The app reports a
needs-attention state only when sharing is on but access is missing.

Notifications can contain private messages, account information and message
previews. Anyone who has the phone token and network access to the phone can
read the retained feed. A user of the same computer who obtains the per-launch
GUI URL can read it through the loopback server while that launch is running.
Enable sharing only when needed, protect the phone token and GUI URL, and use a
trusted network or encrypted transport to reach the phone. Payment-message text
is hidden, but that safeguard does not make the rest of the feed non-sensitive.
