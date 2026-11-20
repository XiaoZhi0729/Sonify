package yos.music.player.code.utils.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 歌词排版缓存键：UI 组合与后台预热共用同一函数，键相同才允许互换排版结果。
 * 这里锁住「哪些输入必须让键变化」——漏一个就会出现缓存命中却几何不符的排版。
 */
class LyricRenderKeyTest {

    private fun key(
        mediaIdKey: String = "kugou-online-abc",
        width: Float = 899.4f,
        subTextSize: Int = 18,
        systemFontScale: Float = 1f,
        entriesSize: Int = 42,
        fontScale: Float = 1f,
        fontWeight: String = "ExtraBold",
        lineBalance: Boolean = false,
        customFontPath: String = "",
    ) = lyricRenderKey(
        mediaIdKey = mediaIdKey,
        layoutWidthDp = width,
        subTextSize = subTextSize,
        systemFontScale = systemFontScale,
        entriesSize = entriesSize,
        fontScaleSetting = fontScale,
        fontWeightSetting = fontWeight,
        lineBalance = lineBalance,
        customFontPath = customFontPath,
    )

    @Test
    fun sameInputsProduceSameKey() {
        assertEquals(key(), key())
    }

    @Test
    fun widthIsPartOfKey() {
        assertNotEquals(key(width = 899.4f), key(width = 700f))
    }

    @Test
    fun estimateVersusMeasuredWidthStillMatches() {
        // 预热侧宽度来自屏宽推算、UI 侧来自实际测量：差一点点也要命中
        assertEquals(key(width = 899.4f), key(width = 899.0f))
    }

    @Test
    fun subPixelWidthDriftDoesNotInvalidate() {
        // 排版宽度按 dp 取整后比较：浮点抖动不该让预热结果作废
        assertEquals(key(width = 899.4f), key(width = 899.1f))
        assertNotEquals(key(width = 899.4f), key(width = 900.6f))
    }

    @Test
    fun fontScaleSettingIsPartOfKey() {
        assertNotEquals(key(fontScale = 1f), key(fontScale = 1.2f))
    }

    @Test
    fun fontWeightSettingIsPartOfKey() {
        assertNotEquals(key(fontWeight = "ExtraBold"), key(fontWeight = "Regular"))
    }

    @Test
    fun lineBalanceIsPartOfKey() {
        // 行平衡开关改变断行策略 → 必须重排
        assertNotEquals(key(lineBalance = false), key(lineBalance = true))
    }

    @Test
    fun systemFontScaleIsPartOfKey() {
        assertNotEquals(key(systemFontScale = 1f), key(systemFontScale = 1.15f))
    }

    @Test
    fun customFontPathIsPartOfKey() {
        assertNotEquals(key(customFontPath = ""), key(customFontPath = "/data/user/0/app/files/lyric_fonts/a.ttf"))
    }

    @Test
    fun differentSongOrLineCountIsNotInterchangeable() {
        assertNotEquals(key(mediaIdKey = "kugou-online-a"), key(mediaIdKey = "kugou-online-b"))
        assertNotEquals(key(entriesSize = 42), key(entriesSize = 43))
    }

    @Test
    fun listStateKeyIgnoresTypography() {
        // 滚动状态键只有「歌曲 + 栏宽」两个维度：改字号/字重/行平衡时键不变，
        // 列表状态才不会被重建（否则歌词会跳回顶部重滚）。
        val base = lyricListStateKey("kugou-online-abc", 899.4f)
        assertEquals(base, lyricListStateKey("kugou-online-abc", 899.0f))
        // 换歌、换栏宽（竖屏整页 vs 横屏右栏）必须分开
        assertNotEquals(base, lyricListStateKey("kugou-online-def", 899.4f))
        assertNotEquals(base, lyricListStateKey("kugou-online-abc", 400f))
    }

    @Test
    fun translationFontSizeScalesProportionallyFrom100Percent() {
        assertEquals(18f, scaledLyricTranslationFontSizeSp(18, 1.0f), 0.001f)
        assertEquals(14.4f, scaledLyricTranslationFontSizeSp(18, 0.8f), 0.001f)
        assertEquals(21.6f, scaledLyricTranslationFontSizeSp(18, 1.2f), 0.001f)
        // 越界比例应被夹紧在 0.8f..1.2f
        assertEquals(14.4f, scaledLyricTranslationFontSizeSp(18, 0.5f), 0.001f)
        assertEquals(21.6f, scaledLyricTranslationFontSizeSp(18, 1.5f), 0.001f)
    }
}
