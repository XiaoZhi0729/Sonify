package yos.music.player.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YosLyricGeometryTest {

    // 竖屏典型视口：1080p 屏幕扣除状态栏 spacer 后的歌词区高度
    private val viewportHeight = 1400
    private val lastLineHeight = 130
    private val blankHeightPx = 70
    private val contentPaddingPx = 16

    @Test
    fun topMaskEndsBeforeActiveLineForDifferentHeightsAndDensities() {
        for (density in listOf(1f, 2.75f, 3.5f)) {
            for (heightDp in listOf(100f, 400f, 650f, 900f)) {
                val viewportPx = heightDp * density
                val anchorPx = viewportPx * LYRIC_ACTIVE_ANCHOR_FRACTION
                val insetPx = LYRIC_TOP_FADE_INSET_DP * density
                val endPx = lyricTopMaskEndPx(viewportPx, insetPx)
                val expected =
                    (anchorPx - lyricTopFadeInsetPx(viewportPx, insetPx)).coerceAtLeast(0f)
                assertEquals(expected, endPx, 0.001f)
                assertTrue("height=$heightDp density=$density", endPx <= anchorPx)
                assertTrue("height=$heightDp density=$density", endPx >= 0f)
            }
        }
    }

    @Test
    fun collapsedTopMaskNeverHasANegativeEnd() {
        assertEquals(0f, lyricTopMaskEndPx(0f, 16f), 0f)
        assertEquals(0f, lyricTopMaskEndPx(-100f, 16f), 0f)
        // 极短视口：终点被钳在 [0, anchor) 内。旧公式在这里恰好塌成 0（渐隐带消失），
        // 新公式保住 anchor 的一半，所以只断言范围，不再钉死 0。
        val end = lyricTopMaskEndPx(100f, 16f)
        assertTrue("end=$end must stay non-negative", end >= 0f)
        assertTrue("end=$end must stay below anchor", end < 100f * LYRIC_ACTIVE_ANCHOR_FRACTION)
    }

    @Test
    fun shortLandscapeViewportKeepsAVisibleTopFadeBand() {
        // 手机横屏：853dp 屏 − 56dp 顶 − 18dp 底 = 320dp 内容区，再减标题块约 49dp + 4dp 间距。
        val density = 3.25f
        val viewportDp = 267f
        val viewportPx = viewportDp * density
        val insetPx = LYRIC_TOP_FADE_INSET_DP * density

        // 旧公式在这里算出约 0.04dp，渐隐带整条消失（横屏顶部硬切）。
        val legacy = (viewportPx * LYRIC_ACTIVE_ANCHOR_FRACTION - insetPx).coerceAtLeast(0f)
        assertTrue("legacy should collapse on short viewport", legacy / density < 0.5f)

        // 新公式保住了锚点距离的一半。
        val endDp = lyricTopMaskEndPx(viewportPx, insetPx) / density
        val anchorDp = viewportDp * LYRIC_ACTIVE_ANCHOR_FRACTION
        assertTrue("fade band $endDp dp too thin", endDp >= anchorDp * 0.5f - 0.001f)
        assertTrue("fade band must stay below the anchor", endDp < anchorDp)
    }

    @Test
    fun tallViewportsAreUnaffectedByTheInsetClamp() {
        // 竖屏与平板横屏（视口 ≥ 4 × inset ≈ 533dp）必须与旧公式逐像素一致，
        // 不能动到已经验收的观感。
        for (density in listOf(2.75f, 3.25f, 3.5f)) {
            for (heightDp in listOf(534f, 620f, 700f, 900f)) {
                val viewportPx = heightDp * density
                val insetPx = LYRIC_TOP_FADE_INSET_DP * density
                val legacy = viewportPx * LYRIC_ACTIVE_ANCHOR_FRACTION - insetPx
                assertEquals(
                    "height=$heightDp density=$density",
                    legacy,
                    lyricTopMaskEndPx(viewportPx, insetPx),
                    0.001f,
                )
                assertEquals(
                    "height=$heightDp density=$density",
                    insetPx,
                    lyricTopFadeInsetPx(viewportPx, insetPx),
                    0.001f,
                )
            }
        }
    }

    @Test
    fun obstructionZeroMatchesLegacyCentering() {
        // 横屏（无底部遮挡，obstruction=0）时末句落点必须与旧公式逐像素一致，行为零变化。
        val expected = (viewportHeight / 2 - lastLineHeight / 2).coerceAtLeast(0)
        assertEquals(
            expected,
            lastLineCenterScrollOffset(viewportHeight, lastLineHeight, obstructionFraction = 0f)
        )
    }

    @Test
    fun obstructionZeroMatchesLegacyTrailingExtra() {
        val expected =
            (viewportHeight - lastLineHeight) / 2 - blankHeightPx - contentPaddingPx
        assertEquals(
            expected.coerceAtLeast(0),
            lyricTrailingExtraPx(
                viewportHeight, lastLineHeight, blankHeightPx, contentPaddingPx,
                obstructionFraction = 0f
            )
        )
    }

    @Test
    fun fixedPortraitObstructionRaisesLastLineVersusLandscape() {
        val obstruction = LAST_LINE_OBSTRUCTION_FRACTION
        val landscapeOffset = lastLineCenterScrollOffset(viewportHeight, lastLineHeight, 0f)
        val portraitOffset =
            lastLineCenterScrollOffset(viewportHeight, lastLineHeight, obstruction)

        // 竖屏末句固定更高：落点更小 = 更靠上，且抬高幅度约等于遮挡高度的一半。
        assertTrue(portraitOffset < landscapeOffset)
        val raise = landscapeOffset - portraitOffset
        assertTrue("unexpected raise=$raise", raise in 306..310)

        // 竖屏尾部余量更大，保证末句仍有内容滚到更高的固定落点。
        val landscapeTrailing = lyricTrailingExtraPx(
            viewportHeight, lastLineHeight, blankHeightPx, contentPaddingPx, 0f
        )
        val portraitTrailing = lyricTrailingExtraPx(
            viewportHeight, lastLineHeight, blankHeightPx, contentPaddingPx, obstruction
        )
        assertTrue(portraitTrailing > landscapeTrailing)
    }

    @Test
    fun portraitObstructionIsComplementOfVisibleArea() {
        assertEquals(1f - LYRIC_AREA_FRACTION_CONTROLS_VISIBLE, LAST_LINE_OBSTRUCTION_FRACTION)
        assertTrue(LAST_LINE_OBSTRUCTION_FRACTION > 0f)
        assertTrue(LAST_LINE_OBSTRUCTION_FRACTION < 1f)
    }

    @Test
    fun lastLineOffsetIsStableForAGivenObstruction() {
        // 固定落点：同一个 obstruction 反复计算结果一致（不随调用时刻/控件显隐变化）。
        val first = lastLineCenterScrollOffset(viewportHeight, lastLineHeight, LAST_LINE_OBSTRUCTION_FRACTION)
        val second = lastLineCenterScrollOffset(viewportHeight, lastLineHeight, LAST_LINE_OBSTRUCTION_FRACTION)
        assertEquals(first, second)
    }

    @Test
    fun extremeObstructionNeverProducesNegativeOffsets() {
        // 遮挡超过整个视口时落点/余量都不能为负（会被钳到 0），否则滚动目标越界。
        val offset = lastLineCenterScrollOffset(
            viewportHeight = 200, lastLineHeight = 130, obstructionFraction = 1f
        )
        assertTrue(offset >= 0)
        val trailing = lyricTrailingExtraPx(
            viewportHeight = 200, lastLineHeight = 130,
            blankHeightPx = 70, contentPaddingPx = 16, obstructionFraction = 1f
        )
        assertTrue(trailing >= 0)
    }

    @Test
    fun zeroViewportYieldsZeroTrailingExtra() {
        assertEquals(
            0,
            lyricTrailingExtraPx(
                viewportHeight = 0, lastLineHeight = 130,
                blankHeightPx = 70, contentPaddingPx = 16,
                obstructionFraction = LAST_LINE_OBSTRUCTION_FRACTION
            )
        )
    }
}
