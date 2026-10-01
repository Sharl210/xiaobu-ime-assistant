#!/usr/bin/env bash
# 1.31.0 发布：提交 → 推送 → 打标签 → 建 Release → 上传 APK
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
git commit -q -m "1.31.0：修搜索完全无效（快照线程池提交完即关闭、任务被丢弃 → 改常驻单线程 + 谁问谁兜底重建 + 失败标记防空转）；搜索条按钮间距（8dp 外边距 / 18dp 圆角）；超长条目渲染上限 1500→800" || echo "nothing to commit"
git log --oneline -1

echo "== 2/5 推送源码 =="
git push "$URL" HEAD:main

echo "== 3/5 打标签并推送 =="
git tag -f v1.31.0 >/dev/null 2>&1 || true
git push -f "$URL" refs/tags/v1.31.0

echo "== 4/5 建 Release =="
REL=$(curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/Sharl210/xiaobu-ime-assistant/releases \
  -d '{"tag_name":"v1.31.0","name":"小布输入法助手 v1.31.0","body":"详见 RELEASE_NOTES_1.31.0.md。本轮修复：搜索点了等于没点（快照构建线程池提交完即关闭、任务被丢弃 → 改常驻单线程 + 谁问谁兜底 + 防空转）；搜索条「取消 / 搜索」两个气泡挤在一起；超长条目渲染上限 1500→800（复制仍为完整原文）。本版本默认关闭日志，需要排障时在模块 App 内打开日志开关。","draft":false,"prerelease":false}')
RID=$(echo "$REL" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "release id: $RID"

echo "== 5/5 上传 APK =="
curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @dist/OplusImePanel-1.31.0-release.apk \
  "https://uploads.github.com/repos/Sharl210/xiaobu-ime-assistant/releases/$RID/assets?name=OplusImePanel-1.31.0-release.apk" \
  | grep -o '"state":"[a-z]*"\|"size":[0-9]*' | head -5

echo "== 核对远端 =="
git ls-remote "$URL" refs/heads/main refs/tags/v1.31.0
