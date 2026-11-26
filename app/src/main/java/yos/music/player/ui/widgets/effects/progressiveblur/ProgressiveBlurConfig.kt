package yos.music.player.ui.widgets.effects.progressiveblur

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ProgressiveOrientationMode {
    HORIZONTAL,
    VERTICAL,
    TOP_LEFT_2D,
}

enum class ProgressiveBlurMode {
    TRUE_PROGRESSIVE,
    ALPHA_MASKED_IOS,
}

enum class ProgressiveCurveType {
    SOFTERSTEP,
    SMOOTHSTEP,
}

enum class DemoBackgroundMode {
    OFF,
    DRIFT,
    MESH,
}

@Immutable
data class ResolvedMirrorSplits(
    val splitX: Float,
    val splitY: Float,
)

/**
 * Configuration for the progressive-blur dynamic cover background.
 *
 * [coverSize] locks the crisp cover tile to a 1:1 square relative to the screen's layout axis,
 * and [ProgressiveBlurMath.resolveMirrorSplits] derives `(splitX, splitY)` automatically from the
 * viewport dimensions so the cover never stretches into a non-square rectangle.
 */
@Immutable
data class ProgressiveBlurConfig(
    val orientationMode: ProgressiveOrientationMode = ProgressiveOrientationMode.HORIZONTAL,
    val blurMode: ProgressiveBlurMode = ProgressiveBlurMode.TRUE_PROGRESSIVE,
    val curveType: ProgressiveCurveType = ProgressiveCurveType.SOFTERSTEP,
    val coverSize: Float = 1.0f,
    val blurStart: Float = 0.29f,
    val blurEnd: Float = 0.67f,
    val blurStartY: Float = 0.48f,
    val blurEndY: Float = 0.76f,
    val maxBlurLevel: Float = 6.20f,
    val ambientBlend: Float = 0.68f,
    val curvePower: Float = 0.79f,
    val scrimMaxAlpha: Float = 0.26f,
    val scrimPower: Float = 1.41f,
    val blurSaturation: Float = 1.15f,
    val showDebugGuides: Boolean = false,
) {
    /** Returns a copy with values clamped into valid shader ranges and `blurEnd >= blurStart + 0.02f`. */
    fun sanitized(): ProgressiveBlurConfig {
        val safeCoverSize = if (orientationMode == ProgressiveOrientationMode.TOP_LEFT_2D) {
            coverSize.coerceIn(0.35f, 0.95f)
        } else {
            coverSize.coerceIn(0.65f, 1.25f)
        }
        val safeStart = blurStart.coerceIn(0.0f, 0.90f)
        val safeEnd = blurEnd.coerceIn((safeStart + 0.02f).coerceAtMost(1.0f), 1.0f)

        val safeStartY = blurStartY.coerceIn(0.0f, 0.90f)
        val safeEndY = blurEndY.coerceIn((safeStartY + 0.02f).coerceAtMost(1.0f), 1.0f)

        return copy(
            coverSize = safeCoverSize,
            blurStart = safeStart,
            blurEnd = safeEnd,
            blurStartY = safeStartY,
            blurEndY = safeEndY,
            maxBlurLevel = maxBlurLevel.coerceIn(0.0f, 7.0f),
            ambientBlend = ambientBlend.coerceIn(0.0f, 0.85f),
            curvePower = curvePower.coerceIn(0.4f, 3.0f),
            scrimMaxAlpha = scrimMaxAlpha.coerceIn(0.0f, 0.85f),
            scrimPower = scrimPower.coerceIn(0.5f, 3.0f),
            blurSaturation = blurSaturation.coerceIn(0.7f, 1.6f),
        )
    }

    companion object {
        fun defaultTabletLandscape(): ProgressiveBlurConfig = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.TOP_LEFT_2D,
            blurMode = ProgressiveBlurMode.TRUE_PROGRESSIVE,
            curveType = ProgressiveCurveType.SOFTERSTEP,
            coverSize = 0.65f,
            blurStart = 0.35f,
            blurEnd = 0.56f,
            blurStartY = 0.38f,
            blurEndY = 0.80f,
            maxBlurLevel = 5.95f,
            ambientBlend = 0.85f,
            curvePower = 0.96f,
            scrimMaxAlpha = 0.22f,
            scrimPower = 2.84f,
            blurSaturation = 1.28f,
            showDebugGuides = false,
        )

        fun defaultTabletPortrait(): ProgressiveBlurConfig = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.VERTICAL,
            blurMode = ProgressiveBlurMode.TRUE_PROGRESSIVE,
            curveType = ProgressiveCurveType.SOFTERSTEP,
            coverSize = 1.00f,
            blurStart = 0.44f,
            blurEnd = 0.84f,
            blurStartY = 0.48f,
            blurEndY = 0.76f,
            maxBlurLevel = 6.20f,
            ambientBlend = 0.68f,
            curvePower = 0.79f,
            scrimMaxAlpha = 0.26f,
            scrimPower = 1.41f,
            blurSaturation = 1.15f,
            showDebugGuides = false,
        )

        fun defaultPhoneLandscape(): ProgressiveBlurConfig = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.HORIZONTAL,
            blurMode = ProgressiveBlurMode.TRUE_PROGRESSIVE,
            curveType = ProgressiveCurveType.SOFTERSTEP,
            coverSize = 1.00f,
            blurStart = 0.33f,
            blurEnd = 0.57f,
            blurStartY = 0.48f,
            blurEndY = 0.76f,
            maxBlurLevel = 4.99f,
            ambientBlend = 0.49f,
            curvePower = 0.83f,
            scrimMaxAlpha = 0.11f,
            scrimPower = 0.96f,
            blurSaturation = 1.17f,
            showDebugGuides = false,
        )

        fun defaultPhonePortrait(): ProgressiveBlurConfig = ProgressiveBlurConfig(
            orientationMode = ProgressiveOrientationMode.VERTICAL,
            blurMode = ProgressiveBlurMode.TRUE_PROGRESSIVE,
            curveType = ProgressiveCurveType.SOFTERSTEP,
            coverSize = 1.00f,
            blurStart = 0.29f,
            blurEnd = 0.67f,
            blurStartY = 0.48f,
            blurEndY = 0.76f,
            maxBlurLevel = 6.20f,
            ambientBlend = 0.68f,
            curvePower = 0.79f,
            scrimMaxAlpha = 0.26f,
            scrimPower = 1.41f,
            blurSaturation = 1.15f,
            showDebugGuides = false,
        )

        fun defaultLandscape(): ProgressiveBlurConfig = defaultPhoneLandscape()

        fun defaultPortrait(): ProgressiveBlurConfig = defaultPhonePortrait()
    }
}

