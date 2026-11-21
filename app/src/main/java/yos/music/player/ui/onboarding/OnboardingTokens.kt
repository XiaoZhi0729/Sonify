package yos.music.player.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.ui.theme.isFlamingoInDarkMode

/**
 * 引导页的设计令牌（**唯一数值来源**）。
 *
 * ── 这一版和上一版的根本区别 ───────────────────────────────────────
 * 上一版把这页当成"设置页的变体"：16.5dp 页边距、9dp 圆角卡片、27sp 标题，
 * 全部照抄设置页。结果是一页里既有设置页的语言（卡片），又有网页轮播的语言
 * （左上角进度点 + 底部双键），还有自创的玻璃语言（纯色底上的玻璃按钮）。
 * 三种语言并存 = 没有语言。
 *
 * 这一版把它当成**舞台**：一套 20dp 栅格、一种主键、两种字级、一根发丝线。
 * 舞台可以有自己的尺度，但内部必须只有一套。
 *
 * ── 为什么页边距是 20dp 而不是设置页的 16.5dp ─────────────────────
 * 16.5dp 在 411dp 宽的机器上是 4%，对一屏只有两行字的英雄页太挤。
 * 20dp 是 iOS 的页面边距口径（iPhone 上 20pt），是**平台自己的 token**。
 * 设置页用 16.5 是为了塞进更多行，场景不同。
 *
 * ── 圆角 ──────────────────────────────────────────────────────────
 * 只有三档，且都遵守同心规则（外 = 内 + 内边距）：
 *   · 封面卡 26dp
 *   · 二维码板 22dp
 *   · 分段轨道 17dp（= 高度 34 / 2），选中块 15dp（17 − 2）
 */
internal object Ob {

    // ── 栅格 ───────────────────────────────────────────────────────
    /** 页面边距。iOS 口径，见文件头。 */
    val Gutter = 20.dp

    val S2 = 2.dp
    val S4 = 4.dp
    val S6 = 6.dp
    val S8 = 8.dp
    val S10 = 10.dp
    val S12 = 12.dp
    val S14 = 14.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val S28 = 28.dp
    val S40 = 40.dp
    val S48 = 48.dp

    // ── 顶栏 / 底栏 ────────────────────────────────────────────────
    /** 顶栏高度。返回键 44dp 触摸区装在这里。 */
    val TopBarHeight = 44.dp

    /** 主键高度。全页只有一种主键，必须只有一种高度。 */
    val PrimaryHeight = 54.dp

    val FooterGap = 16.dp

    /**
     * 底部次键（文字链）的固定槽位高度。
     *
     * ★这个槽位**在四屏里永远占着**，即使当前屏没有次键。
     *   否则"屏 2 有次键、屏 3 没有"会让主键在切屏时上下跳 40dp ——
     *   主键是全页唯一的动作，它绝对不能动。用看不见的留白换"永不移动"。
     */
    val FooterLinkHeight = 40.dp

    /** 底栏总高 = 主键 + 间隙 + 次键槽。 */
    val FooterHeight = PrimaryHeight + FooterGap + FooterLinkHeight

    // ── 圆角 ───────────────────────────────────────────────────────
    val CardCorner = 26.dp
    val PlateCorner = 22.dp
    val SheetCorner = 18.dp
    val SegmentHeight = 34.dp

    /**
     * 轨道圆角 = 胶囊（`height / 2`）。
     *
     * 原型稿的分段控件是 `border-radius: 9999px`（真胶囊），不是 17dp 圆角矩形 ——
     * 两者在 34dp 高度上数值接近，但**缩放/大字体时只有胶囊能保持"两端是半圆"**。
     */
    val SegmentCorner = SegmentHeight / 2

    /** 轨道内边距。原型 `padding: 3px`。 */
    val SegmentPadding = 3.dp

    /** 滑块圆角：胶囊（原型 `border-radius: 9999px`，不是"轨道圆角减内边距"）。 */
    val SegmentIndicatorCorner = SegmentHeight / 2

