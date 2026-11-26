package yos.music.player.data.objects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendMaskCacheCodecTest {

    @Test
    fun roundTrip4x4Pixels() {
        val pixels = IntArray(16) { idx -> (0xFF shl 24) or (idx * 0x110101) }
        val encoded = encodeMaskPixels(4, 4, pixels)
        val decoded = decodeMaskPixels(encoded)
        assertEquals(4, decoded?.width)
        assertEquals(4, decoded?.height)
        assertArrayEquals(pixels, decoded?.pixels)
    }

    @Test
    fun roundTrip7x7Pixels() {
        val pixels = IntArray(49) { idx -> (0xAA shl 24) or (idx * 0x050307) }
        val encoded = encodeMaskPixels(7, 7, pixels)
        val decoded = decodeMaskPixels(encoded)
        assertEquals(7, decoded?.width)
        assertEquals(7, decoded?.height)
        assertArrayEquals(pixels, decoded?.pixels)
    }

    @Test
    fun rejectCorruptAndOutOfBoundsPayloads() {
        assertNull(decodeMaskPixels(byteArrayOf()))
        assertNull(decodeMaskPixels(byteArrayOf(1, 2, 3, 4)))
        // width = 0, height = 4
        assertNull(decodeMaskPixels(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 4)))
        // width = 33 (> 32), height = 1
        assertNull(decodeMaskPixels(ByteArray(8 + 33 * 4).apply {
            this[3] = 33
            this[7] = 1
        }))
        // truncated pixel payload for 4x4
        val valid = encodeMaskPixels(4, 4, IntArray(16) { 0xFF112233.toInt() })
        assertNull(decodeMaskPixels(valid.copyOf(valid.size - 1)))
    }

    @Test
    fun keyOfIgnoresNullAndBlank() {
        assertNull(RecommendMaskCache.keyOf(null))
        assertNull(RecommendMaskCache.keyOf(""))
        assertNull(RecommendMaskCache.keyOf("   "))
        assertEquals("https://img.example.com/a.jpg", RecommendMaskCache.keyOf("https://img.example.com/a.jpg"))
    }
}