/**
 * Pure Kotlin reference math mirroring the progressive-blur GLSL shaders for unit tests and UI helpers.
 */
object ProgressiveBlurMath {

    const val TABLET_SPLIT_CONTROLS_CENTER_X = 0.225f
    const val TABLET_LANDSCAPE_REF_SPLIT_X = 0.65f / 1.4f
    const val LEFT_BLUR_ORIGIN_RAMP = 0.008f

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val span = max(edge1 - edge0, 0.0001f)
        val t = ((x - edge0) / span).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun softerstep(edge0: Float, edge1: Float, x: Float): Float {
        val span = max(edge1 - edge0, 0.0001f)
        val t = ((x - edge0) / span).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }

    fun resolveMirrorSplits(
        viewportWidth: Float,
        viewportHeight: Float,
        config: ProgressiveBlurConfig,
    ): ResolvedMirrorSplits {
        val c = config.sanitized()
        val w = max(viewportWidth, 1f)
        val h = max(viewportHeight, 1f)
        return when (c.orientationMode) {
            ProgressiveOrientationMode.HORIZONTAL -> ResolvedMirrorSplits(
                splitX = ((h * c.coverSize) / w).coerceIn(0.20f, 0.85f),
                splitY = 1f,
            )
            ProgressiveOrientationMode.VERTICAL -> ResolvedMirrorSplits(
                splitX = 1f,
                splitY = ((w * c.coverSize) / h).coerceIn(0.25f, 0.85f),
            )
            ProgressiveOrientationMode.TOP_LEFT_2D -> {
                val side = min(w, h) * c.coverSize
                ResolvedMirrorSplits(
                    splitX = (side / w).coerceIn(0.20f, 0.85f),
                    splitY = (side / h).coerceIn(0.20f, 0.85f),
                )
            }
        }
    }

