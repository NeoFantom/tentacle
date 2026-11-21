# Tentacle

A manual toolkit that lets a local agent or human **browse Android phone files,
pull call recordings, transcribe them locally on demand, receive verification
codes and phone notifications, and notify the phone owner from the paired
computer**.

```text
Android phone ← token-protected HTTP → tentacle CLI → local tools and agents
```

- Browse photos, downloads and documents with `ls`, `find`, `recent`, `stat`,
  `get` and `cat`.
- List recordings from configured folders with `calls`, filtered by contact,
  app and date; `pull` downloads only the files you select.
- `transcribe` accepts a phone path or local file, caches transcripts by audio
  SHA-256, and prints text or JSON. FunASR, faster-whisper and external command
  backends are available.
- `code` waits for a short-lived verification code received directly by SMS,
  with app notifications as an optional extra source.
- `notifications` reads the owner-enabled phone notification feed; `gui` opens
  the same feed in a private loopback web view.
- `notify` sends a **Normal alert**, **Ring alert**, or **Forced alert**. Ring
  requires an event-specific request; forced requires an explicit wake-up request.
- The Python ≥ 3.11 core uses only the standard library. The Android app uses
  Kotlin with no third-party runtime dependencies.

The automation implementation is preserved on branch `experimental/automation`.

## Quick start

Build the Android app:

```bash
cd android
./gradlew :app:assembleDebug
```

Install the debug APK from `app/build/outputs/apk/debug/`, open **Tentacle**,
grant All files access and notification permission, allow background operation,
and tap **Start**. Copy the pairing link shown for a reachable address.

Install the PC CLI from the repository root:

```bash
python3 -m venv pc/.venv
pc/.venv/bin/pip install -e ./pc
source pc/.venv/bin/activate
tentacle config init
tentacle pair 'tentacle://example.com:8765?token=YOUR_TOKEN'
tentacle ping
tentacle ls Download
tentacle discover
```

Replace `example.com` with the phone address. Add the recorder folders found by
`discover` to `[[sources]]` in the config. Example source patterns for app calls
and phone calls are included in the template.

Install an optional ASR backend into the same environment:

```bash
pip install -e './pc[funasr]'    # default: Chinese ASR with speaker labels
# or
pip install -e './pc[whisper]'   # multilingual faster-whisper
```

Models download on first use, then run locally. Configure `[asr]` to select the
backend and CPU/CUDA device. The `command` backend runs an external local
program that writes the documented transcript JSON.

## Manual workflow

```bash
tentacle doctor
tentacle calls --with Alice --since 7d
tentacle calls --app wechat --limit 50 --json
tentacle pull 'MIUI/sound_recorder/call_rec/Alice_20261118120000.m4a'
tentacle transcribe 'MIUI/sound_recorder/call_rec/Alice_20261118120000.m4a'
tentacle transcribe ./call.m4a --json
tentacle transcribe ./call.m4a --force -o ./transcript
```

`calls` lists files currently on the phone, newest first, from the last seven
days by default. `--since` and `--until` filter on the phone; `--limit` applies
per source. `--with` matches part of the
counterpart name without case sensitivity. Files still being recorded remain
visible with `recording: true`. `pull` resumes partial downloads and skips
complete files of the same size. Transcription cache hits avoid ASR work;
`--force` regenerates the result. `-o` exports `.json` and `.txt` files.

```text
tentacle config init|show|path
tentacle pair ADDRESS [--token TOKEN]
tentacle ping
tentacle ls [PATH] [-a]
tentacle stat PATH
tentacle find [ROOT] --ext jpg,pdf --name TEXT --since 7d --limit 50
tentacle recent --since 1d
tentacle get PATH [DEST]
tentacle cat PATH [--max-bytes N]
tentacle discover
tentacle calls [--since 7d] [--until DATE] [--source NAME] [--with TEXT] [--app APP]
tentacle pull PHONE_PATH... [--to DIR]
tentacle transcribe PHONE_PATH_OR_LOCAL_FILE [--json] [--force] [-o PREFIX]
tentacle code [--wait 120] [--from TEXT] [--since now|30s|2m] [--json]
tentacle notifications [--since 10m|ISO] [--app NAME-or-package] [--grep TEXT] [--follow] [--json] [--limit N]
tentacle gui [--port N] [--no-open]
tentacle notify TEXT [--title T] [--level normal|ring|forced] [--urgent] [--wait-ack [SECONDS]] [--json]
tentacle notify questions [show|clear]
tentacle notify questions push FILE
tentacle notify questions generate [--count N] [--out FILE] [--no-push]
tentacle doctor
```

