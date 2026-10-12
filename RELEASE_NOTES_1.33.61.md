# 小布输入法助手 1.33.61

> **开发阶段适配版本声明**：本版本是在已核验宿主版本上继续适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 修复中文和英文候选状态下的回车行为：第一次点击回车只完成当前候选/组合文本上屏，不再同时自动补发一次真正的 Enter。
- 主键盘回车收尾 Hook 现在会观察当前 `InputConnection` 的 `setComposingText`、`finishComposingText` 和 `commitText` 状态；检测到候选/组合文本后，跳过模块自己的“原生回车缺失”兜底。
- 候选上屏后状态只绑定到当前这一次回车抬起，不会永久屏蔽下一次回车；用户再次点击回车时，仍会按正常链路发送真正的 Enter。
- 无候选时保留原有 SSH/终端回车兼容：宿主原生回车链优先，宿主链缺失时才使用服务或当前连接兜底。
- 保留键盘“换行”文案替换为 `↵`，以及文本编辑面板的独立回车路径。

## 验证状态

- `:app:compileDebugKotlin`：PASS
- `git diff --check`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- Debug APK：`dist/OplusImePanel-1.33.61-debug.apk`，2,970,447 bytes，SHA-256 `e05828db059914c82e1df9c142b593914cb4ae1cc939eeba0c60ee3dbb5c978a`
- Release APK：`dist/OplusImePanel-1.33.61-release.apk`，2,577,536 bytes，SHA-256 `f868d644479dbf8b4187f7e90ae8b93179678495ba71920cd6b9f824119f2146`
- Release 版本配置：`versionName=1.33.61`、`versionCode=100`
- 中文候选真机验证：待安装本轮 APK 后确认第一次回车只上屏、第二次回车才换行
- 英文候选真机验证：待安装本轮 APK 后确认第一次回车只上屏、第二次回车才换行
- SSH/终端无候选回车回归：待安装本轮 APK 后确认原有回车链仍可用

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
