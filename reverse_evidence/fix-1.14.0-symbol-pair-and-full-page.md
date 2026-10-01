# 1.14.0 取证与实现：成对符号不补全 + 「符号」键直达完整符号页

日期：2026-10-01
宿主：`com.oplus.keyboard` 1.7.38.17-os

---

## 一、「符号」键为什么先进简洁页

宿主自己 dex 里的证据（`input/view/O` 的 `q` / `s` / `t` / `u` 是符号键分发，
`input/manager/l` 是输入法管理器）：

```text
SymbolKeyboardType = { NONE_SYMBOL, SYMBOL1, SYMBOL2 }     ← 常量名未被混淆，是语义串
```

管理器上的字段：

```text
l->v:Lcom/oplus/keyboard/base/enums/SymbolKeyboardType;    ← 当前符号页档位
```

写入点（用字段反查得到，共 5 个方法）：

| 方法 | 行为 |
|---|---|
| `l->v(KeyboardType, Z)` | 普通键盘切符号：写 `NONE_SYMBOL`；`Z=true` 时写 `SYMBOL2` |
| `l->h0(Z)` | 「显示符号页」总入口；分支里 `SYMBOL1` → 切 `KeyboardType.x` |
| `l->k0(...)` | 换键盘视图时同步写档位 |
| `l->e0()` | 同上（宿主 `IInputApi.showSymbolsView()` 走这条） |

`l->h0` 内部独有的日志串：`"show symbol view shown "`（`const-string`，全包唯一）。

**结论**：键盘上的「符号」键走的是分档那条路，先落 `SYMBOL1`；
要再点页内「更多」才升到 `SYMBOL2`。

## 二、做法

不猜「更多」按钮长什么样，而是在**档位被写下的时刻把它抬到最高档**：

1. 按枚举常量名（语义串 `NONE_SYMBOL` / `SYMBOL1` / `SYMBOL2`）找到这枚枚举；
2. 按 `"show symbol view shown "` 找到输入法管理器类，再核对它确实持有该枚举类型的实例字段；
3. 取该类上**全部**方法挂钩子，调用**前**与调用**后**各查一次；
4. 只把 `SYMBOL1` 改写成 `SYMBOL2`，其余取值原样放行。

调用**前**也查一次是必要的：宿主是「先写档位、再切键盘视图」，
切视图那一步才知道该建哪一页；在它之前把档位抬上去，建出来的就是完整页。

**不写死任何宿主类名**：编译产物里 `com/oplus/keyboard` 出现 **0** 次。

---

## 三、成对符号「自动补全」

### 取证

Java 侧**没有**「输入左符号 → 补右符号」的判定：

- 全包内含 `“”` 的类只有三处，全是**符号清单/候选表**（`input/utils/l` 的符号集合、
  `input/view/O` 的符号键候选、`base/data/s` 的符号数据）；
- 字符码 `0x201c` 在 `com/oplus/keyboard` 全包内只出现在 markdown 的 HTML 解析器里；
- `SmartPunctuation` 属于**语音输入**加标点（`VoiceInputPanelHelper`），与符号面板无关。

因此配对由**输入引擎（native）**完成，Java 层没有可直接关闭的开关。

### 做法：在上屏结果上做修正

配对完成后，编辑框里必然出现同一个可检测状态：

```text
光标前面是左符号  且  光标后面是它的配对右符号      →  （ | ）
```

检测到就删掉光标后面那个右符号。三条挂载：

| 挂点 | 作用 |
|---|---|
| `commitText` 参数 | 一次提交就是「左＋右」两个字符时，**直接在参数上砍掉右半边**，交给宿主自己的流程提交单字符 |
| `setComposingText` 参数 | 组合文本形态的成对提交，同上 |
| `commitText` / `setSelection` 之后 | 兜「先提交左、再补右并移动光标」的形态 |

### 安全闸（本次最重要的改动）

`setSelection` 挂在输入连接的每次光标移动上。**用户手动把光标点到一段已有文字的
「（）」中间时，光标同样呈现"被一对符号夹住"的状态** —— 若不加限制就会误删
用户自己的右括号。

因此加了 `SANDWICH_WINDOW_MS = 400ms` 的有效时间窗：

> 只有在"刚刚确实有一次成对符号提交"之后的 400ms 内才允许修正；
> 其余时刻一律只观察、不动手。

修正完成即关闭时间窗（`lastPairCommitAt = 0L`），避免同一状态被反复处理。

### 覆盖范围（22 对）

引号：`“”`、`‘’`、`„“`、`«»`、`‹›`、`‚‘`、`「」`、`『』`、`〝〞`、`"`、`'`
圆括号：`()`、`（）`
方括号：`[]`、`【】`、`［］`
花括号：`{}`、`｛｝`
尖括号/书名号：`<>`、`〈〉`、`《》`、`＜＞`

---

## 四、自校验（对**编译产物**逐条核对，非源码）

工作区 `mt://current-apk` = 本次 1.14.0 产物（`versionCode=17`，验签 V1/V2/V3 齐）。

**在产物自身的 smali 里读到：**

```text
.field private static final NAME_NONE:Ljava/lang/String; = "NONE_SYMBOL"
.field private static final NAME_SIMPLE:Ljava/lang/String; = "SYMBOL1"
.field private static final NAME_FULL:Ljava/lang/String; = "SYMBOL2"
const-string v4, "symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase="
.field private static final HOLDER_ANCHOR:Ljava/lang/String; = "show symbol view shown "
```

成对符号表（`QuotePairSuppressor` 的 `<clinit>`，逐条 `const` 已确认在包里）：

```text
const/16 v1, 0x201c      ← “
const v1, 0xff08         ← （
const/16 v1, 0x3010      ← 【
const/16 v1, 0x300a      ← 《
```

宿主混淆类名：`smali` 全文搜 `com/oplus/keyboard` → **0 命中**。

保留能力：`panel transplanted` / `content-truncation` / `record-trim` /
`clip_length` / `panel verify rows=` 全部仍在。

---

## 五、真机核对

```
logcat -b all -d | grep OplusImePanel
```

| 日志行 | 说明 |
|---|---|
| `symbol-page: enum=… constants=[NONE_SYMBOL, SYMBOL1, SYMBOL2]` | 枚举已按语义串找到 |
| `symbol-page: installed holder=… fields=1 hooks=N` | 管理器与档位字段已定位 |
| `symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=…)` | **键「符号」时应出现这行**，且直接是完整页 |
| `quote-pair: pair commit trimmed to single …` | 一次提交成对 → 已在参数上砍成单字符 |
| `quote-pair: removed auto-inserted closing symbol …` | 先提交左再补右的形态被拆掉 |

**若 `symbol-page: enum unresolved` 或 `installed` 没出现**，说明这台固件上
常量名/日志串对不上 —— 把这几行发回，按实际串改查询条件，不必再猜。