    /** 分段选项里的图标尺寸（原型 `width:13px height:13px stroke-width:2.2`）。 */
    val SegmentIcon = 13.dp

    /** 预览舞台（第 3 屏歌词区 / 第 4 屏玻璃区）的圆角。比卡片再大一档。 */
    val StageCorner = 28.dp

    /** 装一行控件的 22dp 圆角卡（原型稿的 iOS 分组列表口径）。 */
    val ControlCorner = 22.dp

    /** 阶段预览的外壳圆角，与 [StageCorner] 同心（28 − 内边距 6）。 */
    val StageInnerCorner = 22.dp

    /** 扫码弹层的顶部圆角（iOS sheet 口径）。 */
    val SheetTopCorner = 34.dp

    // ── 首屏图标 ───────────────────────────────────────────────────
    val HeroIcon = 80.dp
    val HeroIconCorner = 22.dp

    // ── 输入 / 验证码 ──────────────────────────────────────────────
    /** 手机号输入行高度。56dp 是 iOS 表单字段的口径。 */
    val FieldHeight = 56.dp
    val FieldCorner = 20.dp

    /** 验证码格子。 */
    val OtpCellHeight = 60.dp
    val OtpCellCorner = 18.dp
    val OtpGap = 10.dp
    val OtpCellText = 26.sp

    // ── 发丝线 ─────────────────────────────────────────────────────
    val Hairline = 0.6.dp

    // ── 章节记号 ───────────────────────────────────────────────────
    val MarkWidth = 3.dp
    /** 未到的章节。 */
    val MarkHeightIdle = 8.dp
    /** 已经过的章节。 */
    val MarkHeightPassed = 12.dp
    /** 当前章节。 */
    val MarkHeightActive = 18.dp
    val MarkGap = 6.dp

    // ── 字级 ───────────────────────────────────────────────────────
    /** 屏标题。34sp 是 iOS large title 的口径；上一版 27sp 是"安全中间值"，没有记忆力。 */
    val TitleSize = 34.sp
    val TitleTracking = (-1.1).sp

    /** 导语 / 正文。 */
    val LeadSize = 15.sp
    val LeadLine = 22.sp

    /** 设置行标题。 */
    val RowSize = 15.5.sp

    /** 说明 / 次键。 */
    val DescSize = 13.sp
    val DescLine = 18.sp

    /** 分段标签。 */
    val SegmentLabel = 13.5.sp

    /** 主键文字。 */
    val ButtonSize = 16.5.sp
    val ButtonTracking = 0.2.sp

    /**
     * 首屏手写大字。
     *
     * 两颗字号差得比"视觉同高"更多，是因为字体**字面高度**不同：
     * CJK 字面只有拉丁的 ~70%，44 / 52 让两行墨迹高度接近（44×0.78 ≈ 34.3，
     * 52×0.69 ≈ 35.9）。**换字体必须重新量这两行，别照搬数字。**
     */
    val HelloCjkSize = 44.sp
    val HelloLatinSize = 52.sp

    /** 末屏的"署名"字（同一款手写体，小一号，当做出品人的落款）。 */
    val SignatureSize = 27.sp

    /**
     * 手写大字四周的**墨迹补偿**。
     *
     * ★这不是排版留白，是给离屏层留的地方。
     *   `graphicsLayer { alpha }` 在 alpha<1 时会建一层尺寸等于**节点布局边界**的
     *   离屏层；而这款手写体的墨迹远超 `Text` 的排版框，动画中途会被层边界切掉。
     *
     * ★数值来自字体本身，不是试出来的（`fontTools` 实测 `macintosh_skeletal_filled.otf`，
     *   unitsPerEm=3000、hhea descent=-750）：
     *     · "Sonify" 的 `y` / `f` 降部到 **-1063**，比排版框底低 313 units = **0.104em**
     *       → 52sp 下 ≈ 5.4dp；
     *     · 连笔外溢到 xMax 9841.6，而总 advance 只有 9225
     *       → 右侧溢出 617 units = **0.206em ≈ 10.7dp**（旧的 10dp 差 0.7dp，就是差这一点）。
     *   取 16dp 覆盖两向并留余量。**换字体必须重新量这两行**（见 [HelloCjkSize]）。
     */
    val HandwriteVPad = 36.dp
    val HandwriteHPad = 48.dp

