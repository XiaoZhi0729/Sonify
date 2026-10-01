package yos.music.player.ui.widgets.basic

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max

/** The smallest square cover size used by discovery card rails and grids. */
val DiscoveryMinImageSize = 150.dp

/**
 * Calculates a card size for a horizontal rail without allowing the cover to
 * become smaller than [minImageSize]. The available width is divided into the
 * largest possible number of cards, then the remaining space is shared evenly.
 */
data class AdaptiveImageLayout(
    val itemCount: Int,
    val itemSize: Dp,
    val pageSize: Dp
)

fun calculateAdaptiveImageLayout(
    containerWidth: Dp,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    minImageSize: Dp = DiscoveryMinImageSize,
    spacing: Dp = 10.dp
): AdaptiveImageLayout {
    val safeMinImageSize = minImageSize.coerceAtLeast(1.dp)
    val safeSpacing = spacing.coerceAtLeast(0.dp)
    val availableWidth = (containerWidth - startPadding - endPadding).coerceAtLeast(0.dp)
    val itemCount = max(
        1,
        floor((availableWidth.value + safeSpacing.value) / (safeMinImageSize.value + safeSpacing.value))
            .toInt()
    )
    val itemSize = if (itemCount == 1) {
        max(availableWidth.value, safeMinImageSize.value).dp
    } else {
        ((availableWidth.value - (itemCount - 1) * safeSpacing.value) / itemCount)
            .coerceAtLeast(safeMinImageSize.value)
            .dp
    }
    return AdaptiveImageLayout(
        itemCount = itemCount,
        itemSize = itemSize,
        pageSize = itemSize + safeSpacing
    )
}
