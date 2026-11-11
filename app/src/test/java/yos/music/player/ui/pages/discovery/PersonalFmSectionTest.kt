package yos.music.player.ui.pages.discovery

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 私人电台板块的纯逻辑：几何不变量、镜像位移、调色板打包、颜色派生。
 *
 * 这些是「半遮」「不闪」「颜色不跳」的可回归部分——放进 JVM 单测，
 * 避免每次改版式/手感都要上真机试。
 */
class PersonalFmSectionTest {

    // ---------- 几何：半遮 ----------

    @Test
    fun glassWidthIsOneCentimeterNarrowerThanTheWideBoardAtScale() {
        // 基准 134dp（197 − 63 的「削 1cm」）整体乘 FmScale
        assertEquals(134.dp * FmScale, FmGlassWidth)
        assertEquals(160.800003f, FmGlassWidth.value, 0.01f)
    }

    @Test
    fun circleCenterSitsExactlyOnGlassRightEdge() {
        // 圆是关于圆心左右对称的，圆心落在玻璃右缘 ⇔ 面积恰好各半
        assertEquals(FmGlassWidth, fmCircleCenterX())
        assertEquals(FmGlassWidth, fmCircleRowStart() + FmPageSize / 2)
    }

    @Test
    fun overlapIsExactlyHalfTheCircle() {
        assertEquals(FmCircleDiameter.value / 2f, fmHalfOverlap().value, 0.01f)
        // 反证：若误用「圆心 = start + 直径/2」，会差 gap/2
        val wrongCenter = fmCircleRowStart() + FmCircleDiameter / 2
        assertEquals(FmCircleGap.value / 2f, (FmGlassWidth - wrongCenter).value, 0.01f)
    }

    @Test
    fun rowStartIsDerivedFromPageSizeNotDiameter() {
        // 圆在 pageSize 里居中（左右各 gap/2），起点必须用 pageSize/2 反推
        assertEquals(FmGlassWidth - (FmCircleDiameter + FmCircleGap) / 2, fmCircleRowStart())
    }

    @Test
    fun stageIsScaledUpFromThePreviousVersion() {
        assertEquals(150.dp * FmScale, FmStageHeight)
        assertEquals(FmCircleDiameter + FmCircleGap, FmPageSize)
        // 「适当放大」：既不能保持原尺寸，也不能大到挤掉相邻板块
        assertTrue("整体放大倍率应落在 1.1..1.5", FmScale in 1.1f..1.5f)
    }

    // ---------- ★Pager padding 相对屏幕（封面要能从屏幕外进来） ----------

    @Test
    fun pagerStartIncludesTheRailIndent() {
        // 板块已铺满屏宽，圆心要仍落在玻璃右缘，start 必须把 20dp 缩进算进来
        assertEquals(FmRailStart + fmCircleRowStart(), fmPagerStartPadding())
        // 圆心相对屏幕 = railStart + glassWidth
        assertEquals(FmRailStart + FmGlassWidth, fmPagerStartPadding() + FmPageSize / 2)
    }

    @Test
    fun pagerEndLetsTheLastPageReachTheFocusColumn() {
        // 屏幕宽 360dp：end = 360 − 20 − pageSize
        val stage = 360.dp
        assertEquals(stage - FmRailStart - FmPageSize, fmPagerEndPadding(stage))
        // 极端窄屏不能算出负 padding（会抛异常）
        assertEquals(0.dp, fmPagerEndPadding(50.dp))
    }

    // ---------- ★★封面按横向位置淡出，且整段落在玻璃内部 ----------

    // 便于手算的一组像素：玻璃从 40px 到 240px（宽 200px），一页宽 100px。
    //  fadeStartX = 玻璃右缘 = 240px（alpha 1.0）
    //  fadeEndX   = 玻璃左缘 = 40px （alpha 0.0）
    private val glassLeftX = 40f
    private val glassRightX = 240f
    private val pageW = 100f

    private fun alpha(page: Int, pagerPosition: Float) =
        fmCoverAlpha(page, pagerPosition, pageW, glassRightX, glassLeftX)

