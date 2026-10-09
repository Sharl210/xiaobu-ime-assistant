# 小布输入法助手 1.33.51

> **开发阶段适配版本声明**：本版本是针对当前开发阶段已取得并验证过的宿主版本进行适配的安装包。虽然模块使用 DexKit 按资源语义、代码字符串、调用关系和成员结构进行通用匹配，尽量减少对宿主混淆类名与方法名的依赖，但这不保证其他小布输入法版本可以直接使用。由于宿主功能改动较多，资源、数据库、调用链和 Hook 点都容易变化，其他版本可能出现部分功能不可用、诊断点未匹配或需要重新适配的情况。请以当前宿主版本的 Hook 诊断页和实际功能验证为准。

## 本轮修复

- Room 降级兼容：取证确认 1.7.38.17-os 与 1.8.33.17-mkt 都把旧版本回退交给 `androidx.sqlite.db.framework.*.onDowngrade`，而新版宿主的自定义回调会直接抛出 `SQLiteDowngradeException`。新增 `RoomDowngradeGuard`，在真实 `onDowngrade` 发生且版本确实回退时，清理旧 schema 并调用当前 RoomOpenHelper 的建表回调，避免 `A migration from 31 to 26 was required but not found` 直接终止宿主。该路径是破坏性降级兜底，设备端仍需验证数据保留策略。
- 常用语/剪贴板编辑模板：放宽 `HostPhraseEditor` 的入口解析，不再要求 DexKit 结果唯一；优先使用 `directory -> setDirectory(String)` 结构，旧版 `S.c(String)` 与新版 `X.b(String)` 均可进入同一模板匹配链。
- 英文候选图标：颜色继续以宿主 `key_en_predict` 实际读回值为唯一状态源；切换键盘视图或语言时只清除旧坐标，并在 clear/reset/update 前重新读取真实状态；本轮增加新 input view 后延迟复读，避免符号页往返读取切换前的缓存值。
- 剪贴板编辑写回：针对新版宿主新增 `clipboard_preview_text` 列，改用 SQL 表名 Contains 语义召回；本轮进一步修复两处真正阻断：Room 剪贴板 SQL getter 是实例方法，不能用 `invoke(null)`；事务执行器的 Kotlin 函数接口运行时名称为 `kotlin.jvm.functions.l`，不能限定为 `Function1`。现在按实例方法、Room 适配器基类、构造参数中的 DAO owner 和实体字段结构收敛；正文与预览同步写回。
- 系统返回手势：针对最新日志仍出现 `window hidden; no re-show fallback`，新增最近面板时间记录，并在 `onWindowHidden` 后补执行宿主 closePanel 与 `InputMethodService.showWindow(false)` 恢复输入法窗口。
- Hook 诊断：安装轮初始化不再把 false 占位落盘覆盖真实结果；跨进程合并跳过初始化占位，无结果统一显示“等待回传”；新增稳定的符号键盘切换诊断点。

## 验证

- `:app:compileDebugKotlin`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- 纯编辑模型回归：58 项 PASS
- Debug 默认日志：开启
- Release 默认日志：关闭
- Debug SHA-256：`5882f4482d8fe470c0d329c7f9f057f29a106b28d0f35bc8784786cf30f9c3ba`（3,228,154 bytes）
- Release SHA-256：`0761a3059d95597b774ad371cc77a6805241adfb5803b7e1467ab351daec84e1`（2,561,153 bytes）
- 设备端功能：待安装测试包、重启宿主并按验收动作逐项确认；构建和静态证据不替代真机验收。

## 产物

- 测试包：`dist/OplusImePanel-1.33.51-test.apk`
- Release 包：`dist/OplusImePanel-1.33.51-release.apk`
- Release 包是当前开发阶段适配版本的安装包，不代表对其他宿主版本的兼容承诺。
