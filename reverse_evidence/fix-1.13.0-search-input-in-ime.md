# 1.13.0 取证与修复记录：搜索输入框必须长在输入法窗口内部

## 一、问题（用户实测）

搜索弹窗里有输入框，但**光标点上去不弹键盘、打不了字**。

## 二、被否决的两种做法（都试过了，都错）

### 错法一：`PopupWindow`

它只是挂在当前窗口上的一层浮层，没有自己的 window token，不参与输入法的窗口层级，
在输入法进程里**永远收不到键盘输入**。

### 错法二：`type=0x3eb` 的附加 Dialog

宿主自己的确认框 `com.oplus.keyboard.input.view.body.D;->q(String, Function0)` 确实是这么做的：

```text
COUIAlertDialogBuilder(context).setTitle(...).create()
window.attributes.token  = <IME 的 windowToken>
window.attributes.type   = 0x3eb      # TYPE_APPLICATION_ATTACHED_DIALOG
window.addFlags(0x20002)              # FLAG_DIM_BEHIND | FLAG_ALT_FOCUSABLE_IM
dialog.show()
```

但这**只适用于"点一下按钮"的确认框**。搜索框要打字，而这个 Dialog 是叠在输入法窗口
**之上**的另一层窗口——输入法本身就是要弹出来的那个东西，它没法再在自己头上弹一次，
于是键盘不出来。

> 这一条是用户在真机上判断出来的，不是我先想到的。

## 三、正确做法（本版采用的）

**不做第二个窗口，把输入界面放进输入法窗口内部**——与宿主「添加常用语」那一页同思路：

1. 在搜索按钮所在的父容器（`tv_clip_count` 的 parent，即宿主 `res/IB.xml` 的根）里
   加一张**输入法窗口内部的**白色卡片：标题 + 输入框 + 取消/搜索；
2. 输入框用宿主自己的编辑框类（按 `onCreateInputConnection` 的返回值定位）；
3. 通过宿主自己的内部焦点切换让 IME 的按键进到这个 EditText：

```text
com.oplus.keyboard.input.manager.h;->l(Landroid/widget/EditText;Z)V
  → switchInternalFocus(EditText, boolean)
     → 设置 h.f = 该 EditText、请求焦点、setCursorVisible(true)
     → 宿主自己的常用语编辑框走的就是这条链
```

宿主的按键事件天然进入 `h.f` 指向的 EditText，所以字就能打进去。

4. 键盘本来就是输入法窗口的一部分，它一直在下面，卡片不会把它盖住。

## 四、定位方式（DexKit / 结构匹配，未写死任何混淆类名）

| 目标 | 判据 |
|---|---|
| 宿主 EditText 类 | 有 `onCreateInputConnection(EditorInfo) → InputConnection` 且可赋值给 `EditText` |
| 内部焦点切换 | 静态 + `(android.widget.EditText, boolean)` + 返回 void |
| 计数控件 | 资源名 `tv_clip_count` |
| 按钮父容器 | 计数控件的 parent（运行时取），**不是**面板本身 |

### 为什么按钮父容器必须是「计数控件的 parent」

从宿主 dex 读类头：

```text
.class public final Lcom/oplus/keyboard/input/view/body/D;    ← 剪贴板面板类
.super Lcom/oplus/keyboard/base/widget/g;

.class public abstract Lcom/oplus/keyboard/base/widget/g;
.super Landroid/widget/RelativeLayout;                        ← 面板自己是 RelativeLayout
```

面板对象是 **RelativeLayout**，不解析 `topToTop` / `endToEnd` 这些约束锚点。把带约束锚点的
按钮加到面板身上 → 锚点写进去也没人读 → 按钮落在 `(0,0)`，就是用户看到的「飘在左上角」。
正确容器是计数控件的 parent（真正的 `ConstraintLayout`）。

## 五、静态自校验（编译产物逐字符串核对）

```text
新逻辑(应>0)
  search card shown in ime window   -> 1
  clip-search: button created       -> 1
  anchor verify                     -> 1
  in-ime search card failed         -> 1

已移除的旧做法(应=0)
  attach dialog to ime window       -> 0
  host dialog shown                 -> 0
  dialog-builder: selected          -> 0
  setBlurBackgroundDrawable         -> 0

宿主混淆类名(应=0)
  input/view/body/D  -> 0
  input/view/body/A0 -> 0
  base/enums/BoxEnums-> 0
  input/adapter/Q    -> 0
  input/manager/h    -> 0

保留能力(应>0)
  panel transplanted / quote-pair / content-truncation / record-trim /
  clip_length / panel verify rows=   全部 > 0
```

## 六、真机核对行

```bash
logcat -b all -d | grep OplusImePanel
```

| 日志行 | 含义 |
|---|---|
| `clip-search: search card shown in ime window, input-registered=…` | 卡片已插进输入法窗口 |
| `anchor verify PASS` | 搜索按钮锚点写进去了 |
| `clip-search: button created … parent=androidx.constraintlayout.widget.ConstraintLayout` | 父容器正确 |

若 `input-registered=false`：内部焦点没接上，把该行发回。