    @Test
    fun coverIsOpaqueWhenSittingOnTheGlassRightEdge() {
        // 玻璃右缘 = "正在播放那个位置" → 1.0
        assertEquals(1f, alpha(page = 2, pagerPosition = 2f), 0.0001f)
    }

    @Test
    fun coverIsOpaqueToTheRightOfTheGlass() {
        // 右边本来就该是满的，钳到 1 不能超过
        assertEquals(1f, alpha(page = 3, pagerPosition = 2f), 0.0001f)
        assertEquals(1f, alpha(page = 8, pagerPosition = 2f), 0.0001f)
    }

    @Test
    fun coverReachesZeroExactlyAtTheGlassLeftEdge() {
        // ★这次的核心口径：0 点必须落在**玻璃左缘**，不是屏幕左缘。
        // 圆心 X = glassRightX + (page − pos) × pageW = 40
        //   ⇒ (page − pos) = (40 − 240) / 100 = −2
        assertEquals(0f, alpha(page = 0, pagerPosition = 2f), 0.0001f)
        // 再往左仍然 0，不出现负值
        assertEquals(0f, alpha(page = -1, pagerPosition = 2f), 0.0001f)
        assertEquals(0f, alpha(page = -5, pagerPosition = 2f), 0.0001f)
    }

    @Test
    fun coverIsAlreadyFullyGoneBeforeLeavingTheGlass() {
        // 圆心滑到玻璃左缘再往左一点（X = 20）时，必须已经是 0 ——
        // 上一版把 0 放在屏幕左缘，这里会残留 0.08 左右，就是"消失得太晚"。
        //   (page − pos) = (20 − 240)/100 = −2.2
        assertEquals(0f, alpha(page = -1, pagerPosition = 1.2f), 0.0001f)
    }

    @Test
    fun coverFadesLinearlyInsideTheGlass() {
        // 淡出带 = 玻璃内部 200px，所以每左移半页（50px）掉 0.25
        // page=1, pos=2 → 圆心 X = 140 → (140−40)/200 = 0.5
        assertEquals(0.5f, alpha(page = 1, pagerPosition = 2f), 0.0001f)
        // page=1, pos=1.5 → 圆心 X = 190 → 0.75
        assertEquals(0.75f, alpha(1, 1.5f), 0.0001f)
        // page=1, pos=2.5 → 圆心 X = 90 → 0.25
        assertEquals(0.25f, alpha(1, 2.5f), 0.0001f)
    }

    @Test
    fun samePageFadesLeftAndComesBackRight() {
        // 同一个 page 在滑动过程中的 alpha 变化 = "往左淡、往右显"
        val centered = alpha(2, 2f)      // 1.0
        val partway = alpha(2, 2.25f)    // 0.875
        val further = alpha(2, 2.5f)     // 0.75
        assertTrue("往左必须越来越淡", centered > partway && partway > further)
        // 反向滑回来（同一位置）又变清楚
        assertEquals(partway, alpha(2, 2.25f), 0.0001f)
        // 继续往左直到玻璃左缘 → 0
        assertEquals(0f, alpha(2, 4f), 0.0001f)
    }

    @Test
    fun everyCoverFollowsTheSameRuleNotJustThePlayingOne() {
        // 判据是位置，不是"这一页是不是在播"：
        // 任何一页滑到同样的相对位置，alpha 必须相同
        assertEquals(alpha(1, 2f), alpha(5, 6f), 0.0001f)
        assertEquals(alpha(0, 3f), alpha(9, 12f), 0.0001f)
    }

    @Test
    fun degenerateGeometryFallsBackToOpaque() {
        // 尺寸还没测量出来时不能把封面算成透明（会闪一下全没）
        assertEquals(1f, fmCoverAlpha(0, 0f, pageSizePx = 0f, glassRightX, glassLeftX), 0.0001f)
        // 玻璃宽为 0（测量前的中间态）同样兜底
        assertEquals(1f, fmCoverAlpha(0, 0f, pageW, fadeStartXPx = 100f, fadeEndXPx = 100f), 0.0001f)
        assertEquals(1f, fmCoverAlpha(0, 0f, pageW, fadeStartXPx = 0f, fadeEndXPx = 0f), 0.0001f)
    }

