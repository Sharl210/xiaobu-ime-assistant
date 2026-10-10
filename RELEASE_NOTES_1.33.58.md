# 小布输入法助手 1.33.58

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 保留键盘文案“换行”→`↵`显示功能，不修改宿主用于识别按键动作的原始资源值。
- 主键盘增加独立的触摸抬起结构匹配：按 `body.*` 中 `(int,float,float,long,SoftKey)->void` 的形状定位主键盘收尾方法。
- 回车判断严格读取宿主 `SoftKey.a` 键码；`ENTER` 值为 `66` 时才进入回车处理，不扫描其它整数状态字段，避免误把删除键或其它状态误判成回车。
- 保留宿主原生执行链；只有在主键盘确认是 `ENTER=66` 且短窗口内没有观察到宿主原生回车分发时，才通过当前宿主服务补发一次 `sendDownUpKeyEvents(KEYCODE_ENTER)`。
- `input.event` 分发器继续只观察实际参数，不设置 `param.result`，不改宿主返回值，不重复拦截普通按键。
- 单独提交换行文本时仍保留文本兜底；普通文本不改。

## 静态取证

- `1.7.38.17-os`：`com.oplus.keyboard.input.event.r#t0(II)Z` / `u0(II)Z`，连接来自 `input.manager.s.d()`。
- `1.8.33.17-mkt`：`com.oplus.keyboard.input.event.p#q0(II)Z` / `r0(II)Z`，连接来自 `input.manager.h.c()`。
- 两个版本的 `KeyCode.ENTER` 值均为 `0x42`，即十进制 `66`。
- 两个版本的主键盘收尾方法均符合 `com.oplus.keyboard.input.view.body.*` 下 `(int,float,float,long,SoftKey)->void` 的结构；`SoftKey` 的实际键码字段为 `a:I`。
- 旧版设备日志只出现 `args=67,0`，对应删除键；没有出现 `args=66` 或主键盘收尾 Hook 记录，因此不能把旧版日志当作新版本修复已命中的证据。

## 验证状态

- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.58-debug.apk`，3,309,964 bytes，SHA-256 `7205438cb4e33950bdbdfc89cade898e64f616168a559106bab3e8abe75670d`
- Release APK：`dist/OplusImePanel-1.33.58-release.apk`，2,577,544 bytes，SHA-256 `1d88169756eb6269ca7cfa1181ea0c5babc1a20bf435664bf0bfa28f68e1280d`
- APK 元数据：Release `versionName=1.33.58`、`versionCode=97`
- SSH/终端真机验证：尚未完成。当前 MT 日志仍是旧模块安装状态，未取得 `1.33.58` 安装后的 `keyboard release`、`args=66` 或 SSH 终端实际回车结果。

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。当前已取得的宿主 APK 为：

- `XiaobuInputMethod-1.7.38.17-os-host.apk`：`com.oplus.keyboard`，versionCode `1517238`
- `XiaobuInputMethod-1.8.33.17-mkt-host.apk`：`com.oplus.keyboard`，versionCode `1518033`

缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
