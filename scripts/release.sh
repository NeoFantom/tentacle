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

android/gradlew -p android :app:assembleRelease -Ptentacle.updateRepo="$update_repo"

apk="android/app/build/outputs/apk/release/tentacle-$version.apk"
checksum="$apk.sha256"
if [[ ! -f $apk || ! -f $checksum ]]; then
    echo "Release APK or checksum was not produced." >&2
    exit 1
fi

gh release create "v$version" "$apk" "$checksum" \
    --repo "$update_repo" \
    --title "Tentacle $version" \
    --generate-notes
