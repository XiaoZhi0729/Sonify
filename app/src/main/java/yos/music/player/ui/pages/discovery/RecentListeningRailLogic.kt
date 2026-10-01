package yos.music.player.ui.pages.discovery

/**
 * Describes how the horizontal recent-list rail should react to a history update.
 * The old head index is used as the temporary anchor before revealing the new head.
 */
internal sealed interface RecentListeningUpdatePlan {
    data object KeepViewport : RecentListeningUpdatePlan
    data class RevealHead(val oldHeadIndex: Int) : RecentListeningUpdatePlan
}

internal fun planRecentListeningUpdate(
    previousKeys: List<String>,
    nextKeys: List<String>,
    wasAtStart: Boolean
): RecentListeningUpdatePlan {
    if (previousKeys == nextKeys || previousKeys.isEmpty() || nextKeys.isEmpty()) {
        return RecentListeningUpdatePlan.KeepViewport
    }

    if (!wasAtStart) return RecentListeningUpdatePlan.KeepViewport

    val previousHead = previousKeys.first()
    val oldHeadIndex = nextKeys.indexOf(previousHead)
    return if (oldHeadIndex > 0) {
        RecentListeningUpdatePlan.RevealHead(oldHeadIndex)
    } else {
        RecentListeningUpdatePlan.KeepViewport
    }
}
