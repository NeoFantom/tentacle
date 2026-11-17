"""Centralised configuration. Every tunable of phone2agent is defined here and
loaded from one TOML file (see config.example.toml). Modules receive these
dataclasses; they must not read environment variables or config files
themselves."""
from __future__ import annotations

import dataclasses
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
    token: str = ""
    token_file: str = ""
    token_env: str = ""
    timeout_s: float = 20
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
    data_dir: str = "~/.local/share/phone2agent"

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
class SyncConfig:
    interval_s: int = 300
    backfill_days: int = 7
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


@dataclass
class AgentConfig:
    kind: str = "codex"
    binary: str = "codex"
    model: str = ""
    extra_args: list[str] = field(default_factory=list)
    command: list[str] = field(default_factory=list)
    timeout_s: int = 1800


@dataclass
class AnalyzeConfig:
    owner_name: str = "me"
    language: str = "zh"


@dataclass
class ProjectsConfig:
    roots: list[str] = field(default_factory=lambda: ["~/projects"])
    extra: list[str] = field(default_factory=list)
    exclude: list[str] = field(default_factory=lambda: ["archive", ".*"])
    index_file: str = ""
    min_confidence: float = 0.6
    default_notes_dir: str = "notes/calls"
    include_transcript: bool = False
    auto_commit: bool = True
    auto_push: bool = True
    no_push: list[str] = field(default_factory=list)

    def root_paths(self) -> list[Path]:
        return [Path(_expand(r)) for r in self.roots]

    def extra_paths(self) -> list[Path]:
        return [Path(_expand(r)) for r in self.extra]

    def index_path(self) -> Path | None:
        return Path(_expand(self.index_file)) if self.index_file else None


@dataclass
class TasksConfig:
    require_approval: bool = True


@dataclass
class PipelineConfig:
    max_attempts: int = 5


@dataclass
class NotifyConfig:
    command: list[str] = field(default_factory=list)


@dataclass
class Config:
    phone: PhoneConfig = field(default_factory=PhoneConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    sources: list[SourceConfig] = field(default_factory=list)
    sync: SyncConfig = field(default_factory=SyncConfig)
    asr: AsrConfig = field(default_factory=AsrConfig)
    agent: AgentConfig = field(default_factory=AgentConfig)
    analyze: AnalyzeConfig = field(default_factory=AnalyzeConfig)
    projects: ProjectsConfig = field(default_factory=ProjectsConfig)
    tasks: TasksConfig = field(default_factory=TasksConfig)
    pipeline: PipelineConfig = field(default_factory=PipelineConfig)
    notify: NotifyConfig = field(default_factory=NotifyConfig)
    path: Path | None = None  # file this config was loaded from

    @property
    def data_dir(self) -> Path:
        return self.storage.path


_SECTIONS = {
    "phone": PhoneConfig, "storage": StorageConfig, "sync": SyncConfig, "asr": AsrConfig,
    "agent": AgentConfig, "analyze": AnalyzeConfig, "projects": ProjectsConfig,
    "tasks": TasksConfig, "pipeline": PipelineConfig, "notify": NotifyConfig,
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
    env = os.environ.get("P2A_CONFIG")
    if env:
        return Path(_expand(env))
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "phone2agent" / "config.toml"


def example_text() -> str:
    return resources.files("phone2agent").joinpath("config.example.toml").read_text(encoding="utf-8")


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
    config.example.toml (so read-only commands work before `p2a config init`)."""
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
    [section]; used by `p2a pair`. Keeps comments and everything else."""
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


def masked(cfg: Config) -> dict:
    d = dataclasses.asdict(cfg)
    d["path"] = str(cfg.path) if cfg.path else None
    if d["phone"]["token"]:
        d["phone"]["token"] = d["phone"]["token"][:4] + "…"
    return d
