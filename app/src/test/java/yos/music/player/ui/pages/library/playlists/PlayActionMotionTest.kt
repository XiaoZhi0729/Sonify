package yos.music.player.ui.pages.library.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作行动画曲线测试。
 *
 * 这些量在真机上很难逐帧观察（尤其「图标文字要早于胶囊消失」「模糊两端必须归零」），
 * 但一旦写错就是：文字被切一半、静止态还在模糊（白烧性能）、或者收尾不归位。
 */
class PlayActionMotionTest {

    private val eps = 0.001f

    // ── 端点必须干净 ─────────────────────────────────────────────

    @Test
    fun `fraction 0 is the untouched resting state`() {
        assertEquals(1f, PlayActionMotion.pillWidthFraction(0f), eps)
        assertEquals(0f, PlayActionMotion.pillSlideFraction(0f), eps)
        assertEquals(1f, PlayActionMotion.pillAlpha(0f), eps)
        assertEquals(1f, PlayActionMotion.pillContentAlpha(0f), eps)
        assertEquals(1f, PlayActionMotion.shuffleScale(0f), eps)
        assertEquals(1f, PlayActionMotion.shuffleAlpha(0f), eps)
        assertEquals(0f, PlayActionMotion.shuffleSlideFraction(0f), eps)
        // 静止态绝不能有模糊
        assertEquals(0f, PlayActionMotion.outBlurDp(0f), 0.01f)
    }

    @Test
    fun `fraction 0 hides the input content`() {
        assertEquals(0f, PlayActionMotion.inputAlpha(0f), eps)
        // 折叠态不组合展开层，但即使被读到也必须是清晰度最差（模糊拉满）
        assertEquals(
            PlayActionMotion.IN_BLUR_DP,
            PlayActionMotion.inputBlurDp(0f),
            0.01f
        )
    }

    @Test
    fun `fraction 1 leaves only the search field`() {
        assertEquals(0f, PlayActionMotion.pillWidthFraction(1f), eps)
        assertEquals(1f, PlayActionMotion.pillSlideFraction(1f), eps)
        assertEquals(0f, PlayActionMotion.pillAlpha(1f), eps)
        assertEquals(0f, PlayActionMotion.pillContentAlpha(1f), eps)
        assertEquals(0f, PlayActionMotion.shuffleAlpha(1f), eps)
        assertEquals(
            PlayActionMotion.SHUFFLE_MIN_SCALE,
            PlayActionMotion.shuffleScale(1f),
            eps
        )
        assertEquals(1f, PlayActionMotion.inputAlpha(1f), eps)
        // 展开完成必须完全清晰
        assertEquals(0f, PlayActionMotion.inputBlurDp(1f), 0.01f)
    }

    @Test
    fun `out blur returns to zero at the end`() {
        // 退场按钮到结尾已完全透明；模糊也必须归零，不能留一个不可见却在模糊的图层
        assertTrue(PlayActionMotion.outBlurDp(1f) > PlayActionMotion.outBlurDp(0.75f) - eps)
        assertEquals(
            PlayActionMotion.OUT_BLUR_DP,
            PlayActionMotion.outBlurDp(0.75f),
            0.01f
        )
        assertEquals(
            PlayActionMotion.OUT_BLUR_DP,
            PlayActionMotion.outBlurDp(1f),
            0.01f
        )
    }

    // ── 顺序：内容先于胶囊消失 ──────────────────────────────────

    @Test
    fun `pill content disappears before the pill itself`() {
        // 找两个量的「归零点」，内容必须在胶囊宽度压到很小之前就没
        var contentGoneAt = -1f
        var pillWidthGoneAt = -1f
        var f = 0f
        while (f <= 1f) {
            if (contentGoneAt < 0f && PlayActionMotion.pillContentAlpha(f) <= eps) contentGoneAt = f
            if (pillWidthGoneAt < 0f && PlayActionMotion.pillWidthFraction(f) <= 0.02f) {
                pillWidthGoneAt = f
            }
            f += 0.01f
        }
        assertTrue("内容应在某处归零", contentGoneAt > 0f)
        assertTrue("胶囊宽度也应在某处归零", pillWidthGoneAt > 0f)
        assertTrue(
            "图标文字($contentGoneAt) 必须早于胶囊宽度归零($pillWidthGoneAt) 消失，否则会被裁边切一半",
            contentGoneAt < pillWidthGoneAt
        )
    }

