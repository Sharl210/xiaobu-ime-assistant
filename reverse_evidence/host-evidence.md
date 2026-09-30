# 宿主取证记录（小布输入法 1.7.38.17-os）

本文件记录「为什么这样改」的原始证据，供后续宿主升级时重新推导使用。
所有结论均来自对目标 APK 的静态读取，未做任何运行时假设。

```
TARGET_PACKAGE  = com.oplus.keyboard
TARGET_VERSION  = 1.7.38.17-os (versionCode 1517238)
TARGET_APK      = 小布输入法_1.7.38.17-os.apk (102948779 bytes)
DEX             = 3 个
CLASSES         = 20742
```

---

## 1. 面板归属判定

截图中「没有『剪贴板』三字」的那一张，其右列按钮为 剪切 / 全选 / 复制 / 删除 / 粘贴 / 回车。
判别方法：直接在目标 APK 的资源表里找这些文案。

| 资源名 | 值 | 资源 id |
|---|---|---|
| `text_editing_title` | 文本编辑 | `0x7f1305ad` |
| `text_editing_clip` | 剪切 | `0x7f1305a4` |
| `text_editing_copy` | 复制 | `0x7f1305a5` |
| `text_editing_paste` | 粘贴 | `0x7f1305a9` |
| `text_editing_select_all` | 全选 | `0x7f1305ac` |
| `text_editing_begin` | 起始 | `0x7f1305a3` |
| `text_editing_end` | 末尾 | `0x7f1305a7` |
| `text_editing_select` | 选择 | `0x7f1305ab` |
| `text_editing_up/down/left/right` | 上/下/左/右 | `0x7f1305af/…a6/…a8/…aa` |
| `clipboard` | 剪贴板 | `0x7f13013d` |

结论：**该面板就是小布输入法自带的「文本编辑」面板，不是系统面板、也不是其它模块。**

## 2. 布局与控件 id

- 布局资源：`layout/lib_input_text_editing_view` → 实际文件 `res/bL.xml`，根节点是 `<merge>`。
- 该布局由 `layout` 资源 id `0x7f0c017b` 引用，反查引用者得到唯一方法：
  `…input/view/body/A0;-><init>(Landroid/content/Context;)V`。

控件 id（全部由资源名称解析，不写死坐标）：

| 资源名 | id | 面板字段 | 含义 |
|---|---|---|---|
| `btn_select_all` | `0x7f0900eb` | `g` | 全选 |
| `btn_clip` | `0x7f0900d8` | `i` | 剪切 |
| `btn_copy` | `0x7f0900dd` | `h` | 复制 |
| `btn_paste` | `0x7f0900e6` | `x` | 粘贴 |
| `ll_delete` | `0x7f09034c` | `z` | 删除（⌫） |
| `ll_return` | `0x7f09035a` | `A` | 回车（↵） |
| `0x7f090130` | — | `y` | 左侧光标控制容器（列锚点） |
| `0x7f09013f` | — | `E` | 顶部标题栏 |

原始约束关系（`res/bL.xml` 摘要）：

```
全选     end→parent       top→0x7f090130
剪切     end→全选         top→全选
删除     end→全选         top→全选        bottom→回车
回车     end→全选         bottom→0x7f090130
复制     start/end→剪切   top→删除
粘贴     start/end→复制   top→回车
```

## 3. 六个按钮的真实点击行为（`A0.onClick`，逐条核对）

| 控件 | 分支条件 | 实际调用 |
|---|---|---|
| 全选 | id == `btn_select_all` | `performContextMenuAction(0x102001f)`（selectAll）；若 `input/utils/d.c` 含当前包名则改走 `setSelection(0, 0x7fffffff)` |
| 剪切 | id == `btn_clip` | `performContextMenuAction(0x1020020)`（cut） + Toast「文本已剪切」 |
| 复制 | id == `btn_copy` | `performContextMenuAction(0x1020021)`（copy）+ `A0.m()` + Toast |
| 粘贴 | id == `btn_paste` | `performContextMenuAction(0x1020022)`（paste） |
| 回车 | id == `ll_return` | `input/event/p.q0(0x42, 0)`（注入 KEYCODE_ENTER） |
| 删除 | **不在 onClick 中** | 由 `OnTouchListener` `input/view/body/x0`（构造参数 0）接管，支持长按连删 |

