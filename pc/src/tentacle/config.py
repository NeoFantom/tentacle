"""Centralised configuration. Every tunable of tentacle is defined here and
loaded from one TOML file (see config.example.toml). Modules receive these
dataclasses; they must not read environment variables or config files
themselves."""
from __future__ import annotations

import dataclasses
import math
import os
import tomllib
from dataclasses import dataclass, field
from importlib import resources
from pathlib import Path
from typing import Any


class ConfigError(Exception):
    pass


def _expand(p: str) -> str:
    return os.path.expandvars(os.path.expanduser(p)) if p else p


@dataclass
class PhoneConfig:
    url: str = ""
    urls: list[str] = field(default_factory=list)  # ordered candidates; non-empty replaces url
    token: str = ""
    token_file: str = ""
    token_env: str = ""
    timeout_s: float = 20
    find_timeout_s: float = 120
    use_system_proxy: bool = False

    def resolved_token(self) -> str:
        if self.token:
            return self.token
        if self.token_env:
            v = os.environ.get(self.token_env, "")
            if v:
                return v.strip()
        if self.token_file:
            p = Path(_expand(self.token_file))
            if p.is_file():
                return p.read_text(encoding="utf-8").strip()
        return ""


@dataclass
class StorageConfig:
    data_dir: str = "~/.local/share/tentacle"

    @property
    def path(self) -> Path:
        return Path(_expand(self.data_dir))


@dataclass
class SourceConfig:
    name: str
    path: str
    extensions: list[str] = field(default_factory=lambda: ["aac", "m4a", "mp3", "amr", "wav", "ogg", "opus"])
    name_pattern: str = ""
    ts_format: str = "%Y%m%d%H%M%S"
    min_age_s: int = 60


@dataclass
class CallsConfig:
    timezone: str = ""


@dataclass
class AsrConfig:
    backend: str = "funasr"
    language: str = "zh"
    model: str = ""
    diarize: bool = True
    device: str = "auto"
    compute_type: str = "auto"
    initial_prompt: str = ""
    hotwords: list[str] = field(default_factory=list)
    command: list[str] = field(default_factory=list)


_DEFAULT_REQUEST_PROTECTED = [
    ".github/**",
    "**/*.jks",
    "**/*.keystore",
    "**/*.p12",
    "android/app/src/main/kotlin/dev/tentacle/update/**",
    "android/core/src/main/kotlin/dev/tentacle/core/Tokens.kt",
]


@dataclass
class RequestsConfig:
    command: str = ""
    shell: list[str] = field(default_factory=lambda: ["bash", "-lc"])
    repo: str = ""
    release: str = ""
    test: str = ""
    push: str = "git push"
    timeout_s: float = 7200
    prompt_template: str = ""
    protected: list[str] = field(default_factory=lambda: list(_DEFAULT_REQUEST_PROTECTED))

    def __post_init__(self) -> None:
        string_fields = ("command", "repo", "release", "test", "push", "prompt_template")
        for name in string_fields:
            if not isinstance(getattr(self, name), str):
                raise ConfigError(f"[requests] {name} must be a string")
        if not self.push.strip():
            raise ConfigError("[requests] push must not be empty")
        if (not isinstance(self.shell, list) or not self.shell
                or not all(isinstance(part, str) and part for part in self.shell)):
            raise ConfigError("[requests] shell must be a non-empty array of non-empty strings")
        if (isinstance(self.timeout_s, bool) or not isinstance(self.timeout_s, (int, float))
                or not math.isfinite(self.timeout_s) or self.timeout_s <= 0):
            raise ConfigError("[requests] timeout_s must be a positive finite number")
        if not isinstance(self.protected, list) or not all(isinstance(item, str) for item in self.protected):
            raise ConfigError("[requests] protected must be an array of strings")

    @property
    def repo_path(self) -> Path | None:
        return Path(_expand(self.repo)) if self.repo else None

    @property
    def prompt_template_path(self) -> Path | None:
        return Path(_expand(self.prompt_template)) if self.prompt_template else None


