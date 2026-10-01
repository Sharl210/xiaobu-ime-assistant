# 小布输入法助手 v1.12.0

本版修两个**静态即可证明**的根因，并把模块主页面的文案改成你要求的那种说明。

## 1. 搜索按钮又跑到左上角：这次的根因在「加错父容器」

上一版 1.11.0 改掉了「LayoutParams 用无参构造器」的错误，但按钮仍然落在左上角。
真正的原因有两个，第二个我这次才查出来：

- 前一个（已修）：`ConstraintLayout.LayoutParams` 在宿主里没有无参构造器，用反射 `getConstructor()`
  必然失败，退回普通 `ViewGroup.LayoutParams`，锚点字段根本不存在，写入全部静默失败。
- **这一个（本版）**：我一直在把按钮加到**面板对象本身**上。而宿主的面板类
  `input/view/body/D` 继承的是 `RelativeLayout`——一个 RelativeLayout **不会解析**
  `topToTop` / `endToEnd` 这类约束锚点。锚点写进去了也没人读，于是按钮落在 (0,0)。

修法：按钮加进**计数控件所在的那个容器**（也就是 `res/IB.xml` 的根，一个真正的
`ConstraintLayout`），锚点才有意义。日志里现在会打出父容器类名，可当场核对：

```
clip-search: button created id=0x… parent=androidx.constraintlayout.widget.ConstraintLayout anchored to counter=0x7f0905aa clipboardPage=true
clip-search: anchor verify PASS topToTop=…(want …) endToEnd=0(want 0) lp=androidx.constraintlayout.widget.d
```

同时按「计数控件是否可见」判断当前是不是剪贴板页，切到常用语页时按钮自动隐藏，
不会再在常用语页冒出来。

## 2. 搜索弹窗里打不了字：因为用的是 PopupWindow

你说了好几遍「必须是和添加常用语一样的弹窗」——这次查清楚了，宿主自己就是这么做的，
它的代码（`input/view/body/D;->q(String, Function0)`）是：

```
COUIAlertDialogBuilder(context).setTitle(…).setBlurBackgroundDrawable(true).create()
dialog.window.attributes.token = <输入法自己的 windowToken>     ← 关键一
dialog.window.attributes.type  = 0x3eb（附加对话框）            ← 关键二
dialog.window.addFlags(0x20002)  // 变暗 + FLAG_ALT_FOCUSABLE_IM ← 关键三
dialog.show()
```

**PopupWindow 只是挂在当前窗口上的浮层，没有自己的 window token，也不在输入法的窗口层级里，
所以永远收不到键盘输入。** 本版改成与宿主同族的 Dialog：用宿主自己的对话框构建器（按
`setBlurBackgroundDrawable(boolean)` 的方法形状结构化定位，不写死类名），把输入法窗口的 token
交给它、声明成附加对话框，并把输入框注册成宿主的「内部焦点」目标。输入框本身也换成宿主自己的
编辑框类（按 `onCreateInputConnection` 的产出者定位）。

原 PopupWindow 保留为兜底：宿主对话框类拿不到时会退回去并在日志里写明。

## 3. 主页面文案

「文本编辑面板」分组的第一条改成：

> 排版即百度输入法排版：左列 全选／复制／粘贴，右列 删除／回车／剪贴板

## 静态自校验

- 包身份：`com.oplusime.panel` / `versionCode 15` / `versionName 1.12.0`，标签「小布输入法助手」
- 新逻辑进包：`clip-search: button created`、`host dialog shown`、`dialog-builder: selected`、`anchor verify`
- 宿主混淆类名：`input/view/body/D`、`input/view/body/A0`、`base/enums/BoxEnums`、
  `input/adapter/Q`、`input/manager/h` **命中数全部为 0**
- 结构锚点名（`COUIAlertDialogBuilder`、`CustomEditText`）同样为 0 —— 都是运行时按形状解析的
- 既有能力全部保留：版式置换、引号抑制、字数上限、条目裁剪、`clip_length`、版式自检

## 仍未完成（如实列出，不混进"已完成"）

- 剪贴板页 / 常用语页**两套搜索逻辑**：目前按钮在常用语页会隐藏，但过滤只作用于剪贴板。
- 常用语 500 字上限、引号成对、剪贴板条目上限：代码已在包内，最终判定在你的设备上。
