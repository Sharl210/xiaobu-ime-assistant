#!/usr/bin/env bash
# 发布 1.29.0：提交 → 推送 → 打标签 → 建 Release → 上传 APK。
# 凭据由 tools/vault-get.sh 现取现用，不落盘。
set -euo pipefail
cd /workspace/OplusImePanel

V=1.29.0
APK="dist/OplusImePanel-${V}-release.apk"
NOTES="RELEASE_NOTES_${V}.md"
REPO="Sharl210/xiaobu-ime-assistant"

TOKEN=$(bash tools/vault-get.sh "GitHub fine-grained access token" | grep -o 'github_pat_[A-Za-z0-9_]*' | head -1)
[ -n "$TOKEN" ] || { echo "无法取得凭据"; exit 1; }
echo "token ok ${#TOKEN}"

echo "== 1/5 提交 =="
git add -A
git -c user.name="sharl" -c user.email="sharl@local" commit -q -m "1.29.0：搜索改为数据层过滤（判定按对象递归查正文；重写分页适配器报告的条目数与取值，只作用于我们自己的两张列表）+ 超长条目显示层渲染护栏（不影响复制全文）+ 主界面日志开关（正式版默认关）" || true
git log -1 --oneline

echo "== 2/5 推送源码 =="
git -c http.extraheader= push -q "https://x-access-token:${TOKEN}@github.com/${REPO}.git" HEAD:main
echo "pushed"

echo "== 3/5 打标签并推送 =="
git tag -f "v${V}" >/dev/null
git -c http.extraheader= push -q -f "https://x-access-token:${TOKEN}@github.com/${REPO}.git" "v${V}"

echo "== 4/5 建 Release =="
BODY=$(python3 - "$NOTES" <<'PY'
import json,sys
print(json.dumps({"tag_name":"v1.29.0","name":"小布输入法助手 v1.29.0",
 "body":open(sys.argv[1],encoding="utf-8").read(),"draft":False,"prerelease":False},ensure_ascii=False))
PY
)
curl -sS -X POST "https://api.github.com/repos/${REPO}/releases" \
  -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/vnd.github+json" \
  -d "$BODY" | python3 -c "import json,sys;d=json.load(sys.stdin);print('release id:',d.get('id'))"

echo "== 5/5 上传 APK =="
UP=$(curl -sS "https://api.github.com/repos/${REPO}/releases/tags/v${V}" \
  -H "Authorization: Bearer ${TOKEN}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['upload_url'].split('{')[0])")
curl -sS -X POST "${UP}?name=$(basename "$APK")" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" | head -c 300; echo
