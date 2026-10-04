package yos.music.player.ui.widgets.basic

internal data class CollapseUi(val alpha: Float, val bar: Boolean, val small: Boolean)

internal fun calculateTitleCollapse(
    firstItemInfo: Pair<Int, Int>?,
    isLayoutReady: Boolean,
    extraPx: Int,
    statusPx: Int
): CollapseUi {
    // Missing title geometry also occurs before the first lazy layout measurement.
    if (!isLayoutReady) return CollapseUi(alpha = 1f, bar = false, small = false)
    val info = firstItemInfo ?: return CollapseUi(alpha = 0f, bar = true, small = true)
    val zone = (extraPx + info.second - statusPx).coerceAtLeast(1)
    val raw = -info.first.toFloat() / zone
    return when {
        raw <= 0f -> CollapseUi(alpha = 1f, bar = false, small = false)
        raw >= 1f -> CollapseUi(alpha = 0f, bar = true, small = true)
        else -> {
            val edge = (extraPx.toFloat() / zone).coerceIn(0f, 1f)
            val fade = ((raw - edge) / (1f - edge).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            CollapseUi(alpha = 1f - fade, bar = true, small = false)
        }
    }
}
