/*
 * Adapted from Pear Wall (https://github.com/Nevodev/Pear-Wall) v1.5,
 * Copyright Nevodev, licensed under the Apache License 2.0.
 * See assets/licenses/pear-wall-NOTICE.txt for the porting changes.
 *
 * Sonify adaptation: the moru (long-rain-glass) post pass, the frame-capture path and
 * the four-lane audio pulse are removed; `imageScales` is therefore always 1. The
 * material pass is pinned to the pinch (warped) variant, matching upstream's
 * `behindLyricsProgress = 1f` wallpaper default.
 */

package yos.music.player.ui.widgets.effects.pearmesh

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal class PearMeshGlRenderer(
    context: Context,
    initialState: RendererState,
) : Closeable {
    private val rotationProgram = GlProgram(
        context,
        "pearmesh/shaders/rotation.vert",
        "pearmesh/shaders/rotation.frag",
    )
    private val blurProgram = GlProgram(
        context,
        "pearmesh/shaders/fullscreen.vert",
        "pearmesh/shaders/blur.frag",
    )
    private val fullscreenMaterialProgram = GlProgram(
        context,
        "pearmesh/shaders/fullscreen.vert",
        "pearmesh/shaders/material.frag",
    )
    private val pinchMaterialProgram = GlProgram(
        context,
        "pearmesh/shaders/pinch.vert",
        "pearmesh/shaders/material.frag",
    )
    private val quad = GlGeometry.quad()
    private var mesh = GlGeometry.mesh(
        PearMeshMesh.create(
            isPortrait = true,
            presetIndex = PearMeshState.DefaultPortraitPresetIndex,
        ),
    )

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var isPortrait = true
    private var meshPresetIndex = PearMeshState.DefaultPortraitPresetIndex
    private var rotationTarget: RenderTarget? = null
    private var kawaseHalfTarget: RenderTarget? = null
    private var kawaseQuarterTarget: RenderTarget? = null
    private var kawaseEighthTarget: RenderTarget? = null
    private var lyricsBlurTarget: RenderTarget? = null
    private var materialTarget: RenderTarget? = null

    private val initialArtwork = initialState.artwork?.takeUnless { it.bitmap.isRecycled }
    private var currentArtwork = uploadTexture(
        initialArtwork?.bitmap ?: createGrayArtwork()
    )
    private var previousArtwork = currentArtwork
    private var uploadedArtworkId = initialArtwork?.id ?: 0L
    private var observedArtworkId = uploadedArtworkId
    private var pendingArtwork: Bitmap? = null
    private var pendingArtworkId = 0L
    private var artworkTransitionStart = Double.NEGATIVE_INFINITY
    private var artworkAspect = initialArtwork?.bitmap?.let { it.width.toFloat() / it.height } ?: 1f

    init {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
    }

    fun render(width: Int, height: Int, state: RendererState, time: Double) {
        currentScrimAlpha = state.scrimAlpha
        ensureSize(width, height, state)
        // Artwork transitions must finish even when playback flow is intentionally frozen.
        val artworkTime = System.nanoTime() / 1_000_000_000.0
        updateArtwork(state, artworkTime)
        val transitionMix = artworkTransitionMix(artworkTime)

        val blurSigma = LYRICS_BLUR_SIGMA * state.renderScale * state.blurMultiplier
        val lyricTarget = checkNotNull(lyricsBlurTarget)

        // Upstream rotates the three artwork copies every 120s / 70s / 90s. That cadence is
        // tuned for a wallpaper you glance at, and reads as almost static on a playback page
        // you sit and watch. The rotation and the mesh warp share `uTime` upstream, but they
        // are separate uniforms here, so they can be paced independently: rotation is what
        // the eye reads as "flowing" and takes the user's flow-speed step, while the warp
        // stays slower so it does not turn into a heartbeat.
        val rotationScale = ROTATION_TIME_SCALE * PearMeshState.flowSpeedMultiplier(state.flowSpeed)
        renderBackdrop(
            blurSigma,
            state.blurEnabled,
            lyricTarget,
            time * rotationScale,
            transitionMix,
            audioPulse(state, frameSeconds()),
        )
        renderMaterial(lyricTarget.texture, time * WARP_TIME_SCALE)
    }

    /** 本帧与上一帧的间隔（秒），钳到 [8ms, 100ms] 防首帧/长卡顿把平滑拉飞。 */
    private fun frameSeconds(): Float {
        val now = System.nanoTime()
        val previous = lastFrameNanos
        lastFrameNanos = now
        if (previous == 0L) return 1f / 30f
        return ((now - previous) / 1_000_000_000.0).toFloat().coerceIn(0.008f, 0.1f)
    }

    /**
     * 本帧的律动缩放，`1 + 强度 × power²`。
     *
     * 平方是上游的关键选择（`IMAGE_PULSE_INTENSITY = 0.33`）：持续的大音量只会把画面
     * 轻微推一下，真正让它"弹"的是鼓点的上升沿。
     *
     * ## 为什么在渲染侧再平滑一次
     * 分析侧每 46ms 才产出一个新值，而渲染是 30fps（33ms）——同一拍会被读到两次，
     * 两次之间却没有过渡，画面就变成「跳一下、停住、再跳」。这里用渲染自己的帧时钟
     * 对 power 做一次指数平滑，让缩放变成连续曲线。
     *
     * 时间常数的取舍见 [PULSE_ATTACK_S] / [PULSE_RELEASE_S] 的注释。
     *
     * ## 暂停时必须冻结，不能归零
     * 暂停时旋转时钟已经冻结（`EglRenderThread` 不再累加 animationNanos），画面本该
     * 完全静止。如果这里把 `smoothedPower` 清零，缩放会从放大值**瞬间跳回 1.0**，
     * 观感就是"暂停时突然改了一下"。所以暂停就是保持当前值不动。
     */
    private fun audioPulse(state: RendererState, frameSeconds: Float): Float {
        if (state.isPlaybackPlaying) {
            val target = state.audioPower
            val tau = if (target > smoothedPower) PULSE_ATTACK_S else PULSE_RELEASE_S
            val mix = 1f - exp(-frameSeconds / tau)
            smoothedPower += (target - smoothedPower) * mix
        }
        return 1f + state.pulseIntensity * smoothedPower * smoothedPower
    }

    /** 渲染侧平滑后的能量，跨帧保持。只在 GL 线程访问。 */
    private var smoothedPower = 0f

    /** 上一帧的时间戳，用来算帧长。 */
    private var lastFrameNanos = 0L

    private fun renderBackdrop(
        blurSigma: Float,
        blurEnabled: Boolean,
        target: RenderTarget,
        time: Double,
        transitionMix: Float,
        imageScale: Float,
    ) {
        val rotation = checkNotNull(rotationTarget)
        val half = checkNotNull(kawaseHalfTarget)
        val quarter = checkNotNull(kawaseQuarterTarget)
        val eighth = checkNotNull(kawaseEighthTarget)
        rotation.bind()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        rotationProgram.use()
        rotationProgram.float("uTime", time.toFloat())
        val aspect = outputWidth.toFloat() / outputHeight
        if (aspect >= 1f) {
            rotationProgram.vec2("uViewScale", 1f, aspect)
        } else {
            rotationProgram.vec2("uViewScale", 1f / aspect, 1f)
        }
        // 只读 .x（rotation.vert:53），y/z 上游填同值但从未被采样
        rotationProgram.vec3("uImageScales", imageScale, imageScale, imageScale)
        rotationProgram.float("uTransitionMix", transitionMix)
        rotationProgram.int("uCurrentArtwork", 0)
        rotationProgram.int("uPreviousArtwork", 1)
        bindTexture(0, currentArtwork)
        bindTexture(1, previousArtwork)

        rotationProgram.int("uArtworkFill", 1)
        rotationProgram.int("uInstance", 0)
        quad.draw()
        rotationProgram.int("uArtworkFill", 0)
        repeat(3) { instance ->
            rotationProgram.int("uInstance", instance)
            quad.draw()
        }

        if (!blurEnabled) {
            kawasePass(rotation, target, 0f, upsample = false)
            return
        }

        val kawaseOffset = blurSigma / KAWASE_SIGMA_PER_OFFSET
        kawasePass(rotation, half, kawaseOffset, upsample = false)
        kawasePass(half, quarter, kawaseOffset, upsample = false)
        kawasePass(quarter, eighth, kawaseOffset, upsample = false)
        kawasePass(eighth, quarter, kawaseOffset, upsample = true)
        kawasePass(quarter, half, kawaseOffset, upsample = true)
        kawasePass(half, target, kawaseOffset, upsample = true)
    }

    private fun kawasePass(
        source: RenderTarget,
        target: RenderTarget,
        offset: Float,
        upsample: Boolean,
    ) {
        target.bind()
        blurProgram.use()
        blurProgram.int("uSource", 0)
        blurProgram.vec2("uTexelSize", 1f / source.width, 1f / source.height)
        blurProgram.float("uOffset", offset)
        blurProgram.int("uUpsample", if (upsample) 1 else 0)
        bindTexture(0, source.texture)
        quad.draw()
    }

    private fun renderMaterial(lyricTexture: Int, time: Double) {
        val destination = materialTarget
        if (destination == null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        } else {
            destination.bind()
        }
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        bindTexture(0, lyricTexture)
        bindTexture(1, lyricTexture)

        // Upstream draws the warped mesh material only (portrait passes MATERIAL_LYRICS
        // once through the pinch program; landscape composites a background quad first).
        if (!isPortrait) {
            drawMaterial(MATERIAL_LANDSCAPE_BACKGROUND, 1f)
        }
        drawPinchMaterial(MATERIAL_LYRICS, 1f, time)

        if (destination != null) {
            blitToScreen(destination)
        }
    }

    private fun drawMaterial(mode: Int, modeMix: Float) {
        setMaterialUniforms(fullscreenMaterialProgram, mode, modeMix)
        quad.draw()
    }

    private fun drawPinchMaterial(mode: Int, modeMix: Float, time: Double) {
        setMaterialUniforms(pinchMaterialProgram, mode, modeMix)
        pinchMaterialProgram.float("uTime", time.toFloat())
        if (isPortrait) {
            pinchMaterialProgram.vec4("uTextureTransform", 1f, 1f, 0f, 0f)
        } else {
            pinchMaterialProgram.vec4("uTextureTransform", 0.8f, 0.8f, 0.1f, 0.1f)
        }
        mesh.draw()
    }

    private fun setMaterialUniforms(program: GlProgram, mode: Int, modeMix: Float) {
        program.use()
        program.int("uLyricsBackdrop", 0)
        program.int("uOrdinaryBackdrop", 1)
        program.float("uBlackScrimAlpha", currentScrimAlpha)
        program.float("uLyricsModeMix", modeMix)
        program.float("uDitherStrength", 1f)
        program.int("uMaterialMode", mode)
    }

    private var currentScrimAlpha = PearMeshState.DefaultScrimAlpha

    private fun blitToScreen(source: RenderTarget) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(
            0,
            0,
            source.width,
            source.height,
            0,
            0,
            surfaceWidth,
            surfaceHeight,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_LINEAR
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun ensureSize(width: Int, height: Int, state: RendererState) {
        val newPortrait = height >= width
        val newPresetIndex = if (newPortrait) {
            state.portraitPresetIndex
        } else {
            state.landscapePresetIndex
        }
        if (newPortrait != isPortrait || newPresetIndex != meshPresetIndex) {
            isPortrait = newPortrait
            meshPresetIndex = newPresetIndex
            mesh.close()
            mesh = GlGeometry.mesh(
                PearMeshMesh.create(
                    isPortrait = isPortrait,
                    presetIndex = meshPresetIndex,
                ),
            )
        }

        val scaledWidth = max(1, (width * state.renderScale).roundToInt())
        val scaledHeight = max(1, (height * state.renderScale).roundToInt())
        if (
            width == surfaceWidth &&
            height == surfaceHeight &&
            scaledWidth == outputWidth &&
            scaledHeight == outputHeight
        ) return
        surfaceWidth = width
        surfaceHeight = height
        outputWidth = scaledWidth
        outputHeight = scaledHeight

        rotationTarget?.close()
        kawaseHalfTarget?.close()
        kawaseQuarterTarget?.close()
        kawaseEighthTarget?.close()
        lyricsBlurTarget?.close()
        materialTarget?.close()
        val backdropWidth = max(1, floor(outputWidth / BLUR_DOWNSAMPLE).toInt())
        val backdropHeight = max(1, floor(outputHeight / BLUR_DOWNSAMPLE).toInt())
        rotationTarget = RenderTarget.create(backdropWidth, backdropHeight)
        kawaseHalfTarget = RenderTarget.create(
            max(1, backdropWidth / 2),
            max(1, backdropHeight / 2),
        )
        kawaseQuarterTarget = RenderTarget.create(
            max(1, backdropWidth / 4),
            max(1, backdropHeight / 4),
        )
        kawaseEighthTarget = RenderTarget.create(
            max(1, backdropWidth / 8),
            max(1, backdropHeight / 8),
        )
        lyricsBlurTarget = RenderTarget.create(backdropWidth, backdropHeight)
        materialTarget = if (outputWidth == width && outputHeight == height) {
            null
        } else {
            RenderTarget.create(outputWidth, outputHeight)
        }
    }

    private fun updateArtwork(state: RendererState, time: Double) {
        val artwork = state.artwork ?: return
        if (artwork.id == observedArtworkId || artwork.bitmap.isRecycled) return
        observedArtworkId = artwork.id

        if (artworkTransitionStart.isFinite()) {
            pendingArtwork = artwork.bitmap
            pendingArtworkId = artwork.id
        } else {
            startArtworkTransition(artwork.bitmap, artwork.id, time)
        }
    }

    private fun startArtworkTransition(bitmap: Bitmap, id: Long, time: Double) {
        if (bitmap.isRecycled) return
        artworkAspect = bitmap.width.toFloat() / bitmap.height
        previousArtwork = currentArtwork
        currentArtwork = uploadTexture(bitmap)
        uploadedArtworkId = id
        artworkTransitionStart = time
    }

    private fun artworkTransitionMix(time: Double): Float {
        if (!artworkTransitionStart.isFinite()) return 1f
        val progress = ((time - artworkTransitionStart) / ARTWORK_TRANSITION_SECONDS).toFloat()
        if (progress < 1f) return progress.coerceIn(0f, 1f)

        if (previousArtwork != currentArtwork) {
            GLES30.glDeleteTextures(1, intArrayOf(previousArtwork), 0)
            previousArtwork = currentArtwork
        }
        artworkTransitionStart = Double.NEGATIVE_INFINITY

        val nextArtwork = pendingArtwork
        val nextArtworkId = pendingArtworkId
        pendingArtwork = null
        pendingArtworkId = 0L
        if (nextArtwork != null && nextArtworkId != uploadedArtworkId && !nextArtwork.isRecycled) {
            startArtworkTransition(nextArtwork, nextArtworkId, time)
            return 0f
        }
        return 1f
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        // GLUtils.texImage2D only accepts software-backed 8888 bitmaps. Anything else
        // (HARDWARE from Coil's default path, or RGB_565) throws "invalid Bitmap format"
        // and would kill the whole render thread, leaving a blank surface.
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: createGrayArtwork()
        }
        val handle = IntArray(1)
        GLES30.glGenTextures(1, handle, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR_MIPMAP_LINEAR,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (source !== bitmap) source.recycle()
        return handle[0]
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    override fun close() {
        rotationTarget?.close()
        kawaseHalfTarget?.close()
        kawaseQuarterTarget?.close()
        kawaseEighthTarget?.close()
        lyricsBlurTarget?.close()
        materialTarget?.close()
        quad.close()
        mesh.close()
        rotationProgram.close()
        blurProgram.close()
        fullscreenMaterialProgram.close()
        pinchMaterialProgram.close()
        val textures = if (currentArtwork == previousArtwork) {
            intArrayOf(currentArtwork)
        } else {
            intArrayOf(currentArtwork, previousArtwork)
        }
        GLES30.glDeleteTextures(textures.size, textures, 0)
    }

    private companion object {
        const val BLUR_DOWNSAMPLE = 4f

        /**
         * Base rotation clock multiplier, before the user's flow-speed step is applied.
         *
         * Upstream periods are 120s / 70s / 90s per revolution (≈3°/s), wallpaper pacing.
         * Combined with [PearMeshState.DefaultFlowSpeed] this lands at 3×, i.e.
         * 40s / 23.3s / 30s (≈9°/s): clearly moving while you look at it, still slow enough
         * to read as fluid rather than as spinning.
         */
        const val ROTATION_TIME_SCALE = 1f

        /**
         * Mesh-warp clock multiplier — the 10s breathing cycle in `pinch.vert`.
         *
         * Deliberately much lower than [ROTATION_TIME_SCALE]. The warp is a strong,
         * whole-screen distortion; at 3× it would pulse every 3.3s and dominate the motion.
         * 1.5× gives a 6.7s cycle, which supports the rotation instead of competing.
         */
        const val WARP_TIME_SCALE = 1.5f

        const val KAWASE_SIGMA_PER_OFFSET = 16f

        /**
         * 渲染侧律动平滑的时间常数（秒）。
         *
         * ## 攻击为什么从 120ms 收到 45ms
         * 分析 hop 是 46ms，而渲染 30fps（33ms）。攻击时间常数 120ms 意味着每帧只补齐
         * 1-e^(-33/120) ≈ 24% 的差距，单个鼓点跨过一个 hop 后峰值只到目标值的 1/3 ——
         * 实测就是"柔和但强度不够"。45ms 下每帧补齐约 52%，一个 46ms 的鼓点能吃掉
         * 约 73% 的幅度，既保住峰值又不会变回"啪"地一下（那需要 ≥200ms 才算硬切）。
         *
         * ## 释放
         * 260ms 保留。回落比上升慢是这类呼吸效果的通用手法：快起慢落看起来有弹性，
         * 快起快落才像闪。这个值不动。
         */
        const val PULSE_ATTACK_S = 0.045f
        const val PULSE_RELEASE_S = 0.26f
        const val LYRICS_BLUR_SIGMA = 42.5f
        const val ARTWORK_TRANSITION_SECONDS = 0.5
        const val MATERIAL_LYRICS = 1
        const val MATERIAL_LANDSCAPE_BACKGROUND = 3

        fun createGrayArtwork(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GRAY)
            }
    }
}
