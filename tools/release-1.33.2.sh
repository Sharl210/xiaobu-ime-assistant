#!/usr/bin/env bash
# 1.33.2 发布：提交 → 推送 → 打标签 → 建 Release → 上传 APK
# 凭据从保险箱现取现用，不落盘。
set -euo pipefail
cd /workspace/OplusImePanel

TOK=$(bash tools/vault-get.sh "GitHub fine-grained access token" 2>/dev/null | grep -oE 'github_pat_[A-Za-z0-9_]+' | head -1)
if [ -z "$TOK" ]; then
  TOK=$(bash tools/vault-get.sh "GitHub classic personal access token" 2>/dev/null | grep -oE 'ghp_[A-Za-z0-9]+' | head -1)
fi
if [ -z "$TOK" ]; then echo "无法取得凭据"; exit 1; fi
echo "token ok ${#TOK}"

URL="https://x-access-token:${TOK}@github.com/Sharl210/xiaobu-ime-assistant.git"

echo "== 1/5 提交 =="
git add -A
git commit -q -m "1.33.2：发布脚本与验收记录" || echo "nothing to commit"
git log --oneline -1

echo "== 2/5 推送源码 =="
git push "$URL" HEAD:main

echo "== 3/5 打标签并推送 =="
git tag -f v1.33.2 >/dev/null 2>&1 || true
git push -f "$URL" refs/tags/v1.33.2

echo "== 4/5 建 Release =="
BODY=$(python3 - <<'PY'
import json
print(json.dumps({"tag_name":"v1.33.2","name":"小布输入法助手 v1.33.2","body":open("RELEASE_NOTES_1.33.2.md",encoding="utf-8").read(),"draft":False,"prerelease":False},ensure_ascii=False))
PY
)
REL=$(curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/Sharl210/xiaobu-ime-assistant/releases \
  -d "$BODY")
RID=$(echo "$REL" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")
if [ -z "$RID" ]; then echo "Release 创建失败：$REL" | head -c 400; exit 1; fi
echo "release id: $RID"

echo "== 5/5 上传 APK =="
curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @dist/OplusImePanel-1.33.2-release.apk \
  "https://uploads.github.com/repos/Sharl210/xiaobu-ime-assistant/releases/$RID/assets?name=OplusImePanel-1.33.2-release.apk" \
  | grep -o '"state":"[a-z]*"\|"size":[0-9]*' | head -5

echo "== 核对远端 =="
git ls-remote "$URL" refs/heads/main refs/tags/v1.33.2