    /** 手写大字入场时的起始模糊（对齐原型的 blur(6px) → 0）。 */
    val HandwriteBlur = 6.dp

    // ── 动画 ───────────────────────────────────────────────────────
    /** 按压弹簧。与 InteractiveHighlight 内部同参（0.5 / 300），否则两处不同拍。 */
    val PressSpring: AnimationSpec<Float> = spring(0.5f, 300f, 0.001f)

    /** 项目主曲线（Flamingo 逆向得出，勿改回 tween 或默认 spring(400)）。 */
    val SlideSpring: AnimationSpec<Float> = spring(1.2f, 800f, 0.001f)

    /** 屏内容入场总时长。 */
    const val EnterMs = 620f

    /** 每行之间错峰的毫秒数。 */
    const val StaggerMs = 72f

    /** 转场：进场 / 退场。退场更短，符合"快出慢进"的 iOS 观感。 */
    const val StepInMs = 460
    const val StepOutMs = 300

    /** 主题色渐变（切深浅色时整页调色板过渡）。 */
    const val PaletteMs = 150

    /** 品牌光晕随步数移动的时长。 */
    const val AuraMs = 900

    /** 收尾过渡层停留时长（原型 1.5 秒）。 */
    const val SplashHoldMs = 1500L

    /** 验证码输满后的波浪动画时长，动画走完再推进下一屏。 */
    const val OtpWaveMs = 780L
}

/**
 * 引导页配色。
 *
 * ── 三个底色 + 一个品牌色，就这些 ────────────────────────────────
 * 上一版同时有：iOS 系统灰 #8E8E93、App 灰 #88878e、卡片白、轨道 #F0F0F3、
 * 玻璃白纱、玻璃边光 —— 六个颜色都是"接近白/接近灰"，叠在纯白底上谁也分不清，
 * 于是整页看起来像"什么都没设计"。
 *
 * 这一版把层次交给**明度差**而不是色相差：
 *   · 页面底色带一个 2.5% 的纵向渐变（几乎看不出是渐变，但让白纸有了光）
 *   · 发丝线用 ink 的 10%（不是又一个灰）
 *   · 品牌色只出现在三处：章节记号、主键、预览里的播放键 —— 位置固定，形成语法
 *
 * ── 调色板是**动画**的 ─────────────────────────────────────────
 * [onboardingColors] 里每个颜色都过 `animateColorAsState`。这样屏 3 切"深色"时，
 * 整页（含预览卡、发丝线、主键）会在 300ms 里一起过渡过去，而不是"啪"地换一套。
 * 这是"实时预览"能成立的前提：用户看的是**过渡**，不是一个新页面。
 */
@Immutable
internal class OnboardingColors(
    val dark: Boolean,
    val bgTop: Color,
    val bgBottom: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** 已经带 alpha 的发丝线颜色，直接 drawRect 用。 */
    val hairline: Color,
    val card: Color,
    /** 分段轨道。 */
    val track: Color,
    /** 分段选中块。 */
    val indicator: Color,
    val brand: Color,
    val onBrand: Color,
    /** 弹层遮罩。 */
    val scrim: Color,
    /** 品牌色的淡底（图标块、扫码次键、accent-soft 药丸）。 */
    val accentSoft: Color,
    /** 卡片 / 输入框的 1.5dp 描边。 */
    val cardStroke: Color,
    /** 验证成功的绿。 */
    val success: Color,
    /** 预览舞台的深色底（两屏共用，恒为深色，不跟随主题）。 */
    val stageBase: Color,
    /** 品牌光晕的两档透明度（浅色更淡，深色要更亮才看得见）。 */
    val auraStrong: Float,
    val auraWeak: Float,
)

