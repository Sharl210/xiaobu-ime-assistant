# 小布输入法助手 1.24.0

本次修复两个"已经接近成功、最后一跳断掉"的问题：符号键连点偶尔落回简洁页、搜索确认为何看不到结果。

## 一、符号键：连点稳定进完整符号页

**现场证据**（1.23.0 真机日志，你快速连点那一段）：

```
18:45:29.916  follow-up 'more' invoked h0(true) (total=2)
18:45:30.386  switch observed current=SYMBOL1 target=QWERTY_PINYIN_SYMBOL   ← 进了简洁页，但没补
18:45:31.062  switch observed current=SYMBOL1 target=QWERTY_PINYIN_SYMBOL   ← 又没补
```

两次失败的间隔分别是 **470ms、354ms**，都落在 1.22.0 引入的 **500ms 节流**之内，被静默丢弃（节流返回时一个字都不写，所以现象是"偶尔掉页、日志看不出原因"）。

**改法**：

- 删除该节流；判定完全按**现场档位**（只有确实停在简洁页才补），因此重复触发是幂等的；
- 删除 180ms 延迟补调 —— 那个任务可能在用户已经离开符号页之后才触发，反而会把用户重新拉进简洁页；
- 改为**就地同步**补，最多两次（宿主若把档位写回再补一次，仍写回就停手，不进入循环）；
- 所有"不需要补"的情况都写一行 `follow-up not needed state=... target=...`，以后不再有静默跳过。

## 二、搜索：确认这一下为什么"没用"

**现场证据**（1.23.0 真机日志）：

```
18:46:02.666  clip-search: search bar shown in ime window … input-registered=true
18:46:04.143  quote-pair: commit '计算机' len=3 via=EditableInputConnection.commitText
18:46:04.446  clip-search: page=CLIPBOARD keyword=计算机      ← 关键字收到了
18:46:04.447  clip-search: refresh failed: com.oplus.keyboard.input.adapter.Q.refresh []
18:46:04.448  clip-search: live filter applied kw='计算机'
```

三条硬事实：

1. 打字与关键字**都正常**（`keyword=计算机`、`live filter applied`）；
2. **列表刷新失败** —— 宿主这一版根本没有无参 `refresh()`，所以过滤生效了但屏幕上看不到；
3. 整场日志里**从来没有 `confirm tapped`** —— "确认"这一步根本没走到模块。输入框带 `IME_ACTION_SEARCH`，键盘上因此出现「搜索」键，那一按由**宿主自己**接走并收起了键盘；于是界面关闭、输入条没人清理、结果没过滤 —— 正好是你描述的三个现象。

**改法**：

- 列表刷新改为**三条路依次尝试**并各自留证：`refresh()` → **用同一个 adapter 再 `setAdapter` 一次**（强制所有可见行重走绑定，行级过滤立即生效，不依赖任何混淆方法名）→ `notifyItemRangeChanged` 兜底；
- 新增**输入法窗口隐藏**钩子：只要输入条还显示着而窗口被收起（就是你按键盘「搜索」键那条路），立刻补齐"关键字生效 → 摘输入条 → 回面板看过滤结果"，不再留下屏幕孤儿条；
- 「确认」与「取消」收敛成同一条收尾路径（取消会清空关键字，恢复全部条目）；
- 输入条上的两个按钮加触摸留证（`bar button '搜索' touch down`），下次日志可以直接分辨"没点到"与"点到了但没跑完"；
- **不再调用"解除内部输入目标"** —— 那个调用会让宿主把键盘收下去，正是"一点搜索整个界面就关掉"的来源。

## 三、依然是同样的验证方式

装 1.24.0 后请做两件事：**快速连点符号键若干次**、**点搜索并打字后按键盘上的「搜索」键**。日志里我按这几行判定：

```
symbol-page: follow-up not needed state=… / follow-up 'more' invoked … attempt=N   ← 是否每次都有明确结果
clip-search: rebind(keyword) … setAdapter=true                                     ← 列表是否真的重绑
clip-search: ime window hidden while bar shown -> apply kw='…'                      ← 键盘收起这条路是否被接管
clip-search: finish(window-hidden) page=… kw=…                                      ← 是否收尾完成、没有残留
```

## 产物

- 版本：**1.24.0**（versionCode 29）
- APK：`OplusImePanel-1.24.0-release.apk`
- SHA-256：`028f88c2ccc84df506620e098a2149c7230b0eff50b03bdcc730532be42fa068`
- 静态自校验：产物 dex 内 `com/oplus/keyboard` 命中 **0**（不写死宿主混淆类名）；四个新标记均在产物内。

## 仍未通过

- R-16 搜索最终效果（本轮已修确认链与刷新，待真机确认结果可见）
- R-30 符号键稳定性（本轮已删除根因级节流，待连点复测）
- R-31 面板返回回主键盘（1.23.0 日志已有 3 次成功记录，待你确认体感）
- 已关闭：成对符号（1.23.0 日志中 `dropped auto closing` 持续稳定出现）
