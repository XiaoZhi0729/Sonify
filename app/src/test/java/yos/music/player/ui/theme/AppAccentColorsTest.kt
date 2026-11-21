package yos.music.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccentColorsTest {
    @Test
    fun allElevenApplePresetsHaveUniqueIdsAndExpectedColors() {
        assertEquals(11, AppAccentColors.Presets.size)
        assertEquals(11, AppAccentColors.Presets.map { it.id }.toSet().size)

        val expected = mapOf(
            "red" to (Color(0xFFFF3B30) to Color(0xFFFF453A)),
            "pink" to (Color(0xFFFF2D55) to Color(0xFFFF375F)),
            "orange" to (Color(0xFFFF9500) to Color(0xFFFF9F0A)),
            "yellow" to (Color(0xFFFFCC00) to Color(0xFFFFD60A)),
            "green" to (Color(0xFF34C759) to Color(0xFF30D158)),
            "mint" to (Color(0xFF00C7BE) to Color(0xFF63E6E2)),
            "teal" to (Color(0xFF30B0C7) to Color(0xFF40C8E0)),
            "cyan" to (Color(0xFF32ADE6) to Color(0xFF64D2FF)),
            "blue" to (Color(0xFF007AFF) to Color(0xFF0A84FF)),
            "indigo" to (Color(0xFF5856D6) to Color(0xFF5E5CE6)),
            "purple" to (Color(0xFFAF52DE) to Color(0xFFBF5AF2)),
        )
        for (preset in AppAccentColors.Presets) {
            val pair = expected[preset.id]
            assertNotNull("Missing expected preset ${preset.id}", pair)
            assertEquals(pair!!.first, preset.light)
            assertEquals(pair.second, preset.dark)
        }
    }

    @Test
    fun resolveHandlesPresetsCustomAndMalformedSpecs() {
        assertEquals(Color(0xFF007AFF), AppAccentColors.resolve("blue", darkTheme = false))
        assertEquals(Color(0xFF0A84FF), AppAccentColors.resolve("blue", darkTheme = true))

        assertEquals(Color(0xFF12ABEF), AppAccentColors.resolve("custom:#12abef", darkTheme = false))
        assertEquals(Color(0xFF12ABEF), AppAccentColors.resolve("custom:#12ABEF", darkTheme = true))

        assertEquals(Color(0xFFFF3B30), AppAccentColors.resolve("", darkTheme = false))
        assertEquals(Color(0xFFFF453A), AppAccentColors.resolve("unknown", darkTheme = true))
        assertEquals(Color(0xFFFF3B30), AppAccentColors.resolve("custom:not-hex", darkTheme = false))
        assertEquals(Color(0xFFFF453A), AppAccentColors.resolve("custom:#12345", darkTheme = true))
    }

    @Test
    fun encodeAndParseCustomColorForcesOpaque() {
        val translucent = Color(0x80336699)
        val encoded = AppAccentColors.encodeCustomColor(translucent)
        assertEquals("custom:#336699", encoded)

        val parsed = AppAccentColors.parseCustomColor(encoded)
        assertEquals(Color(0xFF336699), parsed)
        assertEquals(1f, parsed!!.alpha, 0f)
        assertNull(AppAccentColors.parseCustomColor("blue"))
        assertNull(AppAccentColors.parseHexColor("#GGGGGG"))
        assertNull(AppAccentColors.parseHexColor("1234567"))
    }

    @Test
    fun onPrimaryForSelectsLegibleForeground() {
        assertEquals(Color(0xFF1C1C1E), AppAccentColors.onPrimaryFor(Color(0xFFFFCC00))) // Light Yellow
        assertEquals(Color(0xFF1C1C1E), AppAccentColors.onPrimaryFor(Color(0xFFFFD60A))) // Dark Yellow
        assertEquals(Color(0xFF1C1C1E), AppAccentColors.onPrimaryFor(Color(0xFF63E6E2))) // Dark Mint
        assertEquals(Color(0xFF1C1C1E), AppAccentColors.onPrimaryFor(Color.White))

        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFFFF3B30))) // Red
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFFFF2D55))) // Pink
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFFFF9500))) // Orange
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFF34C759))) // Green
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFF007AFF))) // Blue
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFF5856D6))) // Indigo
        assertEquals(Color.White, AppAccentColors.onPrimaryFor(Color(0xFFAF52DE))) // Purple
    }

    @Test
    fun recentsDeduplicateMoveToFrontCapAtEightAndIgnoreMalformed() {
        val parsed = AppAccentColors.parseRecents("#112233,bad,#445566,#112233, ,#778899")
        assertEquals(listOf(Color(0xFF112233), Color(0xFF445566), Color(0xFF778899)), parsed)

        var raw = ""
        for (i in 1..10) {
            val c = Color((0xFF000000L or (i * 0x111111L)).toInt())
            raw = AppAccentColors.recordRecent(raw, c)
        }
        val list = AppAccentColors.parseRecents(raw)
        assertEquals(8, list.size)
        assertEquals(0xFFAAAAAA.toInt(), list.first().toArgb())
        assertEquals(0xFF333333.toInt(), list.last().toArgb())

        // Re-recording an existing entry moves it to front without duplicating
        val promoted = AppAccentColors.recordRecent(raw, Color(0x80555555))
        val afterPromote = AppAccentColors.parseRecents(promoted)
        assertEquals(8, afterPromote.size)
        assertEquals(0xFF555555.toInt(), afterPromote.first().toArgb())
        assertTrue(afterPromote.count { it.toArgb() == 0xFF555555.toInt() } == 1)
    }
}