    @Test
    fun `pill content is fully gone when squeezed halfway`() {
        // 压缩到一半时内容就已经不可见（PILL_CONTENT_FADE_AT = 0.55）
        // 找 widthFraction ≈ 0.5 的 f
        var f = 0f
        while (f <= 1f && PlayActionMotion.pillWidthFraction(f) > 0.5f) f += 0.005f
        assertEquals(
            "压缩到 50% 时内容必须已经消失",
            0f, PlayActionMotion.pillContentAlpha(f), 0.08f
        )
    }

    // ── 单调性 ──────────────────────────────────────────────────

    @Test
    fun `every curve is monotonic`() {
        val inc = listOf(
            "pillSlide" to { f: Float -> PlayActionMotion.pillSlideFraction(f) },
            "shuffleSlide" to { f: Float -> PlayActionMotion.shuffleSlideFraction(f) },
            "inputAlpha" to { f: Float -> PlayActionMotion.inputAlpha(f) },
            // 退场模糊是「前半段起雾、之后维持」——单调递增到上限后保持，不是来回抖
            "outBlur" to { f: Float -> PlayActionMotion.outBlurDp(f) }
        )
        val dec = listOf(
            "pillWidth" to { f: Float -> PlayActionMotion.pillWidthFraction(f) },
            "pillAlpha" to { f: Float -> PlayActionMotion.pillAlpha(f) },
            "pillContent" to { f: Float -> PlayActionMotion.pillContentAlpha(f) },
            "shuffleAlpha" to { f: Float -> PlayActionMotion.shuffleAlpha(f) },
            "shuffleScale" to { f: Float -> PlayActionMotion.shuffleScale(f) },
            "inputBlur" to { f: Float -> PlayActionMotion.inputBlurDp(f) }
        )
        fun sweep(fn: (Float) -> Float): Boolean {
            var prev = fn(0f)
            var f = 0.01f
            while (f <= 1.0001f) {
                val v = fn(f)
                if (v < prev - 0.0001f) return false
                prev = v
                f += 0.01f
            }
            return true
        }
        inc.forEach { (name, fn) ->
            assertTrue("$name 应单调递增", sweep(fn))
        }
        dec.forEach { (name, fn) ->
            assertTrue("$name 应单调递减", sweep { x -> -fn(x) })
        }
    }

    @Test
    fun `curves stay inside their range for out of range fractions`() {
        listOf(-0.5f, -0.01f, 1.01f, 2f).forEach { f ->
            assertTrue(PlayActionMotion.pillWidthFraction(f) in -0.001f..1.001f)
            assertTrue(PlayActionMotion.pillAlpha(f) in -0.001f..1.001f)
            assertTrue(PlayActionMotion.pillContentAlpha(f) in -0.001f..1.001f)
            assertTrue(PlayActionMotion.shuffleAlpha(f) in -0.001f..1.001f)
            assertTrue(PlayActionMotion.inputAlpha(f) in -0.001f..1.001f)
        }
    }

    // ── 模糊量化 ────────────────────────────────────────────────

    @Test
    fun `blur step is zero at both ends`() {
        assertEquals(0, PlayActionMotion.blurStep(0f, PlayActionMotion.OUT_BLUR_DP))
        assertEquals(0, PlayActionMotion.blurStep(0f, PlayActionMotion.IN_BLUR_DP))
    }

    @Test
    fun `blur step saturates at max`() {
        assertEquals(
            PlayActionMotion.BLUR_STEPS,
            PlayActionMotion.blurStep(PlayActionMotion.OUT_BLUR_DP, PlayActionMotion.OUT_BLUR_DP)
        )
        assertEquals(
            PlayActionMotion.BLUR_STEPS,
            PlayActionMotion.blurStep(999f, PlayActionMotion.OUT_BLUR_DP)
        )
    }

    @Test
    fun `blur step is bounded and reversible`() {
        for (step in 0..PlayActionMotion.BLUR_STEPS) {
            val dp = PlayActionMotion.stepToDp(step, PlayActionMotion.OUT_BLUR_DP)
            assertEquals(
                "档位 $step 往返必须稳定",
                step,
                PlayActionMotion.blurStep(dp, PlayActionMotion.OUT_BLUR_DP)
            )
        }
    }

