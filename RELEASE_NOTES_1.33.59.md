# 小布输入法助手 1.33.59

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 修正主键盘抬起链的跨版本结构匹配：
  - 旧版 `1.7.38.17-os` 使用 `com.oplus.keyboard.input.view.body.s.y(int,float,float,SoftKey):void`。
  - 新版 `1.8.33.17-mkt` 使用 `com.oplus.keyboard.input.view.body.t.z(int,float,float,long,SoftKey):void`。
- 回车判断仍严格读取 SoftKey 的 `a` 字段，只有 `ENTER=66` 才进入兜底；删除键 `67` 和其它按键不处理。
- 保留宿主原生方法执行，不设置 `param.result`，不改参数，不重发宿主已经发送的按键。
- 只有确认主键盘回车抬起、且短窗口内没有观察到宿主原生 Enter 分发时，才通过当前宿主服务发送一次 `sendDownUpKeyEvents(KEYCODE_ENTER)`。
- 保留“换行”文案替换为 `↵` 的显示逻辑，以及单独提交换行文本时的兼容兜底。

## 静态取证

- 旧版宿主的主键盘按下方法为 `body.s.w(MotionEvent, SoftKey):void`，抬起方法为 `body.s.y(int,float,float,SoftKey):void`。
- 新版宿主的主键盘按下方法为 `body.t.x(MotionEvent, SoftKey):void`，抬起方法为 `body.t.z(int,float,float,long,SoftKey):void`。
- 两版 SoftKey 实体的键码字段均为 `a:I`，回车值为十进制 `66`。
- 旧版主键盘抬起方法此前未被 `1.33.58` 的五参数匹配规则覆盖，因此在设备日志只看到 `r#t0/r#u0 args=67,0` 时无法取得主键盘回车的 SoftKey；本版补上四参数旧版结构。

## 验证状态

- `:app:compileDebugKotlin`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.59-debug.apk`，2,970,450 bytes，SHA-256 `07a5a2592a0a1041aed1547c76474736b5d34c4ee6b91159ea62d9d00d727e78`
- Release APK：`dist/OplusImePanel-1.33.59-release.apk`，2,577,545 bytes，SHA-256 `98bed7c37734e91c01e8c2b19a90098c59c0386591bcc04a7e1276a296f6e54c`
- Release 版本配置：`versionName=1.33.59`、`versionCode=98`
- SSH/终端真机验证：尚未完成。必须安装本版模块、重启或重新加载宿主进程，并点击一次主键盘回车后读取新日志，确认是否出现 `keyboard release ENTER keyCode=66`、原生 `args=66` 或模块服务兜底记录。

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。当前已取得的宿主 APK 为：

- `XiaobuInputMethod-1.7.38.17-os-host.apk`：`com.oplus.keyboard`，versionCode `1517238`
- `XiaobuInputMethod-1.8.33.17-mkt-host.apk`：`com.oplus.keyboard`，versionCode `1518033`

缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
