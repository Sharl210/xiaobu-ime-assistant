# 小布输入法助手

一个自建的 LSPosed / Xposed 模块，用来给**小布输入法**（`com.oplus.keyboard`，OPPO/一加机型自带的百度定制输入法）补齐几处用起来不顺手的地方。

作用域只勾「小布输入法」一个应用；除下面列出的功能外，不改输入、词库、联想等任何其它行为。

---

## 功能

### 一、文本编辑面板重排

输入框上方弹出的「文本编辑」面板，按钮网格重排成（**即百度输入法的排版**）：

```
┌──────────────┬──────────────┐
│  全选／剪切   │      删除     │
├──────────────┼──────────────┤
│    复制      │      回车     │
├──────────────┼──────────────┤
│    粘贴      │     剪贴板    │
└──────────────┴──────────────┘
       左列            右列
```

- 每个按钮点下去干什么，仍然由输入法自己的控件决定，所以**文字和功能一定对得上**，不存在「换了字、点了没用」。
- 左列第一格是**双态格**：没选中文字时是「全选」；一旦选中了文字，自动变成「剪切」（字样、样式、震动、点击即剪切全部来自输入法自身）。
- 六个按钮宽度统一放大到 1.2 倍；左侧白色面板会自动让位，宽度在任何状态下都保持不变。
- 「剪切」点完回到键盘主界面；「粘贴」只有在**真的粘贴到了内容**时才回键盘，剪贴板为空时不会回。

### 二、剪贴板

- 面板底部「当前条数 / 上限」那一行，显示上限的地方变成 **∞**。
- 真正生效的 500 条上限也一并解除——输入法不会再在存满 500 条时把最旧的一条挤掉。
- 剪贴板页和常用语页各自拥有独立的「搜索」过滤逻辑，切页时不会串用关键词或列表。
- 两页计数行最右侧新增一个**白底圆角气泡「搜索」按钮**。
  输入关键字后点「搜索」，列表就只留下内容里包含该关键字的条目；再点一次按钮即取消搜索、恢复全部。

  > 搜索卡片为什么不做成"弹窗"：输入法进程本身就是输入源，任何叠在输入法窗口**之上**的窗口
  > （`PopupWindow` 或 `type=0x3eb` 的附加 Dialog）都会把输入法压在下面，键盘就再也弹不出来。
  > 因此这里把输入界面放进**输入法窗口内部**，并把输入框交给输入法自己的内部焦点机制
  > （`InputConnectManager.switchInternalFocus`），键盘敲的字才会进到输入框里。

### 三、常用语

- 单条内容的 **500 字输入上限**解除，可以一直往下写；字数统计照常显示，超限提示不再出现，保存照旧。
- 常用语条目的**数量上限**也一并解除，不再有「容量已满」。

### 四、引号

- 中文符号页、英文符号页和括号类符号，都不再**自动配对补全**：按一个就只上一个，光标停在它后面。

---

## 安装

1. 手机需要 **root + LSPosed**。
2. 安装 [`Release`](../../releases) 里最新的那个 APK。
3. 打开 LSPosed → 模块 → 启用「小布输入法助手」。
4. 作用域只勾选 **小布输入法**。
5. 重启输入法进程，或者直接重启手机。

> 卸载或停用模块即可完全恢复原状——本模块不改动输入法的任何文件，只在运行时介入。

---

## 使用后的自查（可选）

用 root 权限抓日志过滤即可，标签统一是 `OplusImePanel`：

```bash
logcat -b all -d | grep OplusImePanel
```

正常应当能看到这样几行：

```
resolved ids selectAll=… clip=… copy=… paste=… delete=… return=…
record-trim: wrappers=2 installed=2
content-length-filter: candidates=1 installed=1
clip-search: convert candidates=… filterHooks=…
panel transplanted: 剪切/全选共用左列第一格 删除<-全选格 回车<-删除格 剪贴板<-回车格
panel verify rows=全选+删除 | 复制+回车 | 粘贴+剪贴板 overlaps=none zeroSize=none verdict=PASS
clip-search: button created id=0x… class=… parent=androidx.constraintlayout.widget.ConstraintLayout anchored to counter=0x7f0905aa
```

几处关键行为对应的日志：

| 你做的事 | 日志里会出现 |
|---|---|
| 点「剪贴板」 | `clipboard opened via structural dispatcher` |
| 点「剪切」回主键盘 | `cut tapped -> back to keyboard` |
| 真的粘贴到内容后回主键盘 | `paste applied -> back to keyboard` |
| 粘贴时剪贴板是空的 | `paste tapped but nothing to paste` |
| 用搜索过滤 | `clip-search: page filtered …` |
| 点「搜索」 | `clip-search: search card shown in ime window, input-registered=…` |
| 常用语写超 500 字 | `over-limit input allowed (limit=500)` |
| 选中文字 | `selection cell switched: 全选->剪切 (宿主已启用剪切)` |

