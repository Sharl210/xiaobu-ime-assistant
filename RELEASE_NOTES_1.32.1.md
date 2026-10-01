# 1.32.1

本版修正 1.32.0 里两处"看起来对了、其实没生效"的问题。

## 1. 中文逗号上滑切「英文候选」——原来改错了键

1.32.0 写的是 `key_english_suggestion`。但 dex 核对发现这两个字符串分工不同：

```text
key_english_suggestion   设置页那一行的 **preference key**（界面用）
key_en_suggestion        **引擎真正读的存储键**
```

宿主自己在设置页就是这么翻译的（`settings/English26KeyFragment.onPreferenceTreeClick` 里两个字符串
同时出现，紧接着调设置写入口写 `key_en_suggestion`）。所以 1.32.0 写偏了：值确实进了偏好文件，
但引擎看的不是它 —— 体感就是不生效。

**现在的做法**：不再自己拼键名，而是**调用宿主自己的设置读写入口**。定位方式是纯结构匹配 ——
同一个宿主类里成对存在

```text
(String 名字, String 键, boolean 默认值) -> boolean    读
(String 名字, String 键, boolean 值)     -> void       写
```

这个成对特征在宿主里唯一。走它的好处是：宿主写入口内部除了落盘，还会**逐个通知注册过的键监听**，
输入法因此**立刻**按新值生效，而不是"写进去了但要等重启"。
只有在定位不到这对方法时，才退回直接写存储键作为兜底。

## 2. 上滑字符改写加复读校验

写 `t` 字段之后立刻复读一遍；如果写不进去（某些机型上 final 字段的反射写会被拒），
当场留一行日志说明，而不是静默失效让用户以为"改了"。

## 其余内容与 1.32.0 相同

常用语页计数居中、剪贴板条目可编辑（排最前，写回输入法自己的库）、26 键上滑字符中/英两套
对齐百度输入法、逗号句号补上滑。

## 产物

| 用途 | 文件 | 日志默认 | SHA-256 |
| --- | --- | --- | --- |
| 真机测试 | `OplusImePanel-1.32.1-test.apk` | **开** | `fbea0bdb7a55347893d1df71ee678920bbe6240a77f6d2726cafc3a8137bbd1b` |
| 仓库发布 | `OplusImePanel-1.32.1-release.apk` | 关 | `d90eab6966486749854b022587dd9b1cb3e0e92fa85bdd120b447e131585452f` |

日志开关在模块主界面：发布版默认关（不产生日志、没有拼字符串与跨进程写日志的开销），
需要排障时打开即可，最多 5 秒在输入法进程内生效。

## 自校验

- 产物 dex 内 `com/oplus/keyboard` 命中 **0**（未写死任何宿主混淆类名）
- 新标记 `swipe-map`、`clip-edit`、`key_en_suggestion`、`settings accessors resolved` 均在产物内
- 签名 `CERT.SF` / `CERT.RSA` 完整

## 装 1.32.1 后请试这几件

1. 中/英文 26 键上滑几处，对照百度输入法；
2. 中文逗号上滑（键面标记为「英」），看设置里「英文候选」是否被切换、并**立刻**生效；
3. 剪贴板条目点最前的「编辑」，改一下内容确认保存；
4. 打开常用语页，看计数是否居中、不再被搜索气泡压住。

日志里我要看这几行：

```text
swipe-map: 'q' mark '…' -> '1' (lang=zh)              ← 上滑字符被改写
swipe-map: settings accessors resolved …              ← 找到宿主设置读写入口
swipe-map: english suggestion false -> true (viaHost=true)   ← 走宿主入口切换成功
clip-edit: button added index=0 parent=…              ← 剪贴板「编辑」已插入
clip-edit: db write done len=…                        ← 编辑内容真的写库了
clip-search: counter centered PASS                    ← 计数已居中
```
