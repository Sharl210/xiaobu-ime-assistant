package com.oplusime.panel

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.View
import android.widget.EditText
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 入口：把小布输入法「文本编辑」面板的六个按钮，重排为
 * 左列 全选／复制／粘贴，右列 删除／回车／剪贴板。
 *
 * 定位原则（DexKit 为唯一宿主定位来源）：
 *  - 资源「名称」是语义锚点，资源「整数 id」在运行时从当前 APK 重新解析，不写死。
 *  - 目标方法由“资源 id 字面量 + 框架调用”结构特征命中，不依赖混淆类名/方法名。
 *  - 混淆类名只在日志里作为证据输出，不回流到查询条件。
 *
 * 面板结构证据（com.oplus.keyboard 1.7.38.17-os）：
 *  - 面板布局：res/bL.xml（lib_input_text_editing_view），由面板构造函数 inflate。
 *  - 面板 onclick 同时使用 btn_select_all / btn_clip / btn_copy / btn_paste / ll_return
 *    五个资源 id，并调用 InputConnection.performContextMenuAction(I)Z。
 *  - 面板 setLayoutParams(...) 内含 "selectAllButton" / "clipButton" / "leftContainerView"
 *    三个语义字符串，用于运行时尺寸与边距计算。
 */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.oplus.keyboard"

        /** 剪贴板面板的语义枚举名（宿主 BoxEnums 常量名，非混淆名）。 */
        private const val CLIP_BOX_ENUM_NAME = "BOX_CLIP"

        /** 资源名称 → 面板六个按钮（名称是语义锚点）。 */
        private const val NAME_SELECT_ALL = "btn_select_all"
        private const val NAME_CLIP = "btn_clip"
        private const val NAME_COPY = "btn_copy"
        private const val NAME_PASTE = "btn_paste"
        private const val NAME_DELETE_LAYOUT = "ll_delete"
        private const val NAME_RETURN_LAYOUT = "ll_return"

        /**
         * 文本编辑面板左上角的返回箭头。
         *
         * 取证（宿主 `res/bL.xml` 反编译 + 面板 onClick 的 id 分支）：它是该面板里唯一的返回控件，
         * 点击落在面板自身 `onClick` 里那条 `resource-id + framework-call` 分支上。
         * 名字取资源表里的真实名字（`iv_back`），不是 dex 里的属性名。
         */
        private const val NAME_BACK_IV = "iv_back"
        private const val NAME_CLIPBOARD_LABEL = "clipboard"

        /** 剪贴板计数的格式化串（`%1$d/%2$d`），用于把上限显示改成 ∞。 */
        private const val NAME_CLIP_LENGTH = "clip_length"

        /** 剪贴板面板底部的计数控件与列表（搜索按钮挂在计数行最右侧）。 */
        private const val NAME_CLIP_COUNTER = "tv_clip_count"

        /**
         * 剪贴板列表的资源名。注意是 **`rv_clipboard`**（资源表里的真实名字），
         * 不是 dex 里那个 `rvClipboard` 字符串——后者是 Kotlin 惰性委托的**属性名**，
         * 出现在 `f.n("rvClipboard")` 之类的空值检查里，用 `getIdentifier` 永远解析不出来。
         * 1.8.0 之前把两者搞混，导致列表 id 恒为 0、整个搜索功能被跳过（按钮根本没建）。
         */
        private const val NAME_CLIP_LIST = "rv_clipboard"

        /** 常用语计数与列表资源名，和剪贴板共用同一面板但不是同一数据源。 */
        private const val NAME_PHRASE_COUNTER = "tv_phrase_count"
        private const val NAME_PHRASE_LIST = "rv_phrase_directory"

        /** 搜索按钮上的两个字。 */
        private const val SEARCH_LABEL = "搜索"

        @Volatile
        var modulePath: String = ""

        init {
            System.loadLibrary("dexkit")
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        val hostClassLoader = lpparam.classLoader
        val apkPath = lpparam.appInfo?.sourceDir
        if (apkPath.isNullOrEmpty()) {
            log("host apk path unavailable, abort")
            return
        }
        // 该 APK 体积大、类数量多，DexKit 静态解析需要可观时间。放到后台线程完成，
        // 避免阻塞输入法进程启动。面板类在用户打开文本编辑面板时才会实例化，
        // 正常情况下解析早已完成。
        Thread({
            val startedAt = System.currentTimeMillis()
            runCatching { install(apkPath, hostClassLoader) }
                .onFailure { log("install failed: ${it.stackTraceToString()}") }
            log("install finished in ${System.currentTimeMillis() - startedAt} ms")
        }, "oplusime-panel-install").start()
    }

    // ---------------------------------------------------------------- install

    private fun install(apkPath: String, hostClassLoader: ClassLoader) {
        // 与面板无关的独立功能：成对符号「自动补全」抑制。
        // 先于面板解析安装，因此即使面板定位失败，符号行为也照常生效。
        runCatching { QuotePairSuppressor.install(hostClassLoader) }
            .onFailure { log("quote-pair install failed: ${it.message}") }

        val ids = PanelIds(
            selectAll = resolveIdentifier(apkPath, "id", NAME_SELECT_ALL),
            clip = resolveIdentifier(apkPath, "id", NAME_CLIP),
            copy = resolveIdentifier(apkPath, "id", NAME_COPY),
            paste = resolveIdentifier(apkPath, "id", NAME_PASTE),
            deleteLayout = resolveIdentifier(apkPath, "id", NAME_DELETE_LAYOUT),
            returnLayout = resolveIdentifier(apkPath, "id", NAME_RETURN_LAYOUT),
        )
        log(
            "resolved ids selectAll=${ids.selectAll} clip=${ids.clip} copy=${ids.copy} " +
                "paste=${ids.paste} delete=${ids.deleteLayout} return=${ids.returnLayout}"
        )
        if (ids.selectAll == 0 || ids.clip == 0 || ids.copy == 0 ||
            ids.paste == 0 || ids.deleteLayout == 0 || ids.returnLayout == 0
        ) {
            log("resource id resolution incomplete, abort this build")
            return
        }
        val backId = resolveIdentifier(apkPath, "id", NAME_BACK_IV)
        log("resolved panel back id=$backId")
        val labelId = resolveIdentifier(apkPath, "string", NAME_CLIPBOARD_LABEL)
        val clipLengthId = resolveIdentifier(apkPath, "string", NAME_CLIP_LENGTH)
        val clipCounterId = resolveIdentifier(apkPath, "id", NAME_CLIP_COUNTER)
        val clipListId = resolveIdentifier(apkPath, "id", NAME_CLIP_LIST)
        val phraseCounterId = resolveIdentifier(apkPath, "id", NAME_PHRASE_COUNTER)
        val phraseListId = resolveIdentifier(apkPath, "id", NAME_PHRASE_LIST)
        log("resolved clip_length id=$clipLengthId counter=$clipCounterId list=$clipListId phraseCounter=$phraseCounterId phraseList=$phraseListId")

        DexKitBridge.create(apkPath).use { bridge ->
            // 引号抑制的宿主实现类：宿主的 InputConnection 由它自己实现、不经过框架代理，
            // 必须等 APK 解析出「谁产出 InputConnection」之后才能挂上。
            runCatching { QuotePairSuppressor.attachHostImplementations(bridge, hostClassLoader) }
                .onFailure { log("quote-pair host impl failed: ${it.message}") }
            runCatching { QuotePairSuppressor.attachHostCommitDispatchers(bridge, hostClassLoader) }
            // native 引擎 → Java 的真正提交汇聚点（引擎回调 + 提交分发器），
            // 符号键也走这里，因此成对符号必须在这一层拦截才有效。
            runCatching { QuotePairSuppressor.attachEngineCommit(bridge, hostClassLoader) }
                .onFailure { log("quote-pair engine commit install failed: ${it.message}") }
                .onFailure { log("quote-pair dispatcher failed: ${it.message}") }

            // 「符号」键直达完整符号页：在旁边把符号分档抬到最高档，
            // 于是键盘上的「符号」键一按就是完整符号页，不再先落简洁页。
            runCatching { SymbolPageRedirect.install(bridge, hostClassLoader) }
                .onFailure { log("symbol-page install failed: ${it.message}") }

            // 「返回 = 回键盘主页面」：面板显示期间接管系统返回键。
            runCatching { PanelBackRouter.install(bridge, hostClassLoader) }
                .onFailure { log("panel-back install failed: ${it.message}") }

            val onClick = resolvePanelOnClick(bridge, ids)
            if (onClick == null) {
                log("panel onclick unresolved, abort")
                return@use
            }
            val panelClass = runCatching { onClick.declaredClass?.getInstance(hostClassLoader) }
                .onFailure { log("panel class load failed: ${it.message}") }
                .getOrNull()
            if (panelClass == null) {
                log("panel class unresolved, abort")
                return@use
            }
            val layoutMethod = resolvePanelLayoutMethod(bridge, hostClassLoader)
            val keyFeedback = resolveKeyFeedback(onClick, hostClassLoader)
            val closePath = resolveClosePath(onClick, hostClassLoader)
            PanelState.closePanel = closePath

            val opener = ClipboardOpener.resolve(bridge, hostClassLoader, CLIP_BOX_ENUM_NAME)
            val arranger = PanelArranger(
                ids = ids,
                labelId = labelId,
                fallbackLabel = "剪贴板",
                openClipboard = { context -> opener.open(context) },
                keyFeedback = keyFeedback,
            )

            // 容量与计数相关修正（计数显示改 ∞、剪切/粘贴条件返回键盘）。
            val tweaks = HostTweaks(clipLengthId = clipLengthId, closePanel = closePath)
            tweaks.install()

            // 解除宿主的两处容量上限（记录表到顶裁剪、正文长度上限），全部结构匹配。
            runCatching { HostLimits.install(bridge, hostClassLoader) }
                .onFailure { log("host-limits install failed: ${it.message}") }

            // 剪贴板面板：计数行最右端加白底气泡「搜索」，点击弹窗输入关键字过滤条目。
            //
            // 按钮是**本模块新建**的控件，不接管宿主任何既有控件——上一版抢宿主那个
            // 两页共用的右端槽位，导致文字被宿主改写成常用语计数、计数被挤得不居中。
            // 新建控件的代价是「ConstraintLayout 上无约束会被摆到 (0,0)」，
            // 因此 [ClipSearch.place] 必须把锚点显式写全（本版已写：end→parent、
            // 上下贴计数控件）。宿主不认识这个控件，就不会再改写它。
            val clipSearch = ClipSearch(
                counterId = clipCounterId,
                phraseCounterId = phraseCounterId,
                listId = clipListId,
                phraseListId = phraseListId,
                label = SEARCH_LABEL,
                createViewLike = { template -> cloneTextView(template) },
                createInputField = resolveHostEditTextClass(bridge, hostClassLoader)?.let { cls ->
                    { context: Context ->
                        cls.constructors
                            .firstOrNull {
                                it.parameterTypes.size == 1 &&
                                    it.parameterTypes[0] == Context::class.java
                            }
                            ?.let { ctor ->
                                runCatching { ctor.newInstance(context) as EditText }.getOrNull()
                            }
                            ?: EditText(context)
                    }
                },
                registerInputTarget = resolveInputTargetRegistrar(bridge, hostClassLoader),
                clearInputTarget = resolveInputTargetClearer(bridge, hostClassLoader),
                closePanel = closePath,
                openPanel = { boxName -> opener.openByName(boxName) },
            )
            if (clipCounterId != 0) {
                runCatching { clipSearch.installPagingFilter(bridge, hostClassLoader) }
                    .onFailure { log("clip-search paging install failed: ${it.message}") }
                runCatching { clipSearch.installRowFilter(bridge, hostClassLoader) }
                    .onFailure { log("clip-search row install failed: ${it.message}") }
                val clipPanelClass = resolveClipPanelClass(bridge, hostClassLoader, clipCounterId)
                if (clipPanelClass != null) {
                    XposedBridge.hookAllConstructors(clipPanelClass, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.thisObject as? ViewGroup)?.let {
                                PanelState.remember(it)
                                clipSearch.attach(it)
                            }
                        }
                    })
                    XposedBridge.hookAllMethods(
                        clipPanelClass,
                        "onVisibilityAggregated",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.args?.getOrNull(0) == true) {
                                    (param.thisObject as? View)?.let { PanelState.remember(it) }
                                }
                            }
                        },
                    )
                    // 构造函数里可能还没把子视图挂完；附加一次“上屏后”的挂载机会，
                    // 保证计数行已经存在时按钮一定能插进去。
                    XposedBridge.hookAllMethods(
                        clipPanelClass,
                        "onAttachedToWindow",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                (param.thisObject as? ViewGroup)?.let { clipSearch.attach(it) }
                            }
                        },
                    )
                    // 宿主在同一个面板实例内通过“一个 int + 一个 boolean”的页切换方法
                    // 更新 tv_clip_count / tv_phrase_count；切页后必须重新识别当前页面，
                    // 否则常用语页的按钮和关键词会继续沿用剪贴板页状态。
                    clipPanelClass.declaredMethods
                        .filter { method ->
                            method.parameterTypes.size == 2 &&
                                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        }
                        .forEach { method ->
                            runCatching {
                                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        (param.thisObject as? ViewGroup)?.let { clipSearch.attach(it) }
                                    }
                                })
                            }.onFailure {
                                log("clip-search: page-switch hook failed ${method.name}: ${it.message}")
                            }
                        }
                    log("clip-search: panel constructor/page-switch hooks installed ${clipPanelClass.name}")
                } else {
                    log("clip-search: clipboard panel class unresolved")
                }
            } else {
                log("clip-search: counter/list id unresolved; search button skipped")
            }

            // 本模块不抢宿主的任何槽位：按钮是自己新建的，宿主按页切换可见性时
            // 不会碰它；由 [ClipSearch.attach] 自己按「计数控件是否可见」判断当前是不是
            // 剪贴板页，再决定按钮显示还是隐藏。

            val onClickMethod = runCatching { onClick.getMethodInstance(hostClassLoader) }
                .onFailure { log("panel onClick instance failed: ${it.message}") }
                .getOrNull()
            if (onClickMethod != null) {
                XposedBridge.hookMethod(onClickMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clicked = param.args.getOrNull(0) as? View ?: return
                        tweaks.onHostButtonClick(
                            clickedId = clicked.id,
                            panelIds = ids,
                            panel = param.thisObject as? View,
                        )
                        // 文本编辑面板的返回箭头（iv_back）：宿主只做「隐藏当前容器」，
                        // 用户实测按完之后输入法一路退了出去。这里等宿主动作落地，再借宿主
                        // 自己的「重置键盘」入口把键盘恢复成主键盘页 —— 即「返回 = 回键盘主页面」。
                        if (backId != 0 && clicked.id == backId) {
                            clicked.postDelayed({
                                log("panel back tapped -> restoring main keyboard")
                                SymbolPageRedirect.backToMainKeyboard()
                            }, 180L)
                        }
                    }
                })
                log("panel onClick hooked for conditional close: ${onClickMethod.declaringClass.name}")
            } else {
                log("panel onClick not hooked; cut/paste will not return to keyboard")
            }

            XposedBridge.hookAllConstructors(panelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.let {
                        PanelState.remember(it)
                        arranger.apply(it)
                    }
                }
            })
            log("panel constructor hooked: ${panelClass.name}")

            // 本模块不接管"关闭面板"：宿主的按钮本来就是动作做完、面板留在原地，
            // 退出面板由用户按返回箭头完成。曾经在此挂 after 钩子自动收面板，已按用户
            // 要求整体移除（详见 reverse_evidence/fix-1.4.1-revert-auto-close.md）。

            if (layoutMethod != null) {
                XposedBridge.hookMethod(layoutMethod, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? View)?.let { arranger.apply(it) }
                    }
                })
                log("panel layout method hooked: ${layoutMethod.declaringClass.name}#${layoutMethod.name}")
            } else {
                log("panel layout method not found; arrangement will only run once per panel")
            }
        }
    }

    /**
     * 克隆一个与模板同族的文字控件：用模板自己的类与 `(Context)` 构造器创建，
     * 于是字体、行高、内边距默认值都随宿主控件体系走（拿不到时退回普通 `TextView`）。
     */
    private fun cloneTextView(template: View): TextView? {
        if (template !is TextView) return null
        val context = template.context
        return runCatching {
            val ctor = template.javaClass.constructors.firstOrNull {
                it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            } ?: return@runCatching null
            ctor.newInstance(context) as? TextView
        }.getOrNull()
    }

    /**
     * 宿主自己的 EditText 类。
     *
     * 判据：谁产出 `InputConnection`，谁就是宿主接输入用的编辑框类型
     * （它的 `onCreateInputConnection` 就是这条链的入口）。搜索弹窗用同款控件，
     * 输入行为才与宿主自己的编辑界面一致。
     */
    private fun resolveHostEditTextClass(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): Class<*>? {
        val candidates = findMethods(bridge, "host-edit-text") {
            matcher {
                name("onCreateInputConnection")
                paramTypes("android.view.inputmethod.EditorInfo")
                returnType("android.view.inputmethod.InputConnection")
            }
        }
        return candidates
            .mapNotNull { runCatching { it.declaredClass?.getInstance(hostClassLoader) }.getOrNull() }
            .distinct()
            .firstOrNull { EditText::class.java.isAssignableFrom(it) }
            ?.also { log("host-edit-text: selected ${it.name}") }
    }

    /**
     * 宿主「把某个 EditText 设成当前输入目标」的方法。
     *
     * 这里不能只用“两个参数 + void”作为条件：宿主 dex 里有上万条这样的候选，
     * 1.18.0 实际误选了 `androidx.appcompat.widget.Toolbar#b`，它是实例方法，
     * 没有可用的宿主管理器实例，所以搜索框虽然创建成功，却没有接入输入法焦点链。
     *
     * 先用 DexKit 做宽结构召回，再用反射事实做第二层收敛：必须是静态方法、
     * 第二参数为 boolean、第一参数与 EditText 可赋值兼容、返回 void。宿主已有取证
     * 表明内部焦点切换就是静态 `(EditText, boolean) -> void` 入口；不写死类名或混淆方法名。
     */
    private fun resolveInputTargetRegistrar(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): ((EditText) -> Boolean)? {
        // 佐证：宿主自己的常用语编辑框走的正是 `input/view/head/O;->o(CustomEditText)`：
        // 它先 setImeOptions(1)，紧接着 `manager/h;->l(editText, true)`，
        // 即"把一个 EditText 设为输入法内部输入目标（并让键盘出来）"的写法。
        //
        // 该方法的形状在整包内是唯一的：静态 + `(EditText, boolean) -> void`。
        // 1.18.0 用的是"两参数 + void"这种过宽的召回，命中了 11766 个候选，
        // 最后选中 `androidx.appcompat.widget.Toolbar#b`（实例方法、拿不到宿主实例），
        // 于是搜索框创建成功却收不到输入法按键。本版把召回收紧到宿主真实的参数形状。
        val candidates = findMethods(bridge, "input-target") {
            matcher {
                returnType("void")
                paramTypes("android.widget.EditText", "boolean")
            }
        }
        val resolved = candidates.mapNotNull { data ->
            runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
        }
        val compatible = resolved.filter { candidate ->
            Modifier.isStatic(candidate.modifiers) &&
                candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                (EditText::class.java.isAssignableFrom(candidate.parameterTypes[0]) ||
                    candidate.parameterTypes[0].isAssignableFrom(EditText::class.java))
        }
        log(
            "input-target: resolved=${resolved.size} compatibleStatic=${compatible.size}" +
                " candidates=" + compatible.joinToString(",") { it.declaringClass.name + "#" + it.name }
        )
        val method = compatible.firstOrNull()
            ?: run {
                log("input-target: no compatible static EditText registrar")
                return null
            }
        log(
            "input-target: selected ${method.declaringClass.name}#${method.name}" +
                " params=${method.parameterTypes.joinToString { it.name }}"
        )
        return { field ->
            runCatching {
                method.isAccessible = true
                // 与宿主同向：true = 设为内部输入目标，并让输入法键盘显示出来。
                method.invoke(null, field, true)
                true
            }.getOrElse {
                log("input-target: invoke failed: ${it.message}")
                false
            }
        }
    }

    /**
     * 解除宿主的「内部输入目标」。
     *
     * 为什么必须有这一步：搜索条排在输入法窗口根视图上时，宿主把内部输入目标指向它。
     * 如果只把视图摘掉、不让宿主忘记它，宿主接下来会对着一个已经脱离视图树的输入框
     * 继续处理焦点，输入法就把整个窗口收下去 —— 这正是用户实测的「点搜索后界面被关掉」。
     *
     * 定位方式仍然是结构化的：复用上面那条唯一形状 `(EditText, boolean) -> void` 的声明类，
     * 在它身上找**静态、类型可赋值为 EditText** 的字段（宿主的内部输入框就存在这个字段里），
     * 置空即完成解除。字段名是混淆的，但我们是按类型拿的，不写死名字。
     */
    private fun resolveInputTargetClearer(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): (() -> Boolean)? {
        val candidates = findMethods(bridge, "input-target-clear") {
            matcher {
                returnType("void")
                paramTypes("android.widget.EditText", "boolean")
            }
        }
        val owner = candidates
            .mapNotNull { runCatching { it.getMethodInstance(hostClassLoader) }.getOrNull() }
            .firstOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            ?.declaringClass
        if (owner == null) {
            log("input-target-clear: registrar owner unresolved; search close will not release target")
            return null
        }
        val holder = owner.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) && EditText::class.java.isAssignableFrom(field.type)
        }
        if (holder == null) {
            log("input-target-clear: no static EditText field on ${owner.name}")
            return null
        }
        runCatching { holder.isAccessible = true }
        log("input-target-clear: bound to ${owner.name}#${holder.name}")
        return {
            runCatching {
                holder.set(null, null)
                log("input-target-clear: host internal edit target released")
                true
            }.getOrElse {
                log("input-target-clear: release failed: ${it.message}")
                false
            }
        }
    }

    /**
     * 剪贴板面板类：构造函数里用到了计数控件 `tv_clip_count` 的资源 id，
     * 且自身是可容纳子视图的容器。资源名是语义锚点，id 每版运行时重新解析。
     */
    private fun resolveClipPanelClass(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        counterId: Int,
    ): Class<*>? {
        val candidates = findMethods(bridge, "clip-panel") {
            matcher {
                name("<init>")
                usingNumbers(listOf(counterId))
            }
        }
        return candidates
            .mapNotNull { data ->
                runCatching { data.declaredClass?.getInstance(hostClassLoader) }.getOrNull()
            }
            .distinct()
            .firstOrNull { ViewGroup::class.java.isAssignableFrom(it) }
    }

    // ---------------------------------------------------------------- queries

    /** 面板 onclick：五个按钮资源 id 同时出现，且调用 InputConnection.performContextMenuAction。 */
    private fun resolvePanelOnClick(bridge: DexKitBridge, ids: PanelIds): MethodData? {
        val numbers: List<Number> = listOf(
            ids.selectAll, ids.clip, ids.copy, ids.paste, ids.returnLayout,
        )
        val candidates = findMethods(bridge, "panel-onclick") {
            findFirst = true
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.view.View")
                usingNumbers(numbers)
                addInvoke("Landroid/view/inputmethod/InputConnection;->performContextMenuAction(I)Z")
            }
        }
        val selected = candidates.firstOrNull() ?: return null
        log("panel-onclick selected=${selected.descriptor} reason=resource-id+framework-call")
        return selected
    }

    /**
     * 宿主自己的按键反馈（震动/音效）。
     *
     * 面板 onClick 的每个分支开头都会调用同一个“静态 + 无参 + void + 非面板自身类”的方法，
     * 它就是宿主给功能键用的反馈链（键音/触感）。因此不需要猜也不需要写死类名：
     * 从已定位的 panel-onclick 的 invokes 里筛出来即可。
     *
     * 新建的「剪贴板」按钮不是宿主原有控件，它的点击不会经过宿主 onClick，
     * 所以必须显式调用这条反馈链，才能和其余五个按钮的震动完全一致。
     */
    private fun resolveKeyFeedback(
        onClick: MethodData,
        hostClassLoader: ClassLoader,
    ): (() -> Boolean)? {
        val candidates = runCatching {
            onClick.invokes.filter { invoke ->
                invoke.paramCount == 0 &&
                    invoke.returnTypeName == "void" &&
                    Modifier.isStatic(invoke.modifiers) &&
                    invoke.className != onClick.className
            }
        }
            .onFailure { log("key-feedback scan failed: ${it.message}") }
            .getOrDefault(emptyList())
        candidates.take(5).forEach { log("key-feedback candidate=${it.descriptor}") }
        val selected = candidates.firstOrNull() ?: run {
            log("key-feedback unresolved; clipboard button will use framework haptics")
            return null
        }
        val method = runCatching { selected.getMethodInstance(hostClassLoader) }
            .onFailure { log("key-feedback instance failed: ${it.message}") }
            .getOrNull() ?: return null
        log("key-feedback selected=${selected.descriptor}")
        return {
            runCatching {
                method.invoke(null)
                true
            }.getOrElse { error ->
                log("key feedback invoke failed: ${error.message}")
                false
            }
        }
    }

    /** 面板运行时排布方法：内部使用三个语义字段名做空值保护。 */
    private fun resolvePanelLayoutMethod(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): java.lang.reflect.Method? {
        val candidates = findMethods(bridge, "panel-layout") {
            matcher {
                name("setLayoutParams")
                returnType("void")
                paramCount(1)
                usingStrings(
                    listOf("selectAllButton", "clipButton", "leftContainerView"),
                    StringMatchType.Equals,
                    false,
                )
            }
        }
        val selected = candidates.firstOrNull() ?: return null
        log("panel-layout selected=${selected.descriptor} reason=semantic-field-names")
        return runCatching { selected.getMethodInstance(hostClassLoader) }
            .onFailure { log("panel layout method load failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * 本模块**不接管**面板的关闭。宿主六个按钮的原生行为是"动作做完、面板留在原地"，
     * 用户要连续操作（全选→复制→粘贴）时不必反复重新打开面板；退出面板由用户自己按返回。
     * 这里曾有一条把面板自动收起的实现，因其行为与用户要求相反，已整体移除。
     */
    /**
     * 宿主自己"关闭面板、回到键盘"的那条链（左上返回箭头走的就是它）。
     *
     * 形态固定为两步：静态无参访问器取回容器管理器，静态单参关闭方法把它隐掉。
     * 两个方法都从已定位的 panel-onclick 的 invokes 里按**形状**筛出来，
     * 因此不依赖任何宿主混淆名。只有"宿主自己的返回箭头仍能关面板"这个事实成立，它就能被重新找到。
     */
    private fun resolveClosePath(
        onClick: MethodData,
        hostClassLoader: ClassLoader,
    ): (() -> Boolean)? {
        val invokes = runCatching { onClick.invokes.toList() }
            .onFailure { log("close-path scan failed: ${it.message}") }
            .getOrDefault(emptyList())

        val closeCandidates = invokes.filter { invoke ->
            Modifier.isStatic(invoke.modifiers) &&
                invoke.returnTypeName == "void" &&
                invoke.paramCount == 1 &&
                invoke.paramTypeNames.firstOrNull()?.startsWith(TARGET_PACKAGE) == true
        }
        closeCandidates.take(5).forEach { log("close-path candidate=${it.descriptor}") }
        val closeData = closeCandidates.firstOrNull() ?: run {
            log("close-path unresolved; cut/paste will keep the panel open")
            return null
        }
        val closeMethod = runCatching { closeData.getMethodInstance(hostClassLoader) }
            .onFailure { log("close method instance failed: ${it.message}") }
            .getOrNull() ?: return null

        val accessorData = invokes.firstOrNull { invoke ->
            if (!Modifier.isStatic(invoke.modifiers) || invoke.paramCount != 0) return@firstOrNull false
            if (invoke.returnTypeName == "void") return@firstOrNull false
            runCatching {
                val returnClass = Class.forName(invoke.returnTypeName, false, hostClassLoader)
                closeMethod.parameterTypes[0].isAssignableFrom(returnClass)
            }.getOrDefault(false)
        } ?: run {
            log("close-path accessor unresolved; cut/paste will keep the panel open")
            return null
        }
        val accessorMethod = runCatching { accessorData.getMethodInstance(hostClassLoader) }
            .onFailure { log("close accessor instance failed: ${it.message}") }
            .getOrNull() ?: return null

        log("close-path selected accessor=${accessorData.descriptor} close=${closeData.descriptor}")
        return {
            runCatching {
                val manager = accessorMethod.invoke(null) ?: error("manager singleton unavailable")
                closeMethod.invoke(null, manager)
                true
            }.getOrElse { error ->
                log("close path invoke failed: ${error.message}")
                false
            }
        }
    }

    private fun findMethods(
        bridge: DexKitBridge,
        label: String,
        init: FindMethod.() -> Unit,
    ): List<MethodData> = runCatching { bridge.findMethod(init).toList() }
        .onFailure { log("$label query failed: ${it.message}") }
        .getOrDefault(emptyList())
        .also { log("$label candidates=${it.size}") }

    private fun findClasses(
        bridge: DexKitBridge,
        label: String,
        init: FindClass.() -> Unit,
    ): List<ClassData> = runCatching { bridge.findClass(init).toList() }
        .onFailure { log("$label query failed: ${it.message}") }
        .getOrDefault(emptyList())
        .also { log("$label candidates=${it.size}") }

    // ------------------------------------------------------------- resources

    /**
     * 从「当前」APK 独立解析资源整数 id。资源名称是跨版本锚点，id 每版重新解析。
     */
    private fun resolveIdentifier(apkPath: String, type: String, name: String): Int {
        return runCatching {
            val assets = AssetManager::class.java
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            val addAssetPath = assets.javaClass
                .getMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            val cookie = (addAssetPath.invoke(assets, apkPath) as? Number)?.toInt() ?: 0
            if (cookie == 0) return@runCatching 0
            val ctor = Resources::class.java.getDeclaredConstructor(
                AssetManager::class.java,
                DisplayMetrics::class.java,
                Configuration::class.java,
            ).apply { isAccessible = true }
            val resources = ctor.newInstance(assets, DisplayMetrics(), Configuration()) as Resources
            resources.getIdentifier(name, type, TARGET_PACKAGE)
        }
            .onFailure { log("resolve $type/$name failed: ${it.message}") }
            .getOrDefault(0)
    }
}

/** 反射字段缓存的小工具，面板重排与剪贴板兜底共用。 */
internal object Reflect {
    private val cache = HashMap<String, Field?>()

    fun field(cls: Class<*>, name: String): Field? {
        val key = cls.name + "#" + name
        if (cache.containsKey(key)) return cache[key]
        val found = runCatching { cls.getField(name) }.getOrNull()
        cache[key] = found
        return found
    }

    fun readInt(target: Any, name: String): Int? {
        val f = field(target.javaClass, name) ?: return null
        return runCatching { f.getInt(target) }.getOrNull()
    }

    /** 逐层向上查找字段（含私有）：框架的 `mAdapter` 之类只存在于父类。 */
    fun fieldDeep(cls: Class<*>, name: String): Field? {
        var current: Class<*>? = cls
        var depth = 0
        while (depth < 12) {
            val owner: Class<*> = current ?: break
            if (owner == Any::class.java) break
            runCatching { owner.getDeclaredField(name) }.getOrNull()?.let {
                runCatching { it.isAccessible = true }
                return it
            }
            current = owner.superclass
            depth++
        }
        return null
    }

    fun readObject(target: Any, name: String): Any? {
        val f = fieldDeep(target.javaClass, name) ?: return null
        return runCatching { f.get(target) }.getOrNull()
    }

    fun writeInt(target: Any, name: String, value: Int): Boolean {
        val f = field(target.javaClass, name) ?: return false
        return runCatching { f.setInt(target, value) }.isSuccess
    }

    /** 布尔字段必须用 setBoolean；对 boolean 字段调用 setInt 会抛 IllegalArgumentException。 */
    fun writeBoolean(target: Any, name: String, value: Boolean): Boolean {
        val f = field(target.javaClass, name) ?: return false
        return runCatching { f.setBoolean(target, value) }.isSuccess
    }

    /** 取类的“自类型静态单例”字段（宿主常见的 object 单例形态）。 */
    fun selfSingleton(cls: Class<*>): Any? {
        return cls.declaredFields.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.type == cls
        }?.let { f ->
            runCatching {
                f.isAccessible = true
                f.get(null)
            }.getOrNull()
        }
    }
}
