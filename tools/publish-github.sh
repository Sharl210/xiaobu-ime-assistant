#!/usr/bin/env bash
# Build and publish a signed GitHub Release for this existing repository.
# Usage: GITHUB_TOKEN=... tools/publish-github.sh OWNER/REPO VERSION HOST_INPUT_METHOD_APK
# This script does not create repositories, commit files, or force-push branches/tags.
set -euo pipefail

SLUG="${1:-}"
VERSION="${2:-1.33.60}"
HOST_APK="${3:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
NOTES="$ROOT/RELEASE_NOTES_${VERSION}.md"
TOKEN="${GITHUB_TOKEN:-}"

if [[ -z "$SLUG" || "$SLUG" != */* || -z "$HOST_APK" ]]; then
  echo "Usage: GITHUB_TOKEN=... $0 OWNER/REPO VERSION HOST_INPUT_METHOD_APK" >&2
  exit 2
fi
if [[ -z "$TOKEN" ]]; then
  echo "GITHUB_TOKEN is required (repo permission). No repository changes were made." >&2
  exit 2
fi
if [[ -z "$HOST_APK" || ! -f "$HOST_APK" ]]; then
  echo "对应宿主输入法 APK 是 Release 必需资产：请传入 HOST_INPUT_METHOD_APK 的现有文件路径。" >&2
  exit 2
fi
if [[ ! -f "$NOTES" ]]; then
  echo "Release notes missing: $NOTES" >&2
  exit 2
fi

cd "$ROOT"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit and push the reviewed source before publishing." >&2
  exit 2
fi

echo "Building signed Release $VERSION (release logging default must remain disabled)..."
./gradlew :app:assembleRelease --no-daemon
[[ -f "$APK" ]] || { echo "Release APK missing: $APK" >&2; exit 1; }
sha256sum "$APK"

TAG="v${VERSION}"
API="https://api.github.com"
RESPONSE="$(curl -fsS -X POST "$API/repos/$SLUG/releases" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  --data-binary "$(python3 - "$TAG" "$VERSION" "$NOTES" <<'PY'
import json, pathlib, sys
tag, version, notes = sys.argv[1:]
print(json.dumps({
    "tag_name": tag,
    "target_commitish": "main",
    "name": f"小布输入法助手 {version}",
    "body": pathlib.Path(notes).read_text(encoding="utf-8"),
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY
)")"
UPLOAD_URL="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["upload_url"].split("{")[0])' <<<"$RESPONSE")"

curl -fsS -X POST "${UPLOAD_URL}?name=OplusImePanel-${VERSION}-release.apk" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" >/dev/null

HOST_NAME="$(basename -- "$HOST_APK")"
HOST_NAME_ENCODED="$(python3 - "$HOST_NAME" <<'PY'
from urllib.parse import quote
import sys
print(quote(sys.argv[1], safe=""))
PY
)"
curl -fsS -X POST "${UPLOAD_URL}?name=${HOST_NAME_ENCODED}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$HOST_APK" >/dev/null

echo "Release uploaded with module APK and host input-method APK: https://github.com/$SLUG/releases/tag/$TAG"
