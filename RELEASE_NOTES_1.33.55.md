# 小布输入法助手 1.33.55

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 保留键盘文案“换行”→`↵`显示功能。
- 根据实际宿主 APK 的静态调用链，改为匹配 `com.oplus.keyboard.input.event.*` 中真正的回车入口：
  - 新版：`q0(int,int):boolean` / `r0(int,int):boolean`；
  - 旧版：对应结构的 `t0(int,int):boolean` / `u0(int,int):boolean`；
  - 同时覆盖 `p0(int):void`、引擎回调 `g(int):boolean` / `h(int):boolean`。
- 回车点击时优先调用宿主 `InputMethodService.sendDownUpKeyEvents(KEYCODE_ENTER)`，让系统生成完整的按下、抬起事件；宿主服务入口不可用时才使用当前连接直接发送。
- 修复当前版本只匹配“方法体直接调用 sendKeyEvent”的过窄规则。宿主实际 q0/t0 会通过 `input.manager.h.c()` 或旧版 `input.manager.s.d()` 取连接，随后再调用 `sendKeyEvent`；现在按 `input.event` 包和参数结构覆盖这条真实链。
- 对重复的按下/回调做短窗口去重，避免同一次点击重复发送 Enter。
- 关键安装、提供者命中、回车命中和发送结果改用关键日志记录，不受普通日志开关影响，方便在 LSPosed 日志中确认点击是否进入模块。

## 验证状态

- `:app:assembleDebug`：待本轮构建
- `:app:assembleRelease`：待本轮构建
- `git diff --check`：待本轮检查
- SSH/终端真机验证：需要安装本轮 APK 后点击一次“↵”再读取日志确认；当前不能把尚未发生的设备实测写成通过
- 已通过 MT MCP 静态核验：
  - `1.7.38.17-os` 的真实链路为 `com.oplus.keyboard.input.event.r#t0(II)Z` / `u0(II)Z`，连接来自 `input.manager.s.d()`；
  - `1.8.33.17-mkt` 的真实链路为 `com.oplus.keyboard.input.event.p#q0(II)Z` / `r0(II)Z`，连接来自 `input.manager.h.c()`；
  - 两个版本的 q0/t0 都会构造 `KeyEvent` 并调用 `InputConnection.sendKeyEvent()`。

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
