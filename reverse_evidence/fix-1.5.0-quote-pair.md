# 1.5.0 · 引号「成对补全」抑制

## 需求

在符号键盘上输入**中文前引号**或**英文引号**时，宿主会自动补出配对的右引号，并把光标移到两个引号之间。
用户要的是：把引号当**普通符号**输入——只上屏这一个引号字符，光标停在它**后面**。

## 取证：配对不在 Java 层

在 `小布输入法_1.7.38.17-os.apk` 内逐条排查，结论是 **Java/Dex 侧没有任何"输入左引号 → 补右引号"的实现**：

| 排查项 | 结果 |
|---|---|
| 字符串 `“”` / `“` / `”` | 仅命中 3 个类，**全部是符号清单**：`input/utils/l` 的符号集合（用于 `checkCangjieZhuyinReturnSymbol` 判断键盘类型）、`input/view/O` 的符号键候选数组、`base/data/s` 的符号数据表 |
| 字符码 `0x201c`（U+201C） | 在 `com/oplus/keyboard` 全包内**只出现在 markdown 的 HTML 解析器** `markwon/html/jsoup/parser/j`（实体表） |
| `SymbolsHelper`（`input/utils/l`） | 只有 `addSymbol` / `checkCangjieZhuyinReturnSymbol` / `loadRecentSymbols`，无配对 |
| `toolbox/b->v/w` | 仅 4–5 条指令，往 `ArrayList` 里塞符号，是候选表构造器，不是配对判定 |
| `SmartPunctuation` 相关 | `input/view/k0` = `VoiceInputPanelHelper`（**语音输入**加标点）、`input/view/params/c` = 其参数类，与符号面板无关 |
| 设置项 | 无"配对/成对"字样；`key_punctuation_set` 属于语音标点设置 |
| 中文里"引号/配对/成对" | 全库**零命中** |

native 侧则存在输入引擎本体：`libjni_ime.so`（含 AOSP `latinime::MergeUtil::isSymbol`）、`libIQQILib.so`、`libokim_shared.so`、`libengine_jni.so`。

**判定：引号配对由 native 输入引擎完成，Java 层没有可直接关闭它的开关或函数。**

## 因此采取的策略：在上屏结果上修正

不去猜配对是谁做的、也不去猜它是"一次提交两个字符"还是"提交左引号后再补右引号并移动光标"，
而是利用一个**两种形态都必然产生**的可检测状态：

```text
光标前 = 左引号   且   光标后 = 配对的右引号          “ | ”
```

检测到就删掉光标后的那个右引号：

```text
只剩左引号，光标在它后面                              “ |
```

### 挂点

输入法进程内承载"输入法 → 编辑框"全部调用的，是 framework 的 InputConnection 代理实现。
**native 与 Java 代码最终都经过它**，所以在它的 `commitText` 与 `setSelection` 之后各检查一次：

| 挂点 | 覆盖的形态 |
|---|---|
| `commitText` after | 配对是"一次提交两个字符" → 提交后立刻成立 |
| `setSelection` after | 配对是"提交左引号后再补右引号并 setSelection 到中间" → setSelection 后才成立 |

两个检查都是幂等的（删掉后状态不再成立，不会重复删除）。
代理类名在版本间可能不同，因此按候选列表逐个尝试：`com.android.internal.view.IInputConnectionWrapper`、
`com.android.internal.view.InputConnectionWrapper`、`android.view.inputmethod.InputConnectionWrapper`。

### 误伤控制

1. `commitText` 只在**本次提交文本涉及引号**（长度 ≤ 4 且含引号）时才进入检查；
2. `setSelection` 虽然高频，但必须同时满足"光标前 1 字符是左引号""光标后 1 字符是其配对右引号"
   才会动作，普通文本永不命中；
3. 两次修正之间有 60 ms 最小间隔，防止同一状态被连续处理；
4. 编辑器不支持 `deleteSurroundingText` 时只记日志、不改动，不会崩。

四对引号纳入范围（用户只要求引号，不涉及括号等其它成对符号）：
`“ ”`、`‘ ’`、`" "`、`' '`。

## 静态自校验（1.5.0）

```
versionCode=7  versionName=1.5.0
dex 内 quote-pair 出现 9 次；IInputConnectionWrapper / InputConnectionWrapper / getTextAfterCursor /
deleteSurroundingText / "removed auto-inserted closing quote" 均在包内（各 ≥ 1）
宿主混淆类名写死检查：input.view.body.A0 = 0，base.enums.BoxEnums = 0，input.view.M; = 0
签名：apksigner 通过，证书 SHA-256 8ba6e538…（与 1.4.1 同一把密钥，可直接覆盖安装）
```

## 验证边界（务必按此核对）

- 本版**未在装有 LSPosed 的真机上验证**（工作区没有可运行的宿主设备）。
- 该功能与面板重排**互不影响**：面板定位失败时引号抑制依然会安装。
- 真机日志标签 `OplusImePanel`：

| 日志行 | 含义 |
|---|---|
| `quote-pair: installed, hook points=N` | 代理类已挂上；N 应 ≥ 1 |
| `quote-pair: no InputConnection proxy hooked; quotes keep host behaviour` | **三种代理类名都不存在** → 本功能在本机不生效，需要按新版本重新定位代理类 |
| `quote-pair: <候选类名> not present` | 该候选类名不在本版本 |
| `quote-pair: removed auto-inserted closing quote '”' (U+201D) after '“' (U+201C) (source=commitText, total=n)` | **生效证据**：右引号已按预期摘掉 |
| `quote-pair: editor rejected deleteSurroundingText` | 当前编辑框（多见于网页/自绘编辑器）不支持该操作，本功能对这类编辑框无效 |

真机核对动作：在中文符号页输入前引号 → 应只出现一个引号、光标在其后；
切英文面板输入 `"` 同样；再随便输入普通文本与成对括号，确认未受影响。
