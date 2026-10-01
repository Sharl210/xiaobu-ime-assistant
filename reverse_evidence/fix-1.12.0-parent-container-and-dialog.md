# 1.12.0 取证记录：按钮父容器 + 搜索弹窗

## 一、按钮为什么还在左上角

这一版之前我改过「LayoutParams 用哪个构造器」，但没用——因为**锚点根本没人读**。

宿主侧事实（MT MCP 读类头）：

```
.class public final Lcom/oplus/keyboard/input/view/body/D;
.super Lcom/oplus/keyboard/base/widget/g;

.class public abstract Lcom/oplus/keyboard/base/widget/g;
.super Landroid/widget/RelativeLayout;
```

也就是说面板对象自己是一个 **RelativeLayout**。把带 `ConstraintLayout.LayoutParams`
（锚点字段 `topToTop` / `endToEnd`…）的按钮 `addView` 到面板上时，RelativeLayout 不会解析这些字段，
按钮就落在 (0,0)。**正确容器 = 计数控件 `tv_clip_count` 的 parent**，即 `res/IB.xml` 的根，
一个真正的 `androidx.constraintlayout.widget.ConstraintLayout`。

日志已加 `parent=<类名>` 字段，并在 `place()` 里回读锚点值做 PASS/FAIL 判定。

## 二、搜索弹窗为什么打不了字

宿主自己的输入弹窗 `input/view/body/D;->q(Ljava/lang/String;Lkotlin/jvm/functions/a;)V` 原文：

```text
new-instance v0, Lcom/coui/appcompat/dialog/COUIAlertDialogBuilder;
invoke-direct {v0, v1}, ...COUIAlertDialogBuilder;-><init>(Landroid/content/Context;)V
invoke-virtual {v0, p1}, ...->setTitle(Ljava/lang/CharSequence;)...
invoke-virtual {v0, p1}, Landroidx/appcompat/app/n;->setCancelable(Z)...
... setNeutralButton(0x7f1301dc, ...) / setNegativeButton(0x7f130105, ...)
invoke-virtual {v0, p1}, ...->setBlurBackgroundDrawable(Z)...
invoke-virtual {v0}, ...->create()Landroidx/appcompat/app/o;
iget-object p2, p0, ...D;->P:Landroidx/appcompat/app/o;   (保存引用)
# 关键三步
invoke-virtual {p0}, Landroid/view/View;->getWindowToken()Landroid/os/IBinder;
iput-object p2, v3, Landroid/view/WindowManager$LayoutParams;->token:Landroid/os/IBinder;
const/16 p2, 0x3eb      # TYPE_APPLICATION_ATTACHED_DIALOG
iput p2, v3, Landroid/view/WindowManager$LayoutParams;->type:I
const p2, 0x20002       # FLAG_DIM_BEHIND | FLAG_ALT_FOCUSABLE_IM
invoke-virtual {v1, p2}, Landroid/view/Window;->addFlags(I)V
invoke-virtual {p1}, Landroid/app/Dialog;->show()V
```

结论：**输入法进程里的弹窗必须带 IME 自己的 window token、声明为附加对话框、
并加 FLAG_ALT_FOCUSABLE_IM，键盘输入才会进到弹窗里**。PopupWindow 没有这些，所以打不了字。

本版按同样的三要素构造（用宿主自己的对话框构建器，按
`setBlurBackgroundDrawable(boolean)` 的方法形状结构化定位，不写死类名）。

## 三、输入框的来源

宿主自己的编辑框类由「谁实现 `onCreateInputConnection`」定位。宿主侧候选里排在最前、
且继承自 `androidx.appcompat.widget.D` 的是 `com.oplus.keyboard.base.widget.CustomEditText`；
其「内部焦点」切换是 `input/manager/h;->l(Landroid/widget/EditText;Z)V`（静态、`(EditText,boolean)`、void），
两个都按签名/形状解析，不写死。

## 四、静态自校验（编译产物逐字符串）

- 新逻辑进包：`clip-search: button created` / `host dialog shown` / `dialog-builder: selected` / `anchor verify`
- 宿主混淆类名命中 **全 0**：`input/view/body/D`、`input/view/body/A0`、`base/enums/BoxEnums`、
  `input/adapter/Q`、`input/manager/h`
- 结构锚点名同样 0：`COUIAlertDialogBuilder`、`CustomEditText`
- 既有能力保留：`panel transplanted` / `quote-pair` / `content-truncation` / `record-trim` /
  `clip_length` / `panel verify rows=`

## 五、仍未做

- 剪贴板页 / 常用语页两套搜索逻辑（当前按钮在常用语页隐藏，过滤只作用于剪贴板）
- 常用语 500 字上限、引号成对：代码在包内，用户实测曾失败，本版未动