关键推论：**六个按钮的行为全部由「控件自身」决定**，与它被摆在网格的哪一格无关。
因此重排位置即可让点击行为与文字一一对应，无需重新接线。

## 4. 打开剪贴板的真实调用链

两条互相印证的代码路径：

### 路径 A：头部剪贴板按钮
`input/view/head/k0;->onClick` 中 `view.getId() == 4` 分支：

```
BoxEnums.e (剪贴板) → 埋点
input.view.c0.b()            → input.manager.d
d.q(internal)
  d.q(base.manager.g.a, new input.view.b0(1))
input.manager.l.A = true
```

### 路径 B：主视图的 BoxEnums 分发器
`input/view/M;->b(BoxEnums, boolean, String)` 是覆盖全部 BoxEnums 的 `packed-switch(ordinal())`。
其中 `ordinal == 5`（`BOX_CLIP`）分支原样代码为：

```
v1 = 1
c0.b()                                  → input.manager.d
new input.view.b0(1)
d.q(base.manager.g.a, <lambda>)
input.manager.l.A = true
```

→ **该分支完全不使用第二个（boolean）与第三个（String）参数**，因此调用时传
`(BOX_CLIP, false, null)` 即可，不存在参数语义歧义。

### 事件 → 面板 的归属证明
`input/manager/d.c(Object)`（面板工厂）中的类型判定：

```
instanceof base.manager.g → new input.view.body.D(context)     ← 剪贴板面板
instanceof base.manager.k → new input.toolbox.media.Y0
instanceof base.manager.e → new input.toolbox.e               （工具箱）
……
```

→ `base.manager.g` 即剪贴板面板事件，`input.view.body.D` 即剪贴板面板类。

## 5. 枚举语义名（可跨版本使用的锚点）

`BoxEnums` 的常量**名称**不是混淆短名，而是语义串，由 `<clinit>` 逐条写死：

```
a = BOX_KEY              i = BOX_EDITING
b = BOX_EMOJI            j = BOX_SEPARATE_KEYBOARD
c = BOX_VOICE            k = BOX_FEEDBACK
d = BOX_SUPER_ASSOCIATION
e = BOX_CLIP   ← 剪贴板（ordinal 5, id 4）
m = BOX_TOOLBAR          t = BOX_TRANSLATION
```

字段名 `e` 是混淆的，但 `BoxEnums.values()` 的 `Enum.name()` 恒为 `BOX_CLIP`，
因此本模块用「字符串 `BOX_CLIP` 命中枚举类 → 遍历 values() 取 name」定位，
既不依赖 `e` 这个短名，也不依赖 ordinal。

## 6. 面板尺寸/边距是运行时重算的（为什么必须挂 after 钩子）

`A0;->setLayoutParams(...)` 会在每次布局时重算六个按钮的宽高与边距，并按
`setPortLayout` / `setLandLayout` 分支处理横竖屏。它**只写尺寸与 margin，不改约束锚点**。

因此本模块：
- 在 `A0.<init>` 与 `A0.setLayoutParams(...)` 之后各应用一次重排；
- 锚点（`topToTop`）只采集一次；
- 列的 margin 每次从宿主「当前」LayoutParams 读取（右列读全选按钮，列间距读剪切按钮），
  从而跟随宿主自己的几何计算，不写死 dp 值。

## 7. 锚点策略（跨版本抗性）

| 定位目标 | 使用锚点 | 不使用 |
|---|---|---|
| 六个按钮 | 资源**名称** `btn_select_all` 等，id 每版重新解析 | 资源整数 id 常量 |
| 面板 onClick | 五个按钮 id 字面量 + `InputConnection.performContextMenuAction` 调用 | 混淆类名/方法名 |
| 面板排布方法 | 语义串 `selectAllButton` / `clipButton` / `leftContainerView` | 方法签名中的混淆参数类型 |
| 剪贴板枚举 | 语义串 `BOX_CLIP` | 字段短名 `e`、ordinal |
| 剪贴板分发器 | 参数形状 `(BoxEnums, boolean, String)` → void | 类名 `M`、方法名 `b` |

唯一使用本版类名的地方是「剪贴板打开」的**兜底路径**
（`input.view.c0` / `base.manager.g` / `input.view.b0` / `input.manager.i`），
仅在结构化主路径不可用时才尝试，且每一步都做存在性校验，日志中会明确打印
`VERSION_PINNED fallback`。
