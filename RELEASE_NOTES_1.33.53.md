# 小布输入法助手 1.33.53

> **开发阶段适配版本声明**：本版本仍是针对当前开发阶段已取得并验证过的宿主版本进行适配的版本。虽然模块使用 DexKit 做通用匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 保留键盘主回车键文案替换：仅把 `enter_btn_title_enter` 对应的“换行”显示为 `↵`，不再误删该功能。
- SSH/终端回车链路改为优先调用宿主 `InputMethodService.sendDownUpKeyEvents(KEYCODE_ENTER)`，让系统和宿主自身生成并分发完整的按下、抬起事件；宿主服务路径不可用时保留直接 `InputConnection.sendKeyEvent` 兜底。
- 模块版本升级到 `1.33.53`，版本号不复用上一版。

## 验证状态

- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- SSH/终端真机验证：待安装本轮 APK 后验证；当前不能把尚未进行的设备实测写成通过
- 百度输入法定制版取证：确认其回车链路使用 `ImeService.sendDownUpKeyEvents(I)`；该样本仅用于实现路径对照，不作为小布功能已验证的证据
- 当前对应宿主 APK：MT MCP 已核验 `1.7.38.17-os` 与 `1.8.33.17-mkt` 样本；宿主 APK 尚未进入仓库本地 `dist/`，不能伪造 Release 双 APK 资产

## 发布资产约定

正式发布时必须同时包含：

1. `OplusImePanel-1.33.53-release.apk`：本模块安装包；
2. 与当前实际适配并核验过的小布输入法 APK。

宿主 APK 必须保留真实来源、包名、版本名、版本号和 SHA-256。缺少真实宿主 APK 时，发布流程必须阻塞，不能用占位文件代替。
