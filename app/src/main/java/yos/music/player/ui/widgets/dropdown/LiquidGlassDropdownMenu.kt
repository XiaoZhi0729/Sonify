package yos.music.player.ui.widgets.dropdown

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick as onSemanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.widgets.basic.adaptiveBlurPx
import yos.music.player.ui.widgets.basic.adaptiveGlassColorControls
import yos.music.player.ui.widgets.basic.rememberAdaptiveBackdropLuminance
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import yos.music.player.ui.widgets.liquid.classyabaBlackRim
import yos.music.player.ui.widgets.liquid.classyabaWhiteEdge
import yos.music.player.ui.widgets.liquid.glassBlurScale

/** 外层为阴影预留的内缩；外部按"占位槽真实位置"摆放本控件时需要减掉它 */
// 宿主层要读它换算占位槽位置，跨模块须 public
val LiquidGlassDropdownShadowPadding = 24.dp

/** 非自适应路径的稳定单例 onDrawBackdrop（库默认值同款）：内联 lambda 会被
 * DrawBackdropElement.equals 判为参数变化，每次重组都 invalidateDrawCache。 */
private val DefaultOnDrawBackdrop: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) -> Unit = {
    it()
}

/** 收起态圆形按钮直径，也是容器变换的起点尺寸 */
private val ButtonDiameter = 42.dp

/** 面板宽度与圆角 */
private val PanelWidth = 200.dp
private val PanelCornerRadius = 25.dp

/**
 * 面板外投射阴影档位。⚠️ ListPopup.kt 的 popupPanelShadow 有一份**同值副本**
 * （那边是 miuix 通用组件，不反向依赖 app 层），调整时两处必须同步，否则两种弹窗阴影深浅不一。
 */
private val ShadowColorLight = Color(0x1A000000)
private val ShadowColorDark = Color(0x2E000000)
private const val ShadowBlur = 12f
private const val ShadowExtend = 2f
/** 阴影 alpha 会被放大这么多倍 —— 环形路径重复描边会累积浓度 */
private const val ShadowAlphaGain = 3.2f

/**
 * 右上角「更多」按钮与下拉菜单是**同一个控件**（容器变换）：fraction=0 时是 42dp 玻璃盒
 * （圆角 25dp 被钳成正圆），fraction=1 时长成 [PanelWidth] × 面板高的圆角矩形。已展开时支持
 * 跟手滑动选菜单项（见 [dropdownPanelDragSelect]），无长按门槛。
 */
