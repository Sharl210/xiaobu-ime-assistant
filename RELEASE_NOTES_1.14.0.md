# 小布输入法助手 v1.14.0

## 本版两项改动

### 1. 成对符号不再自动补全

引号、括号、书名号等 **22 对成对符号**，点哪个就上屏哪个：

- 不再替你补另一半；
- 不再把光标夹在中间。

上滑输入的符号、符号页面里点的符号，一视同仁。

**实现方式**：输入法把"配对"这件事做在输入引擎（native）里，Java 侧没有可以关掉的开关。
因此改为在**上屏结果**上修正 —— 提交内容若正好是"左符号＋右符号"，直接在参数上砍掉右半边；
若光标被夹在一对中间，则删掉后面那个。三条挂载：`commitText` 参数、`setComposingText` 参数、
`commitText` / `setSelection` 之后。

**安全闸**：`setSelection` 挂在每次光标移动上，而用户手动把光标点到已有「（）」中间时
状态完全一样。因此只有"刚刚确实发生了一次成对提交"之后的 **400 毫秒**内才允许修正，
其余时刻只观察不动手 —— 不会误删你自己的符号。

### 2. 「符号」键直达完整符号页

键盘左下的「符号」键不再先落简洁符号页，一按就是完整符号页
（等价于原来连点两次的效果）。

**实现方式**：宿主的符号页由一枚枚举分三档，而**枚举常量名没有被混淆**，本身就是语义串：

```
SymbolKeyboardType = { NONE_SYMBOL, SYMBOL1, SYMBOL2 }
```

档位挂在输入法管理器的一个字段上。模块挂住所有会写这个字段的方法，
只把 `SYMBOL1`（简洁页）抬到 `SYMBOL2`（完整页），其余取值原样放行。
调用前查一次、调用后查一次 —— 因为宿主是"先写档位、再切键盘视图"，
在切视图之前把档位抬上去，建出来的就是完整页。

---

## 自校验

在**编译产物自身的 smali** 里逐条核对（不是看源码）：

```
.field private static final NAME_NONE:Ljava/lang/String; = "NONE_SYMBOL"
.field private static final NAME_SIMPLE:Ljava/lang/String; = "SYMBOL1"
.field private static final NAME_FULL:Ljava/lang/String; = "SYMBOL2"
const-string v4, "symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase="
.field private static final HOLDER_ANCHOR:Ljava/lang/String; = "show symbol view shown "
```

成对符号表逐字符确认在包里：

```
const/16 v1, 0x201c   ← “
const v1, 0xff08      ← （
const/16 v1, 0x3010   ← 【
const/16 v1, 0x300a   ← 《
```

宿主类名：产物里搜 `com/oplus/keyboard` → **0 命中**（全部运行时结构匹配，未写死）。

---

## 真机核对

```
logcat -b all -d | grep OplusImePanel
```

| 日志行 | 说明 |
|---|---|
| `symbol-page: enum=… constants=[NONE_SYMBOL, SYMBOL1, SYMBOL2]` | 枚举已按语义串找到 |
| `symbol-page: installed holder=… fields=1 hooks=N` | 管理器与档位字段已定位 |
| `symbol-page: promoted SYMBOL1 -> SYMBOL2 (phase=…)` | 点「符号」时出现＝直达完整页 |
| `quote-pair: pair commit trimmed to single …` | 成对提交被砍成单字符 |
| `quote-pair: removed auto-inserted closing symbol …` | 被夹在中间的形态已拆掉 |

若出现 `symbol-page: enum unresolved` 或没有 `installed`，说明该固件上
常量名/日志串对不上，请把这几行发回，按实际串调整查询条件。

---

## 前提

- 设备需 root + LSPosed。
- 作用域勾选 **小布输入法**（`com.oplus.keyboard`）。
- 每次改动后建议重启输入法进程。