    fun axisProgression(
        coord: Float,
        start: Float,
        end: Float,
        curveType: ProgressiveCurveType,
        curvePower: Float,
    ): Float {
        val safeEnd = max(end, start + 0.02f)
        val s = when (curveType) {
            ProgressiveCurveType.SOFTERSTEP -> softerstep(start, safeEnd, coord)
            ProgressiveCurveType.SMOOTHSTEP -> smoothstep(start, safeEnd, coord)
        }
        return s.coerceIn(0f, 1f).pow(curvePower.coerceAtLeast(0.1f))
    }

    fun progression(axis: Float, config: ProgressiveBlurConfig): Float {
        val c = config.sanitized()
        return axisProgression(axis, c.blurStart, c.blurEnd, c.curveType, c.curvePower)
    }

    fun progression2D(x: Float, y: Float, config: ProgressiveBlurConfig): Float {
        val c = config.sanitized()
        return when (c.orientationMode) {
            ProgressiveOrientationMode.HORIZONTAL ->
                axisProgression(x, c.blurStart, c.blurEnd, c.curveType, c.curvePower)
            ProgressiveOrientationMode.VERTICAL ->
                axisProgression(y, c.blurStart, c.blurEnd, c.curveType, c.curvePower)
            ProgressiveOrientationMode.TOP_LEFT_2D -> {
                val ux = axisProgression(x, c.blurStart, c.blurEnd, c.curveType, c.curvePower)
                val uy = axisProgression(y, c.blurStartY, c.blurEndY, c.curveType, c.curvePower)
                1f - (1f - ux) * (1f - uy)
            }
        }
    }

    /**
     * Normalized X origin when docked to the split-pane column in tablet landscape (`alignLeftProgress = 1`).
     * Flush-left (`0f`) on 4:3 and 7:5, and shifted right on wider ratios (e.g. 16:10, 16:9, 20:9)
     * so the 1:1 cover remains centered directly above the 45% left media column controls (`0.225f`).
     */
    fun dockedOriginX(splitX: Float): Float {
        val clampedSplit = splitX.coerceIn(0.20f, 0.85f)
        return (TABLET_SPLIT_CONTROLS_CENTER_X - clampedSplit * 0.5f).coerceAtLeast(0f)
    }

    /**
     * Normalized X origin of the primary cover tile when morphing between centered (`alignLeftProgress = 0`)
     * and docked (`alignLeftProgress = 1`) in tablet landscape.
     */
    fun centeredOriginX(splitX: Float, alignLeftProgress: Float): Float {
        val p = alignLeftProgress.coerceIn(0f, 1f)
        val clampedSplit = splitX.coerceIn(0.20f, 0.85f)
        val centered = ((1f - clampedSplit) * 0.5f).coerceAtLeast(0f)
        val docked = dockedOriginX(clampedSplit)
        return centered + (docked - centered) * p
    }

