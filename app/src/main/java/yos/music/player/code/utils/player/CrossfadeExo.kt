package yos.music.player.code.utils.player

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary

/**
 * Automatic crossfade between consecutive tracks.
 *
 * The primary player remains the MediaSession and UI authority. The secondary player only
 * preloads the next item from position zero and supplies its audio during the overlap. No player
 * ever seeks the old track backwards, which prevents an audible replay at the seam.
 */
@OptIn(UnstableApi::class)
object CrossfadeExo {
    private enum class Phase { IDLE, PREFETCH, CROSSFADE }

    private const val TICK_MS = 150L
    private const val MAX_TICK_FAILURES = 8
    private const val SONG_RETRY_COOLDOWN_MS = 30_000L
    private const val SELF_ADVANCE_GRACE_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private var primary: ExoPlayer? = null
    private var secondary: ExoPlayer? = null
    private var secondaryBuilder: (() -> ExoPlayer)? = null

    private var phase = Phase.IDLE
    private var startIndex = C.INDEX_UNSET
    private var targetIndex = C.INDEX_UNSET
    private var windowMs = 0L
    private var rampEndsAt = 0L
    private var rampStartedAt = 0L
    private var expectedAdvanceMediaId: String? = null
    private var expectedAdvanceAt = 0L
    private var tickFailures = 0
    private var disarmedIndex = C.INDEX_UNSET
    private var disarmedUntilMs = 0L

    @Volatile
    private var armingSuspended = false

    fun attach(primary: ExoPlayer, secondaryBuilder: () -> ExoPlayer) {
        this.primary?.removeListener(primaryListener)
        this.primary = primary
        this.secondaryBuilder = secondaryBuilder
        primary.addListener(primaryListener)
    }

    fun start() {
        handler.removeCallbacks(tickRunnable)
        tickFailures = 0
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        rampAnim?.cancel()
        rampAnim = null
        primary?.removeListener(primaryListener)
        primary = null
        secondaryBuilder = null
        runCatching { secondary?.release() }
        secondary = null
        armingSuspended = false
        disarmedIndex = C.INDEX_UNSET
        disarmedUntilMs = 0L
        toIdle()
    }

    fun abort(reason: String) {
        if (phase == Phase.IDLE) return
        if (Looper.myLooper() == Looper.getMainLooper()) abortNow(reason)
        else handler.post { abortNow(reason) }
    }

    private fun abortNow(reason: String) {
        if (phase == Phase.IDLE) return
        YosDiagnostics.log("XFADE_ABORT", "why" to reason, "ph" to phase.name)
        if (phase == Phase.PREFETCH) {
            primary?.let {
                disarmedIndex = it.currentMediaItemIndex
                disarmedUntilMs = SystemClock.elapsedRealtime() + SONG_RETRY_COOLDOWN_MS
            }
        }
        handBackToPrimary()
    }

    fun suspend(reason: String) {
        if (armingSuspended) return
        armingSuspended = true
        YosDiagnostics.log("XFADE_SUSPEND", "why" to reason)
        abort("suspended_$reason")
    }

