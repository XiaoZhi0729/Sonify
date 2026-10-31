package yos.music.player.ui.pages.library.playlists

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作行几何测试。
 *
 * 搜索框折叠态必须**精确**落在操作行右侧那颗圆钮上（两者在 UI 上本就是同一个东西，
 * 位置差一点就能看出来），所以这套算式要和 CompactPlayActionRow 的排布严格一致。
 */
class PlayActionGeometryTest {

    /** 常见手机内容宽度：360dp 屏 − 头部左右各 18dp = 324dp。 */
    private val phoneContentWidth = 324.dp

    @Test
    fun `pill width matches CompactPlayActionRow formula`() {
        val g = PlayActionGeometry(phoneContentWidth)
        // remaining = 324 − 42 − 13 − (42+13) = 214；×2/3 ≈ 142.67 > 140，不触发下限
        val remaining = 324f - 42f - 13f - (42f + 13f)
        assertEquals(remaining * 2f / 3f, g.pillWidth.value, 0.01f)
    }

    @Test
    fun `pill width is clamped to minimum on narrow screens`() {
        // 320dp 屏 − 36dp padding = 284dp 内容宽：remaining=174，×2/3=116 < 140 → 钳到 140
        val g = PlayActionGeometry(284.dp)
        assertEquals(140f, g.pillWidth.value, 0.01f)
    }

    @Test
    fun `trailing button sits at the right edge of the centered row`() {
        val g = PlayActionGeometry(phoneContentWidth)
        val rowWidth = 42f + 13f + g.pillWidth.value + (42f + 13f)
        val expectedRowStart = (324f - rowWidth) / 2f
        // 尾部圆钮左缘 = 行首 + 随机钮 + 间距 + 胶囊 + 间距
        val expectedLeft = expectedRowStart + 42f + 13f + g.pillWidth.value + 13f
        assertEquals(expectedLeft, g.trailingLeft.value, 0.01f)
        // 中心 = 左缘 + 半钮
        assertEquals(expectedLeft + 21f, g.trailingCenterX.value, 0.01f)
    }

    @Test
    fun `trailing button right edge aligns with row right edge`() {
        val g = PlayActionGeometry(phoneContentWidth)
        val rowWidth = 42f + 13f + g.pillWidth.value + (42f + 13f)
        val rowStart = (324f - rowWidth) / 2f
        val rowRight = rowStart + rowWidth
        val trailingRight = g.trailingLeft.value + 42f
        assertEquals(rowRight, trailingRight, 0.01f)
    }

    @Test
    fun `expanded width fills the whole action row`() {
        val g = PlayActionGeometry(phoneContentWidth)
        assertEquals(324f, g.expandedWidth.value, 0.01f)
    }

    @Test
    fun `expanded field is wider than collapsed button`() {
        val g = PlayActionGeometry(phoneContentWidth)
        assertTrue(
            "展开宽度必须明显大于 42dp 圆钮",
            g.expandedWidth > g.buttonSize * 3
        )
    }

    @Test
    fun `trailing center stays inside the row`() {
        listOf(284.dp, 324.dp, 500.dp, 900.dp).forEach { width ->
            val g = PlayActionGeometry(width)
            assertTrue(
                "width=$width 时尾部圆钮中心 ${g.trailingCenterX} 跑出了容器",
                g.trailingCenterX.value in 0f..width.value
            )
        }
    }

    @Test
    fun `narrow screen may push row start negative, matching real layout`() {
        // 极窄（200dp）：remaining 变负，胶囊钳到 140，整行比容器宽 → 行首为负。
        // 这是 CompactPlayActionRow 的真实行为（Arrangement.Center 会溢出居中），
        // 搜索框起点必须跟着一致，不能自作主张 clamp 到 0。
        val g = PlayActionGeometry(200.dp)
        val rowWidth = 42f + 13f + g.pillWidth.value + (42f + 13f)
        val rowStart = (200f - rowWidth) / 2f
        assertTrue("极窄屏行首应为负", rowStart < 0f)
        assertEquals(
            rowStart + 42f + 13f + g.pillWidth.value + 13f,
            g.trailingLeft.value,
            0.01f
        )
    }

    @Test
    fun `tablet width keeps the same formula`() {
        // 平板内容宽：1000dp 屏 − 36dp = 964dp
        val g = PlayActionGeometry(964.dp)
        val remaining = 964f - 42f - 13f - (42f + 13f)
        assertEquals(remaining * 2f / 3f, g.pillWidth.value, 0.01f)
        assertEquals(964f, g.expandedWidth.value, 0.01f)
    }
}
