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
