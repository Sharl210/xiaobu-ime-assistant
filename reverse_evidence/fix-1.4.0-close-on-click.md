# 1.4.0 补正：点完即收（回到键盘页）

## 用户报告

面板弹出来以后，点「全选」「复制」「甚至粘贴」等按钮，**面板不会自己收回去**，
一直停在那一页，必须手动按返回才回到键盘。要求：这六个按键点完就回到键盘页。

## 宿主机制（取证）

宿主「关闭面板、回到键盘」只有一条链，被两处复用：

| 使用位置 | smali 位置 | 行为 |
|---|---|---|
| 面板自己的返回箭头（`backIv`，id `0x7f0902c5`） | `A0.onClick` 首个分支 | 关面板 |
| 面板的「粘贴」分支（`btn_paste`） | `A0.onClick` 末分支 | 粘贴后关面板 |

链的形状（两步，静态调用）：

```text
静态无参访问器()            -> 取得面板容器管理器（BaseContainerManager 实现，input.manager.d）
静态单参关闭方法(管理器)     -> a.g(manager)
```

`a.g(manager)` 全文只有 4 条指令：

```smali
.method public static g(Lcom/oplus/keyboard/base/manager/a;)V
    .registers 3
    iget-object v0, p0, Lcom/oplus/keyboard/base/manager/a;->d:Ljava/lang/Object;   # 当前面板类型
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Lcom/oplus/keyboard/base/manager/a;->f(Ljava/lang/Object;Z)Z   # hide(current, false)
    return-void
.end method
```

而 `a.f(type, false)` 的实际动作（节选，全文见宿主 smali）：

```text
e == type 且面板可见时：
  → iput-object 0 -> a.e            # 清掉“当前显示类型”
  → a.K(a.a)  隐藏容器 ViewGroup    # 面板容器
  → a.K(a.b)  隐藏另一个容器
  → 启动 BaseContainerManager$hide$1 协程（收尾/回键盘）
  → return true
否则 return false（幂等：已关过一次就不会重复动作）
```

结论：**这就是"收面板回键盘"**，且重复调用是安全的（第二次 `e == null`，直接返回 false）。

## 实现方式（不写死宿主名）

从已经结构定位到的 `panel-onclick` 的 `invokes` 里取出这两个方法，判据是形状而不是名字：

| 目标 | 判据 |
|---|---|
| 关闭方法 | 静态 + `void` + 单参 + 参数类型属于宿主包 |
| 管理器访问器 | 静态 + 无参 + 返回类型可赋给关闭方法的参数类型（子类关系用 `isAssignableFrom` 判定） |

本版实取结果（仅作证据，不作为契约）：`accessor=com/heytap/msp/ipc/client/f;->D()`，
`close=com/oplus/keyboard/base/manager/a;->g(...)`。

接线位置：

1. 入口在面板 `onClick` 上挂 `afterHookedMethod`（宿主动作此刻已执行完），读出被点击的
   `View.getId()` 交给 `PanelArranger.onHostButtonClick`。
2. `PanelArranger` 只对下列控件收面板：**左列第一格（全选/剪切）、复制、粘贴、回车**。
3. 「剪贴板」不收——它的动作就是切换到剪贴板面板，收了等于把刚打开的目标关掉。
4. 「删除」不经过 `onClick`（宿主用 `OnTouchListener` 做长按连删），单独在监听器外层读动作：
   **短按抬起且未进入长按**才收；长按连续删除既不打断、抬起时也不收。

## 自证日志

```
panel onClick hooked: <混淆类>#onClick
close-path candidate=<descriptor>
close-path selected accessor=<descriptor> close=<descriptor>
host button 全选 clicked -> request return to keyboard
panel closed -> back to keyboard
panel close skipped (host already closed or type mismatch)     ← 粘贴：宿主自己已经关过了
delete tapped -> request return to keyboard
delete long-press ended; panel stays open
```

## 风险与边界

- 关闭链**只用宿主自己的方法**；拿不到时日志出 `close-path unresolved; buttons will keep the
  panel open`，此时面板行为回退为宿主原生（点了不收），不会崩、也不会改坏宿主。
- 若宿主把「返回箭头能关面板」这个事实改掉（例如换成别的关闭路径），本解析需要重新推导。
- 「删除」的判定依赖框架字段 `View.mListenerInfo` / `View$ListenerInfo.mOnTouchListener`
  （Android 框架字段，不是宿主混淆名）。
