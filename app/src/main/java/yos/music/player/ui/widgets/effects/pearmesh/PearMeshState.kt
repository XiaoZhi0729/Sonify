/*
 * Adapted from Pear Wall (https://github.com/Nevodev/Pear-Wall) v1.5,
 * Copyright Nevodev, licensed under the Apache License 2.0.
 * See assets/licenses/pear-wall-NOTICE.txt for the porting changes.
 *
 * Sonify adaptation: audio visualisation, the moru (long-rain-glass) style and the
 * demo pulse are removed. `behindLyricsProgress` is pinned to 1f, which is Pear Wall's
 * own default for the wallpaper and selects the warped (pinch) material — that is the
 * flowing look we want here. Everything else keeps the upstream defaults.
 */

package yos.music.player.ui.widgets.effects.pearmesh

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lock-free holder of everything the render thread needs for a single frame.
 *
 * The Compose side only ever writes through the setters; [snapshot] returns an
 * immutable copy so the GL thread never observes a torn state.
 */
internal class PearMeshState(
    portraitPresetIndex: Int = DefaultPortraitPresetIndex,
    landscapePresetIndex: Int = DefaultLandscapePresetIndex,
    renderScale: Float = DefaultRenderScale,
    targetFrameRate: Int = DefaultTargetFrameRate,
    scrimAlpha: Float = DefaultScrimAlpha,
    blurMultiplier: Float = DefaultBlurMultiplier,
    blurEnabled: Boolean = true,
    flowSpeed: Int = DefaultFlowSpeed,
    pauseFlowEnabled: Boolean = true,
) {
    private val artworkId = AtomicLong(0L)
    private val artwork = AtomicReference<ArtworkFrame?>(null)
    private val playbackPlaying = AtomicReference(true)
    private val pauseFlow = AtomicReference(pauseFlowEnabled)
    private val portraitPreset = AtomicInteger(
        portraitPresetIndex.coerceIn(0, PearMeshMesh.PortraitPresetCount - 1),
    )
    private val landscapePreset = AtomicInteger(
        landscapePresetIndex.coerceIn(0, PearMeshMesh.LandscapePresetCount - 1),
    )
    private val renderScaleTarget = AtomicReference(
        renderScale.coerceIn(MinRenderScale, MaxRenderScale),
    )
    private val targetFrameRateTarget = AtomicInteger(
        targetFrameRate.coerceIn(MinTargetFrameRate, MaxTargetFrameRate),
    )
    private val scrimAlphaTarget = AtomicReference(scrimAlpha.coerceIn(0f, 1f))
    private val blurMultiplierTarget = AtomicReference(blurMultiplier.coerceAtLeast(0f))
    private val blurEnabledTarget = AtomicReference(blurEnabled)
    private val flowSpeedTarget = AtomicInteger(flowSpeed.coerceIn(MinFlowSpeed, MaxFlowSpeed))

    /** 低音律动能量 0..1，raw bits 存避免每帧装箱。由 Compose 侧从 [PlaybackLevelBridge] 轮询写入。 */
    private val audioPowerBits = AtomicInteger(0f.toRawBits())

    /** 律动缩放强度：0.33 为上游原值；0 表示关掉律动。 */
    private val pulseIntensityTarget = AtomicReference(DefaultPulseIntensity)

    fun setPulseIntensity(intensity: Float) {
        pulseIntensityTarget.set(intensity.coerceIn(0f, MaxPulseIntensity))
    }

    fun setAudioPower(power: Float) {
        val clamped = if (power.isFinite()) power.coerceIn(0f, 1f) else 0f
        audioPowerBits.set(clamped.toRawBits())
    }

    var renderInvalidatedListener: (() -> Unit)? = null

    fun setPortraitPreset(index: Int) {
        portraitPreset.set(index.coerceIn(0, PearMeshMesh.PortraitPresetCount - 1))
    }

    fun setLandscapePreset(index: Int) {
        landscapePreset.set(index.coerceIn(0, PearMeshMesh.LandscapePresetCount - 1))
    }

    fun setRenderScale(scale: Float) {
        renderScaleTarget.set(scale.coerceIn(MinRenderScale, MaxRenderScale))
    }

    fun setTargetFrameRate(frameRate: Int) {
        targetFrameRateTarget.set(frameRate.coerceIn(MinTargetFrameRate, MaxTargetFrameRate))
    }

    fun setScrimAlpha(alpha: Float) {
        scrimAlphaTarget.set(alpha.coerceIn(0f, 1f))
    }

    fun setBlurMultiplier(multiplier: Float) {
        blurMultiplierTarget.set(multiplier.coerceAtLeast(0f))
    }

    fun setBlurEnabled(enabled: Boolean) {
        blurEnabledTarget.set(enabled)
    }

    fun setFlowSpeed(speed: Int) {
        flowSpeedTarget.set(speed.coerceIn(MinFlowSpeed, MaxFlowSpeed))
        renderInvalidatedListener?.invoke()
    }

    fun setPauseFlowEnabled(enabled: Boolean) {
        pauseFlow.set(enabled)
        renderInvalidatedListener?.invoke()
    }

    fun setPlaybackPlaying(playing: Boolean) {
        if (playbackPlaying.getAndSet(playing) != playing) {
            renderInvalidatedListener?.invoke()
        }
    }

    /** The state retains this bitmap so a recreated surface can upload it again. */
    fun setArtwork(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "Artwork bitmap has already been recycled" }
        artwork.set(ArtworkFrame(artworkId.incrementAndGet(), bitmap))
        renderInvalidatedListener?.invoke()
    }

    fun snapshot(): RendererState = RendererState(
        artwork = artwork.get(),
        portraitPresetIndex = portraitPreset.get(),
        landscapePresetIndex = landscapePreset.get(),
        renderScale = renderScaleTarget.get(),
        targetFrameRate = targetFrameRateTarget.get(),
        scrimAlpha = scrimAlphaTarget.get(),
        blurMultiplier = blurMultiplierTarget.get(),
        blurEnabled = blurEnabledTarget.get(),
        flowSpeed = flowSpeedTarget.get(),
        pauseFlowEnabled = pauseFlow.get(),
        isPlaybackPlaying = playbackPlaying.get(),
        audioPower = Float.fromBits(audioPowerBits.get()),
        pulseIntensity = pulseIntensityTarget.get(),
    )

    companion object {
        const val DefaultPortraitPresetIndex = 2
        const val DefaultLandscapePresetIndex = 4
        const val DefaultRenderScale = 0.25f
        const val MinRenderScale = 0.1f
        const val MaxRenderScale = 1f
        const val DefaultTargetFrameRate = 30
        const val MinTargetFrameRate = 1
        const val MaxTargetFrameRate = 240
        const val DefaultScrimAlpha = 0.4f
        const val DefaultBlurMultiplier = 1f

        /**
         * Flow speed，三档：流动 / 标准 / 强烈。
         */
        const val MinFlowSpeed = 0
        const val MaxFlowSpeed = 2
        const val DefaultFlowSpeed = 1

        /**
         * Clock multiplier for a given flow step: 0→1×（上游原速）、1→3×、2→5×。
         *
         * Upstream's own periods (120s / 70s / 90s per revolution) were tuned for a live
         * wallpaper people glance at; on a playback page they read as frozen. The default
         * step of 1 gives 3×, i.e. 40s / 23.3s / 30s per revolution.
         */
        fun flowSpeedMultiplier(speed: Int): Float = when (speed.coerceIn(MinFlowSpeed, MaxFlowSpeed)) {
            0 -> 1f
            1 -> 3f
            else -> 5f
        }

        /** 上游原值：缩放 1.00→1.33 倍。 */
        const val DefaultPulseIntensity = 0.33f
        const val MaxPulseIntensity = 0.60f

        /**
         * 律动强度三档换算：柔和 0.22 / **标准 0.33（上游原值）** / 强烈 0.45。
         *
         * 曾经因为「太强」把整表下移过一版，但那次的真实原因是两处 bug：
         * ① `power.max(transient)` 漏乘 level，瞬态未经电平缩放就顶满；
         * ② 渲染侧攻击常数 120ms 又把峰值削到 1/3。
         * 两个都修掉之后，0.33 的体感回到了上游应有的水平，所以标准档就是原值。
         */
        fun pulseIntensityFor(step: Int): Float = when (step.coerceIn(0, 2)) {
            0 -> 0.22f
            1 -> 0.33f
            else -> 0.45f
        }

        /**
         * 模糊强度三档换算：轻柔 0.75 / **标准 1.0** / 浓郁 1.4。
         *
         * 原版是四档（0.75 / 1 / 1.4 / 2.0）。最高那档 2.0 在播放页上会把颜色糊成一片，
         * 不再提供。
         */
        fun blurMultiplierFor(step: Int): Float = when (step.coerceIn(0, 2)) {
            0 -> 0.75f
            1 -> 1.0f
            else -> 1.4f
        }

        /**
         * 渲染质量三档对应的内部渲染分辨率比例。
         *
         * ★ renderScale 同时乘进模糊 sigma，所以「省电」档画面会明显更糊 —— 原版同款行为。
         */
        fun renderQualityScale(step: Int): Float = when (step.coerceIn(0, 2)) {
            0 -> 0.15f
            1 -> 0.25f
            else -> 0.35f
        }

        /** 渲染质量三档对应的帧率上限。 */
        fun renderQualityFps(step: Int): Int = when (step.coerceIn(0, 2)) {
            0 -> 24
            1 -> 30
            else -> 60
        }
    }
}

internal data class ArtworkFrame(val id: Long, val bitmap: Bitmap)

internal data class RendererState(
    val artwork: ArtworkFrame?,
    val portraitPresetIndex: Int,
    val landscapePresetIndex: Int,
    val renderScale: Float,
    val targetFrameRate: Int,
    val scrimAlpha: Float,
    val blurMultiplier: Float,
    val blurEnabled: Boolean,
    val flowSpeed: Int,
    val pauseFlowEnabled: Boolean,
    val isPlaybackPlaying: Boolean,
    val audioPower: Float,
    val pulseIntensity: Float,
)