    /**
     * Resolves aspect-ratio-invariant horizontal blur transition edges for [ProgressiveOrientationMode.TOP_LEFT_2D].
     * Scales `blurStart`/`blurEnd` proportionally with [splitX] relative to the 7:5 tablet reference so the
     * cover-interior blur onset and seam blur stay consistent on elongated 16:10 / 16:9 / 20:9 screens.
     */
    fun resolveHorizontalBlurEdges(
        splitX: Float,
        config: ProgressiveBlurConfig,
    ): Pair<Float, Float> {
        val c = config.sanitized()
        if (c.orientationMode != ProgressiveOrientationMode.TOP_LEFT_2D) {
            return c.blurStart to c.blurEnd
        }
        val clampedSplit = splitX.coerceIn(0.20f, 0.85f)
        val scaleX = (clampedSplit / TABLET_LANDSCAPE_REF_SPLIT_X).coerceIn(0.40f, 1.35f)
        val start = (c.blurStart * scaleX).coerceIn(0f, 0.90f)
        val end = (c.blurEnd * scaleX).coerceIn((start + 0.02f).coerceAtMost(1f), 1f)
        return start to end
    }

    /**
     * Exact Kotlin mirror of the shader's 2D progression with horizontal [coverOriginX] offset.
     * When [coverOriginX] is non-zero (centered in Album mode or docked on elongated ratios), applies
     * symmetric left + right horizontal feathering combined smoothly with vertical feathering.
     */
    fun progression2DWithOrigin(
        x: Float,
        y: Float,
        config: ProgressiveBlurConfig,
        splitX: Float,
        coverOriginX: Float,
    ): Float {
        val c = config.sanitized()
        if (c.orientationMode != ProgressiveOrientationMode.TOP_LEFT_2D) {
            return progression2D(x, y, c)
        }
        val origin = coverOriginX.coerceIn(0f, 0.45f)
        val (effStartX, effEndX) = resolveHorizontalBlurEdges(splitX, c)
        val uxRight = axisProgression(
            x,
            origin + effStartX,
            origin + effEndX,
            c.curveType,
            c.curvePower,
        )
        val clampedSplitX = splitX.coerceIn(0.20f, 0.85f)
        val leftEnable = smoothstep(0f, LEFT_BLUR_ORIGIN_RAMP, origin)
        val reflectedX = 2f * origin + clampedSplitX - x
        val uxLeft = if (leftEnable > 0f) {
            leftEnable * axisProgression(
                reflectedX,
                origin + effStartX,
                origin + effEndX,
                c.curveType,
                c.curvePower,
            )
        } else {
            0f
        }
        val ux = 1f - (1f - uxRight) * (1f - uxLeft)
        val uy = axisProgression(y, c.blurStartY, c.blurEndY, c.curveType, c.curvePower)
        return 1f - (1f - ux) * (1f - uy)
    }

    fun scrimAlpha(axis: Float, config: ProgressiveBlurConfig): Float {
        val c = config.sanitized()
        val u = progression(axis, c)
        return (c.scrimMaxAlpha * u.pow(c.scrimPower.coerceAtLeast(0.25f))).coerceIn(0f, 0.95f)
    }

    fun scrimAlpha2D(x: Float, y: Float, config: ProgressiveBlurConfig): Float {
        val c = config.sanitized()
        val u = progression2D(x, y, c)
        return (c.scrimMaxAlpha * u.pow(c.scrimPower.coerceAtLeast(0.25f))).coerceIn(0f, 0.95f)
    }

    /**
     * Triangle-wave reflection around [mirrorSplit], C0-continuous at `coord == mirrorSplit`
     * and smoothly folded across `[0, 1]` even when `mirrorSplit < 0.5f`.
     */
    fun mirroredTileCoord(coord: Float, mirrorSplit: Float): Float {
        val split = max(mirrorSplit, 0.001f)
        val uNorm = coord / split
        val period = ((uNorm % 2f) + 2f) % 2f
        return if (period <= 1f) period else 2f - period
    }

    /** Computes `(width, height)` for each downsampled Gaussian pyramid level, clamped to `>= 1`. */
    fun pyramidDimensions(baseWidth: Int, baseHeight: Int, levels: Int = 7): List<Pair<Int, Int>> {
        if (levels <= 0) return emptyList()
        var w = baseWidth.coerceAtLeast(1)
        var h = baseHeight.coerceAtLeast(1)
        return List(levels) {
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
            w to h
        }
    }
}
