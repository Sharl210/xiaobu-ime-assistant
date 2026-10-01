# 小布输入法助手 1.21.1

本版是**崩溃修复版**。1.21.0 点「符号」会直接闪退，根因已定位并移除。

## 崩溃根因（设备真实崩溃栈，不是推断）

```
kotlin.UninitializedPropertyAccessException: lateinit property t9PinYin has not been initialized
	at com.oplus.keyboard.input.manager.l.o(SourceFile:2158)
	at com.oplus.keyboard.input.manager.l.k0(SourceFile:695)
	at com.oplus.keyboard.input.manager.l.h0(SourceFile:220)
	at com.oplus.keyboard.input.event.i.x(SourceFile:1233)
	at com.oplus.keyboard.input.view.body.s.onTouchEvent(SourceFile:2406)
```

1.21.0 做过一件事：在符号键盘切换方法执行**之前**，把参数里的键盘类型从
`QWERTY_*_SYMBOL`（简洁符号页）替换成通用符号页类型 `SYMBOLS`。

结果就是上面这行：宿主 `k0` 拿着 `SYMBOLS` 继续往下走，进到一条需要 T9 键盘实例的分支，
而那个 `t9PinYin` 从未初始化，于是抛异常，点「符号」当场闪退。

**结论：宿主不接受从外部把符号键盘替换成通用符号页类型。这条实现已整体删除**（连同上一版
那个「把档位字段 SYMBOL1 抬成 SYMBOL2」的做法一起删掉——1.20.0 真机日志证明它执行成功
但界面不变，属于无效改动，而且会让档位与实际页面不一致，影响返回箭头）。

本版之后，符号键的行为**完全交还宿主**：不闪退，返回箭头恢复原有表现。

## 搜索输入条：改成"长在输入法窗口内部"

1.16~1.21 都试过叠加窗口（附加 Dialog）。1.21.0 真机日志已经证明**问题不在窗口标志**：

```
clip-search: host dialog shown, input-registered=true windowType=1003 flags=0x1800002 imeOptions=3 inputType=1
```

`0x1800002` 里**没有** `FLAG_ALT_FOCUSABLE_IM(0x20000)`（就是那个"别让输入法弹出来"的标志），
键盘依然不出现。

原因是机制层面的：输入法进程自己就是输入源，任何叠在输入法窗口之上、**可获得焦点**的窗口，
都会让系统认为「当前焦点窗口不是输入法的输入目标」，于是把输入法窗口收下去。

本版改成宿主自己的做法——把输入条做成**输入法窗口内部的普通 View**：

1. 点「搜索」先把面板收起（`res/IB.xml` 根是 `match_parent`，面板会占满整个键盘区域，键盘在里面没有位置）；
2. 把输入条加到**输入法窗口根视图（`anchor.rootView`，即 IME 窗口自己的 DecorView）**顶部——
   键盘仍在下方可见可用；
3. 输入框交给宿主的内部焦点切换（`input/manager/h;->l(editText, true)`），
   与宿主自己的「编辑常用语」`input/view/head/O;->o(CustomEditText)`、
   「搜索框」`input/view/head/h0;->d()` 走的是同一条（后者的 `d()` 就是
   `setImeOptions(3)` + `setOnEditorActionListener` + `h.l(editText, true)`）；
4. 确认 → 应用关键字、移除输入条、重新打开面板看过滤结果；取消 → 移除输入条、重新打开面板。

拿不到根视图时**不硬来**：记一行日志并退回旧的弹窗实现，不会静默失效。

## 仍未修好的两件（如实说明）

- **符号成对上屏**：1.21.0 在提交入口加了「源头拦截 + 事后删除 + 复读验证」，并且加了一行
  证据日志 `quote-pair: commit '…' len=1|2 via=…`。但复测反馈仍是成对，所以本轮**没有改这一块**
  —— 需要先拿到那一行证据，才能判断配对是发生在提交之后（两行、`len=1`）、键位表里（一行、`len=2`）
  还是完全在 native 引擎里（一行都没有）。**不再盲改。**
- **符号键直达完整符号页**：改档位、改参数两条路都已被真机否掉（前者无效，后者闪退）。
  下一轮需要的是宿主那个「更多」按钮的真实点击回调（可以用 MT 的点击监听点一次给我），
  按它的真实实现来，不再猜。

## 产物

| 项 | 值 |
| --- | --- |
| 版本 | 1.21.1（versionCode 26） |
| APK | `OplusImePanel-1.21.1-release.apk` |
| SHA-256 | 见 Release 附件说明 |

## 复测要看的三行

```text
symbol-page: switch observed l#k0 args=5 current=... target=...   ← 只观察，不再改行为（应正常出现）
clip-search: search bar shown in ime window root=... input-registered=true imeOptions=3
clip-search: search bar removed
```
