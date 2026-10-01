# 小布输入法助手 1.20.0

本版是针对 1.19.0 真机复测结果（三项仍未通过）的定点修复。所有改动都由设备端 LSPosed 日志
证明过的真实调用链决定，不是猜测。

## 1.19.0 真机日志里查到的三件事

设备日志（`/data/adb/lspd/log`，MT MCP 读取，2026-10-01 16:51 加载 1.19.0）：

```text
input-target candidates=5 → resolved=3 compatibleStatic=1
input-target: selected com.oplus.keyboard.input.manager.h#l params=android.widget.EditText, boolean
quote-pair: engine callback hooked com.oplus.keyboard.input.event.a#o
quote-pair: engine commit hooks installed=2
symbol-page: installed holder=com.oplus.keyboard.input.manager.l fields=1 switches=1 hooks=1
clip-search: host dialog shown, input-registered=true windowType=1003
```

也就是说：**1.19.0 确实装上了、真的跑起来了**，搜索输入目标也真的选对了（正是宿主自己的
`input/manager/h;->l(EditText, boolean)`）。但同一份日志里**没有**出现下面两行，而它们本来
应该出现：

```text
symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=view-switch-before[...])   ← 一次都没有
quote-pair: engine commit text='...' len=1|2                                ← 一次都没有
```

结论：这两处功能不是「改得不对」，而是**挂点根本没被执行**。于是本版先去宿主 dex 里把真实调用链
读全，再按真实链改。

## 本版改了什么

### 1. 「符号」键直达完整符号页（挂点选错，已改正）

宿主 `input/manager/l;->h0(boolean)`（符号页总入口）的真实形状是：**先写 `SYMBOL1`，
紧接着调用 `k0(管理器自身, KeyboardType, boolean, boolean, int)` 切键盘视图**。

1.19.0 挂的是 3 参数的 `f0`，符号键根本不走它 —— 这就是日志里一次都没出现的原因。

现在按 5 参数形状挂钩子，在**视图切换之前**把档位从 `SYMBOL1` 抬到 `SYMBOL2`：档位与画面
始终由宿主自己的流程产生，保持一致，返回箭头不受影响；不再调用任何猜出来的 `e0()`。
钩子第一次被调用时会写一行证据：

```text
symbol-page: switch hook fired <签名> args=5 current=SYMBOL1
symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=switch-before[...@5])
```

### 2. 符号成对上屏（改成在源头拦掉自动补全）

1.19.0 做的是「提交之后删掉补出来的右符号」。真机日志显示它确实删了 8 次，但用户看到的仍是成对，
原因是：`deleteSurroundingText` 返回 true 只代表**命令发出去了**，而旧代码看到 true 就关掉修正时间窗，
于是没删干净也不再重试。

本版做两件事：

1. **在提交入口直接拦掉自动补出来的右符号**：上一拍刚落下单个左符号、紧接着 130ms 内又送来
   正好配对的右符号、并且光标就贴在左符号后面 —— 这三条同时成立就判为自动补全，直接吞掉这次提交，
   文本里根本不会出现第二个符号。
2. 事后删除仍然保留作兜底，但**删完必须复读验证**：只有确认目标字符真的消失了才关闭时间窗，
   没删掉就继续在后续时间点重试。日志会带上分支与验证结果：

```text
quote-pair: dropped auto closing '" (U+0022)'
quote-pair: removed auto-inserted closing symbol '...' (branch=sandwich/tail, verifiedGone=...)
```

### 3. 搜索框打不了字（面板把键盘位置占了）

宿主布局 `res/IB.xml` 的根是 `match_parent`：剪贴板/常用语面板**占满整个键盘区域**，面板里
只有标题栏、列表和底栏，**没有任何按键**。所以「键盘弹不出来」的直接原因不是弹窗的窗口标志，
而是面板把键盘的位置占了。

宿主自己的做法（两条独立证据）都指向同一个入口：

```text
head/h0（SearchView / emoji 搜索）.d():
    editText.setImeOptions(3)                    // IME_ACTION_SEARCH
    editText.setOnEditorActionListener(...)
    input/manager/h;->l(editText, true)          // 注册成输入法"内部输入目标"
```

因此本版：

- 点「搜索」时**先收起面板**（走宿主自己的返回键链路，与剪切/粘贴返回用的是同一条），
  键盘立刻回来，弹窗里的输入框才有键可按；
- 输入框按宿主同款设置：`setImeOptions(3)`、输入类型 `text`，并交给 `h.l(editText, true)` 注册；
- **不再加 `0x20000`**：那是 `FLAG_ALT_FOCUSABLE_IM`（"这个窗口不要让输入法弹出来"）。
  宿主那个弹窗里没有输入框所以无妨，我们这个是要打字的，带上它系统就会把输入法收下去 ——
  实测表现正是「只有一个光标，键盘不出来」；
- 确认后重新打开面板，看到的就是过滤后的列表；取消同样回到面板，不会把用户丢在空键盘上；
- 注册是否成功、窗口类型与标志、输入类型都会写进日志：

```text
clip-search: panel closed before input=... page=...
clip-search: host dialog shown, input-registered=true windowType=1003 flags=0x... inputType=... imeOptions=3
clip-search: input target re-register=...
clip-search: panel reopened=... box=BOX_CLIP|BOX_PHRASE
```

## 产物

| 项 | 值 |
| --- | --- |
| 版本 | 1.20.0（versionCode 24） |
| APK | `OplusImePanel-1.20.0-release.apk` |
| SHA-256 | `96cbf4851bf3ff00845426c7c5dc6ea49086adb8890278e1e02d787dafc677b1` |

## 验证状态（如实说明）

本版只做到「挂点按宿主真实调用链选对，并且每一步都留了可查证据」。下面三项仍然**必须真机复测**
才能判定通过，本版不声称已完成：

- 符号键是否直达完整符号页、返回箭头是否正常；
- 符号是否只上屏单个字符（引号、括号等全部成对符号）；
- 搜索框是否能真正用键盘打字、过滤结果是否正确显示。

复测时请在日志里核对这四行：

```text
input-target: selected ...                    搜索输入目标
clip-search: host dialog shown, input-registered=true
symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=switch-before[...])
quote-pair: dropped auto closing '...'
```