    // ---------- ★封面旋转：每台各自从 0 起转（"跳一下再转"回归） ----------

    @Test
    fun phaseStartsAtZeroForAFreshStation() {
        // 从没转过 → 第一帧从 0 + 一点点开始，绝不从别的台的角度起跳
        val first = fmAdvancePhase(prevDegrees = null, deltaMs = 100f)
        assertEquals(2f, first, 0.0001f)   // 100ms × 0.02 = 2°
        assertTrue("新台必须从 0 附近起步，不能跳到别的台的角度", first < 10f)
    }

    @Test
    fun phaseAdvancesContinuously() {
        var a: Float? = null
        val samples = ArrayList<Float>()
        repeat(5) {
            a = fmAdvancePhase(a, deltaMs = 100f)
            samples += a!!
        }
        // 每帧稳定 +2°，没有回绕也没有跳变
        for (i in 1 until samples.size) {
            assertEquals(2f, samples[i] - samples[i - 1], 0.0001f)
        }
    }

    @Test
    fun phaseDoesNotWrapAt360() {
        // ★发生 359° → 0° 的回绕会让 rotationZ 反向猛转一整圈。
        // 连续累加必须越过 360 且保持单调。
        val nearFull = fmAdvancePhase(prevDegrees = 359f, deltaMs = 100f)
        assertEquals(361f, nearFull, 0.0001f)
        assertTrue("必须越过 360 而不是回绕", nearFull > 360f)
    }

    @Test
    fun eachStationKeepsItsOwnPhase() {
        // 两台独立推进：A 转 10 帧，B 只转 2 帧，各自的相位互不影响。
        // 这正是"共用同一个累积角度"那版做不到的事。
        var a: Float? = null
        var b: Float? = null
        repeat(10) { a = fmAdvancePhase(a, 100f) }
        repeat(2) { b = fmAdvancePhase(b, 100f) }
        assertEquals(20f, a!!, 0.0001f)
        assertEquals(4f, b!!, 0.0001f)
        assertTrue("两台的相位必须不同（否则就是共用了同一个角度）", a!! != b!!)
    }

    @Test
    fun stoppingDoesNotResetTheAngle() {
        // ★一代一停就归 0 = 又一次跳变。停下的台必须保留它停下的角度。
        assertEquals(137f, fmPhaseToDraw(phaseDegrees = 137f, isSpinning = false), 0.0001f)
        assertEquals(137f, fmPhaseToDraw(phaseDegrees = 137f, isSpinning = true), 0.0001f)
    }

    @Test
    fun neverRotatedStationDrawsAtZero() {
        assertEquals(0f, fmPhaseToDraw(phaseDegrees = null, isSpinning = false), 0.0001f)
        assertEquals(0f, fmPhaseToDraw(phaseDegrees = null, isSpinning = true), 0.0001f)
    }

    @Test
    fun spinFadeMatchesAudioFadeDuration() {
        // ★用户口径：封面起转/停转的缓冲时长必须与音频淡入淡出一致。
        // 音频侧是 FadeExo.fadeAnimationDuration = 200L，这里必须相同。
        assertEquals(200, FM_SPIN_FADE_MS)
    }

    @Test
    fun rampMultipliesElapsedTimeNotAngle() {
        // ★缓冲乘在**时间**上：半速期推进的角度就是一半。
        // 如果改成插值角度，停止时会倒着转回去（像倒带）。
        val full = fmAdvancePhase(prevDegrees = 0f, deltaMs = 100f)
        val half = fmAdvancePhase(prevDegrees = 0f, deltaMs = 100f * 0.5f)
        assertEquals(full / 2f, half, 0.0001f)
        // 斜坡为 0 时不推进（用 deltaMs=0 表达）
        assertEquals(0f, fmAdvancePhase(prevDegrees = 0f, deltaMs = 0f), 0.0001f)
    }

    // ---------- ★玻璃高度只扣一次（真机 bug 回归） ----------

    @Test
    fun glassHeightSubtractsInsetExactlyOnce() {
        // 历史 bug：调用点先 height(stage - inset*2) 又传 padding(vertical=inset)，
        // Modifier 链扣两遍 → 玻璃短 2×inset、上偏 inset、底部空一截。
        assertEquals(FmStageHeight - FmGlassInsetY * 2, FmGlassHeight)
    }

