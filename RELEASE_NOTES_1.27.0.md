# 小布输入法助手 1.27.0

## 这一版在修什么

用户复测 1.26.0 后的原话是：**「点击搜索以后，整个上面的这个白条就被掉了，就自己就垮下来了，然后搜索结果我这边也看不到。」**

读完 1.26.0 的真机日志，断点有两个，而且互相解释。

### 一、点「搜索」的触摸根本没送进输入法

日志（2026-10-01 19:27、19:28）里这一对现象同时出现：

```text
19:27:22.090  clip-search: hideWindow blocked while bar shown
19:28:58.435  clip-search: hideWindow blocked while bar shown
```

但整场**一次都没有**出现搜索条上「搜索」按钮的触摸留证（`bar button '搜索' touch down`）。

原因在宿主自己的窗口计算里。宿主的输入法窗口是**整屏**的，但它在 `ImeService.onComputeInsets` 里把「可触摸区域」显式限定成下面那一块键盘；其余部分（窗口顶部）摸上去等于摸到了下面的应用。搜索输入条被放在窗口顶部（键盘上方），正好落在区域之外，于是：

- 这一摸不归输入法管；
- 系统把它当成"点了输入法外面"，下面应用一反应就要收输入法；
- 我们上一版的窗口守卫挡住了这次收起（所以界面不再整个垮掉），但**按钮本身的点击从头到尾没有发生**。

**修法**：输入条显示期间，把窗口的可触摸区域扩成整窗；输入条一移除立刻不再改写，宿主恢复原本的计算结果，因此不影响正常使用键盘。

这里还有一处顺序问题一并修掉了：宿主覆写的 `onComputeInsets` **第一句就是调用基类**。如果只挂框架基类，我们改完区域之后宿主紧接着就把自己的值覆盖回去，等于白改。所以改成用结构匹配找到**宿主那份覆写**（方法名 `onComputeInsets` + 参数是 `InputMethodService$Insets` + 声明类是 `InputMethodService` 的子类），在它执行**之后**修改。

### 二、过滤挂在了"不会发生的时刻"

同一份日志里，每次重绑之后跟着的都是：

```text
clip-search: row filter summary page=CLIPBOARD kw=ji hidden=0 shown=0
```

**一行都没被处理**，也就是说行级过滤从头到尾没有执行过。

原因是"重绑"这个动作本身不成立：`RecyclerView.setAdapter(同一个 adapter 实例)` 在框架里会直接返回，不会触发任何 `onBindViewHolder`；而且重绑时用的还是 `buttons` 表里缓存的**面板实例** —— 面板重建之后，缓存里那个已经脱离屏幕，"重绑"发生在没人看的列表上。

**修法**：过滤不再依赖"重绑一定会触发绑定"这个假设。新增一层**直接对屏幕上可见行**施加过滤：

- 从输入法窗口根视图（以及各面板实例）里**实时**找出屏幕上真正显示着的那个列表；
- 逐个子行取适配器位置、按关键字判定，不命中就收成 0 高度并隐藏，命中就还原；
- 分页数据是异步提交的，因此在 150 / 400 / 800 / 1400 毫秒各重复一次；
- 每一步都写日志：`live rows pass=N reason=… page=… kw=… hidden=… shown=… unreadable=… total=…`。

读不到条目时**保持可见**，绝不把整屏清空（这是 1.25.0 那次"列表看起来垮掉"的教训）。

## 一次误报的纠正

1.26.0 的日志里出现的 `hideWindow blocked while bar shown`，一度看起来像"搜索已经跑到确认这一步了"。实际不是：那是**点搜索条时触摸穿透出去**引发的收窗口，被守卫拦下顺手跑了收尾。真正的按钮点击一次都没发生。这一版把这条歧义也消掉了 —— 触摸区域修好之后，按钮能正常收到点击，`bar button '搜索' touch down` 与 `clip-search: confirm tapped` 才会成对出现。

## 本版产物

- 版本：`1.27.0`（versionCode 32）
- APK：`OplusImePanel-1.27.0-release.apk`
- SHA-256：`e5af3eb0cb66dbf623a2df0c709499069ee630b7f9247c3d48ffa3eac9ef7ada`
- 静态自校验：产物 dex 内宿主包名 `com/oplus/keyboard` 命中 **0**（不写死任何宿主混淆类名）；签名 `CERT.SF` / `CERT.RSA` 完整；五个新标记全部在产物里。

## 装上之后怎么验

打开剪贴板面板 → 点「搜索」→ 打一个**确定能匹配到条目**的关键字 → 点输入条右边的「搜索」。

要看的日志（按重要性）：

```text
clip-search: touchable region guard installed
clip-search: ime touchable region widened while bar shown       ← 触摸区域扩了（关键）
clip-search: bar button '搜索' touch down                       ← 按钮真的收到了点击（关键）
clip-search: confirm tapped kw='…' page=CLIPBOARD
clip-search: live rows pass=1 reason=reload … hidden=N shown=M  ← 过滤真的落到行上
```

`hidden` 与 `shown` 至少有一个不为 0，说明过滤落到了屏幕上的列表；再点一次面板上的「搜索」即取消搜索、恢复全部条目。

## 仍然保留的能力（不因本版改动回退）

- 成对符号不补全（提交后按运行时真实出口拦截，日志为 `quote-pair: dropped auto closing '…'`）；
- 符号键直接进完整符号页且不再闪退；
- 面板里按返回 = 回键盘主页面，而不是收起整个输入法；
- 常用语 500 字符限制已解除（用户实测通过）。
