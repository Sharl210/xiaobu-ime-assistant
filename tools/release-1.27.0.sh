#!/usr/bin/env bash
# 1.27.0 发布：提交 → 推送 → 打标签 → 建 Release → 上传 APK。
# 凭据只在内存中使用，不写入任何文件；远端 URL 保持干净（不带 token）。
set -euo pipefail

OWNER="Sharl210"
REPO="xiaobu-ime-assistant"
VERSION="1.27.0"
TAG="v${VERSION}"
SRC="/workspace/OplusImePanel"
APK="${SRC}/dist/OplusImePanel-${VERSION}-release.apk"
NOTES="${SRC}/RELEASE_NOTES_${VERSION}.md"
API="https://api.github.com"

TOKEN="$(bash "${SRC}/tools/vault-get.sh" "GitHub fine-grained access token" | grep -o 'github_pat_[A-Za-z0-9_]*' | head -1)"
[ -n "$TOKEN" ] || TOKEN="$(bash "${SRC}/tools/vault-get.sh" "GitHub classic personal access token" | grep -o 'ghp_[A-Za-z0-9_]*' | head -1)"
[ -n "$TOKEN" ] || { echo "无法取得凭据" >&2; exit 1; }

cd "$SRC"
echo "== 1/5 提交 =="
git add -A
git -c user.name="Sharl210" -c user.email="sharl210@users.noreply.github.com" \
    commit -q -m "1.27.0：修搜索两处真实断点——① 宿主 onComputeInsets 把输入法窗口可触摸区域限定在键盘块，搜索条在窗口顶部落在区域外，点击穿透出去（日志里按钮触摸留证从未出现、却出现 hideWindow blocked）；输入条显示期间扩区域为整窗，并挂宿主那份覆写（挂基类会被宿主覆盖）。② 过滤挂在不会发生的时机：setAdapter 传同一实例直接返回、且重绑用的是已脱离屏幕的旧面板（日志 row filter summary hidden=0 shown=0）；改为对屏幕上可见行直接收放，分四个时间点重复，并新增 live rows 自证日志。" || echo "(无新改动可提交)"
git log --oneline -1

echo "== 2/5 推送源码 =="
git push origin HEAD:main

echo "== 3/5 打标签并推送 =="
git tag -f "$TAG"
git push -f origin "$TAG"

echo "== 4/5 建 Release =="
python3 - "$TOKEN" "$API" "$OWNER" "$REPO" "$TAG" "$NOTES" <<'PY'
import json, sys, urllib.request
token, api, owner, repo, tag, notes_path = sys.argv[1:7]
body = open(notes_path, encoding="utf-8").read()
payload = json.dumps({
    "tag_name": tag,
    "name": f"小布输入法助手 {tag}",
    "body": body,
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False).encode("utf-8")
req = urllib.request.Request(
    f"{api}/repos/{owner}/{repo}/releases",
    data=payload,
    headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json; charset=utf-8",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.load(r)
        print("release id:", d["id"], "url:", d["html_url"])
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode("utf-8", "replace")[:500])
    sys.exit(1)
PY

echo "== 5/5 上传 APK =="
python3 - "$TOKEN" "$API" "$OWNER" "$REPO" "$TAG" "$APK" <<'PY'
import json, os, sys, urllib.request
token, api, owner, repo, tag, apk = sys.argv[1:7]
req = urllib.request.Request(
    f"{api}/repos/{owner}/{repo}/releases/tags/{tag}",
    headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
)
with urllib.request.urlopen(req, timeout=60) as r:
    rel = json.load(r)
upload = rel["upload_url"].split("{")[0]
name = os.path.basename(apk)
data = open(apk, "rb").read()
req = urllib.request.Request(
    f"{upload}?name={name}",
    data=data,
    headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/vnd.android.package-archive",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=300) as r:
        d = json.load(r)
        print("asset:", d["name"], "state:", d["state"], "size:", d["size"], "digest:", d.get("digest"))
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode("utf-8", "replace")[:500])
    sys.exit(1)
PY

echo "完成：https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
