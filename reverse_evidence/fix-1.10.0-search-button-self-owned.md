# 1.10.0 · 搜索按钮改为「自建控件 + 显式锚点」

## 症状（用户真机截图）

1. 搜索按钮出现在**面板左上角**，压在返回箭头上方；
2. 计数行 `13/∞` 不再居中（被新按钮挤歪）；
3. 搜索按钮贴住下方列表第一项，视觉粘连；
4. 按钮文字初始是「搜索」，过一会儿变成 `1/∞`。

四个症状**同一个根因**。

## 根因（宿主侧事实）

`res/IB.xml` 里计数行本来是两端结构：

```
tv_clip_count (0x7f0905aa)  end → tv_phrase_count(0x7f0905dd).start   ← 左端计数
tv_phrase_count             end → parent 右端, top 与计数同行          ← 右端槽位
```

1.9.1 版把 `tv_phrase_count` 抢来当搜索按钮。它是宿主
「剪贴板页 / 常用语页」**共用**的一位：

- `body/D.j(IZ)` 会按当前页对该控件 `setText`（常用语计数）与 `setVisibility`；
  因此我们的「搜索」字样被宿主改写成 `1/∞`（症状 4）；
- 它一旦可见，`tv_clip_count` 的 `end` 锚点就被它占住，计数不再落在一行中央（症状 2）；
- 它自身 `top` 与计数同行、高度随内容变化，视觉上压住下方列表第一项（症状 3）。

而症状 1（左上角）来自更早的 1.9.0：**新建** View 直接加到面板根，
面板根是 `ConstraintLayout`，新 View 一条约束都没有 → 被摆到 (0,0)。

## 修法

不再抢宿主的任何控件，改为**本模块自建**，并把锚点写全：

| 属性 | 值 |
|---|---|
| `endToEnd` | `parent`（贴右端，与宿主右端槽位同位置） |
| `topToTop` / `bottomToBottom` | 计数控件 id（在计数行内垂直居中，不会向下延伸） |
| `startToStart` / `startToEnd` / `endToStart` / `leftToLeft` / `rightToRight` | `UNSET` |
| `topToBottom` / `bottomToTop` | `UNSET` |
| marginEnd | 12dp |

宿主那个槽位**保持隐藏、一个字不碰** → 计数控件的锚点链完好 → `13/∞` 恢复居中。

页判定：宿主用「计数控件是否可见」表达当前是否剪贴板页（`D.j(IZ)`），
本版据此决定按钮 `VISIBLE` / `GONE`，不依赖页号。

## 移除

`installSlotVisibilityGuard()`（挂在 `View.setVisibility` 上的槽位守卫）、
`rightSlotId` 参数与 `NAME_CLIP_RIGHT_SLOT` 常量、`bindCounter()`。

## 自校验（编译产物逐字节数，非源码）

| 检查 | 结果 |
|---|---|
| `button created id=` / `anchored to counter=` 在 dex | 各 1 |
| `slot taken over` / `slot visibility guard` / `right slot` | **0**（旧逻辑已清） |
| 宿主混淆类名（`body/D`、`body/A0`、`adapter/Q`、`base/enums/BoxEnums`） | **0** |
| 保留能力（`panel transplanted` / `quote-pair` / `content-truncation` / `record-trim` / `clip_length` / `panel verify rows=`） | 全在 |
| `aapt2 dump badging` | `versionCode 13 / versionName 1.10.0 / application-label:'小布输入法助手'` |
| META-INF 签名块 | 3（v1 SF/RSA/MF） |