    @Test
    fun glassVerticallyCenteredInStage() {
        // 上边距 == 下边距，否则玻璃会"贴着顶、底下漏一条"
        assertEquals(FmGlassInsetY, FmStageHeight - FmGlassHeight - FmGlassInsetY)
    }

    @Test
    fun glassIsTallerThanTheCircleSoTheHalfOverlapIsNotClipped() {
        // 半遮不变量要求圆完整露出，玻璃必须比它高
        assertTrue(
            "玻璃 ${FmGlassHeight} 必须高于圆直径 ${FmCircleDiameter}",
            FmGlassHeight > FmCircleDiameter
        )
    }

    @Test
    fun circlesFitInsideTheStageHeight() {
        // 圆要在板块里完整显示（否则上下被切）
        assertTrue(FmStageHeight >= FmCircleDiameter)
    }

    // ---------- 模糊档位 ----------

    @Test
    fun blurRadiusStaysInTheSameTierAsOtherGlassChrome() {
        // 顶栏/底栏同档：不要为了"更糊"把这块小板调成一片白雾
        assertTrue("玻璃模糊应落在 8..16dp 档", FmGlassBaseBlur.value in 8f..16f)
    }

    @Test
    fun frostedBlurStaysInTheSameTierAsBottomBarFrosted() {
        // 关玻璃退化成磨砂（不是纯色）；底栏磨砂分支是 12dp，这里同档
        assertTrue("磨砂模糊应落在 12..20dp 档", FmFrostedBlur.value in 12f..20f)
    }

    // ---------- ★★换台 = 换歌（真机两次踩坑回归） ----------

    @Test
    fun settleFocusRequiresARealUserGesture() {
        // 外部跟随（通知栏切歌 / 自动下一首）绝不能反过来触发 prepare
        assertEquals(
            false,
            fmShouldFocusOnSettle(
                userBrowsing = false, scrolling = false, offsetFraction = 0f,
                targetMediaId = "kugou-online-a", playingMediaId = "kugou-online-b"
            )
        )
    }

    @Test
    fun settleFocusWaitsForTheSnapToFinish() {
        // 还在滚 / 弹簧没吸附到整数位时不能切歌（否则"滑一半就换"）
        assertEquals(
            false,
            fmShouldFocusOnSettle(true, true, 0f, "a", "b")
        )
        assertEquals(
            false,
            fmShouldFocusOnSettle(true, false, 0.37f, "a", "b")
        )
    }

    @Test
    fun settleFocusSkipsTheSongThatIsAlreadyCurrentEvenWhenPaused() {
        // ★暂停时 playingMediaId 仍然非空：滑回它不该从头重播。
        // 上版回弹 bug 的根也在这——它把"有在播的歌"当成"必须回到那一页"。
        assertEquals(
            false,
            fmShouldFocusOnSettle(true, false, 0f, "same", "same")
        )
    }

    @Test
    fun settleFocusSwitchesToADifferentStation() {
        assertEquals(
            true,
            fmShouldFocusOnSettle(true, false, 0f, "new-song", "old-song")
        )
    }

    @Test
    fun settleFocusIgnoresMissingTarget() {
        // 队列正在变短（补货/换一批）时 target 可能为 null，不能崩也不能切
        assertEquals(
            false,
            fmShouldFocusOnSettle(true, false, 0f, null, "old")
        )
    }

    // ---------- 队列长度突变（真机崩溃回归） ----------

    @Test
    fun pageKeyIsSafeWhenQueueShrinks() {
        // 真机栈：Index 5 out of bounds for length 5（队列 50→5 时 Pager 用旧页数回调 key）
        val short = List(5) { yos.music.player.data.repositories.KugouNewSong(
            hash = "h$it", name = "n$it", author = "a",
            albumName = null, durationMs = 0L, artworkUrl = null
        ) }
        assertEquals("h4", fmPageKey(short, 4))
        assertEquals("fm-placeholder-5", fmPageKey(short, 5))
        assertEquals("fm-placeholder-49", fmPageKey(short, 49))
        assertEquals("fm-placeholder--1", fmPageKey(short, -1))
    }

