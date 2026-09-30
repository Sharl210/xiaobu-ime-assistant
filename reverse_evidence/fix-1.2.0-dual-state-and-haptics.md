# 1.2.0 补正：左列第一格双态化 + 「剪贴板」按钮按键反馈

针对真机验收反馈的两项缺口。全部结论都来自对 `小布输入法_1.7.38.17-os.apk`（`com.oplus.keyboard`）的静态取证。

---

## 1. 问题一：「剪贴板」按钮没有震动

### 现象
其余五个按钮点击有震动/键音，新建的「剪贴板」按钮没有。

### 根因（实证）
宿主面板的 `onClick` **不是**在方法入口做反馈，而是在**每个 id 分支内部**先调一次同一个反馈方法：

```
:cond_41  (btn_select_all 分支)
    invoke-static {}, Lcom/oplus/keyboard/base/util/M;->f()V     ← 反馈在这里
    ...performContextMenuAction(0x102001f)
:cond_81  (btn_clip 分支)
    invoke-static {}, Lcom/oplus/keyboard/base/util/M;->f()V
    ...
```

而「剪贴板」是模块新建的控件，它的点击是模块自己的 `OnClickListener`，**不经过宿主 `onClick`**，所以反馈那一步被跳过了。

反馈链本身（已逐层读出）：

```
M.f()
  → M.e(KeyCode.y0)
       → M.i(keyCode.a())   // SoundPool.play()，日志标签 "SoundManager"
       → M.j()              // 读 Key_vibration_intensity → M.d(int) 触发振动
```

### 修法
不写死类名：从**已经结构定位到的** `panel-onclick` 方法上读 `invokes`，筛出「静态 + 无参 + 返回 void + 声明类不是面板自身类」的那一个——本版只有一个候选，就是 `M.f()`。

```kotlin
onClick.invokes.filter { it.paramCount == 0 && it.returnTypeName == "void"
        && Modifier.isStatic(it.modifiers) && it.className != onClick.className }
```

新建按钮的点击改为：先反射调用该反馈方法，再打开剪贴板。拿不到时退回框架级 `performHapticFeedback(VIRTUAL_KEY)`，并在日志里写明走了兜底。

---

## 2. 问题二：左列第一格需要「全选 ⇄ 剪切」双态

### 需求
有选中文本时该格变成「剪切」（剪切的文字、样式、震动、点击即剪切）；无选中时保持「全选」。

### 信号来源（实证）
宿主面板构造时启动了一个协程收集选择状态，其收集者 `y0.emit(Boolean)` 全文如下（节选，保留关键行）：

```
invoke-virtual {p2, p1}, A0;->setShiftOn(Z)V
if-eqz p1 → 0x7f1305ab ("选择") else 0x7f1305ae ("取消全选")   // 左侧“选择”键的文字
f.setText(...)   f.setSelected(p1)
i.setEnabled(p1)        ← i = btn_clip（剪切）
h.setEnabled(p1)        ← h = btn_copy（复制）
if (p1 == 0) event.p.s()
```

也就是说：**宿主自己在“有选中文本”时对「剪切」按钮调用 `setEnabled(true)`，无选中时 `setEnabled(false)`**。

这个开关就是宿主对“现在能不能剪切”的判定，直接用：

| `clipButton.isEnabled` | 左列第一格显示 | 点击行为 |
|---|---|---|
| true（有选中文本） | 宿主原生的「剪切」 | 宿主原分支：`performContextMenuAction(0x1020020)` = cut + 提示「文本已剪切」 |
| false（无选中） | 「全选」 | 宿主原分支：`performContextMenuAction(0x102001f)` = selectAll |

好处是文字、灰底/黑字样式、震动、行为**全部由宿主自己负责**，模块只负责“显示哪一个”。不需要模块去问 `InputConnection.getSelectedText()`，也就不受非标准编辑器实现影响。

### 实现要点
- 两态共用左列第一格：状态翻转时把「全选」当前的 `LayoutParams` 整份对齐给「剪切」（锚点与宽高），列间距仍取宿主每轮写在「剪切」格上的值，然后切换可见性。
- 选择状态变化**不会触发 layout**，所以用 `OnPreDrawListener` 逐帧比对，且**只在翻转时**才重写 `LayoutParams`（否则每帧 `setLayoutParams` 会引发无限重排）。
- 日志留痕：`selection cell switched: 全选->剪切 (宿主已启用剪切)` / `剪切->全选 (宿主已禁用剪切)`。

---

## 3. 其它保真细节

`android:soundEffectsEnabled="false"`——宿主的功能键都是关掉系统点击音的（靠自己的反馈链发声），新建按钮若不跟随，会多出一层系统点击音。已在克隆样式时一并复制。

---

## 4. 真机核对清单（1.2.0）

```
resolved ids selectAll=... clip=... copy=... paste=... delete=... return=...
panel-onclick selected=... reason=resource-id+framework-call
panel-layout  selected=... reason=semantic-field-names
key-feedback candidate=<descriptor>
key-feedback selected=<descriptor>          ← 反馈链已定位
panel transplanted: 全选<-剪切格 ...
install 全选 fields=.. remapped=[endToStart:...  ->  ...]
clipboard button created class=...
selection cell switched: 全选->剪切 (宿主已启用剪切)   ← 选中文字后出现
selection cell switched: 剪切->全选 (宿主已禁用剪切)   ← 剪切/取消选择后出现
panel verify rows=全选+删除 | 复制+回车 | 粘贴+剪贴板 overlaps=none leftTop=全选 clipEnabled=false verdict=PASS
clipboard opened via structural dispatcher            ← 点「剪贴板」时
```

人工核对：六个位置、每个按钮的文字、点击行为、以及**每个按钮点击都有震动**（尤其「剪贴板」）。

---

## 5. 构建与产物

- 版本：`1.2.0`（versionCode 3）
- `./gradlew :app:assembleRelease`（本机 aarch64，aapt2 走 qemu 包装器）
- 签名：`CN=OplusImePanel`，SHA-256 `8BA6E5388B08901852793B6AF7DA3F1D99B0FDBC18EC6E2CCAF2D1F168F0000F`（与 1.0.0/1.1.0 同证书，可覆盖安装）
- sha256：见 `/chat/outputs/OplusImePanel-SHA256SUMS.txt`
- 宿主类名在 dex 中出现 0 次（`input.view.body.A0` / `base.enums.BoxEnums` / `input.view.M` 均为 0），确认没有把混淆名写死
