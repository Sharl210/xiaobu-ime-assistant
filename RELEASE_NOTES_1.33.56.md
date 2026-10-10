# 小布输入法助手 1.33.56

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 保留键盘文案“换行”→`↵`显示功能。
- 确认新旧宿主的 `KeyCode.ENTER` 数值均为 `0x42`（Android `KEYCODE_ENTER=66`）。
- 修复回车分发器定位过慢或未完成的问题：先用稳定字符串 `sendKeyEvent keyCode: 21` 定位 `input.event` 类，再在命中的类内部按结构匹配真实的 `(int,int)->boolean`、`(int)->void` 和 `(int)->boolean` 方法。
- 覆盖新版 `q0(int,int):boolean` / `r0(int,int):boolean` 与旧版对应的 `t0(int,int):boolean` / `u0(int,int):boolean`，以及 `p0(int):void` / `s0(int):void` 等直接调用 `InputConnection.sendKeyEvent()` 的入口。
- 回车分发器 Hook 改为只观察参数和调用，不再设置 `param.result`、不再替换宿主返回值、不再重复调用 `sendDownUpKeyEvents()`；保留宿主原生 `KeyEvent` 按下/抬起链，避免模块提前截断宿主发送。
- 继续保留单独换行文本提交的兜底转换；普通文本不改。

## 静态取证

- `1.7.38.17-os`：`com.oplus.keyboard.input.event.r#t0(II)Z` / `u0(II)Z`，连接来自 `input.manager.s.d()`。
- `1.8.33.17-mkt`：`com.oplus.keyboard.input.event.p#q0(II)Z` / `r0(II)Z`，连接来自 `input.manager.h.c()`。
- 两个版本的 `q0/t0` 都会用第一个参数构造 `KeyEvent`，随后调用 `InputConnection.sendKeyEvent()`；枚举初始化显示 `ENTER` 的实际值为 `0x42`。
- 最新设备日志此前只出现连接提供者命中和 `methods=0`，没有任何分发器候选、分发器回调或 Enter 命中记录；这说明此前真正的事件分发器 Hook 没有完成，而不是 SSH 端已经收到并拒绝了回车。

## 验证状态

- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.56-debug.apk`，3,326,665 bytes，SHA-256 `9bfe077899397ba11eb5b9beeed6bcef2b01ec2b39ca32091a945bba752b7ab4`
- Release APK：`dist/OplusImePanel-1.33.56-release.apk`，2,577,554 bytes，SHA-256 `767d58458b190687066b4f43167b2b80826659ad1597112618cf2ef347385eb9`
- APK 元数据：Release `versionName=1.33.56`、`versionCode=95`
- SSH/终端真机验证：本轮 MT MCP 在读取设备日志时返回 `Connection refused`，尚未取得 1.33.56 安装后的运行日志，也没有完成 SSH 终端实测；不能把回车功能写成已通过。


## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
