package yos.music.player.data.repositories

/** Background parsing cannot establish an account-wide playback failure. */
enum class PlaybackRequestPurpose(val canBlockPlayback: Boolean) {
    PLAYBACK(true),
    VISIBLE_PROBE(false),
    QUALITY_PROBE(false),
    CROSSFADE_PREFETCH(false),
    MANUAL_RECHECK(true)
}

internal class PlaybackAuthCooldown(private val nowMs: () -> Long) {
    private var version = -1L
    private var untilMs = 0L

    @Synchronized
    fun isActive(authVersion: Long): Boolean = version == authVersion && nowMs() < untilMs

    @Synchronized
    fun confirm(
        purpose: PlaybackRequestPurpose,
        requestVersion: Long,
        currentVersion: Long,
        durationMs: Long
    ): Boolean {
        if (!purpose.canBlockPlayback || requestVersion != currentVersion) return false
        if (isActive(currentVersion)) return false
        version = currentVersion
        untilMs = nowMs() + durationMs
        return true
    }

    @Synchronized
    fun clear() {
        version = -1L
        untilMs = 0L
    }
}
