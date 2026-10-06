package yos.music.player.ui.widgets.basic

import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import kotlin.math.ceil

/** 非自适应路径的稳定单例 onDrawBackdrop（库默认值同款）：内联 lambda 会被
 * DrawBackdropElement.equals 判为参数变化，每次重组都 invalidateDrawCache。 */
private val DefaultButtonOnDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = {
    it()
}

/**
 * 顶栏液态玻璃圆钮（移植自 NexioSchedule LiquidTopBarButton，材质保持一致）：
 * 42dp 圆，vibrancy + blur + lens 采样 backdrop 内容，白色边缘光环 + 背后环形投影，
 * 按压时放大 2dp 当量、弥散高光跟随按压点；点击带 Confirm 触感反馈。
 * draggable = true 时按住可拖动：高光跟手、按钮朝手指方向微倾（≤5%）、松手回弹；
 * 此模式下拖动手势层会消费指针事件，点击改由 InteractiveHighlight 的 onTap 承接
 * （tap 判定 = 按下后未超过 touchSlop 即抬起），clickable 仅保留语义用途。
 *
 * 与原版的差异：
 * - backdropAlpha/shadowAlpha 为 lambda，在绘制期直读（Animatable 驱动的滚动联动
 *   透明度不触发整按钮重组）；
 * - lens 加 TIRAMISU 门禁（项目 minSdk 23，随 MainActivity 惯例）；
 * - 边缘光用圆形等效实现 [circleEdgeLight]，替代 Nexio 完整 EdgeLight 子系统的
 *   Uniform 路径，视觉参数对齐：0.15dp 描边 / 0.5dp 模糊 / 浅色白 0.8、深色白 0.32；
 * - pressOnly 高光位置在按下点（原版固定在 (0,0) 角落，已在 InteractiveHighlight 修正）。
 */
