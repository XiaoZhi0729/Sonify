package yos.music.player.ui.widgets.effects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切歌过渡状态机的纯函数语义。
 *
 * 这里钉死的都是"看上去能跑、但一改就会出残留/闪烁"的规则：
 * 图层数量上限、同封面跳过、alpha 互补、全屏模式禁用缩放。
 */
class TrackArtworkTransitionTest {

    // ---------------------------------------------------------------- 图层表

    @Test
    fun `empty list starts single layer`() {
        assertEquals(listOf("A"), artworkLayersAfter(emptyList(), "A"))
    }

    @Test
    fun `same artwork keeps single layer`() {
        val current = listOf("A")
        assertEquals(current, artworkLayersAfter(current, "A"))
    }

    @Test
    fun `same artwork does not restart animation`() {
        // 同专辑相邻曲目：两个图层表完全相等，调用方据此跳过 animateTo。
        val current = listOf("A", "B")
        assertEquals(current, artworkLayersAfter(current, "B"))
    }

    @Test
    fun `new artwork appends old last and target`() {
        assertEquals(listOf("A", "B"), artworkLayersAfter(listOf("A"), "B"))
    }

    @Test
    fun `rapid switch drops the older layer`() {
        // [A,B] 过渡中又切到 C：A 直接丢弃，只留 [B,C]——任何时刻最多两张。
        assertEquals(listOf("B", "C"), artworkLayersAfter(listOf("A", "B"), "C"))
    }

    @Test
    fun `never exceeds two layers`() {
        var layers = emptyList<Any?>()
        listOf("A", "B", "C", "D", "E").forEach { layers = artworkLayersAfter(layers, it) }
        assertEquals(2, layers.size)
    }

    @Test
    fun `null artwork is a legal layer`() {
        // 无封面的歌曲：模型层不做特殊处理，渲染面按占位图绘制。
        assertEquals(listOf(null, "B"), artworkLayersAfter(listOf<Any?>(null), "B"))
        assertEquals(listOf("A", null), artworkLayersAfter(listOf("A"), null))
    }

    @Test
    fun `null to null is a single layer`() {
        val current = listOf<Any?>(null)
        assertEquals(current, artworkLayersAfter(current, null))
    }

    // ---------------------------------------------------------------- alpha

    @Test
    fun `single layer is fully opaque`() {
        assertEquals(1f, artworkLayerAlpha(0, 1, 0f), 0f)
        assertEquals(1f, artworkLayerAlpha(0, 1, 1f), 0f)
    }

    @Test
    fun `two layers are complementary`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val outgoing = artworkLayerAlpha(0, 2, p)
            val incoming = artworkLayerAlpha(1, 2, p)
            assertEquals(1f, outgoing + incoming, 1e-6f)
        }
    }

    @Test
    fun `incoming wins at the end`() {
        assertEquals(0f, artworkLayerAlpha(0, 2, 1f), 0f)
        assertEquals(1f, artworkLayerAlpha(1, 2, 1f), 0f)
    }

    @Test
    fun `outgoing wins at the start`() {
        assertEquals(1f, artworkLayerAlpha(0, 2, 0f), 0f)
        assertEquals(0f, artworkLayerAlpha(1, 2, 0f), 0f)
    }

    // ---------------------------------------------------------------- scale

    @Test
    fun `scale disabled keeps both layers at one`() {
        for (p in listOf(0f, 0.5f, 1f)) {
            assertEquals(1f, artworkLayerScale(0, 2, p, enabled = false), 0f)
            assertEquals(1f, artworkLayerScale(1, 2, p, enabled = false), 0f)
        }
    }

    @Test
    fun `incoming scales down to one`() {
        assertEquals(TrackArtworkIncomingStartScale, artworkLayerScale(1, 2, 0f, true), 1e-6f)
        assertEquals(1f, artworkLayerScale(1, 2, 1f, true), 1e-6f)
    }

    @Test
    fun `outgoing shrinks to the end scale`() {
        assertEquals(1f, artworkLayerScale(0, 2, 0f, true), 1e-6f)
        assertEquals(TrackArtworkOutgoingEndScale, artworkLayerScale(0, 2, 1f, true), 1e-6f)
    }

    @Test
    fun `scales are monotonic`() {
        // 旧图 1.0→0.97、新图 1.025→1.0，两者都是**持续收缩**：中途不反弹，
        // 也不会有"先放大再缩小"的方向反转，否则观感是封面抖了一下。
        var previousOutgoing = 2f
        var previousIncoming = 2f
        for (step in 0..10) {
            val p = step / 10f
            val outgoing = artworkLayerScale(0, 2, p, true)
            val incoming = artworkLayerScale(1, 2, p, true)
            assert(outgoing <= previousOutgoing) { "outgoing grew at p=$p" }
            assert(incoming <= previousIncoming) { "incoming grew at p=$p" }
            previousOutgoing = outgoing
            previousIncoming = incoming
        }
    }

    @Test
    fun `single layer never scales`() {
        assertEquals(1f, artworkLayerScale(0, 1, 0f, enabled = true), 0f)
        assertEquals(1f, artworkLayerScale(0, 1, 0.5f, enabled = true), 0f)
    }

    // ---------------------------------------------------------------- 背景

    @Test
    fun `background keeps the old layer opaque`() {
        // 背景只淡入新图：新图没就绪时旧图仍不透明，不会露出下面的黑底。
        assertEquals(1f, backgroundLayerAlpha(0, 2, 0.5f), 0f)
        assertEquals(0.5f, backgroundLayerAlpha(1, 2, 0.5f), 0f)
    }

    @Test
    fun `background single layer is opaque`() {
        assertEquals(1f, backgroundLayerAlpha(0, 1, 0f), 0f)
    }

    // ---------------------------------------------------------------- 常量

    @Test
    fun `timeline constants are shared and sane`() {
        assertEquals(TrackArtworkCrossfadeMs, TrackArtworkCrossfadeSpec.durationMillis)
        assert(TrackArtworkCrossfadeMs in 300..900) { "过渡时长超出可接受区间" }
        assert(TrackArtworkIncomingStartScale > 1f)
        assert(TrackArtworkOutgoingEndScale < 1f)
        // 缩放幅度必须克制：这是"辅助"，不是"漂浮"。
        assert(TrackArtworkIncomingStartScale - 1f <= 0.05f)
        assert(1f - TrackArtworkOutgoingEndScale <= 0.05f)
    }
}
