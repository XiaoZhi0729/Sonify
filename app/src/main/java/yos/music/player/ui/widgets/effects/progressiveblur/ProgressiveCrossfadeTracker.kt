package yos.music.player.ui.widgets.effects.progressiveblur

/**
 * Snapshot & blend decision for a single render frame of the progressive blur base pass.
 */
internal data class ProgressiveCrossfadeStep(
    val shouldSnapshotCurrentBase: Boolean,
    val effectiveMix: Float,
    val isBlending: Boolean,
)

/**
 * Pure JVM state machine driving Pass-0 crossfades inside [ProgressiveVideoGlRenderer].
 *
 * Keeps outgoing FBO snapshotting, monotonic progress clamping across rapid skips,
 * and clean cancellation on surface resets or layout/geometry changes deterministic.
 */
internal class ProgressiveCrossfadeTracker {

    private var hasValidBaseFrame: Boolean = false
    private var hasOutgoingSnapshot: Boolean = false
    private var activeCrossfadeEpoch: Long = Long.MIN_VALUE
    private var lastAppliedMix: Float = 1f

    fun onSurfaceReset() {
        hasValidBaseFrame = false
        hasOutgoingSnapshot = false
        lastAppliedMix = 1f
    }

    fun onBaseRendered() {
        hasValidBaseFrame = true
    }

    fun advance(
        epoch: Long,
        rawProgress: Float,
        geometryChanged: Boolean,
    ): ProgressiveCrossfadeStep {
        val clamped = rawProgress.coerceIn(0f, 1f)
        if (geometryChanged) {
            hasOutgoingSnapshot = false
        }
        var shouldSnapshot = false
        if (epoch != activeCrossfadeEpoch) {
            activeCrossfadeEpoch = epoch
            if (hasValidBaseFrame && !geometryChanged && clamped < 0.999f) {
                shouldSnapshot = true
                hasOutgoingSnapshot = true
                lastAppliedMix = clamped
            } else {
                hasOutgoingSnapshot = false
                lastAppliedMix = 1f
            }
        }
        if (!hasOutgoingSnapshot) {
            return ProgressiveCrossfadeStep(
                shouldSnapshotCurrentBase = false,
                effectiveMix = 1f,
                isBlending = false,
            )
        }
        // Keep progress monotonic within an epoch so a 1-frame snapTo(0f) race before the
        // next epoch commits cannot jump the current transition backward.
        val effective = maxOf(lastAppliedMix, clamped)
        lastAppliedMix = effective
        if (effective >= 0.999f) {
            hasOutgoingSnapshot = false
            return ProgressiveCrossfadeStep(
                shouldSnapshotCurrentBase = shouldSnapshot,
                effectiveMix = 1f,
                isBlending = false,
            )
        }
        return ProgressiveCrossfadeStep(
            shouldSnapshotCurrentBase = shouldSnapshot,
            effectiveMix = effective,
            isBlending = true,
        )
    }
}
