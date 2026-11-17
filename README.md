# phone2agent

Let the coding agent on your computer (Codex, Claude Code, …) **see the files on
your Android phone**, and turn your **call recordings into project notes and
agent tasks** — automatically, locally, with one config file.

```
phone ──(read-only HTTP + token)──▶ p2a ──▶ local ASR ──▶ agent analysis ──▶ notes committed & pushed
                                                                      └──▶ tasks → you approve → agent works
```

- `p2a ls / find / recent / get / cat` — browse and fetch phone files in real time.
- Picks up recordings from the system call recorder and from vendor recorders
  (e.g. Xiaomi HyperOS "AI 录音纪要" for WeChat / Feishu / QQ calls) — any folder
  you configure.
- Transcribes locally (FunASR Paraformer with speaker labels by default;
  faster-whisper or any external command as alternatives).
- An agent extracts todos, progress, approach, hand-off info and decisions,
  finds the matching project folder, writes the key points following that
  project's own conventions; `p2a` commits and pushes only those files.
- Things that need your go-ahead become tasks: `p2a tasks approve 3` and the
  agent starts working with the call context.

## Components

| Part | Where | What |
|---|---|---|
| Android app | `android/` | Foreground service serving shared storage read-only over HTTP with a bearer token. Kotlin, no third-party libraries, ~1 MB. |
| `p2a` | `pc/` | Python ≥ 3.11 CLI + daemon. Core uses only the standard library; ASR backends are optional extras. |
| Agent skill | `skills/phone2agent/SKILL.md` | Teaches Codex / Claude Code the `p2a` commands. |

Design and contracts: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Quick start

### 1. Phone

Build (`cd android && ./gradlew :app:assembleDebug`) or download the APK, install
it, open **phone2agent**, grant *All files access*, allow notifications, exempt
it from battery optimisation (and on Xiaomi, enable *Autostart*), tap **Start**.
Copy a pairing link (`p2a://…`).

The phone and the computer must reach each other: same Wi-Fi, a VPN such as
Tailscale (recommended — encrypted and works anywhere), or USB
(`adb forward tcp:8765 tcp:8765` → `http://127.0.0.1:8765`).

### 2. Computer

```bash
pipx install ./pc            # or: cd pc && uv venv && uv pip install -e .
p2a config init              # writes ~/.config/phone2agent/config.toml
p2a pair 'p2a://192.168.1.23:8765?token=…'
p2a ping
p2a ls Download
p2a discover                 # which folders hold recordings → put them in [[sources]]
```

Transcription backend (pick one):

```bash
pipx inject phone2agent 'funasr>=1.2' torch torchaudio av    # default, best for Chinese, speaker labels
pipx inject phone2agent 'faster-whisper>=1.1' av             # multilingual, no speaker labels
```

For CUDA, install the torch wheel matching your driver first
(see pytorch.org). CPU works too: a 12-minute call takes ~70 s with FunASR on a
modern laptop CPU. Models download once from ModelScope / Hugging Face, then
run offline.

Agent: install [Codex CLI](https://github.com/openai/codex) and log in (default),
or set `[agent] kind = "command"` with any CLI that takes a prompt and writes a
final message.

### 3. Run

```bash
p2a doctor                   # checks config, phone, ASR, agent, projects
p2a run                      # one pass: sync → transcribe → analyse → file
p2a daemon                   # loop forever (systemd unit: pc/contrib/systemd/p2a.service)
p2a status
p2a tasks                    # pending actions
p2a tasks approve 3 -m "keep it short" --now
```

Give your agent the skill: link `skills/phone2agent` into
`~/.codex/skills/` or `~/.claude/skills/`.

## Configuration

Everything lives in one TOML file — see the commented
[`config.example.toml`](pc/src/phone2agent/config.example.toml): phone URL and
token (or `token_file` / `token_env`), recording sources with filename
patterns, ASR backend, agent command, project roots and an optional index file
describing your projects, confidence threshold, commit / push policy
(`no_push` globs), notification command.

## Privacy & safety

- The phone server is **read-only** and token-protected. Plain HTTP is not
  encrypted: use Tailscale/VPN or a trusted network.
- Audio, transcripts and analyses stay in your data directory. Only the key
  points go into project repositories (`include_transcript = false`).
- The analysis agent runs read-only. Filing agents can only write inside one
  project and never run git; `p2a` commits exactly the files they list and
  leaves your other uncommitted changes untouched. Tasks run only after you
  approve them.
- Laws on recording calls differ; make sure you have consent where required.

## Known limitations

- Android 11+ forbids apps from reading `Android/data/*` even with all-files
  access. Some vendor recorders store files there (recent Xiaomi recorder
  versions may); those need an extra path (Shizuku or adb) — see
  `docs/ARCHITECTURE.md` and open issues.
- No push notifications from the phone: the computer polls (`sync.interval_s`).
  Recordings made while the computer is off are picked up later.
- Doze / vendor battery managers can pause the server; the battery-optimisation
  exemption and autostart settings help.

## 中文简介

让电脑上的 agent（Codex / Claude Code 等）实时浏览安卓手机文件；自动拾取通话录音（系统通话录音、小米「AI 录音纪要」的微信/飞书/QQ 通话录音等），本地转写（默认 FunASR，带说话人区分），由 agent 提炼待办、进展、推进方式、交接信息，写进对应项目并提交推送；需要主人拍板的事进入待办队列，批准后 agent 直接开工。所有参数集中在一个 TOML 配置文件里。

## License

MIT