File listings and `calls` support `--json`. Human times use the phone's timezone;
`ls`, `stat`, `find`, `recent` and `calls` accept `--local-time` for the PC zone.
Exit codes: `0` success (including empty lists), `1` operation failure,
`2` usage/configuration error or a disabled optional phone feed, `130`
interrupted.

## Verification codes

Tentacle can relay login and verification codes from the phone to a paired
computer. The feature is off by default. Open the app’s **Settings** card, turn
on **Relay verification codes to the computer**, accept the warning, and grant
the SMS permission. The app shows **Waiting for codes** only after that
permission is granted. Keep the file server running so the computer can connect.

If SMS permission is denied or blocked, open **App info** and grant it there. A
sideloaded build on Android 13 or newer may first require **App info → ⋮ → Allow
restricted settings**. Xiaomi, Redmi and POCO phones may also require **App info
→ Permissions → Notification SMS (通知类短信) → Allow**.

**Also read codes from app notifications** is an optional extra source. Android
15 and newer hide one-time codes in other apps' notifications from ordinary
notification listeners, so this mainly helps on Android 14 and older or for
codes sent inside apps. Sideloaded builds may also require **Allow restricted
settings** before notification access can be granted.

A typical agent-assisted login is:

```bash
# First trigger "Send code" on the website, then:
tentacle code --from Aliyun --wait 120
```

The command prints only the matched code. By default it accepts codes received
after the command starts, using the phone's clock to avoid clock skew. Use a
small look-back such as `--since 30s` or `--since 2m` if the code might have
arrived between clicking **Send code** and starting the command. `--from`
matches either the sender or message snippet without case sensitivity.

Codes are held in phone memory for at most ten minutes and are never written to
disk. Messages about payments, purchases, transfers or withdrawals are never
relayed; the owner must type those codes personally. Anyone who has the phone
token and can reach its server can read retained codes, so protect the token and
transport and leave relay off when it is not needed. The full contract is in
[Verification-code relay](docs/CODES.md).

## Notifications from the phone

Phone notification sharing is off by default. In the app, enable **Share
notifications with the computer** and grant Android notification access. A
sideloaded app may require **App info → ⋮ → Allow restricted settings** before
the grant is available, especially on Xiaomi/HyperOS devices. Turning sharing
off clears the retained feed.

```bash
tentacle notifications --since 2h --app Messages
tentacle notifications --grep delivery --follow
tentacle notifications --since 10m --json
tentacle gui
```

The CLI supports duration or ISO start times, app-label or package filters,
case-insensitive search, JSON Lines and live following. The GUI is a local live
view with app/search filters, time groups and unread highlighting. It binds only
to `127.0.0.1`, uses a new secret URL on each launch and sends all phone
requests through the same computer-side module as the CLI; browser code never
receives the phone token.

The phone retains at most 500 entries for three days. Payment-message text is
replaced with `[hidden: payment message]`. Android 15 and newer may hide OTP
content from third-party notification listeners, and Tentacle does not bypass
that protection. Anyone with the phone token and network access can read the
retained feed; anyone who obtains the active local GUI URL can view it from that
computer. See [Phone notification sharing](docs/NOTIFICATIONS.md) for the API,
retention and privacy contract.

## Notifications from the computer

Use `tentacle notify 'The task finished'` for a **Normal alert**. Add
`--level ring` for a **Ring alert** only when the owner asked to be alerted for
that event. Add `--level forced` for a **Forced alert** only when the owner
explicitly asked to be woken or forced for that event. The phone's **Settings →
Alerts** has separate off-by-default switches for ring and forced. Ring offers
sound/vibration choices and stops after acknowledgement or two minutes. Forced
sets the chosen alarm volume, requires a correct general-knowledge answer to
acknowledge, and stops sound at its configurable 5–30 minute cap. The previous
alarm volume is restored on stop and after crash recovery. Android DND/device
policy can still silence alarms.

