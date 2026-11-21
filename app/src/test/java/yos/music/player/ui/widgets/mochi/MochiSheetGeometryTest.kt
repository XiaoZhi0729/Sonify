package yos.music.player.ui.widgets.mochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiSheetGeometryTest {
    @Test
    fun expandedAndOverpulledSheetsAlwaysEndAtTheWindowBottom() {
        for (w in listOf(320, 1080, 2400)) {
            for (h in listOf(480, 1600, 2608)) {
                for (top in listOf(-120f, 0f, 24f, 180.4f)) {
                    val frame = sheetFrame(w, h, top, 1f, 24f)
                    assertEquals(0, frame.x)
                    assertEquals(w, frame.width)
                    assertEquals(h, frame.y + frame.height)
                }
            }
        }
    }

    @Test
    fun floatingInsetsCloseContinuouslyAndKeepTheSameBottomGap() {
        for (step in 0..100) {
            val l = step / 100f
            val frame = sheetFrame(1080, 2400, 1200 - 1100 * l, l, 24f)
            assertEquals(frame.x, 2400 - frame.y - frame.height)
            assertEquals(1080, frame.x * 2 + frame.width)
        }
        assertEquals(0, sheetFrame(1080, 2400, Float.NaN, 0f, 24f).height)
        assertEquals(0, sheetFrame(1080, 2400, 2800f, 0f, 24f).height)
    }

    @Test
    fun topCornerMaximumDoesNotSubtractFromSmallDevices() {
        for (density in listOf(1f, 2.75f, 3f)) {
            for (radius in listOf(0f, 12f, 28f, 32f, 40f, 52f, 58f)) {
                val screen = MochiScreenCorners(radius * density, radius * density, radius * density, radius * density)
                for (step in 0..100) {
                    val gap = 8 * density * (1 - step / 100f)
                    val radii = sheetCornerRadii(screen, gap, 48 * density, 32 * density)
                    val original = (maxOf(radius, 32f) * density - gap).coerceAtLeast(0f)
                    assertEquals(minOf(original, 48 * density), radii.topLeft, 0.001f)
                    assertEquals(radii.topLeft, radii.topRight, 0f)
                    assertTrue(radii.topLeft <= 48 * density)
                }
            }
        }
    }
}
