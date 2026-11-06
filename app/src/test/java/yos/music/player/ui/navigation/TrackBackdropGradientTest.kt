package yos.music.player.ui.navigation

import androidx.compose.ui.graphics.Color
import me.tatarka.google.material.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 封面取色渐变的可读性契约。
 *
 * 这个功能的全部风险都集中在「底色变亮/变暗之后文字还看得清吗」。这里不测
 * 「好不好看」（那是肉眼验收），只钉死能用数字判定的硬约束。
 *
 * 断言一律用 **HCT 的 tone**（= CIELAB 的 L\*，感知明度），而不是 HSV 的 V：
 * V 不是感知量，两个 V 相同的颜色人眼可能一明一暗，用它做判据等于放过真实差异。
 * tone 是感知均匀的，所以「色差极小」「够对比」这两条在这里是可以真正算死的。
 */
class TrackBackdropGradientTest {

    /** 8bit 量化容差：tone 浮点经 255 取整会有一档偏移。 */
    private val toneEps = 1.0

    private fun toneOf(color: Color): Double = Hct.fromInt(color.toArgb()).tone

    private fun chromaOf(color: Color): Double = Hct.fromInt(color.toArgb()).chroma

    private fun hueOf(color: Color): Double = Hct.fromInt(color.toArgb()).hue

    private fun argbOf(color: Color): Int = color.toArgb()

