package yos.music.player.ui.pages.library.artists

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistHeroStretchTransformTest {

    @Test
    fun restingAtTopHasZeroOffsetAndUnitScale() {
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = 0,
            overscrollYPx = 0f,
            heroHeightPx = 400f,
        )
        assertEquals(0f, result.translationY, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
    }

    @Test
    fun topOverscrollKeepsTopPinnedAndScalesToMatchPulledBottom() {
        val heroHeightPx = 400f
        val overscrollYPx = 100f
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = 0,
            overscrollYPx = overscrollYPx,
            heroHeightPx = heroHeightPx,
        )
        assertEquals(0f, result.translationY, 0.0001f)
        assertEquals(1.25f, result.scale, 0.0001f)
        assertEquals(heroHeightPx + overscrollYPx, heroHeightPx * result.scale, 0.0001f)
    }

    @Test
    fun normalDownwardScrollTranslatesWithoutScaling() {
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = -120,
            overscrollYPx = 0f,
            heroHeightPx = 400f,
        )
        assertEquals(-120f, result.translationY, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
    }

    @Test
    fun bottomOverscrollWhileHeroVisibleShiftsHeroWithList() {
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = 0,
            overscrollYPx = -60f,
            heroHeightPx = 400f,
        )
        assertEquals(-60f, result.translationY, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
    }

    @Test
    fun zeroHeroHeightFallsBackSafelyWithoutNaNOrInfinity() {
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = 0,
            overscrollYPx = 80f,
            heroHeightPx = 0f,
        )
        assertEquals(80f, result.translationY, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
    }

    @Test
    fun nullHeroOffsetDefaultsToZeroBaseOffset() {
        val result = computeArtistHeroStretchTransform(
            heroOffsetPx = null,
            overscrollYPx = 0f,
            heroHeightPx = 400f,
        )
        assertEquals(0f, result.translationY, 0.0001f)
        assertEquals(1f, result.scale, 0.0001f)
    }
}
