package yos.music.player.ui.widgets.mochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiPageControlTest {

    @Test
    fun `dotWidth transfers capsule width continuously between adjacent pages`() {
        val dot = 7f
        val active = 18f

        assertEquals(active, PageControlGeometry.dotWidth(0, 0f, dot, active), 0.0001f)
        assertEquals(dot, PageControlGeometry.dotWidth(1, 0f, dot, active), 0.0001f)

        val mid = (dot + active) / 2f
        assertEquals(mid, PageControlGeometry.dotWidth(0, 0.5f, dot, active), 0.0001f)
        assertEquals(mid, PageControlGeometry.dotWidth(1, 0.5f, dot, active), 0.0001f)
        assertEquals(dot + active, PageControlGeometry.dotWidth(0, 0.3f, dot, active) + PageControlGeometry.dotWidth(1, 0.3f, dot, active), 0.0001f)
    }

    @Test
    fun `windowStart and dotScale keep all dots full size when count fits and shrink edges when overflowing`() {
        assertEquals(0f, PageControlGeometry.windowStart(1f, count = 2, maxVisible = 7), 0.0001f)
        assertEquals(1f, PageControlGeometry.dotScale(0, 0.5f, count = 2, maxVisible = 7), 0.0001f)
        assertEquals(1f, PageControlGeometry.dotScale(1, 0.5f, count = 2, maxVisible = 7), 0.0001f)

        val start = PageControlGeometry.windowStart(position = 5f, count = 12, maxVisible = 7)
        assertEquals(2f, start, 0.0001f)
        // Center dot in window is full scale
        assertEquals(1f, PageControlGeometry.dotScale(5, 5f, count = 12, maxVisible = 7), 0.0001f)
        // Window boundary dots (not real first/last) shrink to EdgeScale
        assertEquals(PageControlGeometry.EdgeScale, PageControlGeometry.dotScale(2, 5f, count = 12, maxVisible = 7), 0.0001f)
        assertEquals(PageControlGeometry.EdgeScale, PageControlGeometry.dotScale(8, 5f, count = 12, maxVisible = 7), 0.0001f)
        // Real end dots stay full scale when window rests at start/end
        assertEquals(1f, PageControlGeometry.dotScale(0, 0f, count = 12, maxVisible = 7), 0.0001f)
        assertEquals(1f, PageControlGeometry.dotScale(11, 11f, count = 12, maxVisible = 7), 0.0001f)
        // Far off-window dots are hidden
        assertTrue(PageControlGeometry.dotScale(0, 5f, count = 12, maxVisible = 7) == 0f)
    }

    @Test
    fun `indexAt maps pointer x coordinate relative to center to clamped page index`() {
        val pitch = 16f
        assertEquals(0, PageControlGeometry.indexAt(-8f, position = 0f, count = 2, maxVisible = 7, pitch = pitch))
        assertEquals(1, PageControlGeometry.indexAt(8f, position = 0f, count = 2, maxVisible = 7, pitch = pitch))
        assertEquals(0, PageControlGeometry.indexAt(-500f, position = 0f, count = 5, maxVisible = 7, pitch = pitch))
        assertEquals(4, PageControlGeometry.indexAt(500f, position = 4f, count = 5, maxVisible = 7, pitch = pitch))
    }
}
