package yos.music.player.code.utils.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import yos.music.player.code.MediaController
import yos.music.player.code.livesort.LiveSortTransitionController
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.TransitionDisplayMode

/**
 * Automatic crossfade between consecutive tracks.
 *
 * During the ramp the primary player keeps playing the current track (fading out) while the
 * secondary player renders the next track from position zero (fading in). When the ramp ends the
 * audible stream never changes hands: the still-playing secondary is promoted to be the new
 * primary via [onPromote] (MediaSession binding, listeners, audio focus and diagnostics move over
 * to it), and the old primary is released. The secondary carries a clone of the full queue from
 * arm time, so promotion needs no seek and no timeline work — the seam cannot go silent.
 */
@OptIn(UnstableApi::class)
object CrossfadeExo {
    private enum class Phase { IDLE, PREFETCH, CROSSFADE }

    private const val TICK_MS = 150L
    private const val MAX_TICK_FAILURES = 8
    private const val SONG_RETRY_COOLDOWN_MS = 30_000L
    private const val SMART_PREFETCH_LEAD_MS = 8_000L
    private const val SMART_MIN_ABORT_REMAINING_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private var primary: ExoPlayer? = null
    private var secondary: ExoPlayer? = null
    private var secondaryBuilder: (() -> ExoPlayer)? = null
    private var onPromote: ((ExoPlayer) -> Unit)? = null

    private var phase = Phase.IDLE
    private var startIndex = C.INDEX_UNSET
    private var targetIndex = C.INDEX_UNSET
    private var startMediaId: String? = null
    private var targetMediaId: String? = null
    private var windowMs = 0L
    private var rampEndsAt = 0L
    private var tickFailures = 0
    private var disarmedIndex = C.INDEX_UNSET
    private var disarmedUntilMs = 0L

    private var activeSmartMode = false
    private var armedSmartMode = false
    private var smartUserFxScale = 0.78f
    private var smartBreathRatio = 0.2f
    private var smartDynamicProfile: LiveSortTransitionPolicy.DynamicProfile? = null
    private var smartOverlapMs = 0L
    private var smartFullMixMs = 0L
    private var smartContinueRelease = false

    @Volatile
    private var armingSuspended = false

    fun attach(
        primary: ExoPlayer,
        secondaryBuilder: () -> ExoPlayer,
        onPromote: (ExoPlayer) -> Unit,
    ) {
        this.secondaryBuilder = secondaryBuilder
        this.onPromote = onPromote
        this.primary?.removeListener(primaryListener)
        this.primary = primary
        primary.addListener(primaryListener)
    }