如果某行没出现，说明该功能在你这台机器上没挂上，可以把日志发到 [Issues](../../issues)。

---

## 它是怎么定位到这些地方的

这个输入法的类名、方法名都被混淆过（比如面板类本身叫 `A0`），所以模块**不写死任何混淆名**，全部靠 DexKit 在运行时按「结构特征」和「语义锚点」找目标：

| 要改的东西 | 靠什么找 |
|---|---|
| 面板的六个按钮 | 资源**名称**（`btn_select_all` / `btn_clip` / …），整数 id 每次启动都从当前 APK 重新解析 |
| 面板类 | 同一个 `onClick` 里同时出现五个按钮资源 id，并且调用系统的文本操作接口 |
| 面板的排布方法 | 方法内部带有 `selectAllButton` / `clipButton` / `leftContainerView` 这几个语义串 |
| 面板按钮的震动/音效 | 面板 `onClick` 的每个分支开头都在调同一个「静态、无参、返回 void」的方法 |
| 剪贴板面板 | 构造函数里用到计数控件 `tv_clip_count` 的资源 id |
| 剪贴板枚举 | 字符串常量 `BOX_CLIP`（枚举常量名是语义串，不是短名） |
| 打开剪贴板 | 参数形状为 `(枚举, boolean, String) → void` 的那一个方法 |
| 容量上限的裁剪 | 事务 lambda 的形状：`invoke(Object)Object` + 用到 500 + 调用 `Number.intValue()` |
| 正文长度上限 | 输入过滤器的标准签名 `filter(CharSequence,int,int,Spanned,int,int)` + 用到 500 |
| 分页列表 | 继承链上带分页包名、且方法形状是「List 进 List 出」 |

**一句话：资源名称和字符串常量当锚点，类名一律不参与判断。** 所以输入法小版本升级后，多数情况下不需要改模块。

---

## 自己编译

环境：Gradle 8.10.2 + AGP 8.6.1 + Kotlin 1.9.24，`compileSdk 34`。

```bash
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

- 签名用的是仓库里 `keystore/oplusime-panel.jks`（密码写在 `app/build.gradle` 的 `signingConfigs` 里）。想换成自己的，改那四行即可。
- `local.properties` 需要指向你的 Android SDK（`sdk.dir=...`）。
- 如果你在 aarch64 的机器上编译（比如手机里的 PRoot），Google 只发布了 x86_64 版 `aapt2`，需要在 `gradle.properties` 里加一行把 aapt2 指到你自己的包装器：
  ```properties
  android.aapt2FromMavenOverride=/path/to/aapt2-wrapper
  ```

---

## 目录

```
app/src/main/java/com/oplusime/panel/
  HookEntry.kt          入口：资源名解析、DexKit 查询、Hook 装配
  PanelArranger.kt      文本编辑面板：格子置换、双态格、按钮加宽、版式自检
  ClipboardOpener.kt    打开剪贴板面板：结构定位 + 版本兜底
  HostLimits.kt         解除容量上限：到顶裁剪、正文长度上限
  ClipSearch.kt         剪贴板搜索：气泡按钮 + 输入法窗口内搜索卡片 + 分页过滤
  HostTweaks.kt         计数显示改 ∞、剪切/粘贴条件返回键盘
  QuotePairSuppressor.kt 引号自动配对抑制
  MainActivity.kt       模块说明页
reverse_evidence/       每一步的取证与修复记录（含原始 smali 结论）
```

`reverse_evidence/` 里按版本记录了每一次改动「为什么这么改、依据是什么」，遇到问题可以直接翻。

---

## 边界

- 只针对「小布输入法」`com.oplus.keyboard`。别的输入法不生效，也不应该勾选别的应用。
- 只在运行时介入，不改动输入法的安装文件。
- 面板的宽高、边距仍然由输入法自己算，模块只改按钮之间的锚定关系，因此横竖屏、单手、悬浮等形态会跟着输入法自身的几何走。
- 上游输入法大版本升级、混淆策略变化时，某些定位可能失效；此时对应功能会安静地不生效（日志里会写明是哪一处没找到），不会让输入法崩溃。

## 免责声明

仅供个人在自己设备上改善输入体验使用。使用前请自行确认符合你所在地区的法律法规与设备厂商条款。因使用本模块产生的任何后果由使用者自行承担。

## 许可

AGPL-3.0（因为依赖的 DexKit 采用 AGPL-3.0）。
