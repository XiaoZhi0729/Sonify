package yos.music.player.ui.widgets.mochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiActivityIndicatorTest {
    @Test
    fun spokeAlphaSegmentsMatchIosCurve() {
        assertEquals(RestAlpha, spokeAlpha(0f), 1e-6f)
        assertEquals(1f, spokeAlpha(0.1f), 1e-6f)
        assertEquals(RestAlpha, spokeAlpha(0.5f), 1e-6f)
        assertEquals(RestAlpha, spokeAlpha(0.99f), 1e-6f)
        // 亮起快、暗回慢：5% 处在上升段，30% 处在下降段
        assertTrue(spokeAlpha(0.05f) in RestAlpha..1f)
        assertTrue(spokeAlpha(0.3f) in RestAlpha..1f)
    }

    @Test
    fun adjacentSpokesAreOffsetByOneEighthCycle() {
        val phase = 0.37f
        val offsets = (0 until Spokes).map { k -> (phase - k.toFloat() / Spokes + 1f) % 1f }
        for (k in 1 until Spokes) {
            assertEquals(1f / Spokes, (offsets[k - 1] - offsets[k] + 1f) % 1f, 1e-5f)
        }
    }
}
