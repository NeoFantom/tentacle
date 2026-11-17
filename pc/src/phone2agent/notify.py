"""Best-effort external notifications."""
from __future__ import annotations

import re
import subprocess
import sys


_PLACEHOLDER = re.compile(r"\{(title|body)\}")


def notify(cfg, title: str, body: str) -> None:
    """Run the configured notification command without affecting the pipeline."""
    command = cfg.notify.command
    if not command:
        return

    try:
        values = {"title": str(title), "body": str(body)[:1000]}
        argv = [_PLACEHOLDER.sub(lambda match: values[match.group(1)], arg)
                for arg in command]
        result = subprocess.run(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
            check=False,
        )
        if result.returncode:
            detail = (result.stderr or "").strip()
            suffix = f": {detail}" if detail else ""
            print(f"Notification command failed with exit code {result.returncode}{suffix}",
                  file=sys.stderr)
    except Exception as exc:
        print(f"Notification command failed: {exc}", file=sys.stderr)
