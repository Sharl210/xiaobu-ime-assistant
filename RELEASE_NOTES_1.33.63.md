# 小布输入法助手 1.33.63

> **开发阶段适配版本声明**：本版本是在已核验宿主版本上继续适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 暂不继续猜测候选提交的最终触发点；新增主键盘触摸边缘方法的 before/after 日志，不修改参数、不修改返回值。
- 增加回车分发器参数日志，记录方法、参数、Enter 键码和调用时机。
- 增加 `InputConnection.commitText()` 日志，记录提交文本、文本长度、候选状态和组合文本状态；单独换行文本会明确显示为 `\\n`。
- 保留 1.33.62 的候选后自动换行抑制逻辑，当前版本主要用于收集一次完整的运行链路证据。

## 验证状态

- `:app:compileDebugKotlin`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.63-debug.apk`，2,970,449 bytes，SHA-256 `5383dcb6e2433a9f44c9e2fe1a5b01a7803ef0a35884829751b21d57760558d2`
- Release APK：`dist/OplusImePanel-1.33.63-release.apk`，2,577,548 bytes，SHA-256 `d86e73dfcd4058b565609d1a8789e7777402e97c8bc43dfad0a5e0bee66a3d0e`
- Release 版本配置：`versionName=1.33.63`、`versionCode=102`
- 真机日志验证：已通过 MT MCP 读取本次复现日志；额外 Enter 确认为模块延迟兜底触发，见 1.33.64 修复说明。

## 日志筛选

```bash
logcat -b all -d | grep -E 'return-key: (keyboard touch edge|keyboard release|dispatcher|ENTER|commitText|connection ENTER|SERVICE)'
```

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
