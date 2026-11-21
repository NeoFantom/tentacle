#!/usr/bin/env python3
"""Offline ``gh`` test double driven by ``TENTACLE_FAKE_GH_STATE``.

The JSON state records every argv in ``calls``. Tests may provide ordered
``responses`` with ``prefix``, ``stdout``, ``stderr`` and ``returncode`` fields;
the first prefix matching the current argv wins. Small defaults cover auth,
PR creation/view/list, repository resolution, comments, close, and merge.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys


def main() -> int:
    state_path = Path(os.environ["TENTACLE_FAKE_GH_STATE"])
    state = json.loads(state_path.read_text(encoding="utf-8"))
    argv = sys.argv[1:]
    state.setdefault("calls", []).append(argv)
    state_path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    for response in state.get("responses", []):
        prefix = response.get("prefix", [])
        if argv[:len(prefix)] == prefix:
            sys.stdout.write(response.get("stdout", ""))
            sys.stderr.write(response.get("stderr", ""))
            return int(response.get("returncode", 0))

    if argv[:2] == ["auth", "status"]:
        if state.get("authenticated", True):
            return 0
        sys.stderr.write(state.get("auth_error", "not authenticated\n"))
        return 1
    if argv[:2] == ["pr", "create"]:
        sys.stdout.write(state.get("create_url", "https://example.com/owner/tentacle/pull/1") + "\n")
        return 0
    if argv[:2] == ["pr", "view"]:
        key = argv[2] if len(argv) > 2 else ""
        value = state.get("pr_views", {}).get(key, state.get("pr_view", {}))
        sys.stdout.write(json.dumps(value) + "\n")
        return 0
    if argv[:2] == ["pr", "list"]:
        sys.stdout.write(json.dumps(state.get("pr_list", [])) + "\n")
        return 0
    if argv[:2] == ["repo", "view"]:
        sys.stdout.write(json.dumps(state.get("repo_view", {
            "nameWithOwner": "owner/tentacle", "parent": None,
        })) + "\n")
        return 0
    if argv[:2] == ["pr", "merge"]:
        if state.get("merge_remote") and state.get("merge_head"):
            subprocess.run(
                ["git", "--git-dir", state["merge_remote"], "update-ref",
                 "refs/heads/" + state.get("merge_branch", "main"), state["merge_head"]],
                check=True,
            )
        return 0
    if argv[:2] in (["pr", "comment"], ["pr", "close"]):
        return 0
    sys.stderr.write("fake gh has no response for: " + " ".join(argv) + "\n")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