`--urgent` remains a ring alias. Unavailable levels are downgraded and the
command reports the delivered level; old phones rejecting forced are retried
as ring. `--wait-ack [SECONDS]` defaults to 300 seconds; acknowledgement and
dismissal exit 0, while a forced sound-cap `timeout` exits 1. `--json` prints
delivery and, when waiting, the terminal state as JSON Lines.

`tentacle notify questions` shows the question source/count. Use `push FILE`
for a custom English/Chinese JSON bank or `clear` for built-in questions.
`generate` uses `[notify] question_command`, accepts `--count`, `--out` and
`--no-push`, and validates model output before upload. The single word
`questions` is reserved as notification text. See [Alerts](docs/NOTIFY.md)
for settings, compatibility, question schema and the alarm stream rationale.

## Requests from the app

The phone owner can ask for a Tentacle change or attach an app log to a bug
report. A configured computer watcher claims one request and runs an implementer
agent. The agent classifies generally useful work as `upstream` and owner-only
work as `personal`. Upstream work is tested and opened as a pull request without
a version bump; personal work runs only in a separately configured fork
checkout. The agent must never push, open a PR or release.

Configure a generic agent command and repository, then optionally install the
systemd user service:

```bash
tentacle requests setup \
  --command "my-agent --prompt-file {prompt_file}" \
  --repo /path/to/tentacle \
  --base main \
  --push-remote origin \
  --pr-repo owner/tentacle \
  --install-service
```

Maintainers opt into the separate read-only reviewer and release path:

```bash
tentacle requests setup \
  --review-command "review-agent --read-only" \
  --test "scripts/test.sh" \
  --release "scripts/release.sh" \
  --pr-repo owner/tentacle
```

There is no default agent command, and `requests watch` refuses to start until
one is configured. Without systemd, run `tentacle requests watch` in a durable
terminal or use the manual command printed by `setup`. `tentacle requests list`
shows recent requests; `tentacle requests watch --once` handles at most one
request and `tentacle requests run-once` is its alias. `tentacle requests retry
ID` retries a failed request when the app supports it. `tentacle requests review
[--once]` explicitly runs maintainer review; automatic review from `watch` is
off unless `review_command` is configured.

If your agent is a shell function or alias from an interactive startup file
(for example a `cx` function in `~/.bashrc`), don't run it through `bash -ic`:
many startup files read standard input and swallow the prompt. Use a small
wrapper script instead, which imports the function without giving the startup
file the prompt:

```bash
#!/usr/bin/env bash
# ~/bin/tentacle-agent.sh
eval "$(bash -ic 'declare -p PATH $(compgen -e | grep -i proxy); declare -f cx' </dev/null 2>/dev/null)"
cx exec -
```

```bash
tentacle requests setup --command /path/to/tentacle-agent.sh --repo /path/to/tentacle
```

The prompt arrives on standard input. The command may instead use
`{prompt_file}`, `{result_file}`, `{log_file}` and `{repo}` placeholders.

Request text is limited to 1000 shared CJK/word units and 8000 UTF-16 code
units. The watcher validates the fixed `tentacle-requests/1` scope, treats phone
text and logs as untrusted data, wraps request text between random boundaries,
and places fixed non-overridable repository rules before and after it. This
scope lock is defense in depth: it reduces prompt-injection risk but cannot
eliminate it.

The result file starts with `Target: upstream` or `Target: personal`, then the
short owner-facing summary. An upstream branch is created from the configured
base (or discovered remote default branch), tested, pushed to `push_remote` and
opened on `pr_repo`. If `tentacle-request/ID` already exists during retry, the
new branch gets a random suffix and preserves the earlier history. Empty
`pr_repo` resolves from the push remote, including its parent when the remote is
a fork. A personal request needs `--fork-repo` and optionally `--fork-release`;
its rerun bumps patch versions for changed code before the direct flow.