@Composable
fun LiquidGlassDropdownMenu(
    show: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    fraction: Animatable<Float, *> = remember { Animatable(0f) },
    onDismiss: (() -> Unit)? = null,
    onBackProgress: ((Float) -> Unit)? = null,
    onBackCancelled: (() -> Unit)? = null,
    triggerIcon: ImageVector? = null,
    triggerIconSize: Dp = 23.dp,
    triggerContentDescription: String? = null,
    onExpand: (() -> Unit)? = null,
    // 整块控件随顶栏一起平移（非当前页移出屏幕外）
    offsetPx: () -> Offset = { Offset.Zero },
    // 顶栏滚动材质透明度：收起态跟随它渐显渐隐，展开时按 fraction 渐显
    materialAlpha: Float = 1f,
    // 收起态跟随顶栏返回键的配色。两个开关分别对应返回键的两条分支：
    //  - adaptiveLuminance：「工具栏液态玻璃」开启时按 backdrop 亮度翻黑白（同 LiquidTopBarButton）；
    //  - contentColor：「工具栏液态玻璃」关闭后的扁平分支，图标用页面文字色（同 TitleBar 的 backIconTint）。
    // 默认都关/为空 → 完全保持原有主题配色，未传参的调用点行为逐像素不变。
    adaptiveLuminance: Boolean = false,
    contentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isLightTheme = !isDropdownDarkTheme()
    val chromeLens = LocalChromeLensEnabled.current
    val hapticFeedback = LocalHapticFeedback.current
    // 面板边光（展开态与未启用自适应的调用点共用）。浅色分支已改走 ClassYaba 描边，
    // 这个 spec 只留给深色分支与自适应路径。
    val themeEdgeLight = rememberDropdownEdgeLight()
    // 形状在收起↔展开间连续变化（42dp 正圆 → 200dp × 面板高圆角矩形），而且
    // `squircle()` 每次调用都新建实例，而 ShapeProvider 按**引用**比较形状、引用一变就
    // 重算 outline。这里 remember 成单实例，供材质、阴影、描边共用同一份。
    val panelShape = remember { squircle(PanelCornerRadius, smoothBand = true) }
    // 浅色模式的黑环走 ClassYaba 配方（左右最深、上下保留 baseline 0.4）：与顶栏圆钮
    // （LiquidTopBarButton）、设置页预览控件同一套。深色不移植，保持项目原状。
    val rimHighlight: (() -> com.kyant.backdrop.highlight.Highlight?)? =
        if (isLightTheme) classyabaBlackRim else null

    // 「工具栏液态玻璃」关闭时收起态是纯图标（无圆底/边光/投影），与 TitleBar 的扁平返回键同形；
    // 开启时是那颗 42dp 玻璃钮，配色按 backdrop 亮度自适应 —— 两条分支都不再看 App 深浅主题。
    val glassOn = SettingsLibrary.BarBlurEffect
    val luminance = rememberAdaptiveBackdropLuminance(
        enabled = glassOn && adaptiveLuminance,
        tag = "LiquidGlassDropdownMenu",
        initialLuminance = if (isLightTheme) 1f else 0f
    )
    val adaptiveContentColor = luminance?.contentColor()
    // 展开态是面板：必须保留实底保证菜单项可读，只有收起态才是那颗钮。
    val panelContainerColor = if (isLightTheme) Color(0xFFFAFAFA).copy(0.76f)
        else Color(0xFF242424).copy(0.84f)
    val collapsedContainerColor = when {
        adaptiveContentColor != null -> Color.White.copy(alpha = 0.10f)
        glassOn -> panelContainerColor
        else -> Color.Transparent
    }
    val triggerIconTint = adaptiveContentColor
        ?: when {
            glassOn && isLightTheme -> Color.Black.copy(alpha = 0.85f)
            glassOn -> Color.White.copy(alpha = 0.85f)
            else -> contentColor ?: MaterialTheme.colorScheme.primary
        }
    // 锚点迁移进度：比尺寸更快到 1，先"移向面板中心"再放大
    val originProgress = remember { Animatable(0f) }
    // 退出回弹脉冲（1 → 欠阻尼 → 0，取越过 0 的部分）
    val settleBounce = remember { Animatable(1f) }
    // 展开过才播退出动画，首帧不播（否则启动时按钮会被带偏）
    var hasOpened by remember { mutableStateOf(false) }
    // 跟手滑选：菜单项各自登记位置，手势逐帧命中测试
    val dragSelectState = remember { DropdownPanelDragSelectState() }
    // 展开进度登记给状态：触发区那边（长按展开后不停手滑选）靠它判断何时能开始命中
    dragSelectState.registerFraction { fraction.value }
    // 触发区手势：与面板共享 dragSelectState，长按展开后同一个手势继续做命中
    val triggerGesture = remember(dragSelectState) { DropdownTriggerGestureState(dragSelectState) }
    val expandAction: () -> Unit = {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
        onExpand?.invoke()
    }

    // 预测性返回：始终注册（否则动画未播完时立即返回会抓不到手势）。⚠️ handler 与进度读取
    // 必须绑定同一份状态，拆开会导致跟手动画失效。
    val navBackState = rememberNavigationBack()
    val predictiveBackEnabled = LocalPredictiveBackEnabled.current
    val backProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // 按压高光 + 跟手拉伸 + 回弹
    val interactiveHighlight = remember(coroutineScope) {
        InteractiveHighlight(
            animationScope = coroutineScope,
            // 面板越大光晕要越小，否则收起态的光会漫出小圆
            radiusScale = { 1f - 0.6f * fraction.value.coerceIn(0f, 1f) },
            // 半径上限 150dp：不用 minDimension，否则菜单项越多面板越高、光晕越大。
            // 收起态是 42dp 小圆，min 会自动退回自身尺寸，光晕不会漫出圆外。
            radiusBaseDp = 150.dp,
            lightPeakAlpha = 0.05f,
            isDarkMode = !isLightTheme,
        )
    }

    navBackState.handler(
        enabled = show && onDismiss != null,
        onBackCancelled = {
            onBackCancelled?.invoke()
            coroutineScope.launch {
                if (backProgress.value > 0f) {
                    fraction.animateTo(1f, animationSpec = tween(150))
                    originProgress.animateTo(1f, animationSpec = tween(150))
                    backProgress.snapTo(0f)
                }
            }
        },
        onBackCompleted = {
            onDismiss?.invoke()
        },
    )

    // 逐帧收手势进度：菜单缩回锚点。backProgress() 手势进行中返回连续进度，其余时间为 null
    LaunchedEffect(Unit) {
        snapshotFlow { navBackState.backProgress() }
            .collect { progress ->
                if (progress != null && predictiveBackEnabled) {
                    backProgress.snapTo(progress)
                    onBackProgress?.invoke(progress)
                    fraction.snapTo(1f - progress)
                    originProgress.snapTo(1f - progress)
                }
            }
    }

    LaunchedEffect(show) {
        if (show) {
            hasOpened = true
            launch {
                fraction.animateTo(
                    1f,
                    spring(dampingRatio = 0.77f, stiffness = 220f, visibilityThreshold = 0.0001f)
                )
            }
            launch {
                originProgress.animateTo(
                    1f,
                    spring(dampingRatio = 0.77f, stiffness = 420f, visibilityThreshold = 0.0001f)
                )
            }
            settleBounce.snapTo(1f)
        } else if (!hasOpened) {
            // 首帧（从未展开）：直接归位，不播退出动画/回弹
            fraction.snapTo(0f)
            originProgress.snapTo(0f)
            settleBounce.snapTo(0f)
        } else {
            val exitEasing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
            launch {
                fraction.animateTo(
                    0f,
                    spring(dampingRatio = 0.85f, stiffness = 650f, visibilityThreshold = 0.0001f)
                )
            }
            // 退出位移回弹：独立一条欠阻尼 spring，与尺寸 spring 解耦
            launch {
                settleBounce.snapTo(1f)
                settleBounce.animateTo(
                    0f,
                    spring(dampingRatio = 0.52f, stiffness = 220f, visibilityThreshold = 0.0001f)
                )
            }
            // 这里必须"等"动画跑完（不能立刻 snapTo，否则会把上面的动画取消掉）；
            // 用锚点 tween 兜住，结束后再收尾
            originProgress.animateTo(0f, tween(340, easing = exitEasing))
            fraction.snapTo(0f)
            originProgress.snapTo(0f)
        }
    }

    // 读 fraction 驱动尺寸；不钳到 1，保留 spring 过冲（末尾回弹）
    val f = fraction.value
    // 模糊随展开过渡（收起 8dp → 展开 24dp）。折射 lens(8, 24) 必须保持常量：它作用在边缘 SDF 上，
    // 随面板变大反而会让边缘变形。effects lambda 换引用才重算，但每帧换会让节点每帧重建 RenderEffect，故量化成 8 档。
    val blurStep = (f.coerceIn(0f, 1f) * 8f).roundToInt()
    val blurDp = 8f + 16f * (blurStep / 8f)
    // 面板自然高度：由容器内的内容以全尺寸测量得到，作为高度插值终点
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val targetHeight = if (panelHeightPx > 0) {
        with(LocalDensity.current) { panelHeightPx.toDp() }
    } else {
        ButtonDiameter
    }
    // 材质可见度：滚动值与展开进度取大（f*5 → 0.2 饱和，出现早、消失晚）
    val materialVisible = max(materialAlpha, (f * 5f).coerceIn(0f, 1f))
    // 内容模糊：清晰(0)→模糊(中途)→清晰(1)
    val contentBlur = 6.dp * (1f - abs(2f * f - 1f)).coerceIn(0f, 1f)
    val width = lerp(ButtonDiameter, PanelWidth, f)
    val height = lerp(ButtonDiameter, targetHeight, f)
    // 内容随容器等比缩放（还原原来"内容有缩放"的手感），同样保留过冲
    val contentScale = (width.value / PanelWidth.value).coerceAtLeast(0f)

    Box(
        modifier = modifier.padding(LiquidGlassDropdownShadowPadding)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    val o = offsetPx()
                    // 绕"右上角→正中心"迁移锚点缩放的位移（p=originProgress，s=宽/面板宽，两端为 0）
                    val p = originProgress.value
                    val s = size.width / PanelWidth.toPx()
                    val k = 0.5f * p * (1f - s)
                    // 退出位移回弹：沿收回方向（右上）越过终点再弹回，进入为 0
                    val pulse = (-settleBounce.value).coerceAtLeast(0f)
                    val bouncePx = pulse * 12f * density

                    // 按压/拖拽形变：按压缩放 4dp、沿拖动方向拉伸、位移 tanh 阻尼跟手
                    val drag = computeDragTransform(
                        width = size.width,
                        height = size.height,
                        fraction = f,
                        pressProgress = interactiveHighlight.pressProgress,
                        offset = interactiveHighlight.offset,
                        density = this,
                    )
                    scaleX = drag.scaleX
                    scaleY = drag.scaleY
                    translationX = o.x - PanelWidth.toPx() * k + bouncePx + drag.translationX
                    translationY = o.y + targetHeight.toPx() * k - bouncePx + drag.translationY
                }
                .size(width = width, height = height)
                // 面板自身的 root 原点：菜单项靠它把 boundsInRoot 换算成面板局部坐标
                // （轴向区间用 y，二维命中的横截区间用 x）
                .onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    dragSelectState.panelTopInRoot = bounds.top
                    dragSelectState.panelLeftInRoot = bounds.left
                }
                // 跟手滑选：挂在面板上而非 42dp 触发图标，手指按下后要能一路滑到任意一项
                .dropdownPanelDragSelect(
                    state = dragSelectState,
                    fraction = { fraction.value },
                    hapticFeedback = hapticFeedback
                )
                // 拖动手势：放在 clip 之前，不受圆角裁剪影响
                .then(interactiveHighlight.gestureModifier)
                .drawBehind {
                    // 与顶栏液态玻璃按钮同一套阴影：环形（外圈减内圈）+ 模糊/外扩随材质衰减。用
                    // materialVisible 而非 materialAlpha：展开后即使顶栏材质透明也要有阴影。
                    val spread = materialVisible
                    if (spread > 0.01f) {
                        val shadowColor = if (isLightTheme) ShadowColorLight else ShadowColorDark
                        val blurRadius = ShadowBlur * density * spread
                        val shadowSpread = ShadowExtend * density * spread
                        val r = PanelCornerRadius.toPx()
                        // 环形：外圈 roundrect CW + 内圈 roundrect CCW（winding 差集）
                        val ringPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(
                                        -shadowSpread, -shadowSpread,
                                        size.width + shadowSpread, size.height + shadowSpread,
                                    ),
                                    cornerRadius = CornerRadius(r + shadowSpread, r + shadowSpread),
                                ),
                                Path.Direction.Clockwise,
                            )
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(0f, 0f, size.width, size.height),
                                    cornerRadius = CornerRadius(r, r),
                                ),
                                Path.Direction.CounterClockwise,
                            )
                        }
                        drawBlurredPathShadow(
                            path = ringPath,
                            // alpha × ShadowAlphaGain 在浮点域 clamp（不提前压到 255，避免精度损失）
                            color = shadowColor.copy(
                                alpha = (shadowColor.alpha * ShadowAlphaGain).coerceAtMost(1f),
                            ),
                            blurRadius = blurRadius.coerceAtLeast(0.1f),
                        )
                    }
                }
                .clip(panelShape)
        ) {
            // 与顶栏液态玻璃按钮同一套采样效果；blur 随展开档位过渡（8dp → 24dp）。
            // 自适应分支与 LiquidTopBarButton 逐项对齐（colorControls + adaptiveBlurPx）；
            // 展开途中（f 增大）逐渐退回固定 vibrancy，面板作为实底阅读面不应该被背景亮度带跑。
            // key 必须用已量化的 blurStep 而不是 f：每帧换引用会让节点每帧重建 RenderEffect。
            val glassEffects: com.kyant.backdrop.BackdropEffectScope.() -> Unit =
                remember(chromeLens, blurStep, luminance) {
                    {
                        val l = luminance?.l()
                        if (l != null && blurStep < 4) {
                            // blurStep/8 ≈ f，用它把自适应模糊线性推回展开态档位
                            adaptiveGlassColorControls(l)
                            val adaptivePx = adaptiveBlurPx(8f.dp.toPx(), l)
                            val panelPx = blurDp.dp.toPx()
                            val t = (blurStep / 4f).coerceIn(0f, 1f)
                            blur((adaptivePx + (panelPx - adaptivePx) * t) * glassBlurScale)
                        } else {
                            vibrancy()
                            blur(blurDp.dp.toPx() * glassBlurScale)
                        }
                        if (chromeLens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            lens(8.dp.toPx(), 24.dp.toPx())
                        }
                    }
                }
            // 边光：自适应分支按内容色给（同 LiquidTopBarButton 的 edgeLightColor 口径），
            // 否则沿用主题两档。展开后（blurStep≥4 ≈ f≥0.5）一律用主题档，
            // 面板是实底阅读面，保持与其它下拉菜单一致。
            val edgeLightSpec = if (adaptiveContentColor != null && blurStep < 4) {
                remember(adaptiveContentColor) {
                    com.kyant.backdrop.edgelight.EdgeLight.Uniform(
                        color = adaptiveContentColor.copy(alpha = 0.55f)
                    )
                }
            } else {
                themeEdgeLight
            }
            // 玻璃单独一层：只有它跟材质 alpha（layerBlock 作用于整个节点，挂容器上会连图标一起透明）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = materialVisible }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { panelShape },
                        effects = glassEffects,
                        // 浅色：ClassYaba 黑色环绕环（走材质自身的 highlight 槽，不额外增加离屏图层）。
                        // 深色：保持 highlight = null + 下方 edgeLight 白环。
                        highlight = rimHighlight,
                        shadow = null,
                        onDrawBackdrop = if (luminance != null) luminance.onDrawBackdrop
                        else DefaultOnDrawBackdrop,
                        onDrawSurface = {
                            // 收起态是那颗钮、展开态是阅读面板：面底随 f 从钮过渡到面板，
                            // 否则收起态的深底钮会长成一块盖住菜单的实心色板。
                            drawRect(
                                androidx.compose.ui.graphics.lerp(
                                    collapsedContainerColor,
                                    panelContainerColor,
                                    f.coerceIn(0f, 1f)
                                )
                            )
                        }
                    )
                    // 描边：浅色换 ClassYaba 配方（黑环已在上面 highlight 槽，这里补白色上下
                    // 提亮边）；深色保留项目原有的 edgeLight 白环。
                    //
                    // 挂在玻璃这一层而不是 42dp 外容器上，与 edgeLight 原位置一致：描边必须
                    // 跟着 materialVisible 一起淡，否则顶栏滚动时会出现「只有边、没有玻璃」。
                    // 展开成 200dp 面板时继续画（与 edgeLight 原作用范围一致），避免收起↔
                    // 展开时描边突然消失。
                    .then(
                        if (isLightTheme) {
                            Modifier.classyabaWhiteEdge(panelShape)
                        } else {
                            Modifier.dropdownEdgeLight(shape = panelShape, spec = edgeLightSpec)
                        }
                    )
            )

            // 按压高光：必须在玻璃壳之上、菜单内容之下 —— 前者否则被材质盖住，
            // 后者否则会盖掉菜单项的跟手高亮底色（Plus 加色会把底色冲白）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(interactiveHighlight.modifier)
            )

            // 菜单内容：全尺寸居中、按中心缩放，随容器长大被裁出
            Column(
                modifier = Modifier
                    // 居中：缩放中心始终 = 当前卡片整体的正中心
                    .align(Alignment.Center)
                    .requiredWidth(PanelWidth)
                    .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                    .onSizeChanged { panelHeightPx = it.height }
                    .graphicsLayer {
                        // 内容可见窗口：f<0.3 全隐、f>0.7 全显
                        alpha = ((f - 0.3f) / 0.4f).coerceIn(0f, 1f)
                        scaleX = contentScale
                        scaleY = contentScale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .blur(contentBlur)
                    .padding(vertical = 8.dp)
            ) {
                // 菜单项从这里读跟手选中态（局部坐标换算要用面板在 root 里的位置）
                CompositionLocalProvider(LocalDropdownPanelDragSelect provides dragSelectState) {
                    content()
                }
            }

            // 收起态的图标：就在同一个玻璃盒里，随盒子长大淡出
            if (triggerIcon != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(ButtonDiameter)
                        // 裁成圆：否则 clickable 的涟漪会按矩形画，展开时露出直角
                        .clip(CircleShape)
                        .graphicsLayer {
                            // 图标从右上角移向容器中心，到达时刚好淡完；退出系数更小 → 更早出现
                            val iconK = if (show) 2.5f else 1.7f
                            val p = (fraction.value * iconK).coerceIn(0f, 1f)
                            alpha = 1f - p
                            translationX = p * (ButtonDiameter.toPx() / 2f - width.toPx() / 2f)
                            translationY = p * (height.toPx() / 2f - ButtonDiameter.toPx() / 2f)
                        }
                       // interactiveHighlight 高光提供，不要默认涟漪。
                       // 点按与长按由同一个手势仲裁（长按 0.5s 展开后可不停手直接滑选）：
                       // 不能再叠 clickable —— 它会无条件消费 down、且在 up 时先 consume 再
                       // 无条件 onClick，长按抬手会被它二次触发一次点击（详见 DropdownTriggerGesture）。
                       // onExpand 为 null 时整块不挂手势（等价于原 clickable(enabled = false)）
                        .then(
                            if (onExpand != null) {
                                Modifier
                                    .dropdownTriggerGesture(
                                        state = triggerGesture,
                                        expanded = { show },
                                        onTap = expandAction,
                                        onExpand = expandAction,
                                    )
                                    // 无障碍：手势节点替代了 clickable，读屏/键盘仍需能触发
                                    .semantics {
                                        role = Role.Button
                                        onSemanticsClick {
                                            expandAction()
                                            true
                                        }
                                    }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = triggerIcon,
                        contentDescription = triggerContentDescription,
                        modifier = Modifier.size(triggerIconSize),
                        tint = triggerIconTint,
                    )
                }
            }
        }
    }
}

