"""Run a configured agent without a shell, retaining its output and diagnostics."""
from __future__ import annotations

import json
import os
import re
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .config import AgentConfig
from .jsonschema_lite import validate


@dataclass
class AgentResult:
    ok: bool
    final_message: str
    json: Any
    returncode: int | None
    log_path: Path
    duration_s: float


def _argv(cfg, *, cwd, mode, prompt_file, output_file, schema_file) -> list[str]:
    if cfg.kind == "codex":
        args = [cfg.binary, "exec", "--skip-git-repo-check", "--sandbox", mode,
                "--cd", str(cwd), "--output-last-message", str(output_file)]
        if schema_file:
            args += ["--output-schema", str(schema_file)]
        if cfg.model:
            args += ["--model", cfg.model]
        return args + list(cfg.extra_args) + ["-"]
    if cfg.kind == "command":
        if not cfg.command:
            raise ValueError("agent.command must not be empty")
        values = dict(prompt_file=str(prompt_file), output_file=str(output_file),
                      schema_file=str(schema_file) if schema_file else "",
                      cwd=str(cwd), mode=mode)
        return [arg.format(**values) for arg in cfg.command]
    raise ValueError(f"unknown agent kind: {cfg.kind}")


def _kill_group(proc: subprocess.Popen) -> None:
    if os.name == "posix":
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    else:
        # taskkill is the Windows process-tree equivalent of killpg.
        try:
            subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           timeout=5, check=False)
        except (OSError, subprocess.TimeoutExpired):
            pass
        if proc.poll() is None:
            proc.kill()


def _reject_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant: {value}")


def run_agent(agent_cfg: AgentConfig, prompt: str, *, cwd: Path,
              mode: str, output_schema: Path | None = None,
              log_path: Path) -> AgentResult:
    """Run once; process, timeout, parsing and validation failures return ok=False."""
    if mode not in {"read-only", "workspace-write"}:
        raise ValueError("mode must be read-only or workspace-write")
    started = time.monotonic()
    log_path = Path(log_path).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    cwd = Path(cwd).resolve()
    schema_file = Path(output_schema).resolve() if output_schema is not None else None
    final_message, parsed, returncode = "", None, None
    ok = False
    with tempfile.TemporaryDirectory(prefix="agent-", dir=log_path.parent) as scratch:
        prompt_file = Path(scratch) / "prompt.txt"
        output_file = Path(scratch) / "output.txt"
        with log_path.open("wb", buffering=0) as log:
            try:
                prompt_file.write_text(prompt, encoding="utf-8")
                args = _argv(agent_cfg, cwd=cwd, mode=mode, prompt_file=prompt_file,
                             output_file=output_file, schema_file=schema_file)
                proc = subprocess.Popen(
                    args, cwd=cwd, stdin=subprocess.PIPE, stdout=log,
                    stderr=subprocess.STDOUT, start_new_session=(os.name == "posix"),
                )
                try:
                    proc.communicate(prompt.encode("utf-8"), timeout=agent_cfg.timeout_s)
                    ok = proc.returncode == 0
                except subprocess.TimeoutExpired:
                    _kill_group(proc)
                    proc.communicate()
                    log.write(f"\nAgent timed out after {agent_cfg.timeout_s}s\n".encode())
                except BaseException:
                    _kill_group(proc)
                    proc.wait()
                    raise
                returncode = proc.returncode
                if returncode != 0:
                    log.write(f"\nAgent exited with return code {returncode}\n".encode())
                if output_file.exists():
                    final_message = output_file.read_text(encoding="utf-8")
                if schema_file is not None:
                    message = final_message.strip()
                    fence = re.fullmatch(r"```(?:json)?\s*\n?(.*?)\n?```", message,
                                         flags=re.DOTALL | re.IGNORECASE)
                    if fence:
                        message = fence.group(1).strip()
                    parsed = json.loads(message, parse_constant=_reject_constant)
                    validate(parsed, json.loads(schema_file.read_text(encoding="utf-8")))
            except (OSError, ValueError, KeyError, IndexError) as exc:
                ok = False
                log.write(f"\nAgent error: {exc}\n".encode("utf-8"))
    return AgentResult(ok, final_message, parsed, returncode, log_path,
                       time.monotonic() - started)
