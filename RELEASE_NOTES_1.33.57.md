# 小布输入法助手 1.33.57

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 保留键盘文案“换行”→`↵`显示功能。
- 回车事件类定位改为使用稳定字符串前缀 `sendKeyEvent keyCode: `，不再把某一个方向键数值 `21` 当成唯一类定位条件。
- 在命中的 `com.oplus.keyboard.input.event.*` 类内继续按真实 `InputConnection.sendKeyEvent()` 调用结构匹配新版 `q0/r0`、旧版 `t0/u0` 以及 `p0/s0` 等入口。
- 回车分发器 Hook 只观察调用和参数，不设置 `param.result`，不改宿主返回值，不重复调用 `sendDownUpKeyEvents()`，避免截断宿主原生按下/抬起链。
- 删除会把“输入提交拦截”诊断点覆盖成 `HostTweaks installed` 的重复状态记录，使诊断点保留真正的 DexKit 方法签名证据。
- 单独换行文本的兜底转换仍保留；普通文本不改。

## 静态取证

- `1.7.38.17-os`：`com.oplus.keyboard.input.event.r#t0(II)Z` / `u0(II)Z`，连接来自 `input.manager.s.d()`。
- `1.8.33.17-mkt`：`com.oplus.keyboard.input.event.p#q0(II)Z` / `r0(II)Z`，连接来自 `input.manager.h.c()`。
- 两个版本的 `q0/t0` 都把第一个参数作为 `KeyEvent` keyCode；两个版本 `KeyCode.ENTER` 的值均为 `0x42`。
- 旧设备日志只有连接提供者命中，没有 `dispatcher candidate hooked`、`dispatcher invoked` 或 Enter 命中记录；此前版本没有拿到真正的事件分发器运行证据。

## 验证状态

- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.57-debug.apk`，2,970,447 bytes，SHA-256 `b4691838906eae7947bb7b4bab28191208a17f4d982c8314160ceb06cc455395`
- Release APK：`dist/OplusImePanel-1.33.57-release.apk`，2,577,550 bytes，SHA-256 `aec61648ef724e43908073e7c7e3282a0c7684dd27212d3e89e106badbb3019e`
- APK 元数据：Release `versionName=1.33.57`、`versionCode=96`
- SSH/终端真机验证：本轮 MT MCP 仍返回 `Connection refused`，没有取得 1.33.57 安装后的运行日志，也没有完成 SSH 终端实测；不能把回车功能写成已通过。

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
