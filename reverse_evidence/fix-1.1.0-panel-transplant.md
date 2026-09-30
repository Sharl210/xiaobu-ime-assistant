# 1.1.0 修复记录：面板重排从「重算约束」改为「格子置换」

## 一、1.0.0 的真机失败与根因（已验证到代码行）

用户反馈：装上 1.0.0 后，右侧操作菜单没有变成六格目标版式，而是只剩顶部一行两个键——
左「粘贴」、右「剪贴板」，下面两行空白。

代码级根因（两条互相独立、但结论一致）：

1. **读锚点时读错了对象。** `PanelArranger` 用
   `Reflect.readInt(selectAll, "topToTop")` 取「全选」当前的纵向锚点，而
   `Reflect.readInt` 内部是 `field(target.javaClass, name)`。
   传进去的 `selectAll` 是一个 **View**（`COUITextView`），而 `topToTop` 是
   **LayoutParams** 的字段，因此取值恒为 `null`，退化写成 `UNSET(-1)`。
   写锚点走的是 `lp` 对象，字段找得到，所以**横向锚点全部生效、纵向锚点全部被写成"无约束"**。

2. 后果与现象逐条对应：
   - 六个控件纵向都无约束 → 全部坍到面板顶部同一行；
   - 横向约束有效 → 左右两列的 x 坐标不变（与描述一致）；
   - 同格叠在一起时露出绘制顺序最后的那个：左列最后是**粘贴**，右列最后是**新建的剪贴板**
     → 显现为「左 粘贴 / 右 剪贴板」，第二三行空白；
   - 被压住的 全选／复制／删除／回车 不可见 → 「灰色按钮和背景全部消失」。
   颜色也吻合：粘贴是可用态（灰底黑字），新按钮克隆自粘贴（同为灰底黑字）。

补充证据：`A0.setLayoutParams(params.d)` 全 APK 只有 `z0.i(IIIFF)` 一个调用点，
说明该钩子确实会在设备上触发，不是"钩子没生效"。

## 二、1.1.0 的做法：不再自己解释任何锚点

目标版式与现版式的差别恰好是"每个格子交给谁"：

| 现版式 | 目标版式（占用者） |
|---|---|
| 左列第一格 剪切 | 全选 |
| 右列第一格 全选 | 删除 |
| 右列第二格 删除 | 回车 |
| 右列第三格 回车 | 剪贴板（新增） |
| 左列第二格 复制 | 复制（不动） |
| 左列第三格 粘贴 | 粘贴（不动） |

所以实现改为**格子置换**：

1. 一次性快照六个格子各自 LayoutParams 的全部 int 字段（锚点、边距、宽高、偏置）；
2. 按上表把"格子的排版属性"整体交给新的占用者；
3. 对快照里出现的被置换控件 id 做**同构重映射**（全选→删除、删除→回车、回车→剪贴板、剪切→全选），
   其余 id（容器、parent、UNSET）原样保留；
4. 新「剪贴板」按钮克隆「粘贴」的外观，点击调用宿主自己的面板分发器。

置换关系直接来自 `res/bL.xml` 已核实的锚点关系：

```
全选(0x7f0900eb)     end=parent          top=容器(0x7f090130)
剪切(0x7f0900d8)     end=全选            top=全选
删除(0x7f09034c)     end=全选            top=全选(bottom)
复制(0x7f0900dd)     start/end=剪切      top=删除
回车(0x7f09035a)     end=全选            bottom=容器
粘贴(0x7f0900e6)     start/end=复制      top=回车
```

### 2.1 连"字段名"也不依赖

本版宿主把 `ConstraintLayout$LayoutParams` 混淆成了 `androidx.constraintlayout.widget.d`
（`topToTop` / `endToStart` 等字段名保留，类名被改）。为了不在别的版本上翻车，
"哪个字段承载锚点 id"不再按字段名判断，而按**取值**判断：

> 只有被置换控件的 id 才会命中重映射表；尺寸、边距、偏置都是小整数，不会误伤。

因此 `PanelArranger` 里已不存在任何锚点字段名清单。

## 三、静态验证（本机可做的全部）

- clean release 构建通过；`apksigner` 验签通过（v1/v2/v3，证书 `8ba6e538…`）；
- dex 内关键字符串核对：`panel transplanted` / `panel verify rows=` / `verdict=` /
  `clipboard button created` / `remapped=` / `BOX_CLIP` 均在包内；
- 反向核对：`ConstraintLayout$LayoutParams`、`com.oplus.keyboard.input.view.body.A0`、
  `com.oplus.keyboard.base.enums.BoxEnums` 这些**宿主类名**在模块 dex 中出现 0 次
  （剪贴板兜底路径除外，它是逐步校验的版本绑定路径）。

## 四、真机验收清单（本机无 LSPosed 设备，必须由用户在设备上完成）

1. 先卸载 1.0.0 再装 1.1.0（或直接覆盖，两者同签名）；LSPosed 作用域勾选 **小布输入法**；
2. 重启输入法进程（或在 LSPosed 里"重新启动作用域"）；
3. 打开 工具箱 → 文本编辑；
4. root 侧取日志：

```bash
logcat -b all -d | grep -E "OplusImePanel"
```

期望看到的关键行：

```
resolved ids selectAll=2131296491 clip=... copy=... paste=... delete=... return=...   ← 非 0
panel-onclick selected=<A0 等混淆名> reason=resource-id+framework-call
panel layout method hooked: <类>#setLayoutParams
panel transplanted: 全选<-剪切格 删除<-全选格 回车<-删除格 剪贴板<-回车格
install 全选 fields=.. remapped=[endToStart:7f0900eb->7f0900d8, topToTop:...]
install 删除 fields=.. remapped=[endToEnd:7f0900eb->7f09034c, topToBottom:...]
install 剪贴板 fields=.. remapped=[endToEnd:..., topToTop:7f09035a-><新id>]
panel verify rows=全选+删除 | 复制+回车 | 粘贴+剪贴板 overlaps=none verdict=PASS   ← 关键行
```

- `verdict=PASS`：版式已是目标版式（三行、每行两个、无重叠）。
- 点各键应分别看到：全选→文本全选；复制→复制成功提示；粘贴→粘贴；删除→退格连删；
  回车→换行；剪贴板→`clipboard opened via structural dispatcher` 并弹出剪贴板面板。
- 若 `verdict=FAIL`：日志把实际分组与重叠对写出来了，直接回传即可定位。
- 若 `panel verify skipped cells=N/6`：说明某个控件没找到，也会写进日志。