/**
 * 本体化菜单里的一行。
 *
 * 与 1.6.5 原版一致：**不挂 clickable**（会与面板的跟手滑选手势抢事件），
 * 命中 + 执行 + 高亮都由 `Modifier.dropdownPanelEntry` 交给面板手势层。
 *
 * [selected] 是本项目补的（原版由 DropdownImpl 负责勾选）：单选菜单需要行尾勾。
 */
@Composable
fun LiquidGlassDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val isLightTheme = !isDropdownDarkTheme()
    val textColor = if (isLightTheme) Color(0xFF1A1A1A) else Color(0xFFE8E4DE)
    // 行高小于 2.0125×17dp 时未钳制 squircle 会在左右两侧中点自相交出倒三角，
    // 必须走钳制版（DropdownCompat.ClampedSquircleShape）
    val rowShape = squircle(17.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(rowShape)
            // 登记纵向区间供面板命中测试 + 绘制跟手高亮
            .dropdownPanelEntry(enabled = enabled, highlightShape = rowShape, action = onClick)
            // 无障碍点击动作：菜单项不再挂 clickable（会与面板手势抢事件），
            // 但读屏/键盘仍要能触发
            .semantics {
                role = Role.Button
                onSemanticsClick {
                    if (enabled) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
            }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 10.5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.size(10.dp))
            }
            Text(
                text = text,
                fontSize = 15.6.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                // 单行 + 超长省略：面板宽度固定 200dp（它参与缩放/位移/命中的几何计算，
                // 不能改成自适应），文字区实际只有约 106dp —— 7 个汉字就会换行。
                // 菜单项一换行，行高翻倍、整块面板被撑变形，所以这里硬性限制单行。
                // 文案侧仍应控制在 6 字以内，省略只是兜底。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.size(6.dp))
                trailing()
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_checkmark),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
        }
    }
}
/**
 * 路径模糊阴影（原版 `internal/CanvasShadow.android.kt` 的 actual 实现）：
 * 填充语义 + BlurMaskFilter（与项目内其他玻璃控件的环形投影同一套画法）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlurredPathShadow(
    path: Path,
    color: Color,
    blurRadius: Float,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
}
