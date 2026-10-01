# 小布输入法助手 1.21.0

本版全部改动都由**设备端 LSPosed 真实日志**决定，不是猜测。先把这次真机日志读完整，再动手。

## 一、先纠正上一版的判断（取证结论）

从设备日志（`/data/adb/lspd/log`，MT MCP 读取）可以确认 1.20.0 **确实装上并在跑**：

```text
17:13:35  symbol-page: switch hooked l#k0(l, KeyboardType, boolean, boolean, int)
17:13:35  symbol-page: switch hooked l#f0(l, boolean, KeyboardType)
17:13:35  symbol-page: installed holder=com.oplus.keyboard.input.manager.l fields=1 switches=2(5arg=1,3arg=1) hooks=2
17:14:04  symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=switch-before[QWERTY_EN_SYMBOL@5] total=1)
17:17:14  symbol-page: switch hook fired l#k0 args=5 current=SYMBOL1
17:17:14  symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=switch-before[QWERTY_PINYIN_SYMBOL@5] total=1)
17:17:30  clip-search: panel closed before input=true page=CLIPBOARD
17:17:30  clip-search: host dialog shown, input-registered=true windowType=1003 flags=0x1800002 imeOptions=3 inputType=1
```

也就是说：**符号页钩子这次真的被调用了**（1.19.0 挂错方法所以一次都没触发），**档位也确实改成了 SYMBOL2**。

但同一份日志里：

```text
symbol-page: 界面仍为简洁页（用户反馈）
quote-pair: engine commit text=...   ← 0 次
quote-pair: dropped auto closing ... ← 0 次
quote-pair: removed auto-inserted ...← 0 次
```

于是去宿主 dex 里核对，得到两条硬事实：

1. **全包内没有任何代码把符号档位写成 `SYMBOL2`**（对该字段做静态引用扫描，只有 3 处读取，没有写入）。
   所以「简洁页 / 完整页」不是由这个档位字段决定的，改它当然不会换页。
2. 真正决定「建哪一页」的是**键盘类型参数**。宿主 `KeyboardType` 枚举里，所有具体键盘都是成对的
   `QWERTY_XXX_SYMBOL`，而**通用完整符号页单独有一个常量 `SYMBOLS`**。

## 二、本版改了什么

### 1. 「符号」键直达完整符号页（换了正确的着力点）

不再去动那个档位字段，而是在**视图切换执行之前**，把参数里的「简洁符号键盘类型」直接换成
完整符号页类型 `SYMBOLS`：

```text
symbol-page: switch target 'QWERTY_PINYIN_SYMBOL' -> SYMBOLS (full page)
```

只对名字里含 `SYMBOL` 的键盘类型生效，普通拼音/英文等一律不碰，因此不影响日常输入；
换不动时（例如该常量在本版不可用）自动退回原来的「抬档」兜底，不会把键盘弄坏。

### 2. 文本编辑面板的返回键 → 回键盘主页面（本轮新增）

取证：`res/bL.xml` 里返回箭头就是 `iv_back`，它落在面板自身 `onClick` 的
`resource-id + framework-call` 分支，做的是「隐藏当前容器」。

本版在这个动作之后，借宿主自己的「重置键盘」入口（也就是 `IInputApi.resetKeyboard()` 的实现，
传入 `false`）把键盘恢复成主键盘页，实现「返回 = 回输入法主页面」：

```text
panel back tapped -> restoring main keyboard
symbol-page: back -> host keyboard reset invoked (total=N)
```

### 3. 成对符号（补上决定性留证）

上一版的三条分支在真机日志里**一条都没出现**，说明钩子没走到用户那条路径上。
本版对**所有短文本提交**（长度 ≤ 4）无条件留证，节流 150ms：

```text
quote-pair: commit '“ (U+201C)' len=1 via=<类名>.commitText
quote-pair: commit '“”'        len=2 via=<类名>.commitText
```

这一行就是下一轮的判据：

- 点一次前引号若出现**两行**（先 `“` 后 `”`）→ 配对发生在提交之后；
- 若出现**一行 `len=2`** → 配对发生在键位表里；
- 若**一行都没有** → 这条链根本没参与，配对完全在 native 侧完成。

原有的「源头拦截自动补出的右符号」与「事后删除 + 复读验证」都保留。

## 三、产物

| 项 | 值 |
| --- | --- |
| 版本 | 1.21.0（versionCode 25） |
| APK | `OplusImePanel-1.21.0-release.apk` |
| SHA-256 | `4299b07d254bac78a0092c463453d5e489d1ac10ef007cd5e02b31c382173121` |

## 四、验证状态（如实说明）

本版仍**不声称**下面三项已完成，必须真机复测：

- 符号键是否真的进入完整符号页；
- 文本编辑面板返回后是否停在键盘主页面；
- 符号（引号、括号等）是否只上屏单个字符。

复测时请核对这几行：

```text
symbol-page: switch target '...' -> SYMBOLS (full page)
panel back tapped -> restoring main keyboard
quote-pair: commit '...' len=1|2 via=...
```