    fun resume(reason: String) {
        if (!armingSuspended) return
        armingSuspended = false
        YosDiagnostics.log("XFADE_RESUME", "why" to reason)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TICK_MS)
            runCatching { step() }.onSuccess { tickFailures = 0 }.onFailure { error ->
                if (tickFailures == 0) {
                    YosDiagnostics.log("XFADE_TICK_FAIL", "err" to error.toString())
                }
                if (++tickFailures >= MAX_TICK_FAILURES) {
                    handler.removeCallbacks(this)
                    abortNow("tick_disabled")
                    YosDiagnostics.log("XFADE_DISABLED", "after" to tickFailures)
                }
            }
        }
    }

    private fun step() {
        val p = primary ?: return
        if (phase != Phase.IDLE && !SettingsLibrary.CrossfadeEnabled) {
            abort("setting_off")
            return
        }
        if (phase == Phase.IDLE && SettingsLibrary.CrossfadeEnabled && p.isPlaying) {
            ensureSecondary()
        }
        when (phase) {
            Phase.IDLE -> armIfDue(p)
            Phase.PREFETCH -> {
                val s = ensureSecondary()
                if (s == null) abort("no_secondary") else prefetchStep(p, s)
            }
            Phase.CROSSFADE -> {
                val s = secondary
                if (s == null) abort("secondary_gone") else crossfadeStep(p, s)
            }
        }
    }

    private fun armIfDue(p: ExoPlayer) {
        if (!SettingsLibrary.CrossfadeEnabled || armingSuspended || !p.isPlaying) return
        if (!p.hasNextMediaItem()) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val window = CrossfadePolicy.windowMs(
            duration,
            SettingsLibrary.CrossfadeDuration.toIntOrNull()
                ?: CrossfadePolicy.DEFAULT_DURATION_SEC
        ) ?: return
        val remaining = remainingWallMs(p, duration)
        if (!CrossfadePolicy.shouldArm(remaining, window)) return
        if (p.currentMediaItemIndex == disarmedIndex &&
            SystemClock.elapsedRealtime() < disarmedUntilMs
        ) return

        val next = p.getNextMediaItemIndex()
        val nextItem = runCatching { p.getMediaItemAt(next) }.getOrNull() ?: return
        val s = ensureSecondary() ?: return
        startIndex = p.currentMediaItemIndex
        targetIndex = next
        windowMs = window
        runCatching {
            s.setMediaItem(nextItem)
            s.volume = 0f
            s.playbackParameters = p.playbackParameters
            s.seekTo(CrossfadePolicy.NEXT_TRACK_START_MS)
            s.playWhenReady = false
            s.prepare()
        }.onFailure {
            abort("secondary_prepare")
            return
        }
        phase = Phase.PREFETCH
        YosDiagnostics.log("XFADE_ARM", "win" to window, "left" to remaining, "next" to targetIndex)
    }

    private fun prefetchStep(p: ExoPlayer, s: ExoPlayer) {
        if (primaryTouchedByUser(p)) {
            abort("primary_moved")
            return
        }
        val remaining = remainingWallMs(p)
        val elapsed = (windowMs + CrossfadePolicy.PREFETCH_LEAD_MS - remaining).coerceAtLeast(0L)
        val rendering = s.playbackState == Player.STATE_READY && s.isPlaying
        when (CrossfadePolicy.crossfade(rendering, remaining, elapsed)) {
            CrossfadePolicy.CrossfadeDecision.Wait -> Unit
            CrossfadePolicy.CrossfadeDecision.Start -> beginCrossfade(p, s)
            CrossfadePolicy.CrossfadeDecision.AbortTooLate,
            CrossfadePolicy.CrossfadeDecision.AbortNotRendering -> abort("secondary_not_ready")
        }
    }

    private fun beginCrossfade(p: ExoPlayer, s: ExoPlayer) {
        if (p.currentMediaItemIndex != startIndex || p.currentPosition < 0L) {
            abort("primary_changed")
            return
        }
        val remaining = remainingWallMs(p)
        if (remaining <= 0L) {
            abort("too_late")
            return
        }
        val duration = CrossfadePolicy.rampMs(minOf(remaining, windowMs))
        rampStartedAt = SystemClock.elapsedRealtime()
        rampEndsAt = rampStartedAt + duration
        expectedAdvanceMediaId = runCatching { p.getMediaItemAt(targetIndex).mediaId }.getOrNull()
        expectedAdvanceAt = rampStartedAt

        rampAnim?.cancel()
        rampAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                p.volume = CrossfadePolicy.volumeOut(1f, t)
                s.volume = CrossfadePolicy.volumeIn(t)
            }
            start()
        }

        runCatching {
            // Start the next player silently before changing the primary timeline. The secondary
            // is already at position zero, so no old-track seek or backward audible jump is possible.
            s.volume = 0f
            s.playWhenReady = true
            p.volume = 0f
            p.seekTo(targetIndex, CrossfadePolicy.NEXT_TRACK_START_MS)
        }.onFailure {
            abort("advance_failed")
            return
        }
        phase = Phase.CROSSFADE
        YosDiagnostics.log("XFADE_START", "ms" to duration, "to" to targetIndex)
    }

    private fun crossfadeStep(p: ExoPlayer, s: ExoPlayer) {
        val index = p.currentMediaItemIndex
        if (index != targetIndex && index != startIndex) {
            abort("index_mismatch")
            return
        }
        if (p.playbackState == Player.STATE_IDLE) {
            abort("primary_idle")
            return
        }
        if (SystemClock.elapsedRealtime() >= rampEndsAt) {
            finish("ramp_done")
            return
        }
        if (s.playbackState == Player.STATE_IDLE || s.playbackState == Player.STATE_ENDED) {
            // The primary is already on the new track, so let it finish the fade without leaving
            // a dead secondary around. The new track remains the sole audible source.
            runCatching {
                s.volume = 0f
                s.stop()
                s.clearMediaItems()
            }
        }
    }

    private fun finish(why: String) {
        YosDiagnostics.log("XFADE_DONE", "why" to why, "idx" to primary?.currentMediaItemIndex)
        handBackToPrimary()
    }

    private fun handBackToPrimary() {
        rampAnim?.cancel()
        rampAnim = null
        runCatching { primary?.volume = 1f }
        stopSecondary()
        toIdle()
    }

    private fun toIdle() {
        phase = Phase.IDLE
        startIndex = C.INDEX_UNSET
        targetIndex = C.INDEX_UNSET
        windowMs = 0L
        rampEndsAt = 0L
        rampStartedAt = 0L
        expectedAdvanceMediaId = null
        expectedAdvanceAt = 0L
    }

    private val primaryListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (phase == Phase.IDLE) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) return
            if (isSelfAdvance(mediaItem)) {
                expectedAdvanceMediaId = null
                return
            }
            abort("transition_$reason")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && phase != Phase.IDLE) abort("paused")
        }
    }

    private fun isSelfAdvance(mediaItem: MediaItem?): Boolean {
        val expected = expectedAdvanceMediaId ?: return false
        if (mediaItem?.mediaId != expected) return false
        return SystemClock.elapsedRealtime() - expectedAdvanceAt <= SELF_ADVANCE_GRACE_MS
    }

    private fun primaryTouchedByUser(p: ExoPlayer): Boolean =
        p.currentMediaItemIndex != startIndex

    private fun remainingWallMs(p: ExoPlayer, duration: Long = p.duration): Long {
        if (duration == C.TIME_UNSET || duration <= 0) return -1L
        return ((duration - p.currentPosition) / currentSpeed(p)).toLong()
    }

    private fun currentSpeed(p: ExoPlayer): Float =
        p.playbackParameters.speed.takeIf { it > 0f } ?: 1f

    private fun ensureSecondary(): ExoPlayer? = secondary ?: runCatching {
        secondaryBuilder?.invoke()
    }.getOrNull()?.also { secondary = it }

    private fun stopSecondary() {
        val s = secondary ?: return
        runCatching {
            s.pause()
            s.volume = 0f
            s.stop()
            s.clearMediaItems()
        }
    }

    private var rampAnim: ValueAnimator? = null
}
