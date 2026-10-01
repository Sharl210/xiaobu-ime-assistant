#!/usr/bin/env bash
# 1.32.1 发布：提交 → 推送 → 打标签 → 建 Release → 上传 APK
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
git commit -q -m "1.32.1：中文逗号上滑切「英文候选」改走宿主自己的设置读写入口（原写错键名 key_english_suggestion，引擎读的是 key_en_suggestion）；上滑字符改写加复读校验" || echo "nothing to commit"
git log --oneline -1

echo "== 2/5 推送源码 =="
git push "$URL" HEAD:main

echo "== 3/5 打标签并推送 =="
git tag -f v1.32.1 >/dev/null 2>&1 || true
git push -f "$URL" refs/tags/v1.32.1

echo "== 4/5 建 Release =="
REL=$(curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/Sharl210/xiaobu-ime-assistant/releases \
  -d '{"tag_name":"v1.32.1","name":"小布输入法助手 v1.32.1","body":"详见 RELEASE_NOTES_1.32.1.md。本轮修正：中文逗号上滑切「英文候选」原本写错了键名（设置页 preference key ≠ 引擎读取的存储键），改为调用输入法自己的设置读写入口，因此会触发它自己的键监听、立刻生效；上滑字符改写后加复读校验。其余内容同 1.32.0（常用语计数居中、剪贴板条目可编辑、26 键上滑字符中英两套对齐百度输入法、逗号句号补上滑）。本版本默认关闭日志，需要排障时在模块 App 内打开日志开关。","draft":false,"prerelease":false}')
RID=$(echo "$REL" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "release id: $RID"

echo "== 5/5 上传 APK =="
curl -s -X POST \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @dist/OplusImePanel-1.32.1-release.apk \
  "https://uploads.github.com/repos/Sharl210/xiaobu-ime-assistant/releases/$RID/assets?name=OplusImePanel-1.32.1-release.apk" \
  | grep -o '"state":"[a-z]*"\|"size":[0-9]*' | head -5

echo "== 核对远端 =="
git ls-remote "$URL" refs/heads/main refs/tags/v1.32.1