    fun start() {
        handler.removeCallbacks(tickRunnable)
        tickFailures = 0
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    /** 扶正后是否仍需继续执行智能过渡尾段（下潜滤波/混响渐出），供服务层避免扶正瞬间把音量强行跳回 1f。 */
    fun isContinuingSmartRelease(): Boolean = smartContinueRelease

    private fun setTransitionDisplayMode(mode: TransitionDisplayMode?) {
        if (MediaViewModelObject.activeTransitionMode.value != mode) {
            MediaViewModelObject.activeTransitionMode.value = mode
        }
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        rampAnim?.cancel()
        rampAnim = null
        cancelPostRelease(restoreVolume = false, immediateEffects = true)
        LiveSortTransitionController.resetAllEffects(immediate = true)
        primary?.removeListener(primaryListener)
        primary = null
        secondaryBuilder = null
        onPromote = null
        runCatching { secondary?.release() }
        secondary = null
        armingSuspended = false
        disarmedIndex = C.INDEX_UNSET
        disarmedUntilMs = 0L
        toIdle()
        setTransitionDisplayMode(null)
    }

    fun abort(reason: String) {
        if (phase == Phase.IDLE && postReleaseAnim == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) abortNow(reason)
        else handler.post { abortNow(reason) }
    }

    private fun abortNow(reason: String) {
        if (phase == Phase.IDLE) {
            if (postReleaseAnim != null) {
                val restoreVol = reason != "paused"
                cancelPostRelease(restoreVolume = restoreVol, immediateEffects = true)
            }
            return
        }
        YosDiagnostics.log("XFADE_ABORT", "why" to reason, "ph" to phase.name)
        if (phase == Phase.PREFETCH) {
            primary?.let {
                disarmedIndex = it.currentMediaItemIndex
                disarmedUntilMs = SystemClock.elapsedRealtime() + SONG_RETRY_COOLDOWN_MS
            }
        }
        handBackToPrimary(restoreVolume = reason != "paused")
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
        if (p.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = p.getNextMediaItemIndex()
        // 单曲队列 + 列表循环时 next 就是当前曲，交叉淡化会变成"自己叠自己"
        if (next == p.currentMediaItemIndex) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return

        val currentItem = p.currentMediaItem
        val nextItem = p.getMediaItemAt(next)
        val smartMode = SettingsLibrary.isSmartCrossfade()

        if (smartMode) {
            // 提前在后台完成当前曲与下一曲的首尾特征提取（含在线缓存曲）
            val appCtx = MediaController.appContext
            LiveSortAudioAnalyzer.ensureAnalyzedAsync(appCtx, currentItem)
            LiveSortAudioAnalyzer.ensureAnalyzedAsync(appCtx, nextItem)
        }

        val outgoingFeatures = if (smartMode) LiveSortFeatureStore.get(currentItem?.mediaId) else null
        val incomingFeatures = if (smartMode) LiveSortFeatureStore.get(nextItem.mediaId) else null

        val window = if (smartMode) {
            LiveSortTransitionPolicy.smartWindowMs(duration, outgoingFeatures, incomingFeatures)
        } else {
            CrossfadePolicy.windowMs(
                duration,
                SettingsLibrary.CrossfadeDuration.toIntOrNull()
                    ?: CrossfadePolicy.DEFAULT_DURATION_SEC
            )
        } ?: return

        val remaining = remainingWallMs(p, duration)
        val shouldArm = if (smartMode) {
            remaining > 0L && remaining <= window + SMART_PREFETCH_LEAD_MS
        } else {
            CrossfadePolicy.shouldArm(remaining, window)
        }
        if (!shouldArm) return
        if (p.currentMediaItemIndex == disarmedIndex &&
            SystemClock.elapsedRealtime() < disarmedUntilMs
        ) return

        // 进入智能预热窗口：如果有进行中的上一轮尾段释放，安全平滑收尾后再装填下一首
        if (postReleaseAnim != null) {
            cancelPostRelease(restoreVolume = true, immediateEffects = false)
        }

        val s = ensureSecondary() ?: return
        startIndex = p.currentMediaItemIndex
        targetIndex = next
        startMediaId = currentItem?.mediaId
        targetMediaId = nextItem.mediaId
        windowMs = window
        armedSmartMode = smartMode
        if (smartMode) {
            val scale = LiveSortTransitionPolicy.userFxScaleForLevel(SettingsLibrary.SmartTransitionFxIntensity)
            val dynProfile = LiveSortTransitionPolicy.getTransitionDynamicProfile(outgoingFeatures, incomingFeatures)
            smartUserFxScale = scale
            smartDynamicProfile = dynProfile
        }
        // 新歌从零潜深缓入，不在预加载阶段提前压闷或开启混响
        LiveSortTransitionController.resetAllEffects(immediate = true)

        runCatching {
            // 副 player 携带完整队列克隆：扶正只是对象换引用，不需要任何时间线操作。
            val items = ArrayList<MediaItem>(p.mediaItemCount)
            for (i in 0 until p.mediaItemCount) items.add(p.getMediaItemAt(i))
            s.shuffleModeEnabled = p.shuffleModeEnabled
            s.repeatMode = p.repeatMode
            s.setMediaItems(items, targetIndex, CrossfadePolicy.NEXT_TRACK_START_MS)
            s.volume = 0f
            s.playbackParameters = p.playbackParameters
            s.playWhenReady = false
            LiveSortTransitionController.bindTrack(
                LiveSortTransitionController.PLAYER_SECONDARY,
                targetMediaId,
                C.TIME_UNSET,
                smartMode,
            )
            s.prepare()
        }.onFailure {
            abort("secondary_prepare")
            return
        }
        phase = Phase.PREFETCH
        YosDiagnostics.log(
            "XFADE_ARM",
            "mode" to if (smartMode) "smart" else "smooth",
            "win" to window,
            "left" to remaining,
            "next" to targetIndex,
        )
    }

    private fun prefetchStep(p: ExoPlayer, s: ExoPlayer) {
        if (primaryTouchedByUser(p)) {
            abort("primary_moved")
            return
        }
        val remaining = remainingWallMs(p)
        // The secondary stays paused until the fade begins, so readiness is judged by
        // playbackState, not isPlaying — otherwise Start could never fire.
        val ready = s.playbackState == Player.STATE_READY

        if (!armedSmartMode) {
            val elapsed = (windowMs + CrossfadePolicy.PREFETCH_LEAD_MS - remaining).coerceAtLeast(0L)
            when (CrossfadePolicy.crossfade(ready, remaining, elapsed, windowMs)) {
                CrossfadePolicy.CrossfadeDecision.Wait -> Unit
                CrossfadePolicy.CrossfadeDecision.Start -> beginCrossfade(p, s)
                CrossfadePolicy.CrossfadeDecision.AbortTooLate,
                CrossfadePolicy.CrossfadeDecision.AbortNotRendering -> abort("secondary_not_ready")
            }
            return
        }

        if (remaining <= 0L) {
            abort("secondary_not_ready")
            return
        }
        if (ready && remaining <= windowMs + CrossfadePolicy.START_TOLERANCE_MS) {
            beginCrossfade(p, s)
            return
        }
        val elapsed = (windowMs + SMART_PREFETCH_LEAD_MS - remaining).coerceAtLeast(0L)
        if (!ready && remaining <= SMART_MIN_ABORT_REMAINING_MS && elapsed >= SMART_PREFETCH_LEAD_MS) {
            abort("secondary_not_ready")
            return
        }
        // 对齐 LiveSort 原版：不在切入前提前变闷，保持主曲目清澈直至交叉点
        LiveSortTransitionController.updateOutgoingEffects(0f, 0f)
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

        val smartMode = SettingsLibrary.isSmartCrossfade()
        activeSmartMode = smartMode

        if (!smartMode) {
            LiveSortTransitionController.resetAllEffects(immediate = true)
            val duration = CrossfadePolicy.rampMs(minOf(remaining, windowMs))
            rampEndsAt = SystemClock.elapsedRealtime() + duration
            smartContinueRelease = false

            // The primary keeps playing the old track; the secondary takes over audibly from silence.
            runCatching {
                s.volume = 0f
                s.playWhenReady = true
            }.onFailure {
                abort("secondary_start_failed")
                return
            }

            rampAnim?.cancel()
            rampAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    val t = animation.animatedValue as Float
                    p.volume = CrossfadePolicy.volumeOut(1f, t)
                    s.volume = CrossfadePolicy.volumeIn(t)
                }
                start()
            }
            phase = Phase.CROSSFADE
            setTransitionDisplayMode(TransitionDisplayMode.SMOOTH)
            YosDiagnostics.log("XFADE_START", "mode" to "smooth", "ms" to duration, "to" to targetIndex)
            return
        }

        // ---- LiveSort 智能无感过渡 ----
        val outgoingFeatures = LiveSortFeatureStore.get(startMediaId)
        val incomingFeatures = LiveSortFeatureStore.get(targetMediaId)
        val overlapMs = LiveSortTransitionPolicy.smartOverlapMs(remaining, outgoingFeatures)
        val overlapSec = overlapMs / 1000f
        val fullMixMs = maxOf(overlapMs, LiveSortTransitionPolicy.smartFullMixDurationMs(outgoingFeatures))
        val fullMixSec = fullMixMs / 1000f
        val breathRatio = LiveSortTransitionPolicy.getBreathRatio(outgoingFeatures, fullMixSec)
        val dynamicProfile = LiveSortTransitionPolicy.getTransitionDynamicProfile(outgoingFeatures, incomingFeatures)
        val userFxScale = LiveSortTransitionPolicy.userFxScaleForLevel(SettingsLibrary.SmartTransitionFxIntensity)

        smartOverlapMs = overlapMs
        smartFullMixMs = fullMixMs
        smartBreathRatio = breathRatio
        smartDynamicProfile = dynamicProfile
        smartUserFxScale = userFxScale
        // 按实际自适应重叠时长同步完成淡入与效果褪去，扶正后不再拖着新歌持续变音
        smartContinueRelease = false
        rampEndsAt = SystemClock.elapsedRealtime() + overlapMs

        val initialState = LiveSortTransitionPolicy.getCrossfadeState(
            ratio = 0f,
            baseVol = 1f,
            breathRatio = breathRatio,
            dynamicProfile = dynamicProfile,
            effectMultiplier = 1f,
            outgoingRatio = 0f,
            incomingRatio = 0f,
            userFxScale = userFxScale,
        )

        runCatching {
            p.volume = initialState.outgoingVolume
            s.volume = initialState.incomingVolume
            LiveSortTransitionController.updateOutgoingEffects(
                initialState.outgoingDepth,
                initialState.outgoingReverb,
            )
            LiveSortTransitionController.updateIncomingEffects(
                initialState.incomingDepth,
                initialState.incomingReverb,
            )
            s.playWhenReady = true
        }.onFailure {
            abort("secondary_start_failed")
            return
        }

        rampAnim?.cancel()
        rampAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = overlapMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val outRatio = animation.animatedValue as Float
                val elapsedMs = outRatio * overlapMs
                val incomingSec = elapsedMs / 1000f
                val effectMult = LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(incomingSec, overlapSec)
                val state = LiveSortTransitionPolicy.getCrossfadeState(
                    ratio = outRatio,
                    baseVol = 1f,
                    breathRatio = breathRatio,
                    dynamicProfile = dynamicProfile,
                    effectMultiplier = effectMult,
                    outgoingRatio = outRatio,
                    incomingRatio = outRatio,
                    userFxScale = userFxScale,
                )
                p.volume = if (p.currentMediaItemIndex != startIndex) 0f else state.outgoingVolume
                val tailBlend = LiveSortTransitionPolicy.smoothStep(((outRatio - 0.75f) / 0.25f).coerceIn(0f, 1f))
                s.volume = state.incomingVolume + (1f - state.incomingVolume) * tailBlend
                LiveSortTransitionController.updateOutgoingEffects(
                    state.outgoingDepth,
                    state.outgoingReverb,
                )
                LiveSortTransitionController.updateIncomingEffects(
                    state.incomingDepth,
                    state.incomingReverb,
                )
            }
            start()
        }
        phase = Phase.CROSSFADE
        setTransitionDisplayMode(TransitionDisplayMode.SMART)
        YosDiagnostics.log(
            "XFADE_START",
            "mode" to "smart",
            "overlap" to overlapMs,
            "full" to fullMixMs,
            "breath" to "%.2f".format(breathRatio),
            "inStart" to "%.2f".format(dynamicProfile.incomingStartGain),
            "to" to targetIndex,
        )
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
        if (SystemClock.elapsedRealtime() < rampEndsAt) {
            if (s.playbackState == Player.STATE_IDLE || s.playbackState == Player.STATE_ENDED) {
                // The secondary died mid-ramp: restore the old track instead of fading into silence.
                abort("secondary_gone")
            }
            return
        }
        // Ramp finished. Promotion requires a live, audibly playing secondary: a track shorter
        // than the window would be ENDED here, and a stalled one would leave the seam silent.
        if (s.playbackState != Player.STATE_READY || !s.isPlaying) {
            abort("secondary_not_playing")
            return
        }
        promote(p, s)
    }

    private fun promote(p: ExoPlayer, s: ExoPlayer) {
        val continueRelease = activeSmartMode && smartContinueRelease
        val overlapMs = smartOverlapMs
        val fullMixMs = smartFullMixMs
        val breathRatio = smartBreathRatio
        val dynamicProfile = smartDynamicProfile
        val userFxScale = smartUserFxScale

        // Detach from the old primary first: the focus handover inside onPromote pauses it.
        p.removeListener(primaryListener)
        runCatching { onPromote?.invoke(s) }.onFailure {
            // 服务事务在会话重绑之前失败：旧主 player 完好，回退硬切。
            p.addListener(primaryListener)
            abortNow("promote_failed")
            return
        }
        // The service has swapped everything onto the secondary; it is the primary now.
        secondary = null
        primary = s
        s.addListener(primaryListener)
        toIdle()

        if (continueRelease && dynamicProfile != null) {
            setTransitionDisplayMode(TransitionDisplayMode.SMART)
            startPostPromoteRelease(
                promoted = s,
                fromMs = overlapMs,
                fullMixMs = fullMixMs,
                breathRatio = breathRatio,
                dynamicProfile = dynamicProfile,
                userFxScale = userFxScale,
            )
        } else {
            s.volume = 1f
            LiveSortTransitionController.resetAllEffects(immediate = false)
            setTransitionDisplayMode(null)
        }

        YosDiagnostics.log(
            "XFADE_PROMOTE",
            "idx" to s.currentMediaItemIndex,
            "pos" to s.currentPosition,
            "postRel" to continueRelease,
        )
    }

    private fun startPostPromoteRelease(
        promoted: ExoPlayer,
        fromMs: Long,
        fullMixMs: Long,
        breathRatio: Float,
        dynamicProfile: LiveSortTransitionPolicy.DynamicProfile,
        userFxScale: Float,
    ) {
        val releaseEndMs = maxOf(
            fullMixMs,
            (LiveSortTransitionPolicy.EFFECT_RELEASE_END_INCOMING_SEC * 1000f).toLong(),
        )
        val remainingReleaseMs = (releaseEndMs - fromMs).coerceAtLeast(300L)
        postReleaseAnim?.cancel()
        postReleaseAnim = ValueAnimator.ofFloat(fromMs.toFloat(), releaseEndMs.toFloat()).apply {
            duration = remainingReleaseMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (primary !== promoted || promoted.playbackState == Player.STATE_IDLE) {
                    cancel()
                    return@addUpdateListener
                }
                val elapsedMs = animation.animatedValue as Float
                val inTimelineRatio = (elapsedMs / fullMixMs.toFloat()).coerceIn(0f, 1f)
                val incomingSec = elapsedMs / 1000f
                val effectMult = LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(incomingSec)
                val state = LiveSortTransitionPolicy.getCrossfadeState(
                    ratio = 1f,
                    baseVol = 1f,
                    breathRatio = breathRatio,
                    dynamicProfile = dynamicProfile,
                    effectMultiplier = effectMult,
                    outgoingRatio = 1f,
                    incomingRatio = inTimelineRatio,
                    userFxScale = userFxScale,
                )
                val tailProgress = ((elapsedMs - fromMs) / remainingReleaseMs.toFloat()).coerceIn(0f, 1f)
                val tailBlend = LiveSortTransitionPolicy.smoothStep(((tailProgress - 0.65f) / 0.35f).coerceIn(0f, 1f))
                promoted.volume = (state.incomingVolume + (1f - state.incomingVolume) * tailBlend).coerceIn(0f, 1f)
                LiveSortTransitionController.updatePromotedEffects(
                    state.incomingDepth,
                    state.incomingReverb,
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (postReleaseAnim === animation) {
                        postReleaseAnim = null
                        if (phase == Phase.IDLE) {
                            setTransitionDisplayMode(null)
                        }
                    }
                    if (!cancelled && primary === promoted) {
                        promoted.volume = 1f
                        LiveSortTransitionController.resetAllEffects(immediate = false)
                    }
                }
            })
            start()
        }
    }

    private fun cancelPostRelease(restoreVolume: Boolean, immediateEffects: Boolean) {
        postReleaseAnim?.cancel()
        postReleaseAnim = null
        if (restoreVolume) {
            runCatching { primary?.volume = 1f }
        }
        if (phase == Phase.IDLE) {
            setTransitionDisplayMode(null)
        }
        LiveSortTransitionController.resetAllEffects(immediate = immediateEffects)
    }

    private fun handBackToPrimary(restoreVolume: Boolean = true) {
        rampAnim?.cancel()
        rampAnim = null
        cancelPostRelease(restoreVolume = restoreVolume, immediateEffects = true)
        if (restoreVolume) {
            runCatching { primary?.volume = 1f }
        }
        stopSecondary()
        toIdle()
        setTransitionDisplayMode(null)
    }

    private fun toIdle() {
        phase = Phase.IDLE
        startIndex = C.INDEX_UNSET
        targetIndex = C.INDEX_UNSET
        startMediaId = null
        targetMediaId = null
        windowMs = 0L
        rampEndsAt = 0L
        activeSmartMode = false
        armedSmartMode = false
        smartDynamicProfile = null
        smartOverlapMs = 0L
        smartFullMixMs = 0L
        smartContinueRelease = false
    }

    private val primaryListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (phase == Phase.IDLE) {
                if (postReleaseAnim != null) {
                    cancelPostRelease(restoreVolume = true, immediateEffects = true)
                }
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) {
                runCatching { primary?.volume = 0f }
                return
            }
            abort("transition_$reason")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                if (phase != Phase.IDLE) {
                    abort("paused")
                } else if (postReleaseAnim != null) {
                    cancelPostRelease(restoreVolume = false, immediateEffects = true)
                }
            }
        }
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
    private var postReleaseAnim: ValueAnimator? = null
}
