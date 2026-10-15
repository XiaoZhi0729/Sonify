package yos.music.player.ui.navigation

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * 无照片歌手的身份派生色契约：同一 seed 恒得同色、不同 seed 色相散开、
 * 明度落在可读区间且深浅主题单调区分、绝不与「空白兜底色」撞色。
 *
 * 后一条是本 bug 的回归闸：白板问题的根因就是无图时底色恒等于主题兜底
 * 0xFFF2F2F4 / 0xFF1B1B1D，hero 与页面无分界；派生色必须真的与它们区分开。
 */
class ArtistIdentityColorTest {

    /** ARGB int → (hue°, saturation, value)；色相 0–360，饱和/明度 0–1。 */
    private fun hsvOf(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val hue = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple(((hue % 360f) + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    @Test
    fun sameSeedIsStableAcrossCalls() {
        assertEquals(artistIdentityRgb("12345", false), artistIdentityRgb("12345", false))
        assertEquals(artistIdentityRgb("12345", true), artistIdentityRgb("12345", true))
        // 跨实例同样稳定（String.hashCode 由规范固定）
        assertEquals(artistIdentityRgb("Adéla Václavova", false), artistIdentityRgb("Adéla Václavova", false))
    }

    @Test
    fun huesSpreadAcrossManyBuckets() {
        // 相邻数值 seed（真实 artistId 即数值串）不得落在同一色相上
        val buckets = (0 until 200).map { (hsvOf(artistIdentityRgb("$it", false)).first / 30f).toInt() }.toSet()
        assertTrue("色相聚集，实际桶数=${buckets.size}", buckets.size >= 12)
    }

    @Test
    fun valueStaysInReadableRange() {
        // 8bit 量化容差：HSV 浮点经 255 取整会有一档偏移
        val eps = 2f / 255f
        for (i in 0 until 100) {
            val light = hsvOf(artistIdentityRgb("$i", false)).third
            assertTrue("浅色明度越界 $light", light in (0.44f - eps)..(0.53f + eps))
            val dark = hsvOf(artistIdentityRgb("$i", true)).third
            assertTrue("深色明度越界 $dark", dark in (0.30f - eps)..(0.39f + eps))
            assertTrue("深色不暗于浅色", dark < light)
        }
    }

    @Test
    fun saturationStaysInTunableRange() {
        val eps = 2f / 255f
        for (i in 0 until 100) {
            val s = hsvOf(artistIdentityRgb("$i", false)).second
            assertTrue("饱和度越界 $s", s in (0.34f - eps)..(0.51f + eps))
        }
    }

    @Test
    fun neverCollidesWithBlankFallbackColor() {
        // 本 bug 的核心回归：派生色必须与主题兜底白/黑可辨（否则仍是白板）
        for (i in 0 until 100) {
            assertNotEquals(0xFFF2F2F4.toInt(), artistIdentityRgb("$i", false))
            assertNotEquals(0xFF1B1B1D.toInt(), artistIdentityRgb("$i", true))
        }
    }

    @Test
    fun blankSeedDoesNotThrow() {
        artistIdentityRgb("", false)
        artistIdentityRgb("", true)
    }

    @Test
    fun identityColorFeedsArtworkColorsWithReadableContent() {
        val light = artistIdentityRgb("98765", false)
        val colors = ArtistArtworkColors.fromRgb(light, false)
        assertEquals(Color(light), colors.accent)
        // 派生色为实色，content 与 accent 必然有别（文字不会与底色同色隐形）
        assertNotEquals(colors.accent, colors.content)
    }
}
