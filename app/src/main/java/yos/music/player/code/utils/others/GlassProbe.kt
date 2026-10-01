package yos.music.player.code.utils.others

import android.content.Context
import android.provider.Settings

/**
 * 液态玻璃的消融探针（播放壳 + 底栏两个 scope）。
 *
 * 存在的理由：历史上那批 ab*.txt 读数是用一套临时 `settings put global flamingo_probe` 开关采的，
 * 但那套开关没进版本库（`git log -S 'flamingo_probe'` 全库零命中），于是"高光环和阴影各值十几毫秒"
 * 这个结论无法复现、也无法用来验证修复。这里重建，并且**把 scope 写进每一位**。
 *
 * **scope 是这套探针最重要的限制**。旧结论的主语其实是"所有玻璃面"，不是"壳"：本机实测把
 * 播放壳的整块玻璃摘掉（noglass）只从 23.3ms 到 21.0ms，而 trace 里每帧 52 次 alpha-caused
 * saveLayer 有 30 次来自底栏。所以只量壳会得出"玻璃不是瓶颈"的错误结论。
 *
 * **形状是"默认 = 线上应有的行为"**，开关只做消融，所以同一个包就能自己 A/B，不需要两个构建、
 * 也不受编译期差异污染。两个例外：[dropDecorationsInMotion] 量不出收益所以默认关；
 * [producerAlwaysMounted] 默认保持改动前的常驻行为（按需挂载有首帧闪空 backdrop 的未验证风险）。
 *
 * 默认路径已用**逐像素 A/B** 验过与改动前一致：HEAD 构建与本构建在同一受控页面（搜索页，底栏
 * 背后为纯黑）同位置截图，底栏带与迷你条带的逐通道平均差均为 0.00；而故意打开 `navcontainer`
 * 作阳性对照时该值为 2.18（同区非 0）。工具：tools/img_band_diff.ps1。
 *
 * 用法（改完必须冷重启，值只在进程启动时读一次）：
 *   adb shell settings put global flamingo_probe navcontainer
 *   adb shell am force-stop yos.music.player.oss
 *   adb shell monkey -p yos.music.player.oss -c android.intent.category.LAUNCHER 1
 *   adb shell settings delete global flamingo_probe   # 复位
 *
 * 为什么不做成实时生效：这些位决定 Modifier 链上挂不挂节点。实时翻转会留下已经创建好的离屏层，
 * 那一帧量到的是"这层从有到无的迁移成本"，不是"这层的成本"——正是要避免的自我欺骗式读数。
 *
 * 全部位为默认值时调用点退化成"修好后该有的样子"，不额外付任何代价。
 */
