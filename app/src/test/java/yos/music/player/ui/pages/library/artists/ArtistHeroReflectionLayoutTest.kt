package yos.music.player.ui.pages.library.artists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺人页 Hero 2D 镜像延伸与 2D 渐进模糊几何契约。
 */
class ArtistHeroReflectionLayoutTest {

    @Test
    fun portraitLayoutUsesFullWidthEvenWhenWidthExceedsHeight() {
        // 平板竖屏 (800x760) 与手机竖屏 (412x383) 宽略大于高，但 extendSides=false 必须维持单图铺满、无侧边模糊
        val cases = listOf(800 to 760, 412 to 383, 760 to 900)
        for ((w, h) in cases) {
            val layout = resolveArtistHeroHorizontalLayout(
                widthPx = w,
                photoHeightPx = h,
                extendSides = false
            )
            assertEquals(0, layout.centerLeftPx)
            assertEquals(w, layout.centerWidthPx)
            assertEquals(w, layout.centerRightPx)
            assertEquals(0f, layout.leftBlurStartPx, 0.001f)
            assertEquals(w.toFloat(), layout.rightBlurStartPx, 0.001f)

            // 垂直模糊起点之上，左右边缘也必须保持 0 模糊进度
            val aboveBlurY = artistReflectionBlurStartPx(h) - 10f
            assertEquals(0f, artistHeroBlurProgression2D(0f, aboveBlurY, w, h, 300, extendSides = false), 0.001f)
            assertEquals(0f, artistHeroBlurProgression2D(w.toFloat(), aboveBlurY, w, h, 300, extendSides = false), 0.001f)
        }
    }

    @Test
    fun landscapeLayoutCentersSquareAndStartsSideBlurInsideCenterPhoto() {
        val layout = resolveArtistHeroHorizontalLayout(
            widthPx = 1536,
            photoHeightPx = 600,
            extendSides = true
        )
        assertEquals(600, layout.centerWidthPx)
        assertEquals(468, layout.centerLeftPx)
        assertEquals(1068, layout.centerRightPx)
        assertEquals(468f + 600f * ArtistSideBlurInwardFraction, layout.leftBlurStartPx, 0.01f)
        assertEquals(1068f - 600f * ArtistSideBlurInwardFraction, layout.rightBlurStartPx, 0.01f)
    }

    @Test
    fun mirrorSeamsAreAlreadyBlurredWhileCenterStaysSharp() {
        val w = 1536
        val h = 600
        val bandH = 420
        val y = 200f // < blurStartY (420f)，纯水平测试
        val layout = resolveArtistHeroHorizontalLayout(w, h, extendSides = true)

        assertEquals(0f, artistHeroBlurProgression2D(w / 2f, y, w, h, bandH, extendSides = true), 0.001f)
        val leftSeam = artistHeroBlurProgression2D(
            layout.centerLeftPx.toFloat(), y, w, h, bandH, extendSides = true
        )
        val rightSeam = artistHeroBlurProgression2D(
            layout.centerRightPx.toFloat(), y, w, h, bandH, extendSides = true
        )
        assertTrue("left mirror seam must already be blurred (> 0.15), was $leftSeam", leftSeam > 0.15f)
        assertTrue("right mirror seam must already be blurred (> 0.15), was $rightSeam", rightSeam > 0.15f)
        assertEquals(leftSeam, rightSeam, 0.001f)

        assertEquals(1f, artistHeroBlurProgression2D(0f, y, w, h, bandH, extendSides = true), 0.001f)
        assertEquals(1f, artistHeroBlurProgression2D(w.toFloat(), y, w, h, bandH, extendSides = true), 0.001f)
    }

    @Test
    fun twoDimensionalBlurCombinesHorizontalAndVerticalSmoothly() {
        val w = 1536
        val h = 600
        val bandH = 420
        val bottomY = (h + bandH).toFloat()
        val layout = resolveArtistHeroHorizontalLayout(w, h, extendSides = true)

        // 底边中点：垂直进到底 (ty = 1) → 总进度 = 1
        assertEquals(1f, artistHeroBlurProgression2D(w / 2f, bottomY, w, h, bandH, extendSides = true), 0.001f)
        // 外角：tx = 1 且 ty = 1 → 1
        assertEquals(1f, artistHeroBlurProgression2D(0f, bottomY, w, h, bandH, extendSides = true), 0.001f)
        assertEquals(1f, artistHeroBlurProgression2D(w.toFloat(), bottomY, w, h, bandH, extendSides = true), 0.001f)

        // 接缝中下点：1 - (1 - tx) * (1 - ty) 严格大于单独的 tx 与 ty，且小于 1
        val midBlurY = (artistReflectionBlurStartPx(h) + bottomY) / 2f
        val txOnly = artistHeroBlurProgression2D(layout.centerLeftPx.toFloat(), 200f, w, h, bandH, extendSides = true)
        val tyOnly = artistHeroBlurProgression2D(w / 2f, midBlurY, w, h, bandH, extendSides = true)
        val combined = artistHeroBlurProgression2D(layout.centerLeftPx.toFloat(), midBlurY, w, h, bandH, extendSides = true)
        assertTrue(combined > txOnly && combined > tyOnly && combined < 1f)
        assertEquals(1f - (1f - txOnly) * (1f - tyOnly), combined, 0.001f)
    }
}
