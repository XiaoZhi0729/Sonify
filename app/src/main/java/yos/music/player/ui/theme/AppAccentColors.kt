package yos.music.player.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.Locale
import yos.music.player.R

@Immutable
data class AccentColorPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val light: Color,
    val dark: Color,
)

object AppAccentColors {
    const val DefaultId: String = "red"
    const val CustomPrefix: String = "custom:"
    const val MaxRecents: Int = 8

    val Presets: List<AccentColorPreset> = listOf(
        AccentColorPreset(
            id = "red",
            nameRes = R.string.accent_color_red,
            light = Color(0xFFFF3B30),
            dark = Color(0xFFFF453A),
        ),
        AccentColorPreset(
            id = "pink",
            nameRes = R.string.accent_color_pink,
            light = Color(0xFFFF2D55),
            dark = Color(0xFFFF375F),
        ),
        AccentColorPreset(
            id = "orange",
            nameRes = R.string.accent_color_orange,
            light = Color(0xFFFF9500),
            dark = Color(0xFFFF9F0A),
        ),
        AccentColorPreset(
            id = "yellow",
            nameRes = R.string.accent_color_yellow,
            light = Color(0xFFFFCC00),
            dark = Color(0xFFFFD60A),
        ),
        AccentColorPreset(
            id = "green",
            nameRes = R.string.accent_color_green,
            light = Color(0xFF34C759),
            dark = Color(0xFF30D158),
        ),
        AccentColorPreset(
            id = "mint",
            nameRes = R.string.accent_color_mint,
            light = Color(0xFF00C7BE),
            dark = Color(0xFF63E6E2),
        ),
        AccentColorPreset(
            id = "teal",
            nameRes = R.string.accent_color_teal,
            light = Color(0xFF30B0C7),
            dark = Color(0xFF40C8E0),
        ),
        AccentColorPreset(
            id = "cyan",
            nameRes = R.string.accent_color_cyan,
            light = Color(0xFF32ADE6),
            dark = Color(0xFF64D2FF),
        ),
        AccentColorPreset(
            id = "blue",
            nameRes = R.string.accent_color_blue,
            light = Color(0xFF007AFF),
            dark = Color(0xFF0A84FF),
        ),
        AccentColorPreset(
            id = "indigo",
            nameRes = R.string.accent_color_indigo,
            light = Color(0xFF5856D6),
            dark = Color(0xFF5E5CE6),
        ),
        AccentColorPreset(
            id = "purple",
            nameRes = R.string.accent_color_purple,
            light = Color(0xFFAF52DE),
            dark = Color(0xFFBF5AF2),
        ),
    )

    val Default: AccentColorPreset = Presets.first()

    fun findPreset(spec: String): AccentColorPreset? = Presets.firstOrNull { it.id == spec }

    fun parseHexColor(hex: String): Color? {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return null
        for (ch in clean) {
            val isHex = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            if (!isHex) return null
        }
        val rgb = clean.toIntOrNull(16) ?: return null
        return Color(0xFF000000L or rgb.toLong())
    }

    fun parseCustomColor(spec: String): Color? {
        if (!spec.startsWith(CustomPrefix)) return null
        return parseHexColor(spec.removePrefix(CustomPrefix))
    }

    fun formatHex(color: Color): String {
        val rgb = color.toArgb() and 0xFFFFFF
        return String.format(Locale.ROOT, "#%06X", rgb)
    }

    fun encodeCustomColor(color: Color): String = "$CustomPrefix${formatHex(color)}"

    fun resolve(spec: String, darkTheme: Boolean): Color {
        findPreset(spec)?.let { preset ->
            return if (darkTheme) preset.dark else preset.light
        }
        parseCustomColor(spec)?.let { custom ->
            return custom
        }
        return if (darkTheme) Default.dark else Default.light
    }

    fun onPrimaryFor(primary: Color): Color =
        if (primary.luminance() > 0.58f) Color(0xFF1C1C1E) else Color.White

    fun parseRecents(raw: String): List<Color> {
        if (raw.isBlank()) return emptyList()
        val seen = LinkedHashSet<Int>(MaxRecents)
        val out = ArrayList<Color>(MaxRecents)
        for (token in raw.split(',')) {
            val parsed = parseHexColor(token) ?: continue
            if (seen.add(parsed.toArgb())) {
                out.add(parsed)
                if (out.size >= MaxRecents) break
            }
        }
        return out
    }

    fun recordRecent(raw: String, color: Color, maxCount: Int = MaxRecents): String {
        if (maxCount <= 0) return ""
        val normalized = formatHex(color)
        val opaque = parseHexColor(normalized) ?: return ""
        val merged = ArrayList<String>(maxCount)
        merged.add(normalized)
        for (existing in parseRecents(raw)) {
            if (merged.size >= maxCount) break
            if (existing.toArgb() != opaque.toArgb()) {
                merged.add(formatHex(existing))
            }
        }
        return merged.joinToString(",")
    }
}