data class GlassProbe(
    /** 壳是否用 drawBackdrop 画玻璃；false = 退回 background(color, shape)。开关：noglass */
    val shellGlass: Boolean = true,
    /** 是否挂高光环。库内每帧 record 一个与壳等大的离屏层，且每帧新建 BlurMaskFilter。开关：nohigh */
    val shellHighlight: Boolean = true,
    /** 是否挂投影环。库内每帧 record 一个比壳还大 radius*4 的离屏层，且强制 Offscreen 合成。开关：noshadow */
    val shellShadow: Boolean = true,
    /** 效果链是否含 lens（折射）。开关：nolens */
    val shellLens: Boolean = true,
    /** 效果链是否含 blur。开关：noblur */
    val shellBlur: Boolean = true,
    /** 是否绘制 backdrop 内容（保留表面色与形状）。开关：nobd */
    val shellBackdropDraw: Boolean = true,
    /** 是否挂壳内那层全屏 LayerBackdrop 生产者。开关：noproducer */
    val shellProducer: Boolean = true,
    /** 壳内背景光效是否绘制。它每帧 invalidate，逼着全屏生产者重录。开关：nobgeff */
    val shellBackgroundEffect: Boolean = true,
    /** 运动期间（拖拽中或动画 Job 存活）是否摘掉高光环与投影环。
     *
     * **默认关**：这台机器上同包 A/B 量不出收益（帧耗时中位数 23.3ms vs 22.7ms，在 ±3ms 噪声内），
     * 而它确实改了观感——动画期间描边和投影会消失。不为零收益的东西付出可见代价。
     * （旧机那批 ab*.txt 里 highlight 值 ~17ms 的结论在本机不复现，见下方 scope 说明。）
     * 开关：dropdecor
     */
    val dropDecorationsInMotion: Boolean = false,

    // ---- 底栏 scope（上一批开关只覆盖播放壳，所以量不到真正的主因）----------------

    /**
     * 非激活的 HouseLayer（alpha==0）是否仍然绘制。
     *
     * 默认 true = 现状：三个全屏页（主页/资料库/搜索）每帧全部绘制，其中两屏完全不可见。
     * 这同时是 alpha caused saveLayer 与大量 FillRectOp/TextureOp 的直接来源，
     * 而且**与液态玻璃无关**——正好解释了为什么整个壳玻璃消融阶梯是空结果。
     * 开关：nohiddendraw（置为 false = 不可见就不画，即候选修复）
     */
    val houseLayersDrawWhenHidden: Boolean = true,
    /**
     * HouseLayer 的 group alpha 用哪种合成策略。
     *
     * 默认 false = 现状（CompositingStrategy.Auto → 图层 alpha 走离屏 saveLayer）。
     * 开关：housemodalpha 切到 ModulateAlpha：把 alpha 乘进每条绘制指令，不再开离屏缓冲。
     */
    val houseModulateAlpha: Boolean = false,
    /**
     * 底栏玻璃的三个子项，分开可控。
     *
     * 已定案（5/5 配对全胜，同窗口内交错）：**容器一项就值 5.2ms/帧**（p50 25.2 -> 20.0），
     * 而 navhidden / navtab 各自实测无效。所以底栏的问题不需要再拆，就是那一个 drawBackdrop。
     */
    val navContainerGlass: Boolean = true,
    /**
     * 底栏容器 drawBackdrop 的四个离屏层，各一个开关。
     *
     * 对照库源码，一个 drawBackdrop 节点恰好开四层：内容层（clip+shape+Offscreen，库内部不可控）、
     * backdrop 采样层、highlight 层、shadow 层，另有 layerBlock 那层缩放层。
     *
     * **逐层开关的结论是"没有最贵的那一层"**：navhi / navsh / navlayer 三项各 ±0.5ms（无效），
     * navsample −2.5ms、naveffects −1.0ms，五项全关（navcombo，仍挂 drawBackdrop）−4.5ms ≈
     * 整块关掉的 −4.75ms。即开销是这层节点**本身摊在 1190x208 上的固定成本**，不是某一项特效。
     * 想要真正拿回这 5ms 只能换库（backdrop 2.x 的按需 record），而不是在现库里挑软柿子。
     * 开关：navsample / navhi / navsh / navlayer / naveffects / navcombo
     */
    val navContainerSample: Boolean = true,
    val navContainerHighlight: Boolean = true,
    val navContainerShadow: Boolean = true,
    val navContainerLayer: Boolean = true,
    /** 底栏容器的效果链（vibrancy + blur 4dp + lens(16,32)）。实测只值 1.0ms。开关：naveffects */
    val navContainerEffects: Boolean = true,
    /**
     * 那个 .alpha(0f) 隐形行：每帧跑 vibrancy+blur+lens+highlight 并录制 tabsBackdrop。
     *
     * 曾经是"最值得先测、且零观感代价"的头号候选——**实测否证**：关掉它 saveLayer 甚
     * 至比基线高（38.5 vs 36.3/帧）。保留这一位是为了下次有人怀疑它时能直接量，而不是重新猜。
     * 开关：navhidden
     */
    val navHiddenProducer: Boolean = true,
    /** 3 个 Tab 胶囊各自的玻璃。实测无效（saveLayer 35.6 vs 36.3）。开关：navtab */
    val navTabGlass: Boolean = true,
    /**
     * 生产者是否无条件常驻。
     *
     * **默认 true = 改动前的原始行为**（始终挂载录制）。按需挂载在理论上不影响外观（没人采样时
     * 就不录），但它存在一个未验证的风险：弹层注册的那一帧生产者才刚刚挂上，可能拿到空 backdrop
     * 而闪一下。外观一致性优先于省一份开销，所以默认回到原行为，要量收益时显式传 prodemand。
     * 开关：prodemand
     */
    val producerAlwaysMounted: Boolean = true,

) {
    companion object {

        const val SETTING = "flamingo_probe"

        val Default = GlassProbe()

        /**
         * 供远离 MainActivity 的组件（如 ShadowImage）读取的进程级副本。
         *
         * 用普通变量而不是 CompositionLocal：[read] 的结果在整个进程内不变，不需要快照观察；
         * 而 CompositionLocal 要在 setContent 外层插一层 Provider，在这么一个嵌套极深的文件里
         * 改括号结构的风险不值得。
         *
         * 必须在 [Default] 之后声明：伴生对象按声明顺序初始化，反过来会让 current 拿到 null。
         */
        @Volatile
        var current: GlassProbe = Default
            private set

        /** MainActivity 启动时调用一次。 */
        fun install(context: Context): GlassProbe = read(context).also { current = it }

        /** 开关名 -> 关掉该项的写法。默认全 true，所以这里只列“怎么把它关掉”。 */
        private val OFF: Map<String, GlassProbe.() -> GlassProbe> = mapOf(
            "noglass" to { copy(shellGlass = false) },
            "nohigh" to { copy(shellHighlight = false) },
            "noshadow" to { copy(shellShadow = false) },
            "nolens" to { copy(shellLens = false) },
            "noblur" to { copy(shellBlur = false) },
            "nobd" to { copy(shellBackdropDraw = false) },
            "noproducer" to { copy(shellProducer = false) },
            "nobgeff" to { copy(shellBackgroundEffect = false) },
            "dropdecor" to { copy(dropDecorationsInMotion = true) },
            "nohiddendraw" to { copy(houseLayersDrawWhenHidden = false) },
            "housemodalpha" to { copy(houseModulateAlpha = true) },
            "navplain" to { copy(navContainerGlass = false, navHiddenProducer = false, navTabGlass = false) },
            "navcontainer" to { copy(navContainerGlass = false) },
            "navsample" to { copy(navContainerSample = false) },
            "navhi" to { copy(navContainerHighlight = false) },
            "navsh" to { copy(navContainerShadow = false) },
            "navlayer" to { copy(navContainerLayer = false) },
            "naveffects" to { copy(navContainerEffects = false) },
            // 四个子层全关但保留 drawBackdrop：与 navcontainer 对比就能夹出“库内部内容层”的份额。
            "navcombo" to {
                copy(
                    navContainerSample = false, navContainerHighlight = false,
                    navContainerShadow = false, navContainerLayer = false,
                    navContainerEffects = false
                )
            },
            "navhidden" to { copy(navHiddenProducer = false) },
            "navtab" to { copy(navTabGlass = false) },
            "prodemand" to { copy(producerAlwaysMounted = false) },
        )

        /**
         * 逗号/空格分隔可叠加（`nohigh,noshadow` 直接量"两个装饰层合计多少"）。
         *
         * 一次读、进程内不变：Settings.Global 是跨进程 binder 查询，放进每帧重组的作用域里
         * 就会让探针自己变成掉帧原因。
         */
        fun read(context: Context): GlassProbe {
            val raw = try {
                Settings.Global.getString(context.contentResolver, SETTING)
            } catch (_: Throwable) {
                // 探针绝不能反过来影响被测对象：读不到就当没设。
                null
            }
            if (raw.isNullOrBlank()) return Default
            var probe = Default
            var unknown = ""
            raw.split(',', ' ', ';').forEach { token ->
                val flag = token.trim().lowercase()
                if (flag.isEmpty()) return@forEach
                val ablate = OFF[flag]
                if (ablate == null) unknown += " $flag" else probe = probe.ablate()
            }
            // 拼错的开关必须响：历史上那批数据就是因为没人知道探针还在不在，才变成一次性结论。
            require(unknown.isEmpty()) {
                "$SETTING='$raw' unknown flag:$unknown known=${OFF.keys.sorted()}"
            }
            return probe
        }
    }
}
