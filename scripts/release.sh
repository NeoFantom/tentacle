#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
cd "$repo_root"

if [[ -n $(git status --porcelain) ]]; then
    echo "Refusing to release from a dirty git tree." >&2
    exit 1
fi

for variable in TENTACLE_KEYSTORE TENTACLE_KEYSTORE_PASSWORD TENTACLE_KEY_ALIAS TENTACLE_KEY_PASSWORD; do
    if [[ -z ${!variable:-} ]]; then
        echo "Missing required signing environment variable: $variable" >&2
        exit 1
    fi
done

if [[ ! -f $TENTACLE_KEYSTORE ]]; then
    echo "TENTACLE_KEYSTORE does not name a readable file." >&2
    exit 1
fi

command -v gh >/dev/null 2>&1 || {
    echo "GitHub CLI (gh) is required." >&2
    exit 1
}
gh auth status >/dev/null

update_repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
if [[ ! $update_repo =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "Could not derive a valid owner/repo from the current GitHub remote." >&2
    exit 1
fi

version=$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' android/app/build.gradle.kts)
if [[ -z $version || $version == *$'\n'* ]]; then
    echo "Could not read one versionName from android/app/build.gradle.kts." >&2
    exit 1
fi

release_notes_file="android/app/src/main/assets/release-notes.json"
release_notes_body=$(mktemp)
trap 'rm -f -- "$release_notes_body"' EXIT
python3 - "$release_notes_file" "$version" "$release_notes_body" <<'PY'
import json
import pathlib
import sys

notes_path = pathlib.Path(sys.argv[1])
version = sys.argv[2]
body_path = pathlib.Path(sys.argv[3])

try:
    document = json.loads(notes_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as error:
    raise SystemExit(f"Release notes error: could not read {notes_path}: {error}")

notes = document.get("notes") if isinstance(document, dict) else None
if not isinstance(notes, list):
    raise SystemExit("Release notes error: release-notes.json must contain a notes list.")
if any(isinstance(entry, dict) and entry.get("version") == "unreleased" for entry in notes):
    raise SystemExit(
        "Release notes error: rename the unreleased entry to the release version before releasing."
    )

entry = next(
    (item for item in notes if isinstance(item, dict) and item.get("version") == version),
    None,
)
if entry is None:
    raise SystemExit(f"Release notes error: no entry exists for version {version}.")

for language in ("en", "zh"):
    bullets = entry.get(language)
    if not isinstance(bullets, list) or not bullets or not all(
        isinstance(bullet, str) and bullet.strip() for bullet in bullets
    ):
        raise SystemExit(
            f"Release notes error: version {version} needs non-empty {language} bullets."
        )

body = "\n".join(f"- {bullet}" for bullet in entry["en"])
body += "\n\n"
body += "\n".join(f"- {bullet}" for bullet in entry["zh"])
body_path.write_text(body + "\n", encoding="utf-8")
PY

android/gradlew -p android :app:assembleRelease -Ptentacle.updateRepo="$update_repo"

apk="android/app/build/outputs/apk/release/tentacle-$version.apk"
checksum="$apk.sha256"
if [[ ! -f $apk || ! -f $checksum ]]; then
    echo "Release APK or checksum was not produced." >&2
    exit 1
fi

# The in-app updater only works if the APK names this repository; refuse to ship one that doesn't.
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' android/local.properties 2>/dev/null)}}
aapt2=$(ls "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
if [[ -z $aapt2 ]]; then
    echo "aapt2 not found (set ANDROID_HOME); cannot verify the update source in the APK." >&2
    exit 1
fi
if ! "$aapt2" dump resources "$apk" | grep -A1 'string/update_repo' | grep -qF "\"$update_repo\""; then
    echo "The built APK does not carry the update source $update_repo; not releasing." >&2
    exit 1
fi

gh release create "v$version" "$apk" "$checksum" \
    --repo "$update_repo" \
    --title "Tentacle $version" \
    --notes-file "$release_notes_body"