private val BgTopLight = Color(0xFFFFFFFF)
private val BgBottomLight = Color(0xFFF3F3F7)
private val InkLight = Color(0xFF131316)
private val Ink2Light = Color(0xFF6C6C74)
private val Ink3Light = Color(0xFF9C9CA4)
private val CardLight = Color(0xFFFFFFFF)
private val TrackLight = Color(0xFFEAEAEF)
private val IndicatorLight = Color(0xFFFFFFFF)

private val BgTopDark = Color(0xFF0C0C0E)
private val BgBottomDark = Color(0xFF000000)
private val InkDark = Color(0xFFF6F6F7)
private val Ink2Dark = Color(0xFFA2A2AA)
private val Ink3Dark = Color(0xFF6A6A72)
private val CardDark = Color(0xFF17171B)
private val TrackDark = Color(0xFF26262C)
private val IndicatorDark = Color(0xFF3A3A42)

@Composable
private fun paletteColor(target: Color, spec: AnimationSpec<Color>): Color =
    animateColorAsState(
        targetValue = target,
        animationSpec = spec,
        label = "onboardingPalette"
    ).value

@Composable
internal fun onboardingColors(): OnboardingColors {
    val dark = isFlamingoInDarkMode()
    val spec: AnimationSpec<Color> = tween(Ob.PaletteMs, easing = FastOutSlowInEasing)

    val bgTop = paletteColor(if (dark) BgTopDark else BgTopLight, spec)
    val bgBottom = paletteColor(if (dark) BgBottomDark else BgBottomLight, spec)
    val ink = paletteColor(if (dark) InkDark else InkLight, spec)
    val ink2 = paletteColor(if (dark) Ink2Dark else Ink2Light, spec)
    val ink3 = paletteColor(if (dark) Ink3Dark else Ink3Light, spec)
    val card = paletteColor(if (dark) CardDark else CardLight, spec)
    val track = paletteColor(if (dark) TrackDark else TrackLight, spec)
    val indicator = paletteColor(if (dark) IndicatorDark else IndicatorLight, spec)
    val brand = paletteColor(MaterialTheme.colorScheme.primary, spec)
    val onBrand = paletteColor(MaterialTheme.colorScheme.onPrimary, spec)

    return OnboardingColors(
        dark = dark,
        bgTop = bgTop,
        bgBottom = bgBottom,
        ink = ink,
        ink2 = ink2,
        ink3 = ink3,
        // 发丝线不单独定义颜色：它必须永远是"当前 ink 的 10%"，
        // 否则切深浅色时会有一条灰线慢半拍地留在原地。
        hairline = ink.copy(alpha = if (dark) 0.12f else 0.10f),
        card = card,
        track = track,
        indicator = indicator,
        brand = brand,
        onBrand = onBrand,
        scrim = Color.Black.copy(alpha = if (dark) 0.60f else 0.40f),
        // accent-soft：品牌色 14%（原型 --accent-soft）。
        // 它必须由 brand 派生而不是写死 #FF3B4E，深色下品牌色是另一支，
        // 硬编码会让图标块在深色里发出一个浅色的错色块。
        accentSoft = brand.copy(alpha = if (dark) 0.20f else 0.14f),
        // 1.5dp 描边：浅色用墨色 8%（比发丝线略实，因为它是"物件的边"不是"分隔"），
        // 深色用白色 10%。
        cardStroke = ink.copy(alpha = if (dark) 0.10f else 0.08f),
        success = Color(0xFF34C759),
        // 预览舞台恒用深底：它模拟的是播放页，浅色下也不该变成白板。
        stageBase = Color(0xFF141926),
        auraStrong = if (dark) 0.20f else 0.13f,
        auraWeak = if (dark) 0.09f else 0.055f,
    )
}
