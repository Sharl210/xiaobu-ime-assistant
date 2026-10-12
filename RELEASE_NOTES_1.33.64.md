# 小布输入法助手 1.33.64

> **开发阶段适配版本声明**：本版本是在已核验宿主版本上继续适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 根据 MT MCP 实测日志修复候选提交时序：主键盘回车抬起发生在候选 `commitText()` 到达之前，导致旧逻辑当时看到 `candidatePending=false`，80ms 后直接执行 `SERVICE sendDownUpKeyEvents ENTER`。
- 现在记录每次非换行文本提交时间；如果回车抬起后候选文本才提交，在延迟兜底检查时会识别为同一次候选操作并跳过自动 Enter。
- 保留 1.33.63 的主键盘触摸、回车分发和 `commitText` 诊断日志。

## 关键实测证据

- `2026-10-12 13:29:12.478`：主键盘确认 `SoftKey keyCode=66`。
- `2026-10-12 13:29:12.558`：宿主原生 Enter 尚未观察到，进入模块兜底。
- `2026-10-12 13:29:12.560`：模块调用 `SERVICE sendDownUpKeyEvents ENTER`。
- 候选 `jdjs` 的 `commitText` 在 `2026-10-12 13:29:12.485` 已到达，但旧状态仍记录为 `candidatePending=false`；本版改用提交时间参与延迟判断。

## 验证状态

- `:app:compileDebugKotlin`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.64-debug.apk`，2,970,444 bytes，SHA-256 `c949cc4b38e0c00bff605d4e478e5821b028ca4c4ef5af9f9bb5bc0ac831fdee`
- Release APK：`dist/OplusImePanel-1.33.64-release.apk`，2,577,535 bytes，SHA-256 `ea57ab914bc314102b1198a17c1269a2b40a3442f2f2b1e6742bd347c22e1bce`
- Release 版本配置：`versionName=1.33.64`、`versionCode=103`
- 中文候选真机验证：用户已确认本轮安装包可正常使用，第一次回车只上屏候选，不自动追加换行
- 英文候选真机验证：用户已确认本轮安装包可正常使用，第一次回车只上屏候选，不自动追加换行
- SSH/终端无候选回车回归：用户已确认主键盘回车仍可正常换行

## 对应宿主 APK

- 文件：`XiaobuInputMethod-1.8.33.17-mkt-host.apk`
- 包名：`com.oplus.keyboard`
- versionName：`1.8.33.17-mkt`
- versionCode：`1518033`
- 文件大小：`65,670,800 bytes`
- 宿主签名 SHA-256：`fc98dae63ad39626c8c67fbe83f2f06f74932a9cd146b92cecfc6a047a904386`
- 宿主文件 SHA-256：`63e626189be93a9916d1fe8912f8362882c2133c6db055e84c4b63931e3a78f4`

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
