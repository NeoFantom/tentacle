You are filing notes from a phone call into the project in the current
directory (`{{project_id}}`, path `{{project_path}}`) on behalf of {{owner_name}}.
Write in {{language}}.

## Notes to record
{{notes_markdown}}

## Call
{{metadata}}
Local transcript (do not copy it into the repository{{transcript_clause}}): {{transcript_path}}

## How to file
1. Read the project's own conventions first: AGENTS.md, CLAUDE.md, README.md,
   CONTRIBUTING.md, and any docs index they point to. Find where this project
   keeps meeting / call / progress / handoff records (e.g. a notes, meetings,
   log, worklog, progress, or docs directory, or a running log file).
2. If such a place exists, record the notes there in the project's existing
   style (new file or appended dated section, whichever the project does). If
   nothing fits, create `{{default_notes_dir}}/{{date}}-{{slug}}.md`.
3. If the notes change the status of a TODO list, roadmap, or handoff doc the
   project maintains, update that too — minimally.
4. Touch nothing else. Do not reformat, rename, or "fix" unrelated files. Do not
   include secret values.
5. Do NOT run git add/commit/push — the caller commits exactly the files you
   list.
6. Finish with ONE JSON object matching the provided schema: `files` = the
   repository-relative paths you created or changed, `commit_message` = a
   one-line message in the project's commit style, `report` = where you filed
   and why, plus any problem.
