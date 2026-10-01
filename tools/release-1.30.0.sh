#!/system/bin/sh
# 1.30.0 发布：提交 → 推送 → 打标签 → 建 Release → 上传 APK
# 凭据从保险箱现取现用，不落盘。
set -e
cd /workspace/OplusImePanel

TOK=$(sh tools/vault-get.sh github 2>/dev/null | grep -o 'github_pat_[A-Za-z0-9_]*' | head -1)
if [ -z "$TOK" ]; then
  TOK=$(sh tools/vault-get.sh github 2>/dev/null | grep -o 'ghp_[A-Za-z0-9]*' | head -1)
fi
if [ -z "$TOK" ]; then echo "无法取得凭据"; exit 1; fi
echo "token ok ${#TOK}"

URL="https://x-access-token:${TOK}@github.com/Sharl210/xiaobu-ime-assistant.git"

echo "== 1/5 推送源码与标签 =="
git push "$URL" HEAD:main
git tag -f v1.30.0 >/dev/null 2>&1 || true
git push -f "$URL" refs/tags/v1.30.0

echo "== 2/5 建 Release =="
REL=$(curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/Sharl210/xiaobu-ime-assistant/releases \
  -d '{"tag_name":"v1.30.0","name":"小布输入法助手 v1.30.0","body":"详见 RELEASE_NOTES_1.30.0.md：修搜索空结果、搜索框退格失效、列表滑动卡顿。本版本默认关闭日志，需要排障时在模块 App 内打开日志开关。","draft":false,"prerelease":false}')
RID=$(echo "$REL" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "release id: $RID"

echo "== 3/5 上传 APK =="
curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @dist/OplusImePanel-1.30.0-release.apk \
  "https://uploads.github.com/repos/Sharl210/xiaobu-ime-assistant/releases/$RID/assets?name=OplusImePanel-1.30.0-release.apk" \
  | grep -o '"state":"[a-z]*"\|"size":[0-9]*\|sha256:[a-f0-9]*' | head -5

echo "== 4/5 核对远端 =="
git ls-remote "$URL" refs/heads/main refs/tags/v1.30.0

echo "== 5/5 核对发布资产 =="
curl -s -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/Sharl210/xiaobu-ime-assistant/releases/tags/v1.30.0" \
  | grep -o '"tag_name":"[^"]*"\|"name":"Oplus[^"]*"\|"state":"[a-z]*"\|"size":[0-9]*' | head -8
