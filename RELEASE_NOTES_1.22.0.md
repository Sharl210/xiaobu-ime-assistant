# 小布输入法助手 1.22.0

本版由设备端 LSPosed 真实运行日志（`/data/adb/lspd/log`，2026-10-01 18:07–18:10，1.21.1 运行期间）
直接驱动。这一轮日志解决了三个悬空问题，也推翻了一条我此前一直在走的方向。

## 一、先确认：崩溃已经修好

18:07:46 加载的是 1.21.1，日志里是：

```text
symbol-page: entryMethod=l#h0
```

不再出现 1.21.0 引入崩溃时的那行 `fullPageType=SYMBOLS`。整场反复按「符号」键没有再崩溃。

## 二、符号页：终于看清宿主自己的「更多」是怎么工作的

你按「符号」键时，日志给的是：

```text
symbol-page: switch observed l#k0 args=5 current=SYMBOL1 target=QWERTY_PINYIN_SYMBOL
```

即：宿主先写档位 `SYMBOL1`，再切到 `QWERTY_PINYIN_SYMBOL`（简洁页）。

而宿主自己那个「更多」按钮，静态取证（符号列表适配器 `SymbolsAdapter` 及其内部类、
符号页回调 `body/x`）表明它走的是**同一个管理器入口 `h0(boolean)`**；`h0` 在
「当前已经是符号键盘」时进入 `v(前一个键盘类型, true)` 分支，那是全包内**唯一**会把档位
写成 `SYMBOL2`（完整页）的地方。

这也解释了 1.20.0 为什么白改：它在 `k0` **执行之前**改档位，而 `k0` 随后会按 body 类型重置；
「更多」是在页面已经建好**之后**改，那时没人重置。

### 本版做法

在「刚从主键盘进入简洁符号页」的那次切换**执行之后**，替用户调用一次宿主自己的 `h0(true)`
——与用户手点「更多」完全同一条路，因此不猜类型、不改参数、不会崩。日志会写：

```text
symbol-page: follow-up 'more' invoked h0(true) (total=N)
```

## 三、成对符号：挂点从头到尾挂错了地方

整场运行只有**一行**提交留证：

```text
quote-pair: commit 'u (U+0075)u (U+0075)h (U+0068)' len=3 via=BaseInputConnection.commitText
```

用户点了很多次引号/括号，`commitText`、`setComposingText`、宿主引擎回调
`input/event/a#o`、`input/event/f#o`、以及两个提交分发器**全都没有出现**。

结论很硬：**符号提交走的是输入法进程里那个框架侧的「远程输入连接」**
（`InputMethodService.getCurrentInputConnection()` 返回的对象），它既不是
`BaseInputConnection`、也不是任何 `InputConnectionWrapper`——而它从来没被挂过。

### 本版做法

挂住框架的提供者 `InputMethodService#getCurrentInputConnection`，拿到实例后**按其运行时真实类**
动态挂载 `commitText` / `setComposingText` / `setSelection`。不写死任何与 Android 版本相关的类名。
留证里同时带上运行时类名：

```text
quote-pair: remote IC provider hooked (android.inputmethodservice.InputMethodService#getCurrentInputConnection)
quote-pair: remote IC hooked <运行时类> points=N
quote-pair: commit '…' len=1|2 via=<运行时类>.commitText
```

## 四、返回键回主键盘：拿到失败原因并修掉

```text
symbol-page: back ignored (holder instance unresolved)
```

根因：之前靠「自类型静态单例」去猜输入法管理器实例，而宿主的管理器不是 Kotlin object，猜不到。

修法：**在切换调用里直接抓实例**——宿主的切换方法第 0 个参数就是管理器自身，这是可靠来源；
同时保留自类型静态单例作为兜底，并写清用的是哪一条。日志会写：

```text
symbol-page: back -> host keyboard reset invoked (total=N)
```

## 五、搜索输入：这一版日志是正向的

```text
18:08:00.610 clip-search: panel closed before input=true page=CLIPBOARD
18:08:00.617 clip-search: search bar shown in ime window root=com.android.internal.policy.DecorView
            input-registered=true imeOptions=3 inputType=1
18:08:00.869 clip-search: search bar input re-register=true
18:08:03.393 quote-pair: commit 'uuh' len=3 via=BaseInputConnection.commitText
```

面板确实先收起了、搜索条确实挂进了输入法窗口根视图（`DecorView`）、输入目标确实注册成功，
而且 3 秒后确实有一次真实提交进入了编辑框。本版把该行留证改为带**运行时类名**，
下一轮就能一眼分辨文字到底进了我们的搜索框还是宿主内部编辑框。

## 产物

| 项 | 值 |
| --- | --- |
| 版本 | 1.22.0（versionCode 27） |
| APK | `OplusImePanel-1.22.0-release.apk` |
| SHA-256 | `fc9a299eca71480f8d37ca3a5d7ae73150d379a69a3a199fccd260eda6e79d04` |

## 验证状态（如实说明）

本版做到的是「按真机日志把挂点挪到正确的一层，并且每一步都留了可查证据」。
下面四项仍**必须真机复测**才能判定，本版不声称已完成：

- 「符号」键是否直达完整符号页、返回箭头是否回到主键盘；
- 引号/括号是否只上屏单个字符；
- 搜索框是否能真正用键盘打字、过滤结果是否正确显示。

复测时请核对这五行：

```text
symbol-page: follow-up 'more' invoked h0(true)
symbol-page: back -> host keyboard reset invoked
quote-pair: remote IC hooked <类名> points=N
quote-pair: commit '…' len=1|2 via=<类名>.commitText
clip-search: search bar shown in ime window root=… input-registered=true
```
