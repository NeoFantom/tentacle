# Tentacle

A manual toolkit that lets a local agent or human **browse Android phone files,
pull call recordings, and transcribe them locally on demand**.

```text
Android shared storage ← read-only HTTP + token → tentacle CLI → local ASR
```

- Browse photos, downloads and documents with `ls`, `find`, `recent`, `stat`,
  `get` and `cat`.
- List recordings from configured folders with `calls`, filtered by contact,
  app and date; `pull` downloads only the files you select.
- `transcribe` accepts a phone path or local file, caches transcripts by audio
  SHA-256, and prints text or JSON. FunASR, faster-whisper and external command
  backends are available.
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
tentacle doctor
```

File listings and `calls` support `--json`. Human times use the phone's timezone;
`ls`, `stat`, `find`, `recent` and `calls` accept `--local-time` for the PC zone.
Exit codes: `0` success (including empty lists), `1` operation failure,
`2` usage/configuration error, `130` interrupted.

## Configuration and local cache

Lookup: `--config PATH`, then `TENTACLE_CONFIG`, then
`$XDG_CONFIG_HOME/tentacle/config.toml` (default
`~/.config/tentacle/config.toml`). See the commented
[config template](pc/src/tentacle/config.example.toml). Settings cover `[phone]`,
`[storage]`, `[[sources]]`, `[calls]` and `[asr]`. Tokens can be supplied inline,
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
`https://example.com/tentacle`. The PC supports URL path prefixes; Android's
built-in SSH-tunnel feature is a later task, so that setup currently needs an
external SSH client. Details: [Connecting](docs/CONNECT.md).

Android allows only one VPN at a time: the Tailscale app cannot run together
with a phone proxy/VPN app. The app's built-in Tailscale node is not a VPN and
works while such an app stays on; an SSH tunnel is an ordinary TCP connection
and also coexists with it. "Copy pairing link (all addresses)" lets the PC pick
the fastest working address automatically (`tentacle ping` shows the choice).

## Privacy and limitations

The phone server is read-only and token-protected. Use a trusted network or an
encrypted transport for HTTP. Audio and transcripts stay local; ask before
uploading phone content elsewhere. Ensure recording consent where required.
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
