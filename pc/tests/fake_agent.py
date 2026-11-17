"""Offline command agent supporting canned output, failures and process trees."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--schema", default="")
    parser.add_argument("--cwd", default="")
    parser.add_argument("--mode", default="")
    parser.add_argument("--canned", type=Path)
    parser.add_argument("--capture", type=Path)
    parser.add_argument("--invalid", action="store_true")
    parser.add_argument("--fenced", action="store_true")
    parser.add_argument("--no-output", action="store_true")
    parser.add_argument("--exit-code", type=int, default=0)
    parser.add_argument("--sleep", type=float, default=0)
    parser.add_argument("--child-pid", type=Path)
    args = parser.parse_args()
    prompt = args.prompt.read_text(encoding="utf-8")
    stdin = sys.stdin.read()
    if args.capture:
        args.capture.write_text(json.dumps({"prompt": prompt, "stdin": stdin,
                                            "cwd": str(Path.cwd()), "cwd_arg": args.cwd,
                                            "schema": args.schema, "mode": args.mode}),
                                encoding="utf-8")
    print("fake stdout", flush=True)
    print("fake stderr", file=sys.stderr, flush=True)
    if args.child_pid:
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        args.child_pid.write_text(str(child.pid), encoding="utf-8")
    time.sleep(args.sleep)
    canned = args.canned or os.environ.get("P2A_FAKE_AGENT_JSON")
    result = Path(canned).read_text(encoding="utf-8") if canned else json.dumps({
        "files": [], "commit_message": "", "report": "ok"})
    if args.invalid:
        result = "{invalid JSON"
    if args.fenced:
        result = f"```json\n{result}\n```"
    if not args.no_output:
        args.output.write_text(result, encoding="utf-8")
    return args.exit_code


if __name__ == "__main__":
    sys.exit(main())
