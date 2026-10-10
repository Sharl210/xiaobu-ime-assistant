# 小布输入法助手 1.33.60

> **开发阶段适配版本声明**：本版本是针对当前已取得并核验的宿主版本进行适配的开发阶段版本。虽然模块使用 DexKit 做结构匹配，但不保证其他小布输入法版本可以直接使用。

## 本轮变更

- 修复主键盘回车收尾 Hook 的参数假设：新版宿主 `body.t.z(int,float,float,long,SoftKey)` 和旧版对应方法在实际抬起调用时，末位 `SoftKey` 参数可能为 `null`，真实键位保存在宿主按指针编号维护的内部表中。
- 主键盘抬起 Hook 改为在宿主方法执行前，从宿主指针表恢复当前 `SoftKey`，再严格读取 `SoftKey.a`；只有 `ENTER=66` 才进入回车处理，删除键 `67` 和其它按键不处理。
- 保留宿主原生回车链，不设置 `param.result`、不修改参数；如果主键盘回车抬起后短窗口内没有观察到宿主原生 Enter 分发，才调用宿主服务的 `sendDownUpKeyEvents(KEYCODE_ENTER)` 兜底。
- 保留键盘“换行”文案替换为 `↵`，以及文本编辑面板单独回车路径。

## 关键静态证据

- 新版宿主主键盘 `onTouchEvent(MotionEvent)` 在抬起时调用 `body.t.z(pointerId,x,y,eventTime,null)`，`body.t.z` 再从内部指针表取回 `SoftKey`。
- 新版宿主 `SoftKey` 类为 `com.oplus.keyboard.base.entity.e`，键码字段为 `a:I`，回车值为 `66`。
- `input.event.r.t0(66,0)` 和 `r.u0(66,0)` 会通过 `InputConnection.sendKeyEvent()` 发送按下、抬起事件。

## 验证状态

- `:app:compileDebugKotlin`：PASS
- `:app:assembleDebug`：PASS
- `:app:assembleRelease`：PASS
- `git diff --check`：PASS
- Debug APK：`dist/OplusImePanel-1.33.60-debug.apk`，2,970,447 bytes，SHA-256 `de2aba65df9f350a62da6920e32e58fda9dc1243000f17fd350cd2a4b970228a`
- Release APK：`dist/OplusImePanel-1.33.60-release.apk`，2,577,546 bytes，SHA-256 `f0f589b4e6118b4f0adb5c4f5c1785d820666b2089d11e42c28d30689e88bd05`
- Release 版本配置：`versionName=1.33.60`、`versionCode=99`
- SSH/终端真机验证：用户已确认当前版本主键盘回车在目标终端中可用；本发布以用户实测结论为验收依据。

- 当前发布对应的真实宿主 APK：`XiaobuInputMethod-1.8.33.17-mkt-host.apk`；包名 `com.oplus.keyboard`，versionName `1.8.33.17-mkt`，versionCode `1518033`，文件大小 `65,670,800 bytes`，签名 SHA-256 `fc98dae63ad39626c8c67fbe83f2f06f74932a9cd146b92cecfc6a047a904386`。

## 发布资产约定

正式发布时必须同时包含模块 APK 与当前实际适配的真实宿主输入法 APK。缺少真实宿主包时，发布脚本必须阻止发布，不得使用占位文件。