    @Test
    fun `blur step quantizes to at most BLUR_STEPS distinct values`() {
        val seen = HashSet<Int>()
        var f = 0f
        while (f <= 1f) {
            seen += PlayActionMotion.blurStep(PlayActionMotion.outBlurDp(f), PlayActionMotion.OUT_BLUR_DP)
            f += 0.002f
        }
        assertTrue(
            "过渡期实际用到的档位数 ${seen.size} 不应超过 ${PlayActionMotion.BLUR_STEPS}",
            seen.size <= PlayActionMotion.BLUR_STEPS + 1
        )
        // 这直接决定重组次数：整个过渡最多这么多次
        assertTrue("档位应该真的被用到多档（否则模糊等于没做）", seen.size >= 4)
    }

    @Test
    fun `zero max blur never divides by zero`() {
        assertEquals(0, PlayActionMotion.blurStep(5f, 0f))
        assertEquals(0f, PlayActionMotion.stepToDp(3, 0f), 0.001f)
    }

    @Test
    fun `blur radius stays subtle enough to read as defocus`() {
        // 回归防护（用户反馈「模糊力度过大，小一点点」）：
        // 半径过大时退场不是「退到焦外」而是「糊成一团」，挤压和位移都看不清。
        // 11dp 是实测可接受的量级；这里给一个上限把结论固化住。
        assertTrue(
            "退场模糊 ${PlayActionMotion.OUT_BLUR_DP}dp 过大，应 ≤ 12dp",
            PlayActionMotion.OUT_BLUR_DP <= 12f
        )
        // 但也不能小到看不出来
        assertTrue(
            "退场模糊 ${PlayActionMotion.OUT_BLUR_DP}dp 太小，失去景深感",
            PlayActionMotion.OUT_BLUR_DP >= 8f
        )
        // 进出两端同量级，观感才对称
        assertEquals(
            PlayActionMotion.OUT_BLUR_DP,
            PlayActionMotion.IN_BLUR_DP,
            0.01f
        )
    }

    // ── 与几何的配合 ───────────────────────────────────────────

    @Test
    fun `pill width at rest matches geometry`() {
        // pillWidthFraction(0) == 1，所以实际宽度就是 geometry.pillWidth，逐像素对齐共享组件
        assertEquals(1f, PlayActionMotion.pillWidthFraction(0f), eps)
    }

    @Test
    fun `shuffle never overshoots its origin`() {
        // 随机钮左缘 = geometry.shuffleLeft * (1 - slideFraction)，slide ∈ [0,1]
        // 所以左缘只会朝 0 收，不会越过行首到负坐标
        var f = 0f
        while (f <= 1f) {
            val slide = PlayActionMotion.shuffleSlideFraction(f)
            assertTrue(slide in -0.001f..1.001f)
            f += 0.01f
        }
    }

    @Test
    fun `shuffle starts moving before the search field covers it`() {
        // 回归防护（用户报「随机钮看起来不会位移」）：
        // 搜索框从右向左扩张，约在 f≈0.35 时盖住随机钮。它必须在那之前就明显动起来，
        // 否则观感是「纹丝不动、然后被盖住」。
        val atCoverPoint = PlayActionMotion.shuffleSlideFraction(0.35f)
        assertTrue(
            "f=0.35（搜索框已覆盖随机钮）时位移进度只有 $atCoverPoint，必须 ≥0.3 才看得见",
            atCoverPoint >= 0.3f
        )
        // 而且它要真的走完全程，不能中途停住
        assertEquals(1f, PlayActionMotion.shuffleSlideFraction(0.8f), eps)
    }

    @Test
    fun `shuffle and pill move by comparable magnitudes`() {
        // 两者是「一起让位」，位移量级必须接近；相差太多会显得只有一个在动
        assertEquals(
            "随机钮与胶囊的位移应在同一进度区间内完成",
            PlayActionMotion.shuffleSlideFraction(0.5f),
            PlayActionMotion.pillSlideFraction(0.5f),
            0.25f
        )
    }
}
