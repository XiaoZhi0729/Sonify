package yos.music.player.ui.widgets.dropdown

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

// =====================================================================
// NexioSchedule 1.6.5 弹层引擎的宿主侧适配层。
//
// 原版依赖 miuix / kyant backdrop fork 的若干符号，本项目用与它们等价的既有实现顶替，
// 语义逐项对齐（见交接文档 §1.5）。组件本体（ListPopup / ListPopupLayout /
// LiquidGlassDropdownMenu）保持逐字移植，差异只集中在下面这几处。
// =====================================================================

/** SinOut 缓动（Nexio `anim/SinOutEasing.kt` 逐字）。 */
internal val SinOutEasing: Easing = Easing { fraction ->
    sin((fraction * PI / 2).toFloat())
}

/** 原版 `com.kyant.backdrop.isRenderEffectSupported()`：API 33+ 才有 RenderEffect 链路。 */
internal fun isRenderEffectSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** 原版 AppMaterialSettings.chromeLensEnabled()：本项目恒开（与既有玻璃控件一致）。 */
internal val LocalChromeLensEnabled = staticCompositionLocalOf { true }

/** 原版 PredictiveBackSettings.enabled：本项目恒开。 */
internal val LocalPredictiveBackEnabled = staticCompositionLocalOf { true }

/**
 * 原版 `utils/NavigationBack.kt` 的 Android 实现（androidx.navigationevent）。
 *
 * 本项目返回桥是 HouseBackDispatcherOwner，没有手势进度通道，因此这里降级为
 * BackHandler：`backProgress()` 恒为 null，跟手分支不触发，返回键/返回手势照常关闭弹层。
 */
internal class NavigationBackState {
    val isTransitioningBack: () -> Boolean = { false }

    val backProgress: () -> Float? = { null }

    @Composable
    fun handler(
        enabled: Boolean,
        onBackCompleted: () -> Unit,
        onBackCancelled: () -> Unit,
    ) {
        BackHandler(enabled = enabled) { onBackCompleted() }
    }
}

@Composable
internal fun rememberNavigationBack(): NavigationBackState = remember { NavigationBackState() }

/** 原版 `MiuixTheme.colorScheme.surfaceContainer`（浅 FAFAFA / 深 242424）。 */
@Composable
internal fun dropdownSurfaceContainer(): Color = Color(0xFFFAFAFA) withNight Color(0xFF242424)

/** 原版 `isAppDarkTheme()`。 */
@Composable
internal fun isDropdownDarkTheme(): Boolean = isFlamingoInDarkMode()

/**
 * 原版 `rememberDefaultEdgeLight`（Uniform 路径）：
 * 浅色 White 0.6 / 0.24dp / blur 0.4dp，深色 White 0.2 / 0.34dp / blur 0.4dp。
 */
internal data class DropdownEdgeLight(
    val color: Color,
    val width: Dp,
    val blurRadius: Dp,
)

@Composable
internal fun rememberDropdownEdgeLight(): DropdownEdgeLight {
    val light = !isDropdownDarkTheme()
    return remember(light) {
        DropdownEdgeLight(
            color = if (light) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.2f),
            width = if (light) 0.24.dp else 0.34.dp,
            blurRadius = 0.4.dp,
        )
    }
}

/**
 * 原版 `Modifier.edgeLight(shape, edgeLight)` 的 Uniform 等效实现
 * （与项目内 `rectEdgeLight` / `circleEdgeLight` 同一套画法）：
 * 描边画在形状边界上再裁剪进形状内，NORMAL 模糊 + ADD 混合。
 */
internal fun Modifier.dropdownEdgeLight(
    shape: Shape,
    spec: DropdownEdgeLight,
): Modifier = drawWithContent {
    drawContent()
    if (spec.color.alpha <= 0f || spec.width <= 0.dp) return@drawWithContent
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path()
    when (outline) {
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
        is Outline.Rectangle -> path.addRect(outline.rect)
    }
    val strokePx = ceil(spec.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
    val blurPx = spec.blurRadius.toPx()
    val nativePath = path.asAndroidPath()
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            color = spec.color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx
            if (blurPx > 0f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        }
        canvas.save()
        canvas.nativeCanvas.clipPath(nativePath)
        canvas.nativeCanvas.drawPath(nativePath, paint)
        canvas.restore()
    }
}

/**
 * 原版 `top.yukonga.miuix.kmp.theme.LocalDismissState`：弹窗内容里读「关闭本弹窗」的通道。
 * 本项目菜单行直接持有 onClick，仅作为移植引擎的兼容符号保留。
 */
internal val LocalDismissState = androidx.compose.runtime.compositionLocalOf<() -> Unit> { {} }


/**
 * 原版 `com.kyant.capsule.ContinuousRoundedRectangle` 的等效实现：
 * 平滑圆角矩形，**圆角半径超过半边长时收缩**（42dp 收起态因此被"胶囊化"成正圆）。
 *
 * 直接用 [YosRoundedCornerShape] 不行：它不钳制半径，25dp 圆角画在 42dp 方块上
 * 路径控制点会越界，在上下左右四个中点向外鼓出三角形（1.6.5 移植实测翻车点）。
 *
 * **必须继承 [androidx.compose.foundation.shape.CornerBasedShape]**：backdrop 的
 * lens 折射要从角半径算边缘 SDF，只认 RoundedRectangularShape / CornerBasedShape，
 * 普通 Shape 直接抛 UnsupportedOperationException（2026-10-24 真机崩溃实证）。
 * lens 侧自己也会把角半径钳到半边长，与本类的渲染钳制一致。
 */
internal class ClampedSquircleShape(
    cornerRadius: Dp,
) : androidx.compose.foundation.shape.CornerBasedShape(
    topStart = androidx.compose.foundation.shape.CornerSize(cornerRadius),
    topEnd = androidx.compose.foundation.shape.CornerSize(cornerRadius),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(cornerRadius),
    bottomStart = androidx.compose.foundation.shape.CornerSize(cornerRadius),
) {
    private val radiusDp: Dp = cornerRadius

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        // 抽象签名不带 density：由「角半径 px / Dp 值」反推缩放（基类就是用它换算的 px）
        val scale = if (radiusDp.value > 0f) topStart / radiusDp.value else 1f
        val maxPx = size.minDimension / 2f
        val clampedDp = (topStart.coerceAtMost(maxPx) / scale).dp
        return YosRoundedCornerShape(clampedDp)
            .createOutline(size, layoutDirection, Density(scale))
    }

    // 本项目没有走 copy 的调用点（Material 组件缩放角才会用），按原半径回一个
    override fun copy(
        topStart: androidx.compose.foundation.shape.CornerSize,
        topEnd: androidx.compose.foundation.shape.CornerSize,
        bottomEnd: androidx.compose.foundation.shape.CornerSize,
        bottomStart: androidx.compose.foundation.shape.CornerSize,
    ): androidx.compose.foundation.shape.CornerBasedShape = ClampedSquircleShape(radiusDp)
}

/** [ClampedSquircleShape] 的便捷构造。 */
internal fun squircle(cornerRadius: Dp): Shape = ClampedSquircleShape(cornerRadius)
