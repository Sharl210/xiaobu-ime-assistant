# 小布输入法助手 1.23.0

本版针对 1.22.0 真机复测暴露的两件事定点修复：**符号键连点偶尔落回简洁页**，以及
**搜索框点「搜索」后整个输入法界面被关掉、输入框残留在屏幕上**。另外按新要求补上
**「面板里按返回 = 回键盘主页面」**。

## 1.22.0 的真机日志说明了什么（`/data/adb/lspd/log`，18:19 那一段）

**成对符号这条已经成立**——这是第一个有真凭据的通过项：

```text
18:19:38.266  quote-pair: commit '“ (U+201C)' len=1 via=RemoteInputConnection.commitText
18:19:38.267  quote-pair: commit '” (U+201D)' len=1 via=RemoteInputConnection.commitText
18:19:38.267  quote-pair: dropped auto closing '” (U+201D)'
```

先落左引号、再落右引号（两次 `len=1`），第二次被源头拦掉。也就是说配对发生在**提交之后**，
由模块的自动补全拦截负责拆——这条链现在挂对了。

同一份日志也暴露了两个新断点：

```text
symbol-page: follow-up 'more' invoked h0(true) (total=1..20)   ← 补调「更多」被执行了 20 次
```

**符号页**：补调本身有效（页面确实进了完整页），但命令式地"每次 k0 之后补一次"在连点时会重复触发，
而"是否要补"依赖一个跨调用的布尔标记 —— 连点时 before/after 会跨调用交错，
于是出现"后一次 before 置位、前一次 after 消费掉"的竞态，表现就是用户看到的**连点有概率落回简洁页**。

**搜索**：日志里只有"输入条已挂上"，**没有**任何"输入条已移除 / 面板已重开"的记录：

```text
18:20:04.650  clip-search: search bar shown in ime window root=com.android.internal.policy.DecorView input-registered=true
18:20:04.902  clip-search: search bar input re-register=true
（此后再无 clip-search 记录）
```

说明点「搜索」那一下**没有走到模块的处理逻辑**，输入法自己把窗口收了下去，而输入条因为没人摘除留在了屏幕上。
根因是：输入条排在输入法窗口根视图上时，宿主把「内部输入目标」指向了它；
**先摘视图、不解除指向**，宿主就会对着一个已脱离视图树的输入框继续处理焦点，输入法随后收窗口。

## 本版改了什么

### 1. 符号键连点稳定（不再靠跨调用的布尔标记）

- 判定改成在**切换执行之后**按**当时实际档位**实时判断：只有"这一步之后真的停在简洁符号页"才补一次「更多」；
- 中间套几层调用、几个线程交错都不影响判定；
- 并增加 180ms 后的**复核补调**：若那时档位仍是简洁页，再补一次（等价再按一次「更多」）。
- 新增证据行：

```text
symbol-page: follow-up 'more' invoked h0(true) (total=N)
symbol-page: follow-up 'more' retried h0(true) (total=N)
```

### 2. 搜索确认不再把输入法关掉、也不再残留

- 搜索条改由**模块级引用**持有（`activeBar`），并在视图上打标记；任何路径都能把它清掉，
  另外每次开搜索前会**扫掉根视图里所有带标记的孤儿输入条**（防残留）；
- 确认路径按证据重排顺序：**先解除宿主的内部输入目标 → 再摘输入条 → 再重开面板**；
- 新增**边打字边过滤**（300ms 防抖）作为兜底：即使"确认"这一下因为任何原因没送到，关键字也已经生效，
  不会再出现"点了等于没点"；
- 键盘上的「搜索/回车」键同样当作确认（内部焦点链下这条最稳，不依赖触摸投递）；
- 新增证据行：

```text
clip-search: confirm tapped kw='...' page=...
clip-search: live filter applied kw='...'
clip-search: search bar removed
clip-search: stale bars removed=N
clip-search: panel reopened=true box=BOX_CLIP
input-target-clear: host internal edit target released
```

### 3. 新增：面板里按返回 = 回键盘主页面

文本编辑面板、剪贴板面板、常用语面板显示期间，**系统返回键被接管**：
先走宿主自己的「收起面板」链，再把键盘恢复成主键盘页；只在"面板确实显示着"时生效，
因此不影响正常收起键盘。新增证据行：

```text
panel-back: back consumed while panel shown (total=N) -> main keyboard
panel-back: candidates=N hooks=N
```

## 产物

| 项 | 值 |
| --- | --- |
| 版本 | 1.23.0（versionCode 28） |
| APK | `OplusImePanel-1.23.0-release.apk` |
| SHA-256 | `6888f07acc299688bfec323eba149d806c1a285da471b0ac9a4904331ff14db9` |

## 验证状态（如实说明）

- **已通过（真机日志证据）**：成对符号只上屏单字符（`dropped auto closing` 实际命中）。
- **本版待真机复测**：符号键连点是否稳定进完整符号页；搜索确认是否真正生效且不残留；
  面板返回是否回主键盘页。
- 本版未声称上述三项已完成，需按下面四行核对后再定。

```text
symbol-page: follow-up 'more' invoked h0(true) (total=N)   ← 是否仍稳定补调
quote-pair: dropped auto closing '…'                        ← 成对符号持续被拦
clip-search: confirm tapped kw='…'                          ← 确认这一下是否终于走到模块
panel-back: back consumed while panel shown (total=N)        ← 返回是否被接管
```