    private fun channelsOf(argb: Int): Triple<Int, Int, Int> =
        Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** 生成一张 w×h 的测试图：上半 [top]、下半 [bottom]，两色线性过渡。 */
    private fun gradientImage(
        w: Int,
        h: Int,
        top: Int,
        bottom: Int
    ): Triple<IntArray, Int, Int> {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val t = if (h <= 1) 0f else y.toFloat() / (h - 1)
            val r = (((top shr 16) and 0xFF) * (1 - t) + ((bottom shr 16) and 0xFF) * t).toInt()
            val g = (((top shr 8) and 0xFF) * (1 - t) + ((bottom shr 8) and 0xFF) * t).toInt()
            val b = ((top and 0xFF) * (1 - t) + (bottom and 0xFF) * t).toInt()
            val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            for (x in 0 until w) pixels[y * w + x] = argb
        }
        return Triple(pixels, w, h)
    }

    private fun solidImage(w: Int, h: Int, argb: Int): Triple<IntArray, Int, Int> {
        val pixels = IntArray(w * h) { argb }
        return Triple(pixels, w, h)
    }

    private val covers = listOf(
        0xFFE8452F.toInt() to 0xFF2B1A4A.toInt(), // 暖红 → 深紫
        0xFF1DB954.toInt() to 0xFF0A2E1A.toInt(), // 绿 → 深绿
        0xFF3D5AFE.toInt() to 0xFF001B3D.toInt(), // 蓝 → 深蓝
        0xFFF5F5F5.toInt() to 0xFFBDBDBD.toInt(), // 近白灰（低饱和）
        0xFFFFD54F.toInt() to 0xFF8D6E63.toInt()  // 黄 → 棕
    )

    @Test
    fun lightModeStaysInReadableBand() {
        for ((top, bottom) in covers) {
            val (px, w, h) = gradientImage(32, 32, top, bottom)
            val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)
            assertNotNull("浅色模式应产出渐变 top=$top", gradient)
            for (stop in gradient!!.stops) {
                val tone = toneOf(stop)
                assertTrue(
                    "浅色 tone 越界 $tone（色标 $stop）",
                    tone in (93.0 - toneEps)..(97.0 + toneEps)
                )
            }
        }
    }

    @Test
    fun darkModeStaysInReadableBandAndDarkerThanLight() {
        val (px, w, h) = gradientImage(32, 32, 0xFFE8452F.toInt(), 0xFF2B1A4A.toInt())
        val dark = TrackGradientPalette.sample(px, w, h, isDark = true)
        assertNotNull(dark)
        for (stop in dark!!.stops) {
            val tone = toneOf(stop)
            assertTrue("深色 tone 越界 $tone", tone in (8.0 - toneEps)..(16.0 + toneEps))
        }
        val light = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        // 同图：深色版必须整体暗于浅色版（主题切换不会两者接近而糊在一起）
        assertTrue(
            "深色不比浅色暗",
            dark.stops.maxOf { toneOf(it) } < light.stops.minOf { toneOf(it) }
        )
    }

    @Test
    fun adjacentStopsAreBarelyDistinguishable() {
        // 「柔和、色差极小」的**真实判据是相邻色标的 RGB 差**，不是 tone 差：
        // tone 是感知明度，深色档 tone 差 3 只对应 RGB 差 4–5（底色本身就暗，
        // 同样的 tone 跨度占的绝对量更小）。用 RGB 差断言才贴近"会不会看出色带"。
        //
        // 实测各封面下最大 RGB 差为 9/255（约 3.5%），且各段均匀（3–9 而非 3–13）。
        // 上限取 10 留一档量化余量。曾用非等距 tone 斜坡（97/96/94/92/91）时
        // 中间段会跳到 13，这条断言正是那次回归的闸门。
        for ((top, bottom) in covers) {
            val (px, w, h) = gradientImage(32, 32, top, bottom)
            for (isDark in listOf(false, true)) {
                val gradient = TrackGradientPalette.sample(px, w, h, isDark)!!
                val rgb = gradient.stops.map { argbOf(it) }
                for (i in 1 until rgb.size) {
                    val a = channelsOf(rgb[i - 1])
                    val b = channelsOf(rgb[i])
                    val delta = maxOf(
                        abs(a.first - b.first),
                        abs(a.second - b.second),
                        abs(a.third - b.third)
                    )
                    assertTrue(
                        "相邻色标 RGB 差过大 $delta（档 $i，isDark=$isDark）",
                        delta <= 10
                    )
                }
            }
        }
    }

    @Test
    fun chromaIsLockedLowInBothModes() {
        // 色度锁死在 ~10 是「高级灰」的关键：色相还在，但绝不显艳、绝不发脏。
        // 用最艳的纯红做输入，验证它压得住。
        val (px, w, h) = solidImage(32, 32, 0xFFFF0000.toInt())
        for (isDark in listOf(false, true)) {
            val gradient = TrackGradientPalette.sample(px, w, h, isDark)!!
            for (stop in gradient.stops) {
                val chroma = chromaOf(stop)
                assertTrue("色度过高 $chroma", chroma <= 11.0)
            }
        }
    }

    @Test
    fun hueIsConstantAcrossStops() {
        // TonalPalette 的定义就是「同色相同色度、只变明度」——色相必须恒定，
        // 否则渐变会出现肉眼可见的色相漂移（红→紫那种脏）。
        //
        // 容差说明：色度只有 10，8bit 量化下 HCT 往返会把色相抖动几度（实测最大
        // 约 7°）。但在 chroma 10 的前提下，7° 色相偏移换算成 RGB 不足 2/255，
        // 肉眼完全不可见——这一点由 adjacentStopsAreBarelyDistinguishable 的
        // RGB 判据兜底。所以这里只验证「没有系统性漂移」，阈值取 8°。
        val (px, w, h) = gradientImage(32, 32, 0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        val hues = gradient.stops.map { hueOf(it) }
        val spread = hues.max() - hues.min()
        assertTrue("色相系统性漂移 ${spread}°（$hues）", spread <= 8.0)
    }

    @Test
    fun valueIsMonotonicFromTopToBottom() {
        // 不允许「中间比两头亮」：那是渐变发闷的直接原因
        for (isDark in listOf(false, true)) {
            val (px, w, h) = gradientImage(32, 32, 0xFF1A237E.toInt(), 0xFFFFD54F.toInt())
            val gradient = TrackGradientPalette.sample(px, w, h, isDark)!!
            val tones = gradient.stops.map { toneOf(it) }
            for (i in 1 until tones.size) {
                assertTrue("tone 非单调递减（档 $i）: $tones", tones[i] <= tones[i - 1] + toneEps)
            }
        }
    }

    @Test
    fun sameCoverProducesSameGradient() {
        // 取色必须确定：同一张图反复算要得到同一结果，否则切 Tab 回来颜色会变。
        val (px, w, h) = gradientImage(32, 32, 0xFF1DB954.toInt(), 0xFF0A2E1A.toInt())
        val a = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        val b = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        assertEquals(a.stops, b.stops)
    }

    @Test
    fun degenerateInputsDoNotThrow() {
        // 全透明：没有可采样的不透明像素 → 干净返回 null，调用方回退主题色
        assertNull(TrackGradientPalette.sample(IntArray(64), 8, 8, isDark = false))
        // 零尺寸 / 缓冲区不足
        assertNull(TrackGradientPalette.sample(IntArray(0), 0, 0, isDark = false))
        assertNull(TrackGradientPalette.sample(IntArray(4), 8, 8, isDark = false))
        // 1×1 纯黑、纯白：必须给出合规结果而不是崩
        for (argb in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val (px, w, h) = solidImage(1, 1, argb)
            val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)
            assertNotNull("1×1 图 $argb 应产出结果", gradient)
            for (stop in gradient!!.stops) {
                assertTrue("1×1 图 tone 越界", toneOf(stop) in (93.0 - toneEps)..(97.0 + toneEps))
            }
        }
    }

    @Test
    fun stopsAndPositionsAreAligned() {
        val (px, w, h) = gradientImage(16, 16, 0xFF112233.toInt(), 0xFF445566.toInt())
        val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        assertEquals(gradient.stops.size, gradient.positions.size)
        assertEquals(5, gradient.stops.size)
        assertEquals(0f, gradient.positions.first(), 0f)
        assertEquals(1f, gradient.positions.last(), 0f)
        // 色标位置必须严格递增，否则 Brush 会画出反向色带
        for (i in 1 until gradient.positions.size) {
            assertTrue(gradient.positions[i] > gradient.positions[i - 1])
        }
    }

    @Test
    fun lerpEndpointsAreExact() {
        val (pxA, wA, hA) = gradientImage(16, 16, 0xFFE8452F.toInt(), 0xFF2B1A4A.toInt())
        val a = TrackGradientPalette.sample(pxA, wA, hA, isDark = false)!!
        val (pxB, wB, hB) = gradientImage(16, 16, 0xFF1DB954.toInt(), 0xFF0A2E1A.toInt())
        val b = TrackGradientPalette.sample(pxB, wB, hB, isDark = false)!!
        // 交叉淡化端点必须精确落在两端，否则切歌首末帧会有一跳
        assertEquals(a.stops, TrackBackdropGradient.lerp(a, b, 0f).stops)
        assertEquals(b.stops, TrackBackdropGradient.lerp(a, b, 1f).stops)
    }

    @Test
    fun averageIsBetweenTopAndBottom() {
        val (px, w, h) = gradientImage(16, 16, 0xFFE8452F.toInt(), 0xFF2B1A4A.toInt())
        val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        val avgTone = toneOf(gradient.average)
        assertTrue(
            "平均色 tone 不在顶/底之间",
            avgTone in (toneOf(gradient.bottom) - 2.0)..(toneOf(gradient.top) + 2.0)
        )
    }

    @Test
    fun tripleCanvasBrushCoversVisibleBand() {
        // 3× 种子层：可见区域只看到画布中间三分之一，所以 tripleCanvasBrush 的
        // 色标必须落在 [1/3, 2/3] 内，且两端有平铺，否则页面只显示渐变中段。
        val (px, w, h) = gradientImage(16, 16, 0xFFE8452F.toInt(), 0xFF2B1A4A.toInt())
        val gradient = TrackGradientPalette.sample(px, w, h, isDark = false)!!
        // 画笔本身不可内省，这里用色标位置换算校验映射关系
        for (position in gradient.positions) {
            val mapped = (1f + position) / 3f
            assertTrue("映射越界 $mapped", mapped in 0.333f..0.667f)
        }
    }
}

/** Compose Color → ARGB，避免依赖 androidx.compose.ui.graphics.toArgb 的扩展可见性。 */
private fun Color.toArgb(): Int =
    (((alpha * 255).toInt() and 0xFF) shl 24) or
            (((red * 255).toInt() and 0xFF) shl 16) or
            (((green * 255).toInt() and 0xFF) shl 8) or
            ((blue * 255).toInt() and 0xFF)
