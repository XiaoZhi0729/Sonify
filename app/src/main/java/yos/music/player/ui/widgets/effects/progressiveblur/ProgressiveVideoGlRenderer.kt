package yos.music.player.ui.widgets.effects.progressiveblur

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal data class ProgressiveRenderState(
    val config: ProgressiveBlurConfig = ProgressiveBlurConfig.defaultLandscape(),
    val useVideo: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val coverBitmap: Bitmap? = null,
    val coverGeneration: Long = 0L,
    val coverOriginX: Float = 0f,
    val pageBlurProgress: Float = 0f,
    val alphaFadeMode: Boolean = false,
    val morphTargetLeft: Float = 0f,
    val morphTargetTop: Float = 0f,
    val morphTargetWidth: Float = 0f,
    val morphTargetHeight: Float = 0f,
    val morphCornerPx: Float = 0f,
    val crossfadeEpoch: Long = 0L,
    val crossfadeProgress: Float = 1f,
    val videoFadeProgress: Float = 1f,
)

/**
 * OpenGL ES 2.0/3.0 multi-pass renderer for a single mirrored video/bitmap source followed by a
 * 5-level Gaussian pyramid and progressive composite pass.
 */
internal class ProgressiveVideoGlRenderer : Closeable {

    val oesTextureId: Int = createOesTexture()
    private val staticCoverTextureId: Int = create2dTexture()

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_DATA.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_DATA)
        .apply { position(0) }

    private val quadVbo: Int = createQuadVbo(quadVertices)

    private val mirrorVideoProgram = ShaderProgram(
        ProgressiveBlurShaders.FULLSCREEN_VERT,
        ProgressiveBlurShaders.MIRROR_VIDEO_OES_FRAG,
    )
    private val mirrorStaticProgram = ShaderProgram(
        ProgressiveBlurShaders.FULLSCREEN_VERT,
        ProgressiveBlurShaders.MIRROR_STATIC_2D_FRAG,
    )
    private val kawaseDownsampleProgram = ShaderProgram(
        ProgressiveBlurShaders.FULLSCREEN_VERT,
        ProgressiveBlurShaders.KAWASE_DOWNSAMPLE_FRAG,
    )
    private val gaussianProgram = ShaderProgram(
        ProgressiveBlurShaders.FULLSCREEN_VERT,
        ProgressiveBlurShaders.GAUSSIAN_SEPARABLE_FRAG,
    )
    private val compositeProgram = ShaderProgram(
        ProgressiveBlurShaders.FULLSCREEN_VERT,
        ProgressiveBlurShaders.PROGRESSIVE_COMPOSITE_FRAG,
    )

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var baseTarget: RenderFbo? = null
    private var outgoingBaseTarget: RenderFbo? = null
    private var pyramidTargets: List<RenderFbo> = emptyList()
    private var tempTargets: List<RenderFbo> = emptyList()

    private val crossfadeTracker = ProgressiveCrossfadeTracker()

    private var uploadedCoverGeneration = -1L
    private var staticCoverWidth = 1
    private var staticCoverHeight = 1

    private var pyramidInitialized = false
    private var lastPyramidWidth = -1
    private var lastPyramidHeight = -1
    private var lastPyramidDrawVideo = false
    private var lastPyramidVideoWidth = 0
    private var lastPyramidVideoHeight = 0
    private var lastPyramidCoverGen = -1L
    private var lastPyramidOrientation = -1f
    private var lastPyramidSplitX = -1f
    private var lastPyramidSplitY = -1f
    private var lastPyramidCrossfadeMix = 1f
    private var lastPyramidVideoMix = 1f

    init {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        uploadFallbackPlaceholder()
    }

    fun render(
        width: Int,
        height: Int,
        state: ProgressiveRenderState,
        texMatrix: FloatArray,
        hasVideoFrame: Boolean,
        newVideoFrame: Boolean = true,
    ) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        ensureSize(w, h)
        syncStaticCover(state)

        val config = state.config.sanitized()
        val splits = ProgressiveBlurMath.resolveMirrorSplits(w.toFloat(), h.toFloat(), config)
        val orientationFloat = when (config.orientationMode) {
            ProgressiveOrientationMode.HORIZONTAL -> 0f
            ProgressiveOrientationMode.VERTICAL -> 1f
            ProgressiveOrientationMode.TOP_LEFT_2D -> 2f
        }

        val geometryChanged = pyramidInitialized && (
            orientationFloat != lastPyramidOrientation ||
                kotlin.math.abs(splits.splitX - lastPyramidSplitX) > 0.0005f ||
                kotlin.math.abs(splits.splitY - lastPyramidSplitY) > 0.0005f
            )
        val crossfadeStep = crossfadeTracker.advance(
            epoch = state.crossfadeEpoch,
            rawProgress = state.crossfadeProgress,
            geometryChanged = geometryChanged,
        )
        if (crossfadeStep.shouldSnapshotCurrentBase) {
            val currentBase = checkNotNull(baseTarget)
            val nextBase = outgoingBaseTarget ?: RenderFbo.create(w, h)
            outgoingBaseTarget = currentBase
            baseTarget = nextBase
        }
        val base = checkNotNull(baseTarget)

        val drawVideo =
            state.useVideo && hasVideoFrame && state.videoWidth > 0 && state.videoHeight > 0
        val effectiveCrossfadeMix = crossfadeStep.effectiveMix
        val effectiveVideoMix = if (drawVideo) state.videoFadeProgress.coerceIn(0f, 1f) else 1f
        val pyramidDirty = !pyramidInitialized ||
            w != lastPyramidWidth ||
            h != lastPyramidHeight ||
            crossfadeStep.shouldSnapshotCurrentBase ||
            kotlin.math.abs(effectiveCrossfadeMix - lastPyramidCrossfadeMix) > 0.0005f ||
            (drawVideo && kotlin.math.abs(effectiveVideoMix - lastPyramidVideoMix) > 0.0005f) ||
            (drawVideo && newVideoFrame) ||
            drawVideo != lastPyramidDrawVideo ||
            (drawVideo && (state.videoWidth != lastPyramidVideoWidth || state.videoHeight != lastPyramidVideoHeight)) ||
            (!drawVideo && state.coverGeneration != lastPyramidCoverGen) ||
            geometryChanged

        if (pyramidDirty) {
            lastPyramidWidth = w
            lastPyramidHeight = h
            lastPyramidDrawVideo = drawVideo
            lastPyramidVideoWidth = state.videoWidth
            lastPyramidVideoHeight = state.videoHeight
            lastPyramidCoverGen = state.coverGeneration
            lastPyramidOrientation = orientationFloat
            lastPyramidSplitX = splits.splitX
            lastPyramidSplitY = splits.splitY
            lastPyramidCrossfadeMix = effectiveCrossfadeMix
            lastPyramidVideoMix = effectiveVideoMix
            pyramidInitialized = true

            val outgoingTexId = outgoingBaseTarget?.texture ?: staticCoverTextureId

            // Pass 0: mirror + center-crop either live OES video or static cover into baseTarget (level 0).
            base.bind()
            if (drawVideo) {
                mirrorVideoProgram.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                mirrorVideoProgram.int("uVideoTex", 0)
                mirrorVideoProgram.mat4("uTexMatrix", texMatrix)
                mirrorVideoProgram.vec2("uViewportSize", w.toFloat(), h.toFloat())
                mirrorVideoProgram.vec2(
                    "uSourceSize",
                    state.videoWidth.toFloat(),
                    state.videoHeight.toFloat(),
                )
                mirrorVideoProgram.float("uOrientationMode", orientationFloat)
                mirrorVideoProgram.float("uMirrorSplit", splits.splitX)
                mirrorVideoProgram.float("uMirrorSplitY", splits.splitY)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticCoverTextureId)
                mirrorVideoProgram.int("uStaticTex", 1)
                mirrorVideoProgram.mat4("uStaticTexMatrix", STATIC_TEX_MATRIX)
                mirrorVideoProgram.vec2(
                    "uStaticSourceSize",
                    staticCoverWidth.toFloat(),
                    staticCoverHeight.toFloat(),
                )
                mirrorVideoProgram.float("uVideoMix", effectiveVideoMix)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outgoingTexId)
                mirrorVideoProgram.int("uOutgoingTex", 2)
                mirrorVideoProgram.float("uCrossfadeMix", effectiveCrossfadeMix)

                drawQuad()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            } else {
                mirrorStaticProgram.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticCoverTextureId)
                mirrorStaticProgram.int("uSourceTex", 0)
                mirrorStaticProgram.mat4("uTexMatrix", STATIC_TEX_MATRIX)
                mirrorStaticProgram.vec2("uViewportSize", w.toFloat(), h.toFloat())
                mirrorStaticProgram.vec2(
                    "uSourceSize",
                    staticCoverWidth.toFloat(),
                    staticCoverHeight.toFloat(),
                )
                mirrorStaticProgram.float("uOrientationMode", orientationFloat)
                mirrorStaticProgram.float("uMirrorSplit", splits.splitX)
                mirrorStaticProgram.float("uMirrorSplitY", splits.splitY)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outgoingTexId)
                mirrorStaticProgram.int("uOutgoingTex", 1)
                mirrorStaticProgram.float("uCrossfadeMix", effectiveCrossfadeMix)

                drawQuad()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            crossfadeTracker.onBaseRendered()

            // Passes 1..7: 5-tap prefilter downsample + separable 9-tap Gaussian H & V per level
            // (double-pass on deeper levels 4..7 for ultra-creamy gradient-like smoothness).
            for (level in 0 until PYRAMID_LEVELS) {
                val src = if (level == 0) base else pyramidTargets[level - 1]
                val dst = pyramidTargets[level]
                val scratch = tempTargets[level]

                dst.bind()
                kawaseDownsampleProgram.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.texture)
                kawaseDownsampleProgram.int("uSourceTex", 0)
                kawaseDownsampleProgram.vec2("uTexelSize", 1f / src.width, 1f / src.height)
                drawQuad()

                gaussianProgram.use()
                gaussianProgram.int("uSourceTex", 0)
                val gaussianPasses = if (level >= 3) 2 else 1
                repeat(gaussianPasses) {
                    scratch.bind()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dst.texture)
                    gaussianProgram.vec2("uDirection", 1f / dst.width, 0f)
                    drawQuad()

                    dst.bind()
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, scratch.texture)
                    gaussianProgram.vec2("uDirection", 0f, 1f / dst.height)
                    drawQuad()
                }
            }
        }

        // Final composite pass to window surface (framebuffer 0).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, if (state.alphaFadeMode) 0f else 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        compositeProgram.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base.texture)
        compositeProgram.int("uTex0", 0)

        for (i in 0 until PYRAMID_LEVELS) {
            val unit = i + 1
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pyramidTargets[i].texture)
            compositeProgram.int("uTex$unit", unit)
        }

        compositeProgram.float("uOrientationMode", orientationFloat)
        compositeProgram.float(
            "uBlurMode",
            if (config.blurMode == ProgressiveBlurMode.TRUE_PROGRESSIVE) 0f else 1f,
        )
        compositeProgram.float(
            "uCurveType",
            if (config.curveType == ProgressiveCurveType.SOFTERSTEP) 0f else 1f,
        )
        val (effBlurStartX, effBlurEndX) = ProgressiveBlurMath.resolveHorizontalBlurEdges(splits.splitX, config)
        compositeProgram.float("uMirrorSplit", splits.splitX)
        compositeProgram.float("uBlurStart", effBlurStartX)
        compositeProgram.float("uBlurEnd", effBlurEndX)
        compositeProgram.float("uMirrorSplitY", splits.splitY)
        compositeProgram.float("uBlurStartY", config.blurStartY)
        compositeProgram.float("uBlurEndY", config.blurEndY)
        compositeProgram.float("uMaxBlurLevel", config.maxBlurLevel)
        compositeProgram.float("uAmbientBlend", config.ambientBlend)
        compositeProgram.float("uCurvePower", config.curvePower)
        compositeProgram.float("uScrimMaxAlpha", config.scrimMaxAlpha)
        compositeProgram.float("uScrimPower", config.scrimPower)
        compositeProgram.float("uBlurSaturation", config.blurSaturation)
        compositeProgram.float("uShowGuides", if (config.showDebugGuides) 1f else 0f)
        compositeProgram.float("uCoverOriginX", state.coverOriginX)
        compositeProgram.float("uPageBlurProgress", state.pageBlurProgress)
        compositeProgram.float("uAlphaFadeMode", if (state.alphaFadeMode) 1f else 0f)
        compositeProgram.vec2("uViewportSize", w.toFloat(), h.toFloat())
        compositeProgram.vec4(
            "uMorphTargetRect",
            state.morphTargetLeft,
            state.morphTargetTop,
            state.morphTargetWidth,
            state.morphTargetHeight,
        )
        compositeProgram.float("uMorphCornerPx", state.morphCornerPx)
        drawQuad()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun syncStaticCover(state: ProgressiveRenderState) {
        if (state.coverGeneration == uploadedCoverGeneration) return
        uploadedCoverGeneration = state.coverGeneration
        val bitmap = state.coverBitmap?.takeUnless { it.isRecycled }
        if (bitmap != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticCoverTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            staticCoverWidth = bitmap.width.coerceAtLeast(1)
            staticCoverHeight = bitmap.height.coerceAtLeast(1)
        } else {
            uploadFallbackPlaceholder()
        }
    }

    private fun uploadFallbackPlaceholder() {
        val buf = ByteBuffer.allocateDirect(4).apply {
            put(0x14.toByte())
            put(0x14.toByte())
            put(0x18.toByte())
            put(0xFF.toByte())
            position(0)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, staticCoverTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            1,
            1,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buf,
        )
        staticCoverWidth = 1
        staticCoverHeight = 1
    }

    private fun ensureSize(width: Int, height: Int) {
        if (width == surfaceWidth && height == surfaceHeight && baseTarget != null) return
        releaseFbos()
        pyramidInitialized = false
        surfaceWidth = width
        surfaceHeight = height
        baseTarget = RenderFbo.create(width, height)
        val dims = ProgressiveBlurMath.pyramidDimensions(width, height, PYRAMID_LEVELS)
        pyramidTargets = dims.map { (w, h) -> RenderFbo.create(w, h) }
        tempTargets = dims.map { (w, h) -> RenderFbo.create(w, h) }
    }

    private fun drawQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(ATTR_POSITION)
        GLES20.glVertexAttribPointer(
            ATTR_POSITION,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * Float.SIZE_BYTES,
            0,
        )
        GLES20.glEnableVertexAttribArray(ATTR_TEX_COORD)
        GLES20.glVertexAttribPointer(
            ATTR_TEX_COORD,
            2,
            GLES20.GL_FLOAT,
            false,
            4 * Float.SIZE_BYTES,
            2 * Float.SIZE_BYTES,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun releaseFbos() {
        pyramidInitialized = false
        crossfadeTracker.onSurfaceReset()
        lastPyramidCrossfadeMix = 1f
        lastPyramidVideoMix = 1f
        baseTarget?.close()
        baseTarget = null
        outgoingBaseTarget?.close()
        outgoingBaseTarget = null
        pyramidTargets.forEach { it.close() }
        pyramidTargets = emptyList()
        tempTargets.forEach { it.close() }
        tempTargets = emptyList()
    }

    override fun close() {
        releaseFbos()
        mirrorVideoProgram.close()
        mirrorStaticProgram.close()
        kawaseDownsampleProgram.close()
        gaussianProgram.close()
        compositeProgram.close()
        GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        GLES20.glDeleteTextures(2, intArrayOf(oesTextureId, staticCoverTextureId), 0)
    }

    private class ShaderProgram(
        vertexSource: String,
        fragmentSource: String,
    ) : Closeable {
        val id: Int
        private val uniforms = HashMap<String, Int>()

        init {
            val vert = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val frag = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vert)
            GLES20.glAttachShader(id, frag)
            GLES20.glBindAttribLocation(id, ATTR_POSITION, "aPosition")
            GLES20.glBindAttribLocation(id, ATTR_TEX_COORD, "aTexCoord")
            GLES20.glLinkProgram(id)
            val status = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteShader(vert)
            GLES20.glDeleteShader(frag)
            check(status[0] == GLES20.GL_TRUE) { "Program link failed:\n$log" }
        }

        fun use() = GLES20.glUseProgram(id)
        fun int(name: String, value: Int) = GLES20.glUniform1i(loc(name), value)
        fun float(name: String, value: Float) = GLES20.glUniform1f(loc(name), value)
        fun vec2(name: String, x: Float, y: Float) = GLES20.glUniform2f(loc(name), x, y)
        fun vec4(name: String, x: Float, y: Float, z: Float, w: Float) =
            GLES20.glUniform4f(loc(name), x, y, z, w)
        fun mat4(name: String, m: FloatArray) =
            GLES20.glUniformMatrix4fv(loc(name), 1, false, m, 0)

        private fun loc(name: String): Int = uniforms.getOrPut(name) {
            GLES20.glGetUniformLocation(id, name)
        }

        override fun close() {
            GLES20.glDeleteProgram(id)
        }

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Shader compile failed:\n$log")
            }
            return shader
        }
    }

    private class RenderFbo private constructor(
        val width: Int,
        val height: Int,
        val texture: Int,
        private val framebuffer: Int,
    ) : Closeable {
        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, width, height)
        }

        override fun close() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }

        companion object {
            fun create(width: Int, height: Int): RenderFbo {
                val tex = create2dTexture()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    width,
                    height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
                val fboHandle = IntArray(1)
                GLES20.glGenFramebuffers(1, fboHandle, 0)
                val fbo = fboHandle[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    tex,
                    0,
                )
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    "Framebuffer incomplete: 0x${status.toString(16)}"
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                return RenderFbo(width, height, tex, fbo)
            }
        }
    }

    private companion object {
        const val PYRAMID_LEVELS = 7
        const val ATTR_POSITION = 0
        const val ATTR_TEX_COORD = 1

        val QUAD_DATA = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        val STATIC_TEX_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )

        fun createOesTexture(): Int {
            val handle = IntArray(1)
            GLES20.glGenTextures(1, handle, 0)
            val id = handle[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            return id
        }

        fun create2dTexture(): Int {
            val handle = IntArray(1)
            GLES20.glGenTextures(1, handle, 0)
            val id = handle[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return id
        }

        fun createQuadVbo(data: FloatBuffer): Int {
            val handle = IntArray(1)
            GLES20.glGenBuffers(1, handle, 0)
            val vbo = handle[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                data.capacity() * Float.SIZE_BYTES,
                data,
                GLES20.GL_STATIC_DRAW,
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            return vbo
        }
    }
}
