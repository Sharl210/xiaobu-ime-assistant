# 1.4.1：撤除「点完即收」，恢复「面板留在原地」

## 为什么撤

1.4.0 把用户的描述读反了。

用户原话（第一次）：「文字编辑面板它是不会自己返回回键盘页面的，就是说比如说我按了全选或者
复制，或者甚至于粘贴按钮，这些就是这六个按键，他都不会点击以后返回我们的键盘页面，它只是一直
在那个页面，除非我们手动去返回。」

我把这句当成缺陷，认定目标是「点完自动收起、回键盘」，于是专门推导出宿主的关闭链并接在六个键上。
用户随即纠正：「我不是说了吗，那一个文本编辑的面板不会自动关，就是我点了按钮以后不会自动关。
就是要让它不会自动关。」

也就是说，那句是在**陈述期望的现状**，不是报故障。宿主的行为（点了不收、手动返回才退出）正是
用户要的——要连续操作（全选 → 复制 → 粘贴）时不必反复重新打开面板。

判定：这是一次**需求方向误读**，不是实现失败。正确处置是把该功能整体移除，而不是调参数或加开关。

## 撤了什么

用按行号定位、逐行内容校验的脚本删除（校验不通过即中止，保证不误删）。

### HookEntry.kt（419 → 340 行）

| 原行号 | 内容 |
|---|---|
| 267–331 | `resolveClosePath(...)`：按形状定位「静态无参访问器 + 静态单参关闭方法」的整段查询与反射调用 |
| 143–162 | 挂在面板 `onClick` 上的 `afterHookedMethod` 收尾钩子（点完即调关闭链） |
| 124 | `val closePath = resolveClosePath(onClick, hostClassLoader)` |
| 133 | 传给 `PanelArranger` 的 `closePanel = closePath` 参数 |

原位留下一条说明注释，写清「本模块不接管关闭面板」及其原因。

### PanelArranger.kt（719 → 584 行）

| 原行号 | 内容 |
|---|---|
| 366–481 | 「点完即收」整段：`onHostButtonClick` / `closeOnClickIds` / `requestClose` / `ensureDeleteTapClose` / `onTouchListenerOf` |
| 80–84 | 构造参数 `closePanel` 及其注释 |
| 93–97 | `TAP_MAX_HOLD_MS`、`hookedTouchClasses` |
| 148–149 | `State.deleteWrapped` |
| 186 | `applyInternal` 里的 `ensureDeleteTapClose(...)` 调用 |
| 4 / 8 / 13 / 14 | 随之无用的 import（`SystemClock` / `MotionEvent` / `XC_MethodHook` / `XposedBridge`） |
| 68–71 | 类注释里的「3. 点完即收」段，改写为「面板**不自动关闭**」 |

**保留未动**：格子置换与锚点依赖链（1.3.0 的修复）、左列第一格双态（全选／剪切）、宿主按键
反馈链（剪贴板按钮的震动）、剪贴板分发器、版式自检日志。

## 影响面与风险

- 六个格子的点击行为**完全回到宿主原生**。宿主本来就不收面板，所以不需要任何补偿动作。
- 移除后模块不再调用任何「关闭」入口，也就不存在「误关面板」或「与宿主自己的关闭动作打架」的风险。
- 唯一被牵动的是导入清理：删 `SystemClock` / `MotionEvent` / `XC_MethodHook` / `XposedBridge` 时，
  曾一并删掉仍在使用的 `android.view.HapticFeedbackConstants`（剪贴板按钮在拿不到宿主反馈链时的
  兜底触感）。编译前已补回并复核使用处计数。

## 自校验（1.4.1 / versionCode 6）

「删是否真生效」用 dex 字符串计数判定，而不是看源码：

| 字符串 | 期望 | 实测 |
|---|---|---|
| `close-path` / `request return to keyboard` / `panel closed -> back to keyboard` | 0 | 0 |
| `delete tapped` / `delete long-press` / `panel onClick hooked` / `host button ` | 0 | 0 |
| `panel close skipped` / `close path unavailable` | 0 | 0 |
| `panel transplanted` / `clipboard button created` / `panel verify rows=` | >0 | 1 |
| `selection cell switched` / `clipboard opened via structural dispatcher` | >0 | 1 |
| `key-feedback` | >0 | 5 |
| 宿主类名写死 `input.view.body.A0` / `base.enums.BoxEnums` / `input.view.M;` | 0 | 0 |

签名：与 1.1.0–1.4.0 同证书（SHA-256 `8ba6e5388b08901852793b6af7da3f1d99b0fdbc18ec6e2ccaf2d1f168f0000f`），
可直接覆盖安装。

## 真机核对

```bash
logcat -b all -d | grep OplusImePanel
```

- 点全选 / 复制 / 粘贴 / 回车 / 删除：**面板应留在原地**，日志里**不应**再出现任何
  `request return to keyboard` 或 `panel closed` 行。
- 只有自己按左上角返回箭头时面板才收起（那是宿主自身行为，不经过模块）。
- 版式自检行仍在：`panel verify rows=… verdict=PASS`。

## 教训（可复用）

**用户描述的一句「它不会 X」既可能是抱怨（该 X 没做到），也可能是期望（就该不 X），字面无法区分，
必须结合他要的最终效果判断。** 本例里「面板不会自动关」我选了前者，代价是一条完整功能被实现又回退。

可用的判别手段：
1. 追问一句「这是要保持的，还是要去掉的」——成本最低；
2. 先只做**最小可逆改动**，并在交付说明里显式写出自己采用的假设；
3. 对只影响「收尾动作」的行为（关/不关、停留/跳转），尤其不要自行推断为缺陷。
