# 小布输入法助手 1.33.50

## 本版内容

本版完善应用内功能说明与 README，并将当前版本作为 GitHub Release 候选包构建。

- 文本编辑面板：重排六个操作按钮，保留宿主操作；剪切完成后返回打字主键盘，系统返回手势从编辑面板回到主键盘。
- 剪贴板：解除条目数量上限、显示无限计数、支持仅剪贴板页搜索、优化超长内容预览，并提供条目编辑写回。
- 常用语：解除正文长度与条目数量限制。
- 键盘输入：中英文 26 键分别使用对应上滑键位；中文逗号上滑输入感叹号，英文书册键切换宿主英文候选设置。
- 候选拼音：提供可点选的编辑光标位置，支持在该位置输入和退格。
- 符号与文字：符号键目标为宿主完整符号页；主键盘“换行”显示为回车箭头；抑制宿主对引号、括号等字符的自动配对补全。
- Hook 诊断：完善诊断页，展示已匹配、匹配失败和未回传状态及匹配详情。
- DexKit 审计：宿主功能定位统一使用 DexKit 语义/结构查询，剪贴板 Room 写回路径改为安装期从构造签名发现运行时数据库类型。
- 日志默认值：Debug 开启，Release 关闭。

- 宿主功能定位统一使用 DexKit 语义/结构查询；平台公开 API 仅用于生命周期和输入窗口观察。
- 未发现宿主包固定混淆类路径、固定混淆方法名或 `getItem(int)`/`itemView` 作为宿主定位条件。


## 日志

- Debug 测试构建默认开启日志，便于验证。
- Release 正式构建默认关闭日志。
- 用户在模块主界面手动更改后，设置优先于构建默认值；排障时可手动开启。

## 安装

在 LSPosed 中启用模块，只将作用域勾选为小布输入法（`com.oplus.keyboard`），然后重启输入法进程。功能是否可用取决于该设备实际安装的宿主版本及诊断匹配结果。

## 验证状态

- Gradle Release 构建：PASS（`assembleRelease`）
- Gradle Debug 构建：PASS（`assembleDebug`）
- 纯编辑模型回归：58 项 PASS
- Debug 默认日志：`DEFAULT_LOG_ENABLED = true`
- Release 默认日志：`DEFAULT_LOG_ENABLED = false`
- Debug APK：`dist/OplusImePanel-1.33.50-test.apk`
- Release APK：`dist/OplusImePanel-1.33.50-release.apk`
- Debug SHA-256：`718fe1dd13432f59c09fdf7f01cba0f1bebbb8e80ae9cde3d83908811ae6463a`
- Release SHA-256：`3301d9522b82c421d7a6bf8c8cd2f31640c1ee9b870fcf330ed3877ef23ef17e`
- 设备端功能回归：仍需目标设备实际验证；构建通过不替代真机验收