@dataclass
class Config:
    phone: PhoneConfig = field(default_factory=PhoneConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    sources: list[SourceConfig] = field(default_factory=list)
    calls: CallsConfig = field(default_factory=CallsConfig)
    asr: AsrConfig = field(default_factory=AsrConfig)
    requests: RequestsConfig = field(default_factory=RequestsConfig)
    path: Path | None = None  # file this config was loaded from

    @property
    def data_dir(self) -> Path:
        return self.storage.path


_SECTIONS = {
    "phone": PhoneConfig, "storage": StorageConfig, "calls": CallsConfig,
    "asr": AsrConfig, "requests": RequestsConfig,
}


def _build(cls: type, data: Any, where: str):
    if not isinstance(data, dict):
        raise ConfigError(f"[{where}] must be a table")
    known = {f.name for f in dataclasses.fields(cls)}
    unknown = set(data) - known
    if unknown:
        raise ConfigError(f"[{where}] unknown key(s): {', '.join(sorted(unknown))}")
    try:
        return cls(**data)
    except TypeError as e:
        raise ConfigError(f"[{where}] {e}") from e


def default_config_path() -> Path:
    env = os.environ.get("TENTACLE_CONFIG")
    if env:
        return Path(_expand(env))
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "tentacle" / "config.toml"


def example_text() -> str:
    return resources.files("tentacle").joinpath("config.example.toml").read_text(encoding="utf-8")


def from_dict(data: dict) -> Config:
    unknown = set(data) - set(_SECTIONS) - {"sources"}
    if unknown:
        raise ConfigError(f"unknown section(s): {', '.join(sorted(unknown))}")
    kwargs: dict[str, Any] = {k: _build(cls, data[k], k) for k, cls in _SECTIONS.items() if k in data}
    srcs = data.get("sources", [])
    if not isinstance(srcs, list):
        raise ConfigError("[[sources]] must be an array of tables")
    kwargs["sources"] = [_build(SourceConfig, s, f"sources[{i}]") for i, s in enumerate(srcs)]
    names = [s.name for s in kwargs["sources"]]
    if len(names) != len(set(names)):
        raise ConfigError("source names must be unique")
    return Config(**kwargs)


def load(path: str | Path | None = None) -> Config:
    """Load config. A missing file yields the built-in defaults of
    config.example.toml (so read-only commands work before `tentacle config init`)."""
    p = Path(_expand(str(path))) if path else default_config_path()
    if p.is_file():
        try:
            data = tomllib.loads(p.read_text(encoding="utf-8"))
        except tomllib.TOMLDecodeError as e:
            raise ConfigError(f"{p}: {e}") from e
    else:
        if path:
            raise ConfigError(f"config file not found: {p}")
        data = tomllib.loads(example_text())
    cfg = from_dict(data)
    cfg.path = p
    return cfg


def init(path: str | Path | None = None, *, force: bool = False) -> Path:
    p = Path(_expand(str(path))) if path else default_config_path()
    if p.exists() and not force:
        raise ConfigError(f"{p} already exists (use --force to overwrite)")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(example_text(), encoding="utf-8")
    os.chmod(p, 0o600)
    return p


def set_values(path: Path, section: str, values: dict[str, Any]) -> None:
    """Minimal in-place TOML editor for flat `key = value` lines inside one
    [section]; used by `tentacle pair`. Keeps comments and everything else."""
    import json
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    out, in_sec, done = [], False, set()
    for line in lines:
        s = line.strip()
        if s.startswith("["):
            if in_sec:
                out += [f"{k} = {json.dumps(v)}" for k, v in values.items() if k not in done]
                done |= set(values)
            in_sec = s == f"[{section}]"
            out.append(line)
            continue
        key = s.split("=", 1)[0].strip() if "=" in s and not s.startswith("#") else None
        if in_sec and key in values:
            comment = ""
            if "#" in line.split("=", 1)[1]:
                comment = "   " + line[line.index("#", line.index("=")):]
            out.append(f"{key} = {json.dumps(values[key], ensure_ascii=False)}{comment}")
            done.add(key)
        else:
            out.append(line)
    if in_sec:
        out += [f"{k} = {json.dumps(v)}" for k, v in values.items() if k not in done]
        done |= set(values)
    if not done:
        out += ["", f"[{section}]"] + [f"{k} = {json.dumps(v)}" for k, v in values.items()]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)


def override_url(cfg: Config, url: str | None) -> None:
    """Apply a command-line ``--url``: one fixed base URL, no path selection."""
    if url:
        cfg.phone.url = url.strip().rstrip("/")
        cfg.phone.urls = []


def masked(cfg: Config) -> dict:
    d = dataclasses.asdict(cfg)
    d["path"] = str(cfg.path) if cfg.path else None
    if d["phone"]["token"]:
        d["phone"]["token"] = d["phone"]["token"][:4] + "…"
    return d
