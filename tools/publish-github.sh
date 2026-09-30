#!/usr/bin/env bash
# 把「小布输入法助手」发布到 GitHub（公开仓库，中文 README 与中文 Release，Release 附 APK）。
#
# 为什么需要你手动执行：本机没有 GitHub 凭据，且 api.github.com 被拦截，无法代为建仓与发布。
#
# 用法：
#   export GITHUB_TOKEN=ghp_xxxxxxxx        # 需要 repo 权限
#   ./tools/publish-github.sh <你的GitHub用户名> [仓库名]
#
# 默认仓库名：OplusImePanel

set -euo pipefail

OWNER="${1:-}"
REPO="${2:-OplusImePanel}"
VERSION="1.6.0"
TAG="v${VERSION}"
APK="/workspace/dist-oplusime-panel/OplusImePanel-${VERSION}-release.apk"
NOTES="/workspace/OplusImePanel/RELEASE_NOTES_${VERSION}.md"
SRC="/workspace/OplusImePanel"

if [ -z "$OWNER" ]; then
  echo "用法: GITHUB_TOKEN=... $0 <GitHub用户名> [仓库名]" >&2
  exit 1
fi
if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "缺少 GITHUB_TOKEN（需要 repo 权限）" >&2
  exit 1
fi
for f in "$APK" "$NOTES"; do
  [ -f "$f" ] || { echo "缺少文件: $f" >&2; exit 1; }
done

API="https://api.github.com"

echo "== 1/4 创建公开仓库 =="
curl -sS -X POST "$API/user/repos" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO\",\"description\":\"小布输入法助手：文本编辑面板重排、剪贴板搜索与容量上限解除\",\"private\":false,\"has_issues\":true}" \
  | head -c 400; echo

echo "== 2/4 推送源码 =="
cd "$SRC"
if [ ! -d .git ]; then
  git init -q
  git add -A
  git -c user.name="publish" -c user.email="publish@local" commit -q -m "小布输入法助手 v${VERSION}"
fi
git remote remove origin 2>/dev/null || true
git remote add origin "https://${OWNER}:${GITHUB_TOKEN}@github.com/${OWNER}/${REPO}.git"
git branch -M main
git push -u origin main

echo "== 3/4 创建标签 =="
git tag -f "$TAG"
git push -f origin "$TAG"

echo "== 4/4 发布 Release（附 APK，中文说明）=="
curl -sS -X POST "$API/repos/${OWNER}/${REPO}/releases" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "$(python3 - "$TAG" "$NOTES" <<'PY'
import json, sys
tag, notes_path = sys.argv[1], sys.argv[2]
body = open(notes_path, encoding="utf-8").read()
print(json.dumps({
    "tag_name": tag,
    "name": f"小布输入法助手 {tag}",
    "body": body,
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY
)" | head -c 400; echo

echo "== 上传 APK 资产 =="
UPLOAD_URL=$(curl -sS "$API/repos/${OWNER}/${REPO}/releases/tags/${TAG}" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['upload_url'].split('{')[0])")

curl -sS -X POST "${UPLOAD_URL}?name=$(basename "$APK")" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" | head -c 300; echo

echo
echo "完成：https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
