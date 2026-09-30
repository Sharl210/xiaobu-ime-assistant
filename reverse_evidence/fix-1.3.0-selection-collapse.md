# 1.3.0 修复记录：选中文本后整片错乱（左列塌陷）

版本：`OplusImePanel-1.3.0-release.apk`（versionCode 4，同证书可覆盖安装）
上一版：1.2.0（默认版式正确，选中文本后右侧按钮组崩坏）

---

## 1. 现象（用户真机反馈 + 截图描述）

默认状态（未选中文本）版式正确：左列 全选／复制／粘贴，右列 删除／回车／剪贴板。

一旦选中文本（左列第一格应变成「剪切」），整片崩坏：

- 右列第二行：原本独立的「复制」与回车图标被并进同一个宽按钮里，文字只剩“复”；
- 右列第三行：「粘贴」与「剪贴板」粘成一条，文字直接拼成“粘贴剪贴板”；
- 左面板（含方向键那一块）的右边缘看起来铺进了按钮区。

---

## 2. 根因：把「全选」置为 GONE，导致锚定在它上面的左列格子一起塌成 0 宽

宿主原始布局（`res/bL.xml` = `lib_input_text_editing_view`）里的锚点依赖链：

```
btn_clip   (剪切)  End_toStartOf btn_select_all | Top_toTopOf btn_select_all
btn_copy   (复制)  End_toEndOf btn_clip | Start_toStartOf btn_clip | Top_toTopOf ll_delete
btn_paste  (粘贴)  End_toEndOf btn_copy | Start_toStartOf btn_copy | Top_toTopOf ll_return
btn_select_all     End_toEndOf parent    | Top_toTopOf 左面板容器
ll_delete          End_toEndOf btn_select_all | Top_toBottomOf btn_select_all | Bottom_toTopOf ll_return
ll_return          End_toEndOf btn_select_all | Bottom_toBottomOf 左面板容器
左面板容器          End_toStartOf btn_clip（剪切）
```

关键点有两条：

1. **左列第二格的水平锚点是「剪切」（`btn_copy` 的 end/start 指向 `btn_clip`），
   左列第三格的水平锚点是「复制」。**
2. **左面板容器的右边缘锚定「剪切」的 start。**

1.2.0 的置换把 `btn_copy` 的水平锚点从「剪切」改成了「全选」（重映射 `clip.id → selectAll.id`），
而双态切换又把「全选」设成 `GONE`。ConstraintLayout 把 `GONE` 控件按 0 尺寸处理，于是：

```
全选 GONE(0 宽) → 复制 end/start 都指向全选 → 复制宽度 = 0
               → 粘贴 end/start 都指向复制 → 粘贴宽度 = 0
```

结果正是用户看到的：复制只剩半截字、与回车图标并排像同一个按钮；粘贴与剪贴板紧贴成一条；
左列原本占位的位置空出来，视觉上像是左面板铺了过去。

**另一个隐患**：`selectAll` 自身在 1.2.0 里的锚点含“指向自己”的重映射结果（`end_toEndOf 全选` 类自引用），
布局解析依赖宿主以固定 dp 宽度兜底才没更早暴露。

---

## 3. 修法：只在右列整列下移，左列第二、三格的水平锚点一律不动

新的置换表（每格只搬运“排版属性”，模块不解释也不重算锚点语义）：

| 目标格子 | 新占用者 | 排版属性来源 | 锚点重映射 |
|---|---|---|---|
| 左列第一格 | 剪切（原地保留） | 剪切原属性 | `全选 → 删除`（原锚点是「全选」，同格会自引用） |
| 左列第一格（另一态） | 全选 | 同一份（剪切原属性） | `全选 → 删除` |
| 右列第一格 | 删除 | 全选原属性 | 无（原锚点是 parent 与左面板容器） |
| 右列第二格 | 回车 | 删除原属性 | `全选 → 删除`、`回车 → 剪贴板` |
| 右列第三格 | 剪贴板（新建） | 回车原属性 | `全选 → 删除` |
| 左列第二格 | 复制（原位） | 复制原属性 | **仅** `删除 → 回车`（水平锚点保持锚定「剪切」） |
| 左列第三格 | 粘贴（原位） | 粘贴原属性 | **仅** `回车 → 剪贴板`（水平锚点保持锚定「复制」） |

