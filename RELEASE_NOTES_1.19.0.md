# 小布输入法助手 1.19.0

版本号：`1.19.0`（versionCode 23）

本版只做一件事：**把上一版改错的地方，按宿主真实代码改对**。三个问题（搜索框打不了字、符号键不进完整符号页、符号成对补全）都重新回到宿主 dex 取证，改成有证据的做法；不再靠猜。

---

## 一、搜索弹窗：输入框终于能接上输入法键盘

### 上一版错在哪

日志给出了直接证据：

```text
input-target candidates=11766
input-target: instance owner unresolved for androidx.appcompat.widget.Toolbar#b
```

召回条件太宽（"两个参数 + 返回 void"），整包命中 11766 个候选，最后选中了 `androidx.appcompat.widget.Toolbar#b`——一个实例方法，拿不到宿主实例，于是**搜索框创建成功，却从来没接上输入法**。这就是"弹窗出来了，光标点上去键盘不弹"的原因。

### 这一版怎么改

回到宿主自己的代码里找答案。宿主"编辑常用语"的输入框是这样接上的：

```text
input/view/head/O;->o(CustomEditText)
    → setImeOptions(1)
    → input/manager/h;->l(editText, true)      ← 把 EditText 设为输入法内部输入目标
    → manager/l;->c0()、容器 z0(view)          ← 让键盘显示出来
```

`input/manager/h;->l` 的形状是 **静态 + `(EditText, boolean) -> void`**，而且在整包内**唯一**（全 dex 只有这一个静态方法长这样）。所以召回收紧成：

```text
返回 void，参数恰好是 (android.widget.EditText, boolean)，且必须是静态方法
```

命中即调用 `l(editText, true)`，与宿主同向：设为内部输入目标 + 让键盘出来。

### 弹窗窗口属性也照抄宿主

宿主「添加常用语」弹窗 `input/view/body/D;->q(String, Function0)` 的窗口设置是：

```text
token  = 当前输入法窗口的 token
type   = 0x3eb
addFlags(0x20002)   // FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM
背景透明、dimAmount = 0.3
```

关键是 `0x20002`：弹窗**不抢焦点**，输入法窗口不会被压下去，文字由内部输入目标接收。上一版用的是 `type=0x7dc` 并主动清掉 `FLAG_ALT_FOCUSABLE_IM`（让弹窗去抢焦点），方向正好相反，所以键盘始终起不来。本版逐字照抄宿主。

---

## 二、「符号」键：直达完整符号页，返回键也恢复正常

### 上一版错在哪

1.17/1.18 的做法是"等 `h0` 返回后把档位从 `SYMBOL1` 改成 `SYMBOL2`，再调用候选的完整页入口 `e0()`"。

但宿主的顺序是**先写档位、后切视图**：

```text
h0(shown)
    → 按当前键盘类型分支：先把档位写成 SYMBOL1（简洁页）
    → 紧接着调用视图切换方法 ->f0(管理器, shown, 键盘类型)
```

等 `h0` 返回时**简洁页已经建好了**；再调别的方法，只会让状态和实际页面错位——页面还是简洁页，而返回箭头因为状态被改过而回不去主键盘。

### 这一版怎么改

只拦**视图切换方法执行之前**那一瞬间：

```text
静态  void  (管理器自身, boolean, 键盘类型枚举)
```

在它执行前把档位从 `SYMBOL1` 抬到 `SYMBOL2`，这一次切换建出来的就是完整页。因为改的是宿主自己的状态字段、走的也是宿主自己的切换流程，档位和实际页面始终一致，返回箭头不再受影响。`NONE_SYMBOL`（回主键盘）与本来就是 `SYMBOL2` 的一律不动，也不再调用任何猜测出来的完整页入口。

---

## 三、成对符号：挂到真正的提交汇聚点

### 找到的实事

native 引擎回调 Java 的实现是 `input/event/a`（类内日志串 `commitText, cleared compositionText`，日志 tag `CjZhuyinEngineCallbackImpl`）；它的 `o(String)` 就是"引擎说要提交这段文字"，紧接着调用

```text
input/event/p;->A(CharSequence, InputConnection, c, boolean, int)
```

完成提交。符号键（上滑符号、符号页）同样从这里出去——之前的版本没挂到这条链上，所以体感毫无变化。

### 这一版怎么改

按结构 + 语义串把两个挂点找出来（不写死类名、不写死混淆方法名）：

1. 类内含上述日志串、且有 `(String) -> boolean` 实例方法 → 挂引擎回调；
2. 类内含 `commitText failed` 串、且有静态 `(CharSequence, InputConnection, *, boolean, int) -> boolean` → 挂真正提交的分发器。

两个挂点都做同样两件事：

- **一次提交上来正好是一对**（例如 `“”`、`「」`、`()`）→ 直接把参数裁成单个左符号；
- 记一行证据日志（`quote-pair: engine commit text='「' (U+300C) len=1` 这种），
  并打开"夹缝修正"的时间窗，由既有的多点复查负责拆掉延迟补出的右符号。

日志里 `len=2` 还是 `len=1`，直接决定下一步往哪查（键位表还是提交后），不再靠推测。

---

## 安装与验证

1. 安装本版 APK，确认模块页面显示 `1.19.0`；
2. LSPosed 中作用域保持勾选 `com.oplus.keyboard`，强制停止并重开输入法；
3. 依次验证：搜索框能否用键盘打字、点「符号」是否直接进完整页且返回键正常、输入 `「` `“` `(` 是否只上屏一个字符。

日志筛选（LSPosed 文件日志或 logcat）：

```text
OplusImePanel
input-target
clip-search
symbol-page
quote-pair
```

---

## 说明

本版仍未把三项标记为"已通过"。这三处都是真机交互行为，必须由真机日志与你的实际操作确认；代码层面的取证只到"挂点选对、证据可查"为止。