The watcher stores each run under `<data_dir>/requests/<id>/`. A stale request
that this computer left in `working` is failed at startup with a clear restart
message; another computer's claim is left alone. If an agent makes no commit,
it must begin `result.txt` with `Done:` for a successful no-code-needed result,
or `Failed:` for a refusal, unclear request, or a bug whose cause could not be
found. The watcher removes this metadata prefix before showing the result on the
phone. A no-commit result without either prefix fails conservatively.

Open PRs are tracked in `<data_dir>/requests-prs.json`. While idle, the watcher
checks at most every five minutes and updates the phone after merge or closure.
An old app may reject a follow-up with 409; the watcher retries it for up to
seven days and then logs that it was dropped. The phone advertises follow-up and
retry support in `GET /v1/ping`.

Maintainer review uses a separate fixed prompt and command in a fresh worktree.
The reviewer reads the PR and writes only `Decision: merge` or `Decision: close`
plus a short note; it cannot edit, run code, push or merge. After approval the
watcher checks protected paths, tests, merges, bumps patch versions on the
default branch, pushes and releases. Pre-merge failures leave the PR open;
post-merge failures report the unfinished step without claiming a release.
Review worktrees are always removed.

If a request creates commits but later fails a safety check, the watcher records
that rejected `HEAD` in `<data_dir>/requests-blocked.json`. Later requests for
the same checkout fail while that commit remains an ancestor of `HEAD`, so a
later run cannot accidentally push rejected changes. The watcher never rewrites
or resets repository history; the maintainer reviews and removes failed commits
manually. If interrupted history cannot be verified, an unconditional blocker
requires review of the checkout and the marker before more requests can run.

**Security:** anyone who can use the unlocked app can make this computer run
the configured agent with that agent's permissions. Keep the agent sandboxed
where possible, use a dedicated repository checkout, and review local commits
and releases. The scope lock and protected-path checks reduce risk but do not
make arbitrary agent execution safe.

## Configuration and local cache

Lookup: `--config PATH`, then `TENTACLE_CONFIG`, then
`$XDG_CONFIG_HOME/tentacle/config.toml` (default
`~/.config/tentacle/config.toml`). See the commented
[config template](pc/src/tentacle/config.example.toml). Settings cover
`[phone]`, `[storage]`, `[[sources]]`, `[calls]`, `[asr]`, `[notify]` and `[requests]`.
Tokens can be supplied inline,
from a file or via an environment variable. `config show` masks the token.
`TENTACLE_ASR_VERBOSE=1` enables verbose ASR output.

The default data directory is `~/.local/share/tentacle`:

```text
recordings/<source or other>/<filename>
transcripts/<audio sha256>.json
transcripts/<audio sha256>.txt
```

To teach your agent the workflow, link `skills/tentacle` into its skill directory
(for example `~/.codex/skills/` or `~/.claude/skills/`).

## Connecting from anywhere