@Composable
fun LiquidTopBarButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    icon: Painter,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    iconOffset: DpOffset = DpOffset.Zero,
    buttonHeight: Dp = 42.dp,
    backdropAlpha: () -> Float = { 1f },
    shadowAlpha: () -> Float = { 1f },
    iconTint: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
    draggable: Boolean = false,
    adaptiveLuminance: Boolean = false
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isLightTheme = !isFlamingoInDarkMode()
    // 自适应亮度共享设施（AdaptiveLuminanceGlass demo 同款，实现见 BackdropAdaptiveLuminance）；
    // null = 关闭或 API<31，走主题静态材质。
    val luminance = rememberAdaptiveBackdropLuminance(
        enabled = adaptiveLuminance,
        tag = "LiquidTopBarButton",
        initialLuminance = if (isLightTheme) 1f else 0f
    )
    val adaptiveContentColor = luminance?.contentColor() ?: Color.White
    val currentOnClick by rememberUpdatedState(onClick)
    val resolvedContainerColor = if (containerColor != Color.Unspecified) containerColor
    else if (luminance != null) Color.White.copy(alpha = 0.10f)
    else if (isLightTheme) Color(0xFFFFFFFF).copy(0.76f)
    else Color(0xFF242424).copy(0.84f)
    val edgeLightColor = if (luminance != null) adaptiveContentColor.copy(alpha = 0.55f)
    else if (isLightTheme) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.32f)
    val shadowColor = if (isLightTheme) "#12000000".toColorInt() else "#20000000".toColorInt()
    val interactionSource = remember { MutableInteractionSource() }
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            // draggable 时手势层会吃掉指针事件、clickable 收不到点击，
            // 未拖动即抬起由 onTap 承接点击（含触感），与 LiquidBottomTabs 同机制
            onTap = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                currentOnClick()
            }
        )
    }

    Box(
        modifier = modifier
            // 按压缩放 + 拖动倾斜作用在整个按钮（投影/玻璃/边缘光/图标同一图层），
            // 而不是 drawBackdrop 的采样层：采样层内动会让磨砂内容在固定的圆形
            // 窗口里滑动（看着像被圆罩圈住），图标也不会跟玻璃一起动。
            .graphicsLayer {
                val progress = interactiveHighlight.pressProgress
                // 按压放大 +6dp 当量（42dp 圆 ≈14%）；原版 +2dp、+4dp 用户仍觉偏弱
                val scale = 1f + 6f.dp.toPx() / buttonHeight.toPx() * progress
                scaleX = scale
                scaleY = scale
                val pressOffset = interactiveHighlight.offset
                translationX = size.minDimension * 0.05f * pressOffset.x / size.maxDimension
                translationY = size.minDimension * 0.05f * pressOffset.y / size.maxDimension
            }
            .size(buttonHeight)
            .drawBehind {
                val spread = shadowAlpha()
                if (spread > 0.01f) {
                    val blurRadius = 10f * density * spread
                    val shadowSpread = 2f * density * spread
                    val outerRadius = size.minDimension / 2f + shadowSpread
                    val innerRadius = size.minDimension / 2f
                    val path = Path().apply {
                        addCircle(center.x, center.y, outerRadius, Path.Direction.CW)
                        addCircle(center.x, center.y, innerRadius, Path.Direction.CCW)
                    }
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            (android.graphics.Color.alpha(shadowColor) * 3.2f).coerceAtMost(255f).toInt(),
                            android.graphics.Color.red(shadowColor),
                            android.graphics.Color.green(shadowColor),
                            android.graphics.Color.blue(shadowColor)
                        )
                        maskFilter = BlurMaskFilter(
                            blurRadius.coerceAtLeast(0.1f),
                            BlurMaskFilter.Blur.NORMAL
                        )
                    }
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawPath(path, paint)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(buttonHeight)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    role = Role.Button,
                    // draggable 模式下 DragGestureInspector 全程不消费指针事件，
                    // clickable 与 InteractiveHighlight.onTap 会双触发 → navigate 两次、
                    // 路由压栈两次（系统返回要弹两次）。点击唯一走 onTap，这里留空。
                    onClick = {
                        if (!draggable) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            currentOnClick()
                        }
                    }
                )
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        if (luminance != null) {
                            val l = luminance.l()
                            adaptiveGlassColorControls(l)
                            blur(adaptiveBlurPx(8f.dp.toPx(), l))
                        } else {
                            vibrancy()
                            blur(4.dp.toPx())
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(8f.dp.toPx(), 24f.dp.toPx())
                        }
                    },
                    highlight = { null },
                    shadow = { null },
                    layerBlock = {
                        // 只保留滚动联动的玻璃淡入；按压/拖动变换已上移到外层整体图层
                        alpha = backdropAlpha()
                    },
                    onDrawBackdrop = if (luminance != null) luminance.onDrawBackdrop
                    else DefaultButtonOnDrawBackdrop,
                    onDrawSurface = {
                        drawRect(resolvedContainerColor)
                        drawRect(Color.Black.copy(alpha = 0.03f * interactiveHighlight.pressProgress))
                    }
                )
                .circleEdgeLight(color = edgeLightColor, width = 0.15f.dp, blurRadius = 0.5f.dp)
                .then(interactiveHighlight.modifier)
                .then(if (draggable) interactiveHighlight.gestureModifier else interactiveHighlight.pressOnlyModifier)
                .zIndex(0f)
        )
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(iconSize)
                .offset(iconOffset.x, iconOffset.y)
                .zIndex(1f),
            tint = if (iconTint != Color.Unspecified) iconTint
            else if (luminance != null) adaptiveContentColor
            else if (isLightTheme) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)
        )
    }
}

/**
 * Uniform 边缘光的圆形等效实现：描边画在圆边界上再裁剪进圆内（只显示内半圈），
 * NORMAL 模糊 + PLUS 混合。对照 NexioSchedule EdgeLightModifier 的 Uniform 路径
 * （strokeWidth = ceil(width px) × 2，强度即图层 alpha）。
 */
internal fun Modifier.circleEdgeLight(
    color: Color,
    width: Dp,
    blurRadius: Dp
): Modifier = drawWithContent {
    drawContent()
    if (color.alpha <= 0f || width <= 0.dp) return@drawWithContent
    val radius = size.minDimension / 2f
    val strokePx = ceil(width.toPx().coerceAtMost(radius)) * 2f
    val blurPx = blurRadius.toPx()
    val path = Path().apply { addCircle(center.x, center.y, radius, Path.Direction.CW) }
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx
            if (blurPx > 0f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            xfermode = android.graphics.PorterDuffXfermode(
                if (color.red + color.green + color.blue < 1.5f) android.graphics.PorterDuff.Mode.SRC_OVER
                else android.graphics.PorterDuff.Mode.ADD
            )
        }
        canvas.save()
        canvas.nativeCanvas.clipPath(path)
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, paint)
        canvas.restore()
    }
}