    @Test
    fun pageKeyIsStableForTheSamePage() {
        val list = List(5) { yos.music.player.data.repositories.KugouNewSong(
            hash = "h$it", name = "n", author = "a",
            albumName = null, durationMs = 0L, artworkUrl = null
        ) }
        assertEquals(fmPageKey(list, 3), fmPageKey(list, 3))
    }

    // ---------- 空态文案 ----------

    @Test
    fun emptyTextDefersToErrorBranch() {
        assertEquals(null, fmEmptyText(loggedIn = true, error = "boom", empty = "E", hint = "H"))
    }

    @Test
    fun emptyTextShowsLoginHintForGuest() {
        assertEquals("H", fmEmptyText(loggedIn = false, error = null, empty = "E", hint = "H"))
        assertEquals("E", fmEmptyText(loggedIn = true, error = null, empty = "E", hint = "H"))
    }

    // ---------- 调色板打包 ----------

    @Test
    fun paletteRoundTripsHueExactly() {
        for (hue in intArrayOf(0, 33, 120, 199, 297, 359)) {
            val packed = encodePalette(hue.toFloat(), isDark = false)
            val decoded = decodePalette(packed)!!
            assertEquals(hue.toFloat(), decoded.first, 0.0001f)
            assertEquals(false, decoded.second)
        }
    }

    @Test
    fun paletteKeepsLightDarkFlagSeparate() {
        val light = decodePalette(encodePalette(200f, isDark = false))!!
        val dark = decodePalette(encodePalette(200f, isDark = true))!!
        assertEquals(200f, light.first, 0.0001f)
        assertEquals(200f, dark.first, 0.0001f)
        assertEquals(false, light.second)
        assertEquals(true, dark.second)
    }

    @Test
    fun paletteHueStaysInRange() {
        val high = decodePalette(encodePalette(999f, isDark = false))!!
        assertEquals(359f, high.first, 0.0001f)
        val negative = decodePalette(encodePalette(-5f, isDark = true))!!
        assertEquals(0f, negative.first, 0.0001f)
    }

    @Test
    fun decodeNullReturnsNull() {
        assertEquals(null, decodePalette(null))
    }

    @Test
    fun paletteKeySeparatesLightAndDark() {
        assertTrue(fmPaletteKey("u", true) != fmPaletteKey("u", false))
    }

    // ---------- 色渍与强调色 ----------

    @Test
    fun smearIsMoreSaturatedThanBaseForNearGreyInput() {
        val gradient = yos.music.player.ui.navigation.TrackBackdropGradient(
            stops = listOf(
                Color(0.94f, 0.95f, 0.92f),
                Color(0.93f, 0.94f, 0.91f),
                Color(0.92f, 0.93f, 0.89f),
                Color(0.91f, 0.92f, 0.88f),
                Color(0.90f, 0.91f, 0.87f)
            ),
            positions = listOf(0f, 0.28f, 0.55f, 0.80f, 1f)
        )
        val smear = smearColorOf(gradient, isDark = false)!!
        val base = gradient.stops[2]
        fun spread(c: Color) =
            maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
        assertTrue(
            "色渍的通道差必须明显大于底色（底 ${spread(base)} / 渍 ${spread(smear)}）",
            spread(smear) > spread(base) * 2f
        )
    }

    @Test
    fun smearAndAccentAreNullWithoutGradient() {
        assertEquals(null, smearColorOf(null, isDark = false))
        assertEquals(null, accentColorOf(null, isDark = false))
    }

    @Test
    fun lightModeAccentIsDarkerThanDarkModeAccent() {
        val gradient = yos.music.player.ui.navigation.TrackBackdropGradient(
            stops = List(5) { Color(0.80f, 0.85f, 0.78f) },
            positions = listOf(0f, 0.28f, 0.55f, 0.80f, 1f)
        )
        val light = accentColorOf(gradient, isDark = false)!!
        val dark = accentColorOf(gradient, isDark = true)!!
        assertTrue(
            "浅色模式的强调色必须更暗（白字药丸要压得住）",
            light.luminance() < dark.luminance()
        )
    }
}
