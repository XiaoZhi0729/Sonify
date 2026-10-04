package yos.music.player.code.utils.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pure timing and gain rules for crossfading two consecutive tracks. */
object CrossfadePolicy {
    val DurationOptionsSec = listOf(2, 3, 4, 5, 6, 8, 10)
    const val DEFAULT_DURATION_SEC = 5

    /** Time reserved for the next player to start rendering before the audible fade. */
    const val PREFETCH_LEAD_MS = 4_000L

    /** The audible fade may begin slightly before the overlap window opens. */
    const val START_TOLERANCE_MS = 200L
    const val WINDOW_MAX_RATIO = 1f / 3f
    const val WINDOW_MIN_MS = 1_000L
    const val MIN_RAMP_MS = 300L

    fun windowMs(durationMs: Long, wantSec: Int): Long? {
        if (wantSec <= 0) return null
        val want = wantSec.toLong() * 1_000L
        val cap = (durationMs * WINDOW_MAX_RATIO).toLong()
        return min(want, cap).takeIf { it >= WINDOW_MIN_MS }
    }

    fun shouldArm(remainingWallMs: Long, windowMs: Long): Boolean =
        remainingWallMs > 0 && remainingWallMs <= windowMs + PREFETCH_LEAD_MS

    /** The secondary always starts the next track at its exact beginning. */
    const val NEXT_TRACK_START_MS = 0L

    fun rampMs(remainingWallMs: Long): Long = remainingWallMs.coerceAtLeast(MIN_RAMP_MS)

    fun volumeOut(from: Float, t: Float): Float =
        (from * cos(t * (PI / 2)).toFloat()).coerceIn(0f, 1f)

    fun volumeIn(t: Float): Float =
        sin(t * (PI / 2)).toFloat().coerceIn(0f, 1f)

    fun volumeInTo(to: Float, t: Float): Float =
        (to * volumeIn(t)).coerceIn(0f, 1f)

    fun isEqualPower(t: Float): Boolean =
        abs(volumeOut(1f, t) * volumeOut(1f, t) + volumeIn(t) * volumeIn(t) - 1f) < 1e-4f

    /**
     * The next player must be buffered and ready before the fade may begin, and the fade only
     * starts once the overlap window is actually open — a ready secondary alone must not cut the
     * current track short.
     */
    fun crossfade(
        secondaryReady: Boolean,
        remainingWallMs: Long,
        elapsedWallMs: Long,
        windowMs: Long,
    ): CrossfadeDecision = when {
        remainingWallMs <= 0L -> CrossfadeDecision.AbortTooLate
        !secondaryReady && elapsedWallMs >= PREFETCH_LEAD_MS -> CrossfadeDecision.AbortNotRendering
        secondaryReady && remainingWallMs <= windowMs + START_TOLERANCE_MS -> CrossfadeDecision.Start
        else -> CrossfadeDecision.Wait
    }

    enum class CrossfadeDecision {
        Wait,
        Start,
        AbortTooLate,
        AbortNotRendering,
    }
}