Use a LAN address on the same network, USB with
`adb forward tcp:8765 tcp:8765`, or Tailscale on both devices. An own-server
setup can expose the phone through an SSH reverse tunnel and HTTPS, for example
`https://example.com/tentacle`; the app maintains that tunnel itself (paste
your server's details into "Own server" and it fills in the fields). The PC
supports URL path prefixes. Details: [Connecting](docs/CONNECT.md).

Android allows only one VPN at a time: the Tailscale app cannot run together
with a phone proxy/VPN app. The app's built-in Tailscale node is not a VPN and
works while such an app stays on; an SSH tunnel is an ordinary TCP connection
and also coexists with it. The app's "Copy pairing link" carries every address;
the PC races them and picks the fastest working one automatically
(`tentacle ping` shows the choice).

## Privacy and limitations

The phone server is token-protected. Its file endpoints are read-only; enabled
code relay exposes in-memory codes for up to ten minutes, and enabled phone
notification sharing exposes retained notification content. Use a trusted
network or an encrypted transport for HTTP. Audio and transcripts stay local;
ask before uploading phone content elsewhere. Ensure recording consent where
required.
Android restricts access to `Android/data` and `Android/obb`. Vendor battery
management can pause the server; allow background operation and autostart when
available.

Contracts: [Architecture](docs/ARCHITECTURE.md). Tests:

```bash
(cd pc && .venv/bin/python -m unittest discover -s tests)
(cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug)
```

## 中文说明

Tentacle 是给本机 agent 或人使用的手动工具箱：浏览安卓手机文件、按需拉取通话录音，并在电脑本地转写。电脑端每次运行一条命令，完成后退出。

- `ls / find / recent / stat / get / cat` 用于照片、下载文件和文档。
- `calls` 列出配置目录中的录音，按联系人、应用和日期筛选；`pull` 下载选中的文件。
- `transcribe` 接受手机路径或本地文件，以音频 SHA-256 缓存转写，输出文本或 JSON。支持 FunASR、faster-whisper 和外部本地命令。
- `code` 等待手机直接收到的短信验证码，也可选读取应用通知作为补充来源。
- `notifications` 读取用户主动开启的手机通知共享；`gui` 在电脑本机打开同一通知源。
- PC 核心只用 Python ≥ 3.11 标准库；安卓端使用 Kotlin，没有第三方运行时依赖。

自动化实现保留在 `experimental/automation` 分支。

### 安装与配对

先在 `android/` 运行 `./gradlew :app:assembleDebug`，安装 `app/build/outputs/apk/debug/` 中的 APK。打开 **Tentacle**，授予所有文件访问和通知权限，允许后台运行，点击 **Start**，复制可连接地址的配对链接。

在仓库根目录安装电脑端：

```bash
python3 -m venv pc/.venv
pc/.venv/bin/pip install -e ./pc
source pc/.venv/bin/activate
tentacle config init
tentacle pair 'tentacle://example.com:8765?token=YOUR_TOKEN'
tentacle ping
tentacle ls Download
tentacle discover
```

将 `example.com` 换成手机地址。在配置的 `[[sources]]` 中填写 `discover` 找到的录音目录。模板包含应用通话和系统通话的文件名解析规则。

在同一环境中运行 `pip install -e './pc[funasr]'` 安装默认中文转写和说话人区分，或 `pip install -e './pc[whisper]'` 安装多语言 faster-whisper。在 `[asr]` 中选择后端及 CPU/CUDA。模型首次使用时下载，之后在本地运行；`command` 后端可调用输出规定 JSON 格式的本地程序。

### 日常使用

```bash
tentacle doctor
tentacle calls --with Alice --since 7d
tentacle calls --app wechat --limit 50 --json
tentacle pull 'MIUI/sound_recorder/call_rec/Alice_20261118120000.m4a'
tentacle transcribe 'MIUI/sound_recorder/call_rec/Alice_20261118120000.m4a'
tentacle transcribe ./call.m4a --json
tentacle transcribe ./call.m4a --force -o ./transcript
```

`calls` 按时间倒序列出手机现有录音，默认查询最近七天。`--since / --until` 在手机端过滤，`--limit` 对每个来源分别生效。`--with` 对联系人做不区分大小写的子串匹配，`--source` 选择来源，`--app` 精确匹配解析后的应用名。正在录制的文件也会列出，标记为 `recording: true`。

`pull PHONE_PATH... --to DIR` 可指定下载目录，支持断点续传，同大小完整文件会跳过。`transcribe` 默认输出 `[mm:ss] SPEAKER: text` 文本；`--json` 输出 JSON，缓存命中时无需重新转写，`--force` 强制重做，`-o PREFIX` 导出 `.json` 和 `.txt`。

### 验证码

验证码转发默认关闭。在应用的 **Settings** 中开启 **Relay verification codes to the computer**，确认警告并授予短信权限；只有短信权限已授予时，状态才会显示 **Waiting for codes**。如果权限被拒绝或受限，请打开应用信息授予权限。安卓 13 及以上的侧载应用可能要先在 **应用信息 → ⋮ → 允许受限设置** 中放行；小米、Redmi 和 POCO 还可能需要在 **应用信息 → 权限 → 通知类短信 → 允许** 中单独授权。

**Also read codes from app notifications** 是可选的补充来源。安卓 15 及以上会向普通通知监听器隐藏其他应用通知中的一次性验证码，因此它主要适用于安卓 14 及以下，或通过应用内消息发送的验证码。开启通知读取时，侧载应用也可能需要先允许受限设置。电脑端使用 `tentacle code --from '<品牌>' --wait 120` 等待验证码；如验证码可能已提前到达，可加 `--since 30s` 或 `--since 2m`。验证码只在手机内存保留十分钟，支付、购买、转账和提现消息永不转发。

### 手机通知

手机通知共享默认关闭。在应用中开启 **Share notifications with the computer** 并授予系统通知使用权；侧载应用可能要先在 **应用信息 → ⋮ → 允许受限设置** 中放行，小米/HyperOS 设备尤其如此。电脑端可用 `tentacle notifications --since 2h --app Messages` 筛选查看，或运行 `tentacle gui` 打开仅监听 `127.0.0.1`、每次启动使用随机密钥的本地实时页面。CLI 与 GUI 共用电脑端数据模块，网页不会获得手机 token。

手机最多保留最近三天的 500 条通知；关闭共享会清空记录。支付消息正文会替换为 `[hidden: payment message]`。安卓 15 及以上可能向第三方通知监听器隐藏验证码，Tentacle 不会绕过此保护。持有手机 token 且能访问手机服务的人可以读取保留的通知；拿到当前 GUI 本地链接的同机用户也可以在该次运行期间查看。完整说明见 [手机通知共享](docs/NOTIFICATIONS.md)。

文件操作：`ls [PATH] [-a]` 列目录，`stat PATH` 看元数据，`find [ROOT] --ext jpg,pdf --name TEXT --since 7d --limit 50` 搜索，`recent --since 1d` 看最近文件，`get PATH [DEST]` 下载，`cat PATH --max-bytes N` 读取文本。列表和录音支持 `--json`；`ls / stat / find / recent / calls` 默认显示手机时区，`--local-time` 切换为电脑时区。退出码：`0` 成功（含空列表）、`1` 操作失败、`2` 用法或配置错误、`130` 中断。

### 配置与缓存

配置优先级：`--config PATH` → `TENTACLE_CONFIG` → `$XDG_CONFIG_HOME/tentacle/config.toml`，默认 `~/.config/tentacle/config.toml`。`config init / show / path` 分别生成模板、显示脱敏配置和配置路径。[配置模板](pc/src/tentacle/config.example.toml) 包含 `[phone] / [storage] / [[sources]] / [calls] / [asr]`；token 可直接填写、从文件读取或通过环境变量提供。`TENTACLE_ASR_VERBOSE=1` 开启 ASR 详细输出。

默认数据目录为 `~/.local/share/tentacle`，录音位于 `recordings/<来源或 other>/<文件名>`，转写位于 `transcripts/<音频 sha256>.json` 和 `.txt`。可将 `skills/tentacle` 链接到 agent 的 skill 目录，例如 `~/.codex/skills/` 或 `~/.claude/skills/`。

### 随处连接

可选同一局域网、USB（`adb forward tcp:8765 tcp:8765`）、两端加入 Tailscale，或通过自己的服务器建立 SSH 反向隧道并用 HTTPS 访问，例如 `https://example.com/tentacle`。PC 支持带路径前缀的 URL；安卓内置 SSH 隧道留待后续实现，目前需要外部 SSH 客户端。详见 [连接说明](docs/CONNECT.md)。

安卓同一时间只能运行一个 VPN，因此 Tailscale 应用不能与代理/VPN 应用同时运行。应用内置的 Tailscale 节点不占用 VPN，可以在代理应用开启时照常工作；SSH 隧道是普通 TCP 连接，同样可以共存。用“复制配对链接（全部地址）”配对后，电脑会自动选用最快的可用地址（`tentacle ping` 会显示所选路径）。

### 隐私、限制与验证

手机接口只读并使用 token 认证；明文 HTTP 应通过可信网络或加密传输使用。录音和转写留在本地，上传手机内容到别处前必须询问。录音前应满足所需的知情同意要求。安卓限制访问 `Android/data` 和 `Android/obb`；厂商省电策略可能暂停服务，应允许后台运行及自启动。

接口与模块契约见 [架构说明](docs/ARCHITECTURE.md)。测试：在 `pc/` 运行 `.venv/bin/python -m unittest discover -s tests`；在 `android/` 运行 `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`。

## License

MIT
