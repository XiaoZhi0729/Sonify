package yos.music.player.ui.widgets.liquid

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.primary
import yos.music.player.ui.theme.primaryDark
import yos.music.player.ui.widgets.basic.LocalGlassContentColor
import yos.music.player.ui.widgets.liquid.DampedDragAnimation
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import yos.music.player.code.utils.others.GlassProbe
import yos.music.player.code.utils.others.GlassDiagnostics
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 容器 backdrop 采样的消融包装（开关位 navContainerSample）。
 *
 * 必须是文件级稳定单例，而不是写在内联位置的 lambda：库里 `onDrawBackdrop` 的默认值是单例
 * `DefaultOnDrawBackdrop = { it() }`，而 `DrawBackdropElement.equals` 会逐个比较这些回调。内联
 * 新建的 lambda 与单例不是同一引用，底栏每次重组都会被判"参数变了"，继而走 `update()` →
 * `invalidateDrawCache()`——于是默认路径比改动前多了一层缓存作废。探针不能自己变成变量。
 */
private val NavContainerOnDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = {
    if (GlassProbe.current.navContainerSample) it()
}

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    onTabReselected: () -> Unit = {},
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    enableInteractiveHighlight: Boolean = true,
    /** 无玻璃材质的磨砂胶囊：容器只画"投影 + blur(12dp) 磨砂面"，不挂折射/透镜；白高光与内阴影延伸由共用覆盖层补齐（与迷你条关玻璃材质一致）。 */
    solidCapsule: Boolean = false,
    /** 底栏容器玻璃开关（消融位 navcontainer）。退化为普通底色，几何不变。 */
    containerGlassEnabled: Boolean = true,
    /** .alpha(0f) 隐形生产者行开关（消融位 navhidden）。 */
    hiddenProducerEnabled: Boolean = true,
    /** Tab 胶囊玻璃开关（消融位 navtab）。 */
    tabGlassEnabled: Boolean = true,
    /**
     * 容器玻璃面的提取色 tint（可空 = 不着色）。画在 surface 色之上、描边高光之下，
     * 绘制期读取：页面转场逐帧只失效重绘，不重组。透明时等于没画。
     */
    containerTintProvider: (() -> Color)? = null,
    content: @Composable RowScope.() -> Unit
) {
    // 主题判定必须走应用内主题（isFlamingoInDarkMode），不能读 isSystemInDarkTheme：
    // 应用内选深色而系统是浅色时，底栏会走浅色分支画出白纱（灰条），
    // 与迷你播放器（withNight，同源判定）出现黑灰两张皮。
    val isLightTheme = !isFlamingoInDarkMode()
    val accentColor =
        if (isLightTheme) primary
        else primaryDark
    // 与迷你播放器玻璃面同色同透明度（MainActivity: White withNight 0xFF1C1C1E，收起态 alpha 0.5）
    val containerColor =
        if (isLightTheme) Color.White.copy(0.5f)
        else Color(0xFF1C1C1E).copy(0.5f)
    // 关玻璃磨砂胶囊表面：与迷你播放器关玻璃分支完全同材质（MainActivity hazeSurfaceAlpha：
    // 浅色 0.85 / 深色 0.55）。此前沿用玻璃面的 0.5，磨砂态会比迷你条更透（用户报告）。
    val solidCapsuleColor =
        if (isLightTheme) Color.White.copy(0.85f)
        else Color(0xFF1C1C1E).copy(0.55f)

    GlassDiagnostics.state(
        "bottom_tabs_gate",
        "solid=$solidCapsule container=$containerGlassEnabled tab=$tabGlassEnabled hidden=$hiddenProducerEnabled " +
                "navContainer=${GlassProbe.current.navContainerGlass} navSample=${GlassProbe.current.navContainerSample} " +
                "navTab=${GlassProbe.current.navTabGlass} barBlur=${yos.music.player.data.libraries.SettingsLibrary.BarBlurEffect}"
    )
    // 采样记录挂进探针门禁内的稳定单回调：重组间引用不变
    // （文件级单例 NavContainerOnDrawBackdrop 的教训同样适用于这里的 remember lambda）。
    val containerOnDrawBackdrop = NavContainerOnDrawBackdrop

    // surface + tint 的组合绘制：tint 在 surface 色之上、描边高光之下（高光由库画在
    // surface 之后），绘制期读取 provider，页面转场时逐帧重绘不重组。
    val containerSurfaceDraw: DrawScope.() -> Unit = {
        drawRect(containerColor)
        containerTintProvider?.invoke()?.let { tint ->
            if (tint.alpha > 0.005f) drawRect(tint)
        }
    }

    // 磨砂胶囊的 surface：迷你条关玻璃同款表面色，提取色 tint 层序与 containerSurfaceDraw
    // 一致（tint 在表面色之上、描边高光之下）。
    val solidCapsuleSurfaceDraw: DrawScope.() -> Unit = {
        drawRect(solidCapsuleColor)
        containerTintProvider?.invoke()?.let { tint ->
            if (tint.alpha > 0.005f) drawRect(tint)
        }
    }

    // 白色提亮高光覆盖层（数值与 ClassYaba LiquidButton defaultHighlight 一致）：angle=90
    // 直上直下，亮在上下直边；挂在 emptyBackdrop 上不再采样玻璃，不会盖住玻璃分支
    // 主层的黑色环绕高光。磨砂胶囊与玻璃分支共用这一层——迷你播放器关玻璃分支
    // （MainActivity PlayerShell Kyant 分支）同样挂着这层高光+内阴影延伸，缺了它
    // 关闭玻璃时底栏会比迷你条少一层亮边（用户报告的材质不一致）。
    val containerHighlightOverlay = Modifier.drawBackdrop(
        backdrop = emptyBackdrop(),
        shape = { Capsule() },
        effects = {},
        highlight = {
            if (GlassProbe.current.navContainerHighlight) {
                Highlight.Default.copy(
                    width = 1f.dp,
                    blurRadius = if (isLightTheme) 0.1f.dp else 0.5f.dp,
                    alpha = if (isLightTheme) 0.8f else 1f,
                    style = HighlightStyle.Default(
                        color = Color.White.copy(alpha = if (isLightTheme) 0.2f else 0.1f),
                        blendMode = BlendMode.Plus,
                        angle = 90f,
                        falloff = 1f
                    )
                )
            } else null
        },
        shadow = { null },
        innerShadow = { null },
        onDrawBackdrop = { },
        onDrawSurface = {
            // 上下高光的内阴影延伸（与迷你条同款）：顶部高光向下、底部高光向上各拉
            // 一条白色内渐变带，颜色与白高光一致、同用 Plus 叠加。画在本层 surface
            // 记录内，节点已按 Capsule 裁剪。门禁与白高光同用 navContainerHighlight。
            if (GlassProbe.current.navContainerHighlight) {
                val glowColor = Color.White.copy(
                    alpha = if (isLightTheme) 0.2f else 0.1f
                )
                val glowH = 6f.dp.toPx()
                // 顶边高光向下渐隐
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to glowColor,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = glowH
                    ),
                    size = Size(size.width, glowH),
                    blendMode = BlendMode.Plus
                )
                // 底边高光向上渐隐
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to glowColor,
                        startY = size.height - glowH,
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - glowH),
                    size = Size(size.width, glowH),
                    blendMode = BlendMode.Plus
                )
            }
        }
    )

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        GlassDiagnostics.state(
            "bottom_tabs_layout",
            "width=${constraints.maxWidth} height=${constraints.maxHeight} tabs=$tabsCount " +
                    "tabsBackdrop=${System.identityHashCode(tabsBackdrop)}"
        )
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val latestSelectedTabIndex = rememberUpdatedState(selectedTabIndex)
        val latestOnTabSelected = rememberUpdatedState(onTabSelected)
        val latestOnTabReselected = rememberUpdatedState(onTabReselected)
        var currentIndex by remember {
            mutableIntStateOf(selectedTabIndex())
        }
        // split 布局会在首次测量后把底栏从全宽缩到 30%；onDrag 必须捕获当前 tabWidth，
        // 否则手势仍按首次全宽的 Tab 尺寸换算，玻璃胶囊看起来会被强力阻尼拖住。
        val dampedDragAnimation = remember(animationScope, tabWidth, isLtr, tabsCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = latestSelectedTabIndex.value().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 65f / 50f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { latestSelectedTabIndex.value() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    latestOnTabSelected.value(index)
                }
        }

        val interactiveHighlight = remember(animationScope, tabWidth, isLtr, tabsCount, dampedDragAnimation) {
            InteractiveHighlight(
                animationScope = animationScope,
                onTap = { latestOnTabReselected.value() },
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // Tab 图标/文字用主题内容色（底栏已放弃自适应亮度适配）。
        CompositionLocalProvider(
            LocalGlassContentColor provides null
        ) {
            // 磨砂/玻璃两分支都是 backdrop 库同类型的 DrawBackdropElement 且同槽位，
            // 运行时翻转开关会被 Compose 原地 update（不重建节点录制管线），模糊层从此
            // 不出图、只剩半透明 surface 色；key 换值强制整行节点销毁重建，双向立即恢复。
            key(solidCapsule) {
                Row(
                    Modifier
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .then(
                            if (solidCapsule) {
                                // 无玻璃材质：磨砂胶囊（Haze 式）——只做背景模糊，不做折射/透镜/
                                // 色散；阴影与"液态玻璃"分支一致用 Shadow.Default。半透明纯色面叠在
                                // 模糊层上即为磨砂效果。表面色/透明度、黑边环绕高光与白高光+内阴影
                                // 延伸（共用覆盖层）全部对齐迷你播放器关玻璃分支，另按用户要求补上
                                // 玻璃态才有的黑色描边。
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { Capsule() },
                                    // 采样层已降为 1/2，模糊由库经 drawImage 作用其上。半径取 12dp。
                                    effects = { blur(12.dp.toPx()) },
                                    // 黑边环绕高光：与玻璃分支/迷你条玻璃面同配方
                                    // （ClassYaba LiquidButton blackSideHighlight）。
                                    highlight = {
                                        if (GlassProbe.current.navContainerHighlight) {
                                            Highlight.Default.copy(
                                                width = if (isLightTheme) 0.5f.dp else 0.4f.dp,
                                                blurRadius = if (isLightTheme) 0.2f.dp else 0.1f.dp,
                                                alpha = 1f,
                                                style = WrapHighlightStyle(
                                                    color = Color.Black.copy(alpha = if (isLightTheme) 0.5f else 0.4f),
                                                    blendMode = BlendMode.SrcOver,
                                                    angle = 0f,
                                                    falloff = 0.9f,
                                                    baseline = 0.4f
                                                )
                                            )
                                        } else null
                                    },
                                    shadow = { Shadow.Default },
                                    innerShadow = { null },
                                    onDrawSurface = solidCapsuleSurfaceDraw
                                )
                                    .then(containerHighlightOverlay)
                            }
                            // 底栏容器玻璃：navcontainer 时整块不挂，退回 containerColor。
                            else if (!containerGlassEnabled) {
                                Modifier.drawBehind { containerSurfaceDraw() }
                            } else {
                                Modifier.drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { Capsule() },
                                    // 玻璃材质与迷你播放器收起态逐项对齐（MainActivity PlayerShell）：
                                    // 自适应 colorControls + blur 4dp + lens(16,32) + Shadow.Default。
                                    // 黑边环绕高光（数值与 ClassYaba LiquidButton blackSideHighlight 一致）：
                                    // WrapHighlightStyle 整圈连续、左右最深、上下保留 baseline=0.4；
                                    // 白色提亮高光由下方 emptyBackdrop 覆盖层单独叠加。
                                    effects = {
                                        // 整条效果链一个开关：量它挂在 1190x208 层上到底贵不贵。
                                        if (GlassProbe.current.navContainerEffects) {
                                            vibrancy()
                                            blur(4f.dp.toPx())
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                lens(16f.dp.toPx(), 32f.dp.toPx())
                                            }
                                        }
                                    },
                                    highlight = {
                                        if (GlassProbe.current.navContainerHighlight) {
                                            Highlight.Default.copy(
                                                width = if (isLightTheme) 0.5f.dp else 0.4f.dp,
                                                blurRadius = if (isLightTheme) 0.2f.dp else 0.1f.dp,
                                                alpha = 1f,
                                                style = WrapHighlightStyle(
                                                    color = Color.Black.copy(alpha = if (isLightTheme) 0.5f else 0.4f),
                                                    blendMode = BlendMode.SrcOver,
                                                    angle = 0f,
                                                    falloff = 0.9f,
                                                    baseline = 0.4f
                                                )
                                            )
                                        } else null
                                    },
                                    shadow = {
                                        if (GlassProbe.current.navContainerShadow) Shadow.Default else null
                                    },
                                    // 静止时 pressProgress==0，缩放恒为 1 —— 这层 graphicsLayer 是白开的。
                                    layerBlock = if (!GlassProbe.current.navContainerLayer) null else {
                                        {
                                            val progress = dampedDragAnimation.pressProgress
                                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                    },
                                    // 采样 + 记录（探针关掉采样时连 record 一起停）。
                                    onDrawBackdrop = containerOnDrawBackdrop,
                                    onDrawSurface = containerSurfaceDraw
                                )
                                // 白色提亮高光覆盖层：定义见函数顶部 containerHighlightOverlay
                                // （磨砂/玻璃两分支共用，保证关玻璃材质与迷你条一致）。
                                .then(containerHighlightOverlay)
                            }
                        )
                        .then(if (enableInteractiveHighlight) interactiveHighlight.modifier else Modifier)
                        .height(58f.dp)
                        .fillMaxWidth()
                        .padding(4f.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    // 这个 Row 永久 alpha=0（它存在的唯一目的是把自身录成 tabsBackdrop 给
                    // Tab 胶囊采样），但库内 LayerBackdropNode.draw() 每帧无条件 recordLayer，
                    // 图层全透也不跳过绘制——所以它是一整份看不见的逐帧玻璃成本。
                    //
                    // 消融必须保住原始挂载顺序：layerBackdrop 原本夹在 alpha 与 translationX
                    // 图层之间。记录层录的是"它内侧"的画面，把它挪到 translationX 之后就会把
                    // 平移一并录进去，Tab 胶囊采样到的内容会偏移——那是外观差异，不是性能优化。
                    .then(
                        if (hiddenProducerEnabled && !solidCapsule) Modifier.layerBackdrop(tabsBackdrop)
                        else Modifier
                    )
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .then(
                        if (hiddenProducerEnabled && !solidCapsule) Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(4f.dp.toPx())
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    lens(
                                        16f.dp.toPx() * progress,
                                        32f.dp.toPx() * progress
                                    )
                                }
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            onDrawSurface = { drawRect(containerColor) }
                        ) else Modifier
                    )
                    .then(if (enableInteractiveHighlight) interactiveHighlight.modifier else Modifier)
                    .height(50f.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    val raw = if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                    else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    // 弹簧过冲（value 短暂越出 [0, tabsCount-1]）时把胶囊钳在玻璃面板内，不越出屏幕
                    translationX =
                        raw.coerceIn(
                            0f,
                            maxOf(0f, constraints.maxWidth - size.width - 4f.dp.toPx())
                        )
                    // 按压/拖动速度的缩放锚点：端点 Tab 固定朝屏幕内侧生长，
                    // 避免玻璃胶囊放大后越出玻璃面板乃至屏幕边缘
                    transformOrigin = TransformOrigin(
                        (dampedDragAnimation.value / (tabsCount - 1).coerceAtLeast(1))
                            .fastCoerceIn(0f, 1f),
                        0.5f
                    )
                }
                .then(if (enableInteractiveHighlight) interactiveHighlight.gestureModifier else Modifier)
                .then(dampedDragAnimation.modifier)
                .then(
                    // 无玻璃材质：选中胶囊退化为纯色平涂（浅色黑 10% / 深色白 10%），
                    // 与玻璃模式 onDrawSurface 的静态色一致，但不做任何 backdrop 采样。
                    if (solidCapsule) {
                        Modifier
                            .background(
                                if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                                Capsule()
                            )
                            // 纯色胶囊分支的 tint：绘制期读取（勿放组合期，转场会逐帧重组），
                            // 用 drawOutline 沿 Capsule 轮廓画，避免直角越出胶囊。
                            .drawBehind {
                                containerTintProvider?.invoke()?.let { tint ->
                                    if (tint.alpha > 0.005f) {
                                        drawOutline(
                                            outline = Capsule().createOutline(
                                                size,
                                                layoutDirection,
                                                this
                                            ),
                                            color = tint
                                        )
                                    }
                                }
                            }
                    }
                    // Tab 胶囊玻璃：即使 pressProgress==0，highlight/shadow 的 width 仍 >0，
                    // 库内依旧每帧 record 离屏层，所以 navtab 要连它一起摘掉。
                    else if (!tabGlassEnabled) Modifier else Modifier.drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        // 提取色 tint 与容器玻璃同源：指示器是选中态的强调元素，
                        // 直接用全浓度（不再另乘系数），随页面转场同步混色。
                        containerTintProvider?.invoke()?.let { tint ->
                            if (tint.alpha > 0.005f) drawRect(tint)
                        }
                    }
                    )
                )
                .height(50f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
