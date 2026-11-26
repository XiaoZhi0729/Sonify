package yos.music.player.ui.pages.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class NewLibraryWidgetsLogicTest {

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val l1 = foreground.luminance()
        val l2 = background.luminance()
        val hi = max(l1, l2)
        val lo = min(l1, l2)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun compositeOver(foreground: Color, background: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + background.red * (1f - a),
            green = foreground.green * a + background.green * (1f - a),
            blue = foreground.blue * a + background.blue * (1f - a),
            alpha = 1f
        )
    }

    @Test
    fun `resolveFmWidgetPalette varies across distinct hues in light and dark modes`() {
        val hues = listOf(20f, 140f, 260f)
        for (isDark in listOf(false, true)) {
            val palettes = hues.map { resolveFmWidgetPalette(it, isDark) }
            assertNotEquals(palettes[0].bgStart, palettes[1].bgStart)
            assertNotEquals(palettes[1].bgStart, palettes[2].bgStart)
            assertNotEquals(palettes[0].bgEnd, palettes[1].bgEnd)
            assertNotEquals(palettes[0].glow, palettes[1].glow)
        }
    }

    @Test
    fun `resolveFmWidgetPalette adapts luminance and meets WCAG contrast across all hues`() {
        for (step in 0 until 12) {
            val hue = step * 30f
            val light = resolveFmWidgetPalette(hue, isDark = false)
            val dark = resolveFmWidgetPalette(hue, isDark = true)

            assertTrue("Light bgStart should be bright for hue=$hue", light.bgStart.luminance() > 0.70f)
            assertTrue("Light bgEnd should be bright for hue=$hue", light.bgEnd.luminance() > 0.65f)
            assertTrue("Light primaryText should be dark for hue=$hue", light.primaryText.luminance() < 0.08f)

            assertTrue("Dark bgStart should be dark for hue=$hue", dark.bgStart.luminance() < 0.08f)
            assertTrue("Dark bgEnd should be dark for hue=$hue", dark.bgEnd.luminance() < 0.06f)
            assertEquals(Color.White, dark.primaryText)

            assertTrue(
                "Light primary contrast < 7.0 at hue=$hue",
                contrastRatio(light.primaryText, light.bgStart) >= 7.0f &&
                    contrastRatio(light.primaryText, light.bgEnd) >= 7.0f
            )
            assertTrue(
                "Dark primary contrast < 7.0 at hue=$hue",
                contrastRatio(dark.primaryText, dark.bgStart) >= 7.0f &&
                    contrastRatio(dark.primaryText, dark.bgEnd) >= 7.0f
            )

            val lightSecondaryOnEnd = compositeOver(light.secondaryText, light.bgEnd)
            val darkSecondaryOnStart = compositeOver(dark.secondaryText, dark.bgStart)
            assertTrue(
                "Light secondary contrast < 4.5 at hue=$hue",
                contrastRatio(lightSecondaryOnEnd, light.bgEnd) >= 4.5f
            )
            assertTrue(
                "Dark secondary contrast < 4.5 at hue=$hue",
                contrastRatio(darkSecondaryOnStart, dark.bgStart) >= 4.5f
            )
        }
    }

    @Test
    fun `resolveFmWidgetPalette null hue provides high contrast fallbacks`() {
        val light = resolveFmWidgetPalette(hue = null, isDark = false)
        val dark = resolveFmWidgetPalette(hue = null, isDark = true)

        assertTrue(light.bgStart.luminance() > 0.70f)
        assertTrue(dark.bgStart.luminance() < 0.08f)
        assertTrue(contrastRatio(light.primaryText, light.bgStart) >= 7.0f)
        assertTrue(contrastRatio(light.primaryText, light.bgEnd) >= 7.0f)
        assertTrue(contrastRatio(dark.primaryText, dark.bgStart) >= 7.0f)
        assertTrue(contrastRatio(dark.primaryText, dark.bgEnd) >= 7.0f)
    }

    @Test
    fun `DailyRecommendPaging computes pageCount and pageRange accurately`() {
        assertEquals(0, DailyRecommendPaging.pageCount(0))
        assertEquals(1, DailyRecommendPaging.pageCount(1))
        assertEquals(1, DailyRecommendPaging.pageCount(3))
        assertEquals(2, DailyRecommendPaging.pageCount(4))
        assertEquals(2, DailyRecommendPaging.pageCount(6))
        assertEquals(3, DailyRecommendPaging.pageCount(7))
        assertEquals(7, DailyRecommendPaging.pageCount(20))

        assertEquals(IntRange.EMPTY, DailyRecommendPaging.pageRange(0, 0))
        assertEquals(IntRange.EMPTY, DailyRecommendPaging.pageRange(7, -1))
        assertEquals(IntRange.EMPTY, DailyRecommendPaging.pageRange(7, 3))
        assertEquals(0 until 3, DailyRecommendPaging.pageRange(7, 0))
        assertEquals(3 until 6, DailyRecommendPaging.pageRange(7, 1))
        assertEquals(6 until 7, DailyRecommendPaging.pageRange(7, 2))
    }

    @Test
    fun `DailyRecommendPaging resolveSwipeTarget handles drags, flicks, and bounds`() {
        // Swipe UP past drag threshold advances
        assertEquals(
            1,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 0,
                pageCount = 4,
                netDragYPx = -50f,
                velocityYPxPerSec = 0f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        // Swipe DOWN past drag threshold returns
        assertEquals(
            1,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 2,
                pageCount = 4,
                netDragYPx = 50f,
                velocityYPxPerSec = 0f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        // Fast upward flick with small drag advances
        assertEquals(
            2,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 1,
                pageCount = 4,
                netDragYPx = -12f,
                velocityYPxPerSec = -650f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        // Sub-threshold drag stays on current page
        assertEquals(
            1,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 1,
                pageCount = 4,
                netDragYPx = -15f,
                velocityYPxPerSec = -100f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        // Clamped at bounds
        assertEquals(
            0,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 0,
                pageCount = 4,
                netDragYPx = 120f,
                velocityYPxPerSec = 900f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        assertEquals(
            3,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 3,
                pageCount = 4,
                netDragYPx = -120f,
                velocityYPxPerSec = -900f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
        // Single page always stays 0
        assertEquals(
            0,
            DailyRecommendPaging.resolveSwipeTarget(
                currentPage = 0,
                pageCount = 1,
                netDragYPx = -120f,
                velocityYPxPerSec = -900f,
                dragThresholdPx = 40f,
                velocityThresholdPxPerSec = 500f
            )
        )
    }
}
