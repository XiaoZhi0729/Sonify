package yos.music.player.ui.widgets.basic

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import kotlin.math.ceil

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
    val backdropLayer = rememberGraphicsLayer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val backdropRecordVersion = remember { longArrayOf(0L) }
    val samplingWarningLogged = remember { booleanArrayOf(false) }
    val sampledLuminance = remember { mutableStateOf(if (isLightTheme) 1f else 0f) }
    LaunchedEffect(backdropLayer, adaptiveLuminance, lifecycle) {
        if (!adaptiveLuminance || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var sampledVersion = 0L
            var emptyVersion = -1L
            var consecutiveFailures = 0
            val pixels = IntArray(25)
            while (isActive) {
                delay(250)
                val version = backdropRecordVersion[0]
                if (version == 0L || version == sampledVersion) continue
                try {
                    val image = backdropLayer.toImageBitmap()
                    // GraphicsLayer snapshots can be HARDWARE bitmaps: read only a software copy.
                    val bitmap = checkNotNull(
                        image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                    )
                    val luminance = try {
                        for (row in 0 until 5) for (column in 0 until 5) {
                            val x = column * (bitmap.width - 1) / 4
                            val y = row * (bitmap.height - 1) / 4
                            pixels[row * 5 + column] = bitmap.getPixel(x, y)
                        }
                        liquidBackdropLuminance(pixels)
                    } finally {
                        // This copy is ours; the original snapshot belongs to Compose.
                        bitmap.recycle()
                    }
                    consecutiveFailures = 0
                    if (luminance != null) {
                        sampledLuminance.value = luminance
                        sampledVersion = version
                    } else if (emptyVersion == version) {
                        sampledVersion = version
                    } else {
                        // A first capture may not be ready. Retry once even without another draw.
                        emptyVersion = version
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!samplingWarningLogged[0]) {
                        samplingWarningLogged[0] = true
                        Log.w("LiquidTopBarButton", "Adaptive backdrop sampling failed", e)
                    }
                    // Retry a transient first capture, then wait for a new record. Back off
                    // across records too, so a persistent failure cannot cause a capture storm.
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    if (consecutiveFailures >= 2) sampledVersion = version
                    delay((1000L shl consecutiveFailures).coerceAtMost(30_000L))
                }
            }
        }
    }
    val adaptiveContentColor by animateColorAsState(
        targetValue = if (adaptiveLuminance && sampledLuminance.value > 0.5f) Color.Black else Color.White,
        animationSpec = tween(1000),
        label = "liquidButtonContentColor"
    )
    val animatedLuminance by animateFloatAsState(sampledLuminance.value, tween(1000), label = "glassLuminance")
    val currentOnClick by rememberUpdatedState(onClick)
    val resolvedContainerColor = if (containerColor != Color.Unspecified) containerColor
    else if (adaptiveLuminance) Color.White.copy(alpha = 0.10f)
    else if (isLightTheme) Color(0xFFFFFFFF).copy(0.76f)
    else Color(0xFF242424).copy(0.84f)
    val edgeLightColor = if (adaptiveLuminance) adaptiveContentColor.copy(alpha = 0.55f)
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
                        if (adaptiveLuminance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val signed = animatedLuminance * 2f - 1f
                            val l = signed * kotlin.math.abs(signed)
                            colorControls(
                                brightness = if (l > 0f) 0.1f + 0.4f * l else 0.1f + 0.3f * l,
                                contrast = if (l > 0f) 1f - l else 1f,
                                saturation = 1.5f
                            )
                            blur((if (l > 0f) 8f + 8f * l else 8f + 6f * l).dp.toPx())
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
                    onDrawBackdrop = { drawBackdrop ->
                        drawBackdrop()
                        if (adaptiveLuminance && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Only the backdrop is recorded: no surface, edge, icon, or producer recursion.
                            backdropLayer.record { drawBackdrop() }
                            backdropRecordVersion[0]++
                        }
                    },
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
            else if (adaptiveLuminance) adaptiveContentColor
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
