from __future__ import annotations

import argparse
import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from phone2agent.config import ProjectsConfig
from phone2agent.projects import add_commands, get_project, list_projects


class ProjectsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)

    def project(self, root: str, name: str, document: tuple[str, str] | None = None) -> Path:
        path = self.base / root / name
        path.mkdir(parents=True)
        if document:
            (path / document[0]).write_text(document[1], encoding="utf-8")
        return path

    def test_discovers_roots_extras_excludes_and_duplicate_ids(self) -> None:
        first = self.project("one", "same")
        second = self.project("two", "same")
        third_duplicate = self.project("nested/two", "same")
        third = self.project("one", "alpha")
        hidden = self.project("one", ".cache")
        archived = self.project("one", "archived-old")
        extra = self.project("standalone", "extra")
        (self.base / "one" / "plain-file").write_text("not a project", encoding="utf-8")

        projects = list_projects(ProjectsConfig(
            roots=[
                str(self.base / "one"),
                str(self.base / "two"),
                str(self.base / "nested" / "two"),
            ],
            extra=[str(extra)],
            exclude=[".*", "archived-*"],
        ))

        self.assertEqual(
            ["alpha", "extra", "nested/two/same", "same", "two/same"],
            [p.id for p in projects],
        )
        self.assertEqual(first, get_project(ProjectsConfig(
            roots=[str(self.base / "one"), str(self.base / "two")], exclude=[]
        ), "same").path)
        self.assertNotIn(hidden, [p.path for p in projects])
        self.assertNotIn(archived, [p.path for p in projects])
        self.assertIn(third, [p.path for p in projects])
        self.assertIn(third_duplicate, [p.path for p in projects])

    def test_index_sections_and_document_fallback(self) -> None:
        indexed = self.project("root", "indexed", ("README.md", "fallback should not win"))
        fallback = self.project(
            "root", "fallback",
            ("AGENTS.md", "# Heading\n\nFirst line.\n\n## Ignored heading\nSecond   line.\n"),
        )
        readme = self.project("root", "readme", ("README.md", "# Title\nREADME summary.\n"))
        headings_only = self.project("root", "headings-only", ("AGENTS.md", "# Rules\n"))
        (headings_only / "README.md").write_text("Useful fallback.\n", encoding="utf-8")
        index = self.base / "INDEX.md"
        index.write_text(
            "# Projects\n### indexed\n First paragraph.\n\nMore detail.\n#### Next heading\nnot included\n"
            "### unrelated\nOther.\n",
            encoding="utf-8",
        )

        projects = {project.id: project for project in list_projects(ProjectsConfig(
            roots=[str(self.base / "root")], index_file=str(index), exclude=[]
        ))}

        self.assertEqual("First paragraph. More detail.", projects["indexed"].description)
        self.assertEqual("First line. Second line.", projects["fallback"].description)
        self.assertEqual("README summary.", projects["readme"].description)
        self.assertEqual("Useful fallback.", projects["headings-only"].description)
        self.assertEqual(indexed, projects["indexed"].path)
        self.assertEqual(fallback, projects["fallback"].path)
        self.assertEqual(readme, projects["readme"].path)

    def test_descriptions_are_bounded(self) -> None:
        self.project("root", "from-index")
        self.project("root", "from-readme", ("README.md", "word " * 200))
        index = self.base / "INDEX.md"
        index.write_text("### from-index\n" + "detail " * 200, encoding="utf-8")
        projects = {project.id: project for project in list_projects(ProjectsConfig(
            roots=[str(self.base / "root")], index_file=str(index), exclude=[]
        ))}
        self.assertEqual(600, len(projects["from-index"].description))
        self.assertEqual(400, len(projects["from-readme"].description))

    def test_git_and_remote_detection(self) -> None:
        repository = self.project("root", "repository")
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        subprocess.run(
            ["git", "-C", str(repository), "remote", "add", "origin", "https://example.invalid/repo.git"],
            check=True,
        )
        plain = self.project("root", "plain")

        projects = {project.id: project for project in list_projects(ProjectsConfig(
            roots=[str(self.base / "root")], exclude=[]
        ))}

        self.assertTrue(projects["repository"].has_git)
        self.assertTrue(projects["repository"].has_remote)
        self.assertFalse(projects["plain"].has_git)
        self.assertFalse(projects["plain"].has_remote)

    def test_cli_json(self) -> None:
        path = self.project("root", "demo", ("README.md", "Demo project."))
        parser = argparse.ArgumentParser()
        subparsers = parser.add_subparsers(dest="command", required=True)
        add_commands(subparsers)
        args = parser.parse_args(["projects", "--json"])
        cfg = type("Config", (), {
            "projects": ProjectsConfig(roots=[str(self.base / "root")], exclude=[])
        })()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = args.func(args, cfg)
        data = json.loads(output.getvalue())
        self.assertEqual(0, result)
        self.assertEqual(str(path), data[0]["path"])
        self.assertEqual("demo", data[0]["id"])
        self.assertEqual("Demo project.", data[0]["description"])

    def test_exclude_matches_qualified_duplicate_id(self) -> None:
        self.project("one", "same")
        self.project("two", "same")
        projects = list_projects(ProjectsConfig(
            roots=[str(self.base / "one"), str(self.base / "two")],
            exclude=["two/*"],
        ))
        self.assertEqual(["same"], [project.id for project in projects])

    def test_missing_git_and_timeout_are_nonfatal(self) -> None:
        self.project("root", "demo")
        cfg = ProjectsConfig(roots=[str(self.base / "root")])
        for failure in (FileNotFoundError("git is unavailable"),
                        subprocess.TimeoutExpired("git", 2)):
            with self.subTest(failure=failure), mock.patch(
                    "phone2agent.projects.subprocess.run", side_effect=failure):
                project = list_projects(cfg)[0]
            self.assertFalse(project.has_git)
            self.assertFalse(project.has_remote)


if __name__ == "__main__":
    unittest.main()
