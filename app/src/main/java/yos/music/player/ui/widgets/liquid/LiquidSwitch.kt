package yos.music.player.ui.widgets.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import io.github.alexzhirkevich.cupertino.CupertinoSwitch
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.settingContainerBack
import yos.music.player.ui.theme.settingContainerBackDark
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * 液态玻璃开关，交互与视觉对齐 backdrop 1.0.5 官方 catalog 的 LiquidToggle：
 * 静止时是不透明白色胶囊拇指；按压/横向拖动时拇指变为透明玻璃球——白色表面淡出、
 * lens+色差与 Highlight.Ambient/InnerShadow 淡入，轨道内容以缩小比例映进玻璃球。
 *
 * backdrop 拓扑：轨道层记录为 trackBackdrop，玻璃拇指是它的兄弟节点（不在其记录子树内）。
 * 设置页的开关住在页面 backdrop 的记录子树内，不能采页面级 backdrop（会构成循环
 * 引用，官方 FAQ #2 SIGSEGV），按 catalog ToggleContent 的行内用法以
 * rememberCanvasBackdrop 传入行卡片底色兜底。
 *
 * 弹层内使用时（播放页定时关闭开关）由调用方传 [contentBackdrop]——传弹层本体
 * 同一采样的页面主背景层（LocalTitlePageBackdrop），玻璃拇指的 blur/lens 才能采到
 * 页面内容，而不是固定底色画板。播放页弹层槽在记录层之外，不构成循环。
 *
 * 尺寸按 catalog 基准 64×28dp 等比缩放（[switchHeight] 为轨道高度）。
 * 颜色为 iOS 开关绿：浅色 0xFF34C759 / 深色 0xFF30D158。
 *
 * 受控组件约定：部分设置行的 onClick 是空实现（展示型开关，如"淡入/淡出"），
 * onCheckedChange 并不会真正改变 [checked]。因此手势结束提交后以 [checkedState]
 * （直接读底层状态的 lambda）校准内部视觉位置，状态没变就弹回原位，
 * 保证与 CupertinoSwitch 相同的"改不动"观感，绝不出现视觉与状态脱钩。
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    switchHeight: Dp = 28.dp,
    checkedState: (() -> Boolean)? = null,
    contentBackdrop: Backdrop? = null,
) {
    val s = switchHeight / 28.dp
    val isDark = isFlamingoInDarkMode()
    val accentColor =
        if (!isDark) Color(0xFF34C759)
        else Color(0xFF30D158)
    val trackColor =
        if (!isDark) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)
    // 行内开关身后的实际内容是设置行卡片底色
    val backgroundColor =
        if (!isDark) settingContainerBack
        else settingContainerBackDark

    val trackWidth = 64f * s
    val thumbWidth = 40f * s
    val thumbHeight = 24f * s
    val edgePadding = 2f * s
    val dragWidth = 20f * s
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val interactive = onCheckedChange != null
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val currentCheckedState by rememberUpdatedState(checkedState)
    val animationScope = rememberCoroutineScope()
    // 累计水平位移是否越过 touchSlop：判定这次手势是"横向拨动"还是"纵向误滑"。
    // 不能用 dragAmount.x != 0f —— 纵向滑动时手指的亚像素横向抖动也会让 x 非零。
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragX by remember { mutableFloatStateOf(0f) }
    var didMove by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                val horizontal = abs(dragX) >= touchSlop
                when {
                    // 横向拖动：按落点方向提交翻转
                    horizontal -> {
                        fraction = if (targetValue >= 0.5f) 1f else 0f
                        currentOnCheckedChange?.invoke(fraction == 1f)
                    }
                    // 只在纵向滑动过：视为误触，回弹到真实状态，绝不翻转、也不滚页面
                    didMove -> {
                        fraction = if (currentCheckedState?.invoke() == true) 1f else 0f
                    }
                    // 完全没动：点按翻转
                    else -> {
                        fraction = if (currentCheckedState?.invoke() == true) 0f else 1f
                        currentOnCheckedChange?.invoke(fraction == 1f)
                    }
                }
                dragX = 0f
                didMove = false
            },
            // 手势被外部抢占（例如父级滚动先消费）：回弹到真实状态，绝不提交翻转。
            onDragCancelled = {
                dragX = 0f
                didMove = false
                fraction = if (currentCheckedState?.invoke() == true) 1f else 0f
            },
            onDrag = { _, dragAmount ->
                dragX += dragAmount.x
                didMove = true
                val delta = dragAmount.x / with(density) { dragWidth.dp.toPx() }
                fraction = if (isLtr) {
                    (fraction + delta).fastCoerceIn(0f, 1f)
                } else {
                    (fraction - delta).fastCoerceIn(0f, 1f)
                }
            },
            // 开关独占手势：在开关上按下再滑动只拨动开关，父级列表不再跟着滚。
            exclusiveDrag = true
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { dampedDragAnimation.updateValue(it) }
    }
    LaunchedEffect(checkedState) {
        snapshotFlow { currentCheckedState?.invoke() ?: checked }
            .collectLatest { isChecked ->
                val target = if (isChecked) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val backgroundBackdrop = rememberCanvasBackdrop { drawRect(backgroundColor) }
    val trackScaleBackdrop = rememberBackdrop(trackBackdrop) { drawBackdrop ->
        val progress = dampedDragAnimation.pressProgress
        val scaleX = lerp(2f / 3f, 0.75f, progress)
        val scaleY = lerp(0f, 0.75f, progress)
        scale(scaleX, scaleY) {
            drawBackdrop()
        }
    }
    // 拇指采样源：调用方给了页面 backdrop（弹层内）就采真实页面内容，
    // 否则保持行卡片不透明底色兜底（设置页内采页面 backdrop 会循环引用）。
    val thumbBackdrop = if (contentBackdrop != null) {
        rememberCombinedBackdrop(contentBackdrop, trackScaleBackdrop)
    } else {
        rememberCombinedBackdrop(backgroundBackdrop, trackScaleBackdrop)
    }

    Box(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule)
                .drawBehind {
                    drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value))
                }
                .size(trackWidth.dp, switchHeight)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val fraction = dampedDragAnimation.value
                    translationX =
                        if (isLtr) lerp(edgePadding, edgePadding + dragWidth, fraction).dp.toPx()
                        else lerp(-edgePadding, -(edgePadding + dragWidth), fraction).dp.toPx()
                }
                .semantics { role = Role.Switch }
                .then(if (interactive) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { Capsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur((8f * s).dp.toPx() * (1f - progress))
                        lens(
                            (5f * s).dp.toPx() * progress,
                            (10f * s).dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = (4f * s).dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = (4f * s).dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(thumbWidth.dp, thumbHeight.dp)
        )
    }
}

/**
 * 应用统一开关入口：设置页开启"工具栏液态玻璃"（[SettingsLibrary.BarBlurEffect]）时
 * 使用 [LiquidSwitch]，否则保持原 [CupertinoSwitch] 外观不变。
 *
 * 注意：[LiquidSwitch] 尺寸由 [switchHeight] 决定；[CupertinoSwitch] 分支不改动
 * [modifier]（需要限高时由调用方在自己的 modifier 里给，与历史行为一致）。
 */
@Composable
fun YosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    switchHeight: Dp = 28.dp,
    checkedState: (() -> Boolean)? = null,
    contentBackdrop: Backdrop? = null,
) {
    if (SettingsLibrary.BarBlurEffect) {
        LiquidSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            switchHeight = switchHeight,
            checkedState = checkedState,
            contentBackdrop = contentBackdrop
        )
    } else {
        // cupertino 0.1.0-alpha04 的 onCheckedChange 为非空参数，仅展示时用空实现兜底
        CupertinoSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange ?: {},
            modifier = modifier
        )
    }
}
