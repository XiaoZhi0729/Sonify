package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothSessionTitlesTest {

    @Test
    fun `artist 拼接歌手与歌名`() {
        assertEquals(
            "ハンバート ハンバート - 午睡",
            BluetoothSessionTitles.artistFor("午睡", "ハンバート ハンバート", "眠れない夜を")
        )
    }

    @Test
    fun `歌名缺失时不留分隔符尾巴`() {
        assertEquals("周杰伦", BluetoothSessionTitles.artistFor(null, "周杰伦", "一行歌词"))
        assertEquals("周杰伦", BluetoothSessionTitles.artistFor("   ", "周杰伦", "一行歌词"))
    }

    @Test
    fun `歌手缺失时退化为歌名`() {
        assertEquals("午睡", BluetoothSessionTitles.artistFor("午睡", null, "一行歌词"))
        assertEquals("午睡", BluetoothSessionTitles.artistFor("午睡", "", "一行歌词"))
    }

    @Test
    fun `标题与歌手都缺失时不写 artist`() {
        assertNull(BluetoothSessionTitles.artistFor(null, null, "一行歌词"))
        assertNull(BluetoothSessionTitles.artistFor("", "  ", "一行歌词"))
    }

    @Test
    fun `歌词行替换标题`() {
        assertEquals("眠れない夜を", BluetoothSessionTitles.titleFor("眠れない夜を"))
        // 首尾空白会被裁掉：车机标题栏不该出现多余缩进
        assertEquals("眠れない夜を", BluetoothSessionTitles.titleFor("  眠れない夜を "))
    }

    @Test
    fun `空歌词行不当作有效标题`() {
        // 空行必须当"没有歌词"，否则车机标题栏会变成空白
        assertNull(BluetoothSessionTitles.titleFor(null))
        assertNull(BluetoothSessionTitles.titleFor(""))
        assertNull(BluetoothSessionTitles.titleFor("    "))
    }

    @Test
    fun `无歌词行时不给 artist 改写目标`() {
        // null 代表"不要改这个字段"：功能关闭时标题必须保持原样
        assertNull(BluetoothSessionTitles.artistFor("午睡", "ハンバート", null))
    }
}