重映射后不存在环：`剪切→删除`、`全选→删除`、`复制→剪切/回车`、`粘贴→复制/剪贴板`、
`删除→parent/左面板`、`回车→删除/剪贴板`、`剪贴板→删除/左面板`。

配套的两条硬约束（写进代码注释，避免以后回退）：

1. **左列第一格的两位占用者永远保持实际尺寸**，切换只用 `INVISIBLE`，绝不用 `GONE`，
   也绝不把宽高改成 0。这样锚定在它们身上的左列格子与左面板右边缘在任何状态下都不变尺寸——
   面板宽度不再铺进按钮区。
2. **切换时不再改写任何 LayoutParams**：两位占用者的排版属性在置换时已对齐到同一格，
   之后只切可见性。

---

## 4. 自检日志（新增，专治“编译通过但版式错”）

```
panel verify box 全选 l=.. t=.. r=.. b=.. w=.. h=..
panel verify anchors 剪切=endToStart=删除,topToTop=删除
panel verify anchors 全选=endToStart=删除,topToTop=删除
panel verify anchors 复制=startToStart=剪切,endToEnd=剪切,topToTop=回车
panel verify anchors 粘贴=startToStart=复制,endToEnd=复制,topToTop=剪贴板
panel verify rows=全选+删除 | 复制+回车 | 粘贴+剪贴板 overlaps=none zeroSize=none leftTop=全选 clipEnabled=false verdict=PASS
```

- `panel verify anchors ...` 打印六个格子实际的锚点关系（按取值翻译成按钮名），
  版式一错即可从这一组行直接读出是哪条锚点不对；
- `zeroSize=` 列出塌成 0 尺寸的格子——本次故障若当时有这一行会立刻暴露；
- 选中文本后再看一次，`rows` 的第一组会变成 `剪切+删除`、`leftTop=剪切`、`clipEnabled=true`。

---

## 5. 本版未变的既有行为

- 「剪贴板」按钮的震动：点击先调宿主自己的按键反馈链（`M.f()` → 音效 + 振动），
  拿不到时退回框架级 `performHapticFeedback(VIRTUAL_KEY)`；日志 `key-feedback selected=...`。
- 「全选／剪切」双态信号：取自宿主自己的 `clip.setEnabled(...)`（选择状态观察者写入），
  不自行判断是否有选中文本。
- 位移、字号、边距仍由宿主每轮重算，模块只在 `after` 钩子里维持锚点与列间距。

---

## 6. 真机核对清单

1. 安装 `OplusImePanel-1.3.0-release.apk`（与 1.2.0 同证书，可直接覆盖），LSPosed 作用域勾「小布输入法」，重启输入法进程；
2. 打开 工具箱 → 文本编辑；
3. `logcat -b all -d | grep OplusImePanel`，确认：
   - `panel transplanted: 剪切/全选共用左列第一格 ...`
   - `install 复制 ... remapped=[topToTop:删除->回车]`（**水平锚点不应出现在 remapped 里**）
   - `panel verify rows=全选+删除 | 复制+回车 | 粘贴+剪贴板 ... verdict=PASS`
4. 选中一段文字后再看一次日志：`rows` 首组应为 `剪切+删除`、`leftTop=剪切`、`clipEnabled=true`，
   且 `zeroSize=none`、`overlaps=none`、左面板宽度不变；
5. 逐个点击：全选／剪切／复制／粘贴／删除／回车／剪贴板，确认文字与行为一一对应且有震动。

> 本版只做到：宿主锚点依赖链有静态取证（`res/bL.xml` 原文）、修复按该依赖链实现、构建与静态自校验通过。
> **真机行为仍是唯一验收标准**；若仍异常，请把 `panel verify` 那一组行原样回传。
