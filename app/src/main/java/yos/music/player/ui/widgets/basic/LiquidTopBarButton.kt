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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.ColorFilter
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
import yos.music.player.ui.widgets.liquid.classyabaBlackRim
import yos.music.player.ui.widgets.liquid.classyabaWhiteEdge
import yos.music.player.ui.widgets.liquid.glassBlurScale
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
    /**
     * **无圆形玻璃底时**（顶栏未滚动，`backdropAlpha ≈ 0`）的图标色。
     *
     * 圆底由材质层的 `layerBlock` 按 backdropAlpha 淡出，而图标是它的兄弟节点、不跟着淡：
     * 滚动前那颗钮只剩一个图标悬在页面裸背景上。背景不是主题白/黑的页面
     * （专辑/歌单/艺人页是封面提取色）上，固定的黑 85% / 白 85% 会与背景融为一体。
     *
     * 传入页面文字色后，图标色按 backdropAlpha 在「本参数（无底，需要自适应）」与
     * 「固定主题色（有底）」之间**绘制期**交叉淡化 —— 不新增状态、不触发逐帧重组。
     * null = 不做自适应，任何滚动位置都用固定主题色（主题底色页面的正确行为）。
     */
    idleIconTint: Color? = null,
    containerColor: Color = Color.Unspecified,
    draggable: Boolean = false,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isLightTheme = !isFlamingoInDarkMode()
    // 玻璃模糊强度倍率：在组合体内读，拖动设置页滑块时这颗按钮当场跟着变
    val glassScale = glassBlurScale
    // 材质与底栏胶囊 / 迷你播放器收起态同源（不再做自适应亮度采样）：
    // 表面色固定白 / #1C1C1E、alpha 0.5，页面若有封面提取色则在其上叠一层 tint
    // （浓度与 HSV 整形见 accentTintOf，明度被钳在安全区间，白面永远透得出来）。
    val accentTint = LocalTitleBarAccentTint.current
    val currentOnClick by rememberUpdatedState(onClick)
    val resolvedContainerColor = if (containerColor != Color.Unspecified) containerColor
    else if (isLightTheme) Color(0xFFFFFFFF).copy(0.5f)
    else Color(0xFF1C1C1E).copy(0.5f)
    val resolvedContentColor =
        if (isLightTheme) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)
    val edgeLightColor =
        if (isLightTheme) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.32f)
    // 描边分两套，与 ClassYaba LiquidButton 的判据一致：那边也是按**应用主题**
    // （GlasenseTheme.darkTheme）分流，不看 backdrop 亮度。本次只复刻浅色那套，
    // 深色保持项目原状（整圈均匀白环），所以这里只判主题。
    val rimHighlight: (() -> com.kyant.backdrop.highlight.Highlight?)? =
        if (isLightTheme) classyabaBlackRim else null
    val shadowColor = if (isLightTheme) "#06000000".toColorInt() else "#20000000".toColorInt()
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
            .drawWithCache {
                val path = Path()
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                var lastBlur = -1f
                onDrawBehind {
                    val spread = shadowAlpha()
                    if (spread > 0.01f) {
                        val blurRadius = (10f * density * spread).coerceAtLeast(0.1f)
                        val shadowSpread = (if (isLightTheme) 1f else 2f) * density * spread
                        val shadowOffsetY = if (isLightTheme) 3.5f * density * spread else 0f
                        val outerRadius = size.minDimension / 2f + shadowSpread
                        val innerRadius = size.minDimension / 2f
                        path.rewind()
                        path.addCircle(center.x, center.y + shadowOffsetY, outerRadius, Path.Direction.CW)
                        path.addCircle(center.x, center.y, innerRadius, Path.Direction.CCW)
                        paint.color = android.graphics.Color.argb(
                            (android.graphics.Color.alpha(shadowColor) * 3.2f).coerceAtMost(255f).toInt(),
                            android.graphics.Color.red(shadowColor),
                            android.graphics.Color.green(shadowColor),
                            android.graphics.Color.blue(shadowColor)
                        )
                        if (blurRadius != lastBlur) {
                            lastBlur = blurRadius
                            paint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawPath(path, paint)
                        }
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
                    // 与底栏胶囊 / 迷你播放器收起态逐项对齐：vibrancy + blur(4dp) + lens(8,24)。
                    // lens 保留 8/24（底栏是 16/32，但那是 58dp 高的全宽胶囊；42dp 小圆上
                    // 16dp 折射带宽占比过大，会糊成一圈）。
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx() * glassScale)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(8f.dp.toPx(), 24f.dp.toPx())
                        }
                    },
                    // 浅色模式描边改为 ClassYaba 配方（黑色环绕环，左右最深、上下保留
                    // baseline 0.4）：交给材质自身的 highlight 槽，不额外增加离屏图层。
                    // 深色模式保持原状（highlight = null + 下方 circleEdgeLight 白环）。
                    highlight = rimHighlight,
                    shadow = { null },
                    layerBlock = {
                        // 只保留滚动联动的玻璃淡入；按压/拖动变换已上移到外层整体图层
                        alpha = backdropAlpha()
                    },
                    onDrawSurface = {
                        drawRect(resolvedContainerColor)
                        // 封面提取色 tint：画在表面色之上、描边高光之下，层序与底栏
                        // containerSurfaceDraw 一致。无提取色的页面为 null，等于不叠。
                        if (accentTint != null && accentTint.alpha > 0.005f) {
                            drawRect(accentTint)
                        }
                        drawRect(Color.Black.copy(alpha = 0.03f * interactiveHighlight.pressProgress))
                    }
                )
                // 白色提亮边（上下直边最亮）：浅色模式下与上面的黑环合成 ClassYaba 的
                // 「左右暗、上下亮」立体描边。深色模式不移植，继续用整圈均匀白环。
                .then(
                    if (isLightTheme) Modifier.classyabaWhiteEdge(CircleShape) else Modifier
                )
                .then(
                    if (isLightTheme) Modifier
                    else Modifier.circleEdgeLight(
                        color = edgeLightColor,
                        width = 0.15f.dp,
                        blurRadius = 0.5f.dp
                    )
                )
                .then(interactiveHighlight.modifier)
                .then(if (draggable) interactiveHighlight.gestureModifier else interactiveHighlight.pressOnlyModifier)
                .zIndex(0f)
        )
        // 图标着色：需要自适应时按 backdropAlpha 在「无底的页面色」与「有底的主题色」
        // 之间绘制期混色（见 idleIconTint 注释）。用 ColorFilter.tint 挂在绘制期求值，
        // 不经过 Icon 的 tint 参数，避免逐帧重组。
        val baseIconTint = if (iconTint != Color.Unspecified) iconTint else resolvedContentColor
        val idleTint = idleIconTint
        val iconModifier = Modifier
            .size(iconSize)
            .offset(iconOffset.x, iconOffset.y)
            .zIndex(1f)
            .then(
                if (idleTint != null) {
                    Modifier.graphicsLayer {
                        // 圆底消失 = 图标直接压在页面背景上 → 用页面文字色
                        val presence = backdropAlpha().coerceIn(0f, 1f)
                        colorFilter = ColorFilter.tint(
                            androidx.compose.ui.graphics.lerp(idleTint, baseIconTint, presence)
                        )
                    }
                } else Modifier
            )
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            modifier = iconModifier,
            tint = if (idleTint != null) Color.White else baseIconTint
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

/**
 * [circleEdgeLight] 的圆角矩形 / 胶囊版：描边画在轮廓上再裁剪进内部（只显示内半圈），
 * NORMAL 模糊 + ADD 混合，参数口径与圆形版一致（width 0.15dp / blur 0.5dp）。
 *
 * 原先私有在 [yos.music.player.ui.pages.settings.performance.userinterface.GlassEffectSetting]
 * 里只服务演示胶囊；音乐画廊的播放药丸也要同一道内缘光，故上收到此处共用，
 * 避免同一段实现留两份。
 */
internal fun Modifier.capsuleEdgeLight(
    color: Color,
    width: Dp,
    blurRadius: Dp
): Modifier = drawWithContent {
    drawContent()
    if (color.alpha <= 0f || width <= 0.dp) return@drawWithContent
    val radius = size.height / 2f
    val strokePx = ceil(width.toPx().coerceAtMost(radius)) * 2f
    val blurPx = blurRadius.toPx()
    val path = Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, radius, radius, Path.Direction.CW)
    }
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx
            if (blurPx > 0f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        }
        canvas.save()
        canvas.nativeCanvas.clipPath(path)
        canvas.nativeCanvas.drawPath(path, paint)
        canvas.restore()
    }
}
