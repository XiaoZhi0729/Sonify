package yos.music.player.ui.widgets.basic

/**
 * Demo-compatible Rec.709 luma on gamma-encoded RGB (not WCAG linear luminance).
 * Transparent padding is not background; null keeps the previous valid sample.
 */
internal fun liquidBackdropLuminance(pixels: IntArray): Float? {
    if (pixels.isEmpty()) return null
    var total = 0.0
    for (argb in pixels) {
        if (argb ushr 24 == 0) continue
        val r = (argb ushr 16 and 0xFF) / 255.0
        val g = (argb ushr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        total += 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val samples = pixels.count { it ushr 24 != 0 }
    return if (samples == 0) null else (total / samples).toFloat()
}
