# Verification-code relay

Tentacle can expose short-lived verification codes from the phone to its paired
computer. The feature is off by default and is intended for an owner-controlled
agent completing a login or account verification. It is not a general message
API: only a code, sender, short source snippet and receipt metadata are exposed.
For the separate, owner-enabled feed of ordinary phone notifications, see
[Phone notification sharing](NOTIFICATIONS.md).

## Capture and safety

The primary source on every Android version is direct SMS reception through
`RECEIVE_SMS`. Tentacle requests this permission when the owner enables relay.
Multipart SMS messages are joined before parsing.

The optional **Also read codes from app notifications** setting enables an
Android `NotificationListenerService` declared with
`BIND_NOTIFICATION_LISTENER_SERVICE`. While this setting and relay are enabled,
Tentacle examines the title, text, expanded text and text lines of new
notifications from messaging and other apps. It ignores Tentacle's own
notifications, ongoing notifications and group summaries. Android 15 and newer
hide one-time codes in other apps' notifications from ordinary notification
listeners, so this source mainly helps on Android 14 and older or when a code is
sent inside an app. Empty notifications and the platform's redaction placeholder
are ignored. Both sources do nothing while relay is disabled, and disabling
relay immediately clears all captured codes.

The parser accepts a 4–8 character digit or alphanumeric code containing at
least one digit when it is close to a verification keyword, in either order.
Chinese keywords include 验证码, 校验码, 动态码, 动态密码, 确认码, 安全码, 登录码,
激活码, 认证码 and 短信码. English matching is case-insensitive and includes
`code`, `verification code`, `OTP`, `one-time`, `passcode`, `PIN` and
`security code`. A code split once by a space, such as `123 456`, is joined only
when it directly follows a keyword. The closest valid candidate wins. A leading
or trailing bracketed signature, such as `【阿里云】` or `[Aliyun]`, identifies
the sender; otherwise Tentacle uses the SMS address or notification app/title.

Amounts, phone numbers, dates, times, years, card tails and unrelated order or
tracking numbers are excluded. As a fixed safety rule, Tentacle never relays a
code from a message containing 支付, 付款, 转账, 汇款, 提现, 消费, 交易密码,
`payment`, `transfer`, `withdraw` or `purchase`, matched without case
sensitivity where applicable. This rule cannot be disabled; the owner must type
those codes personally. A refund message is not blocked solely because it
contains an amount.

Tentacle logs only `verification code received from <sender> (relayed)` or
`verification code received from <sender> (blocked: payment)`. It never logs
the code. Log redaction also masks captured values if one is accidentally
passed to the logger.

## Retention and API

Codes exist in memory only. The store holds at most ten entries and expires an
entry ten minutes after receipt. The same code from the same sender within two
minutes is one entry even if both notification and direct-SMS capture see it.
Nothing is written to disk.

Every request uses the normal bearer token:

```text
GET /v1/codes?since=<phone epoch milliseconds>&wait=<0..60>
Authorization: Bearer <phone token>
```

An enabled response has this shape:

```json
{
  "codes": [
    {
      "id": "c-1763371721000-ab12",
      "code": "123456",
      "sender": "阿里云",
      "source": "notification",
      "snippet": "【阿里云】验证码123456，5分钟内有效",
      "received_ms": 1763371721000
    }
  ],
  "now_ms": 1763371722000,
  "enabled": true
}
```

`source` is `notification` or `sms`. `snippet` is the captured message text cut
to 160 characters. Entries are oldest first and include only those whose
`received_ms` is strictly greater than `since`. With `wait > 0`, the request
blocks until a matching newer entry exists or the timeout expires. Code,
app-request and notification-status long-polls share a two-worker limit so file
requests retain capacity.
When relay is disabled, the endpoint returns immediately:

```json
{"codes": [], "now_ms": 1763371722000, "enabled": false}
```

## Enabling on Android

In Tentacle, open the **Settings** card, turn on **Relay verification codes to
the computer**, accept the warning, and grant the SMS permission. Capture works
independently of the file server, but the server must be running for the
computer to retrieve codes. The main screen shows **Waiting for codes** only
while `RECEIVE_SMS` is granted; otherwise it shows **Needs SMS permission**. It
may also show when and from whom the last code arrived, but never displays the
code itself.

If SMS permission is denied or unavailable, open **App info** and grant it
there. On Android 13 and newer, a sideloaded build may first require **App info
→ ⋮ → Allow restricted settings**. Xiaomi, Redmi and POCO phones may also have
a separate SMS permission: open **App info → Permissions → Notification SMS
(通知类短信) → Allow**.

For the optional notification source, turn on **Also read codes from app
notifications** and grant notification access. A sideloaded build on Android 13
and newer may require **Allow restricted settings** before notification access
can be granted. Android 15 and newer normally redact one-time codes from this
source even after access is granted.

Debug builds accept `codes_enabled=true|false` as an intent extra to set relay
without the warning dialog or a permission prompt. `inject_code_text=<text>` with
`inject_code_sender=<name>` sends test input through the same parser as a
notification. Release builds ignore these extras.

## Computer command

The usual flow is:

1. Note the current time and trigger **Send code** on the website.
2. Run `tentacle code --from <brand> --wait 120`.
3. Enter the code returned on standard output.

The command first calls the endpoint with `wait=0` to learn the phone's clock,
which avoids PC/phone clock skew. Its default `--since now` accepts only codes
arriving after that initial request. If the website was triggered before the
command, use a small look-back such as `--since 30s` or `--since 2m` to avoid
missing a code that arrived between those actions. `--from` matches sender or
snippet without case sensitivity. Each long-poll waits no more than 55 seconds,
even when the overall `--wait` is longer.

Without `--json`, successful output is the code alone. `--json` returns the
matching code object. A timeout exits 1 with a short message on standard error.
Disabled relay exits 2 with `Code relay is off in the app (Settings → Relay
verification codes)`.

Anyone who has the phone token and network access to the server can read
retained codes for ten minutes. Protect the token and transport, and leave relay
off when it is not needed.
