package yos.music.player.ui.widgets.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCirc
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.google.accompanist.insets.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import com.google.accompanist.insets.LocalWindowInsets
import com.google.accompanist.insets.navigationBarsHeight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.withNight

/*@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun NewTitle(
    modifier: Modifier = Modifier,
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    CupertinoScaffold(
        modifier = modifier,
        topBar = {
            CupertinoTopAppBar(
                title = {
                Text(text = title)
                },
                navigationIcon = {
                        CupertinoIcon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onBack?.invoke()
                            })
                },
                actions = {
                    if (rightIcon != null) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = rememberRipple(
                                        bounded = false
                                    ),
                                    onClick = onRightIcon!!
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = rightIcon,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize(),
                                tint = Color.Black withNight Color.White
                            )
                        }
                    }
                }
            )
        }
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
}*/

/*@Composable
fun Title(
    title: String,
    subTitle: String? = null,
    individualScroll: Boolean,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    content: @Composable () -> Unit
) =
    BaseTitle(
        title = title,
        subTitle = subTitle,
        individualScroll = true,
        onBack = onBack,
        rightIcon = rightIcon,
        onRightIcon = onRightIcon,
        content as Any
    )*/

import com.google.accompanist.insets.statusBarsHeight

@Composable
fun Title(
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    bottomPadding: Dp = 134.dp,
    extraTopPadding: Dp = 0.dp,
    topRightIcon: ImageVector? = null,
    onTopRightIcon: (() -> Unit)? = null,
    // 大标题水平 padding：默认 20dp（主页/详情页内容体系）；
    // 设置系页面/资料库用 ListHeader(32dp) 分组标题，传 32.dp 与之对齐。
    titleHorizontalPadding: Dp = 20.dp,
    // 外部持有 listState（滚动加载更多等场景需要观察滚动位置）；默认内部自建
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) =
    BaseTitle(
        title = title,
        subTitle = subTitle,
        onBack = onBack,
        rightIcon = rightIcon,
        onRightIcon = onRightIcon,
        rightBarIcon = rightBarIcon,
        grid = false,
        listState = listState,
        bottomPadding = bottomPadding,
        extraTopPadding = extraTopPadding,
        topRightIcon = topRightIcon,
        onTopRightIcon = onTopRightIcon,
        titleHorizontalPadding = titleHorizontalPadding,
        content = content
    )

@Composable
fun TitleWithLazyVerticalGrid(
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    minItemSize: Dp = DiscoveryMinImageSize,
    extraTopPadding: Dp = 0.dp,
    topRightIcon: ImageVector? = null,
    onTopRightIcon: (() -> Unit)? = null,
    // 外部持有 gridState（滚动加载更多等场景需要观察滚动位置）；默认内部自建
    gridState: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit
) =
    BaseTitle(
        title = title,
        subTitle = subTitle,
        onBack = onBack,
        rightIcon = rightIcon,
        onRightIcon = onRightIcon,
        rightBarIcon = rightBarIcon,
        minItemSize = minItemSize,
        grid = true,
        extraTopPadding = extraTopPadding,
        topRightIcon = topRightIcon,
        onTopRightIcon = onTopRightIcon,
        gridState = gridState,
        content = content
    )

@Composable
private fun BaseTitle(
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    minItemSize: Dp = DiscoveryMinImageSize,
    grid: Boolean,
    bottomPadding: Dp = 134.dp,
    extraTopPadding: Dp = 0.dp,
    topRightIcon: ImageVector? = null,
    onTopRightIcon: (() -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    // 外部持有 listState（滚动加载更多等场景需要观察滚动位置）；默认内部自建
    listState: LazyListState = rememberLazyListState(),
    titleHorizontalPadding: Dp = 20.dp,
    content: Any
) {
    if (grid) {
        BaseTitleGrid(
            title = title,
            subTitle = subTitle,
            onBack = onBack,
            rightIcon = rightIcon,
            onRightIcon = onRightIcon,
            rightBarIcon = rightBarIcon,
            minItemSize = minItemSize,
            extraTopPadding = extraTopPadding,
            topRightIcon = topRightIcon,
            onTopRightIcon = onTopRightIcon,
            gridState = gridState,
            content = content as LazyGridScope.() -> Unit
        )
    } else {
        BaseTitleList(
            title = title,
            subTitle = subTitle,
            onBack = onBack,
            rightIcon = rightIcon,
            onRightIcon = onRightIcon,
            rightBarIcon = rightBarIcon,
            bottomPadding = bottomPadding,
            extraTopPadding = extraTopPadding,
            topRightIcon = topRightIcon,
            onTopRightIcon = onTopRightIcon,
            titleHorizontalPadding = titleHorizontalPadding,
            listState = listState,
            content = content as LazyListScope.() -> Unit
        )
    }
}

/**
 * 列表背后的页面底色。列表/网格自身是透明的，顶栏模糊的采样层需要一层不透明底色
 * 作种子（否则采样为空、模糊「似有似无」），该色**必须与页面实际背景一致**：
 * 设置页是分组灰 secondary，其余页是白/黑。不一致时顶栏会与页面出现明显色差。
 * 未提供时按主题取白/黑（与 MaterialTheme.colorScheme.background 一致）。
 */
val LocalTitlePageColor = compositionLocalOf<Color?> { null }

/**
 * 顶栏模糊的采样源：带不透明底色的层背景。
 * 先用 drawRect 铺满 3× 尺寸的底色作种子，再 drawContent；列表/网格本身透明，
 * 故不会透出黑底。参考页（BaseTitleGrid/BaseTitleList）与自带滚动列表的详情页共用。
 */
@Composable
fun rememberTitleBackdrop(): LayerBackdrop {
    val pageColor = LocalTitlePageColor.current ?: (Color.White withNight Color.Black)
    // onDraw 必须是稳定实例：rememberLayerBackdrop 以 onDraw 为 remember key，
    // 每次重组传新 lambda 会重建 LayerBackdrop，让采样层反复失效。
    val onDraw: ContentDrawScope.() -> Unit = remember(pageColor) {
        {
            drawRect(
                color = pageColor,
                size = Size(size.width * 3f, size.height * 3f),
                topLeft = Offset(-size.width, -size.height)
            )
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

@Composable
private fun BaseTitleGrid(
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    minItemSize: Dp = DiscoveryMinImageSize,
    extraTopPadding: Dp = 0.dp,
    topRightIcon: ImageVector? = null,
    onTopRightIcon: (() -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit
) {
    val state = gridState
    // Nexio 式二态折叠：大标题触及栏缘前不透明、没入后小标题才出现、松手吸附到两端
    val collapse = rememberTitleCollapse(state, extraTopPadding)

    // 默认右上角图标：用于一级 Tab 页面（Home / Discovery / Library / Search）。
    // 当调用方没有自定义 rightBarIcon 但传了 topRightIcon + onTopRightIcon 时，
    // 自动用 TitleBarIcon（液态玻璃圆钮：42dp 底 + 23dp 图标，页面顶部平底、
    // 滚动后毛玻璃+投影，材质见 LiquidTopBarButton）渲染。
    // 该图标渲染在 TitleBar 顶部（与大标题上方），不受大标题 57dp 下移影响。
    val effectiveRightBarIcon: @Composable (RowScope.() -> Unit)? =
        rightBarIcon ?: if (topRightIcon != null && onTopRightIcon != null) {
            {
                TitleBarIcon(
                    icon = topRightIcon,
                    onBack = onTopRightIcon
                )
            }
        } else null

    val titleBackdrop = rememberTitleBackdrop()
    val overlayHost = remember { TitleOverlayHost() }

    CompositionLocalProvider(
        LocalTitlePageBackdrop provides titleBackdrop,
        LocalTitleOverlayHost provides overlayHost,
    ) {
        Box(Modifier.fillMaxSize()) {
            val hazeState = remember(title) { HazeState() }

            LazyVerticalGrid(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .titleCollapseTouchTracker(collapse)
                    .hazeSource(hazeState)
                    .layerBackdrop(titleBackdrop)
                    .overScrollVertical(),
                flingBehavior = rememberOverscrollFlingBehavior { state },
                columns = GridCells.Adaptive(minSize = minItemSize),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp/*, bottom = 18.dp*/,
                    top = 54.dp + extraTopPadding
                )
            ) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Spacer(modifier = Modifier.statusBarsHeight())
                        TitleItem(
                            title,
                            subTitle,
                            rightIcon,
                            onRightIcon,
                            collapse.largeTitleAlpha,
                            true
                        )
                    }
                }
                content()
                item("navbar", span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
                }
            }

            TitleBar(
                title = title,
                onBack = onBack,
                showSmallTitle = collapse.showSmallTitle,
                backdrop = titleBackdrop,
                barBackgroundVisible = collapse.showBarBackground,
                rightBarIcon = effectiveRightBarIcon
            )

            // 页面级弹层宿主：渲染在列表与顶栏之上（设置页下拉玻璃弹层）
            TitleOverlayHostSlot(overlayHost)
        }
    }
}

@Composable
private fun BaseTitleList(
    title: String,
    subTitle: String? = null,
    onBack: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIcon: (() -> Unit)? = null,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    bottomPadding: Dp = 134.dp,
    extraTopPadding: Dp = 0.dp,
    topRightIcon: ImageVector? = null,
    onTopRightIcon: (() -> Unit)? = null,
    // 外部持有 listState（滚动加载更多等场景需要观察滚动位置）；默认内部自建
    listState: LazyListState = rememberLazyListState(),
    titleHorizontalPadding: Dp = 20.dp,
    content: LazyListScope.() -> Unit
) {
    val state = listState
    // Nexio 式二态折叠：大标题触及栏缘前不透明、没入后小标题才出现、松手吸附到两端
    val collapse = rememberTitleCollapse(state, extraTopPadding)

    // 默认右上角图标：用于一级 Tab 页面（Home / Discovery / Library / Search）。
    // 当调用方没有自定义 rightBarIcon 但传了 topRightIcon + onTopRightIcon 时，
    // 自动用 TitleBarIcon（液态玻璃圆钮：42dp 底 + 23dp 图标，页面顶部平底、
    // 滚动后毛玻璃+投影，材质见 LiquidTopBarButton）渲染。
    // 该图标渲染在 TitleBar 顶部（与大标题上方），不受大标题 57dp 下移影响。
    val effectiveRightBarIcon: @Composable (RowScope.() -> Unit)? =
        rightBarIcon ?: if (topRightIcon != null && onTopRightIcon != null) {
            {
                TitleBarIcon(
                    icon = topRightIcon,
                    onBack = onTopRightIcon
                )
            }
        } else null

    val titleBackdrop = rememberTitleBackdrop()
    val overlayHost = remember { TitleOverlayHost() }

    CompositionLocalProvider(
        LocalTitlePageBackdrop provides titleBackdrop,
        LocalTitleOverlayHost provides overlayHost,
    ) {
        Box(Modifier.fillMaxSize()) {
            val hazeState = remember(title) { HazeState() }

            LazyColumn(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .titleCollapseTouchTracker(collapse)
                    .hazeSource(hazeState)
                    .layerBackdrop(titleBackdrop)
                    .overScrollVertical(),
                flingBehavior = rememberOverscrollFlingBehavior { state },
                contentPadding = PaddingValues(top = 54.dp + extraTopPadding)
            ) {
                item("title") {
                    Column {
                        Spacer(modifier = Modifier.statusBarsHeight())
                        TitleItem(
                            title,
                            subTitle,
                            rightIcon,
                            onRightIcon,
                            collapse.largeTitleAlpha,
                            false,
                            titleHorizontalPadding
                        )
                    }
                }
                content()
                item("navbar") {
                    Spacer(modifier = Modifier.navigationBarsHeight(bottomPadding))
                }
            }

            TitleBar(
                title = title,
                onBack = onBack,
                showSmallTitle = collapse.showSmallTitle,
                backdrop = titleBackdrop,
                barBackgroundVisible = collapse.showBarBackground,
                rightBarIcon = effectiveRightBarIcon
            )

            // 页面级弹层宿主：渲染在列表与顶栏之上（设置页下拉玻璃弹层）
            TitleOverlayHostSlot(overlayHost)
        }
    }
}

/**
 * 大标题透明度：随首个 item 滚出视口从 1 衰减到 0（与参考页同公式）。
 * 自带滚动列表的详情页把首 item 当作标题区，即可获得同样的折叠节奏。
 */
@Composable
fun rememberAlpha(state: LazyListState): State<Float> {
    return remember("BaseTitle_alpha") {
        derivedStateOf {
            val currentOffsetY =
                state.layoutInfo.visibleItemsInfo.find { it.index == 0 }?.offset ?: -1
            val height = state.layoutInfo.visibleItemsInfo.find { it.index == 0 }?.size ?: -1
            when {
                currentOffsetY + height <= 0 -> 0f
                else -> (1f + ((currentOffsetY.toFloat() / height) * 1.8f)).coerceAtLeast(0f)
            }
        }
    }
}

/**
 * 小标题可见性：大标题完全滚出后显示，且列表非空时。
 */
@Composable
fun rememberShowSmallTitle(alpha: State<Float>, state: LazyListState): State<Boolean> {
    return remember("BaseTitle_showSmallTitle") {
        derivedStateOf {
            alpha.value <= 0f && state.layoutInfo.visibleItemsInfo.isNotEmpty()
        }
    }
}

/** [rememberTitleCollapse] 的产出：三个绘制期状态。 */
class TitleCollapseState(
    /** 大标题透明度：触及顶栏下缘前恒为 1，之后在玻璃下溶解，完全没入时为 0。 */
    val largeTitleAlpha: State<Float>,
    /** 顶栏背景（模糊+渐变）可见性：列表发生任何滚动即为 true，回到顶部为 false。 */
    val showBarBackground: State<Boolean>,
    /** 小标题可见性：大标题完全没入顶栏后才为 true（两个标题从不同时出现）。 */
    val showSmallTitle: State<Boolean>,
    /** 手指是否按在列表上（[titleCollapseTouchTracker] 写入，吸附暂停判断用）。 */
    internal val touchActive: MutableState<Boolean> = mutableStateOf(false)
)

/**
 * 挂在折叠列表上：跟踪手指按下/抬起写入 [TitleCollapseState.touchActive]。
 * 不消费任何事件，对滚动/点击透明。
 */
fun Modifier.titleCollapseTouchTracker(state: TitleCollapseState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            android.util.Log.d("TitleCollapse", "touch DOWN")
            state.touchActive.value = true
            // 等所有指针抬起（本版本 awaitAllPointersUp 为 internal，手写等价逻辑）
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.all { !it.pressed }) break
            }
            android.util.Log.d("TitleCollapse", "touch UP")
            state.touchActive.value = false
        }
    }

private class CollapseUi(val alpha: Float, val bar: Boolean, val small: Boolean)

/**
 * 大标题折叠逻辑（NexioSchedule CollapsibleTopAppBar 式二态收敛）：
 *
 * - 大标题保持不透明直到顶部触及顶栏下缘，之后在顶栏玻璃下溶解，完全没入时小标题才出现
 *   ——两个标题从不同时可见，也不再有"半透明大标题悬停"的中间态；
 * - 顶栏背景在列表发生任何滚动时即出现（Nexio 顶栏常驻的等价行为），大标题从玻璃下
 *   穿过，而不是裸露着滑过状态栏；
 * - 滚动停止后若停在过渡区内（既没完全展开也没完全收起），自动吸附到更近的端点
 *   （Nexio settleAppBar：折叠比例 <0.5 弹回展开，否则收起）。
 *
 * 几何约定：Base 页首个 item = 状态栏占位 Spacer + 大标题文本；静止时 item offset 为 0，
 * 文本底边触及顶栏底缘（offset = -zone）记为折叠进度 1。
 * [extraTopPadding] 是列表 contentPadding 在 54dp 之外额外下移的大标题间距：
 * 该距离内大标题还在栏外自由区，必须保持完全不透明。
 */
@Composable
fun rememberTitleCollapse(state: LazyListState, extraTopPadding: Dp = 0.dp): TitleCollapseState =
    rememberTitleCollapseImpl(
        scrollable = state,
        firstItemInfo = {
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                ?.let { Pair(it.offset, it.size) }
        },
        extraTopPadding = extraTopPadding
    )

@Composable
fun rememberTitleCollapse(state: LazyGridState, extraTopPadding: Dp = 0.dp): TitleCollapseState =
    rememberTitleCollapseImpl(
        scrollable = state,
        firstItemInfo = {
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
                ?.let { Pair(it.offset.y, it.size.height) }
        },
        extraTopPadding = extraTopPadding
    )

@Composable
private fun rememberTitleCollapseImpl(
    scrollable: ScrollableState,
    firstItemInfo: () -> Pair<Int, Int>?,
    extraTopPadding: Dp
): TitleCollapseState {
    val density = LocalDensity.current
    // 与 TitleItem 内部 Spacer(statusBarsHeight()) 同源（accompanist），保证几何一致
    val statusPx = LocalWindowInsets.current.statusBars.top
    val extraPx = with(density) { extraTopPadding.roundToPx() }
    val currentFirstItem by rememberUpdatedState(firstItemInfo)
    val touchActive = remember { mutableStateOf(false) }

    // 三个对外状态必须与 ui 在同一个 remember(key) 里创建：statusPx 首帧是 0、
    // insets 派发后才变 152，key 变化会重建 ui；若外层 derivedStateOf 不跟着重建，
    // 会永久引用旧 ui（旧 statusPx/zone），小标题永远差一截到不了折叠态。
    val states = remember(scrollable, extraPx, statusPx) {
        val uiState = derivedStateOf {
            val info = currentFirstItem()
                ?: return@derivedStateOf CollapseUi(alpha = 0f, bar = true, small = true)
            // zone：从静止到"文本底边触及顶栏底缘"的总滚动量 = 额外下移 + 文本高度
            val zone = (extraPx + info.second - statusPx).coerceAtLeast(1)
            val raw = -info.first.toFloat() / zone
            when {
                raw <= 0f -> CollapseUi(alpha = 1f, bar = false, small = false)
                raw >= 1f -> CollapseUi(alpha = 0f, bar = true, small = true)
                else -> {
                    // extraTopPadding > 0 时大标题离栏更远：触及栏缘（raw=edge）前不渐隐
                    val edge = (extraPx.toFloat() / zone).coerceIn(0f, 1f)
                    val fade = ((raw - edge) / (1f - edge).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                    CollapseUi(alpha = 1f - fade, bar = true, small = false)
                }
            }
        }
        Triple(
            derivedStateOf { uiState.value.alpha },
            derivedStateOf { uiState.value.bar },
            derivedStateOf { uiState.value.small }
        )
    }
    val largeTitleAlpha = states.first
    val showBarBackground = states.second
    val showSmallTitle = states.third

    // 松手吸附（Nexio settleAppBar 等价）：过渡区内停住时滚向更近的端点。
    // 触发用「offset 稳定 150ms」而不是等 isScrollInProgress=false——惯性衰减动画有
    // 数百毫秒的亚像素隐形尾巴，视觉已停但状态仍算滚动中，等它会延迟数秒。
    // 吸附动画用 dispatchRawDelta 直写（不经滚动锁）：animateScrollBy 会排在仍在
    // 运行的衰减动画后面，锁等待同样造成延迟。手指按住时暂停吸附（touchActive），
    // 抬起后由 ③ 兜底重评估。
    LaunchedEffect(scrollable, extraPx, statusPx) {
        var snapJob: Job? = null
        var snapTarget = Float.NaN
        val fingerDown = touchActive

        fun evaluate() {
            val info = currentFirstItem()
            if (info == null) {
                snapJob?.cancel(); snapJob = null; snapTarget = Float.NaN
                return
            }
            val zone = (extraPx + info.second - statusPx).coerceAtLeast(1)
            val raw = -info.first.toFloat() / zone
            if (raw <= 0.005f || raw >= 1f) {
                snapJob?.cancel(); snapJob = null; snapTarget = Float.NaN
                return
            }
            val target = if (raw < 0.5f) 0f else -zone.toFloat()
            if (snapJob != null && snapTarget == target) return // 已在吸向同一端
            snapJob?.cancel()
            snapTarget = target
            snapJob = launch {
                try {
                    val start = info.first.toFloat()
                    val duration = 180_000_000L
                    var t = withFrameNanos { it }
                    val t0 = t
                    var last = start
                    while (t - t0 < duration) {
                        t = withFrameNanos { it }
                        val p = ((t - t0).toFloat() / duration).coerceIn(0f, 1f)
                        // EaseOutCubic：先快后缓，收尾不顿挫
                        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
                        val pos = start + (target - start) * eased
                        // 符号约定与 animateScrollBy 一致：正 delta = 向前滚 = offset 减小。
                        // 传反了会把列表往回推，与下一轮吸附来回拉锯形成振荡。
                        scrollable.dispatchRawDelta(last - pos)
                        last = pos
                    }
                    scrollable.dispatchRawDelta(last - target)
                } finally {
                    snapTarget = Float.NaN
                }
            }
        }

        // ① 视觉静止 50ms 即吸附（≈120Hz 下 3-4 帧无位移，手指按住时暂缓）。
        // 不能为 0：需要确认惯性确实停了，否则惯性中途就抢滚。
        var stable: Job? = null
        launch {
            snapshotFlow { currentFirstItem()?.first }
                .collect {
                    stable?.cancel()
                    stable = launch {
                        delay(50)
                        if (!fingerDown.value) evaluate()
                    }
                }
        }
        // ② 惯性状态结束兜底重评估（含抬起；与 ① 谁先到都幂等）
        snapshotFlow { scrollable.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) evaluate()
            }
    }

    return TitleCollapseState(largeTitleAlpha, showBarBackground, showSmallTitle, touchActive)
}

/**
 * TitleBar 向顶栏图标按钮透传的玻璃参数：采样源 + 滚动联动的两个透明度。
 * TitleBarIcon 在调用方组合（如 NormalMusic 的 rightBarIcon），拿不到 TitleBar
 * 内部作用域，故经 CompositionLocal 透传；TitleBar 调用 rightBarIcon() 时 provide。
 */
internal class TitleBarGlassContext(
    val backdrop: Backdrop,
    val backdropAlpha: () -> Float,
    val shadowAlpha: () -> Float
)

private val LocalTitleBarGlass = compositionLocalOf<TitleBarGlassContext?> { null }

/**
 * 设置页玻璃弹层（LiquidDropdown）的采样源：Title 页滚动内容层背景。
 * 弹层用它对背后的列表内容做 vibrancy+blur 玻璃化；无上下文时弹层走近实色兜底。
 */
val LocalTitlePageBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 页面级弹层宿主：设置页下拉弹层渲染在 Title 页根 Box 顶层，与页面同窗口同帧。
 * androidx Popup 是独立子窗口：窗口创建/首帧合成的延迟会把展开弹簧的起步截断
 * （弹出来时动画已跑过半，观感生硬），返回键与触摸也走独立输入通道。
 * 对照 NexioSchedule 的 root Scaffold 弹层宿主（renderInRootScaffold）同思路。
 */
@Stable
class TitleOverlaySlot {
    internal val content = mutableStateOf<(@Composable () -> Unit)?>(null)
}

@Stable
class TitleOverlayHost {
    internal val slots = mutableStateMapOf<Any, TitleOverlaySlot>()

    /** 注册弹层内容；slot 对象保持稳定，内容通过可观察 State 更新。 */
    fun set(key: Any, content: @Composable () -> Unit) {
        val slot = slots[key] ?: TitleOverlaySlot().also { slots[key] = it }
        slot.content.value = content
    }

    fun remove(key: Any) {
        slots.remove(key)
    }
}

val LocalTitleOverlayHost = compositionLocalOf<TitleOverlayHost?> { null }

/** 独立作用域读取 slots：弹层注册/注销只重组这一层，不牵动页面内容。 */
@Composable
private fun TitleOverlayHostSlot(host: TitleOverlayHost) {
    Box(Modifier.fillMaxSize()) {
        host.slots.forEach { (slotKey, slot) ->
            key(slotKey) { slot.content.value?.invoke() }
        }
    }
}

@Composable
fun TitleBarIcon(modifier: Modifier = Modifier, icon: ImageVector? = null, onBack: (() -> Unit)? = null) {
    if (icon == null) return
    val glass = LocalTitleBarGlass.current
    if (glass != null) {
        // 液态玻璃圆钮（NexioSchedule LiquidTopBarButton 材质）：页面在顶部时是
        // 半透明平底圆，滚动后变毛玻璃 + 投影；尺寸从 28dp 增至 42dp、图标 19→23dp。
        // 图标着色沿用 Nexio 原版（浅色黑 85% / 深色白 85%），如需主题红改
        // LiquidTopBarButton 的 iconTint 调用处即可。
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .then(modifier)
        ) {
            LiquidTopBarButton(
                onClick = { onBack?.invoke() },
                backdrop = glass.backdrop,
                icon = rememberVectorPainter(icon),
                contentDescription = null,
                iconSize = 23.dp,
                backdropAlpha = glass.backdropAlpha,
                shadowAlpha = glass.shadowAlpha,
                draggable = true
            )
        }
    } else {
        Box(
            modifier = Modifier
                // TitleBar 内容区已在外层 Box(629 行)做过 statusBarsPadding，
                // 此处再加会让 accompanist inset 二次扣高、按钮变形，见 TitleBar 注释。
                .padding(end = 10.dp)
                .size(28.dp)
                .background((Color.LightGray withNight Color.DarkGray).copy(alpha = 0.25f), shape = CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onBack?.invoke()
                }.then(modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(19.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 顶栏：滚动离屏后浮现小标题（18.5sp Bold）+ 模糊/纯色背景 + 返回按钮。
 * 参考页由 BaseTitleList/BaseTitleGrid 调用；自带滚动列表的详情页可直接复用
 * （需自行准备 [showSmallTitle] 与 [backdrop]，见 rememberShowSmallTitle/rememberTitleBackdrop）。
 */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)?,
    rightBarIcon: @Composable (RowScope.() -> Unit)? = null,
    showSmallTitle: State<Boolean>,
    backdrop: Backdrop,
    // 顶栏背景（模糊+渐变）与玻璃按钮的可见时机。默认与小标题同拍；
    // Base 页传入"任何滚动即出现"的状态（Nexio 式常驻顶栏），让大标题从玻璃下穿过。
    barBackgroundVisible: State<Boolean>? = null
) {
    // 结构对照原版（D:\flamingo源码 Title.kt TitleBar），仅一处设备适配：
    // 原版外层 statusBarsHeight(54.dp) 在本机因 accompanist inset=0 只有 54dp、
    // 控件靠自身 statusBarsPadding 溢出到 Box 外显示；本机该 padding 实测 152px，
    // 溢出写法会把 28dp 控件压成 4px 线。改为在外层内容 Box 上 statusBarsPadding()，
    // 控件落在与原版正常设备一致的几何位置（本机实测 inner Box 顶 y=168px）。
    // 注意：accompanist 0.16.1 的 statusBarsPadding 不感知父级已消费的 inset，
    // 内部任何控件（含 TitleBarIcon）再写一次会把 28dp 圆钮压成 48dp−inset 的椭圆/横线。
    //
    // 背景层保持原版时机与动画：AnimatedVisibility(showSmallTitle) + fadeIn/fadeOut，
    // 仅在列表滚到小标题阶段出现；外层 Box 不占 inset，matchParentSize 使背景
    // 覆盖 y=0 起整个区域（状态栏 + 54dp 顶栏），滚动内容不会从状态栏区透出。
    //
    // 玻璃按钮（返回键/右上角图标）的滚动联动透明度：跟随顶栏背景出现/消失（默认与小
    // 标题同拍，Base 页传入的 barBackgroundVisible 更早——列表一滚动按钮即变玻璃材质）。
    // 透明度只在绘制期经 lambda 直读，不触发 TitleBar 逐帧重组。
    val barVisible = barBackgroundVisible ?: showSmallTitle
    val glassBackdropAlpha = remember { Animatable(if (barVisible.value) 1f else 0f) }
    val glassShadowAlpha = remember { Animatable(if (barVisible.value) 1f else 0f) }
    LaunchedEffect(barVisible.value) {
        // target 必须与 key 同源（barVisible）：key 在"开始滚动"即翻转，此刻
        // showSmallTitle 还是 false，若读它，target 恒 0 且 key 不再变化，
        // 玻璃材质永远起不来（本次回归的根因）。
        val target = if (barVisible.value) 1f else 0f
        launch { glassBackdropAlpha.animateTo(target, tween(150)) }
        launch { glassShadowAlpha.animateTo(target, tween(150)) }
    }
    val backIcon = painterResource(id = R.drawable.ic_back)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false, onClick = {})
    ) {
        AnimatedVisibility(
            visible = barVisible.value,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Spacer(
                modifier = Modifier
                    .then(
                        if (SettingsLibrary.BarBlurEffect) {
                            // 顶栏表面色 = 页面底色：设置页是分组灰、其余页白/黑。
                            // 若这里仍写死近白，灰底设置页的顶栏会被染白而与背景脱节。
                            val pageColor =
                                LocalTitlePageColor.current ?: (Color.White withNight Color.Black)
                            // 真·渐进模糊：半径随 Y 从 12dp 连续收到 0，并向栏下多延伸
                            // 40dp 作为淡出尾巴（见 titleBarProgressiveBlur）。
                            // 这样淡出不会被顶栏底边裁断，过渡长度也更接近原参考实现。
                            titleBarProgressiveBlur(backdrop = backdrop, tintColor = pageColor)
                        } else {
                            // 关闭"工具栏液态玻璃"：顶栏改为 Haze 磨砂——采样顶栏下方内容做
                            // 均匀模糊，并叠一层半透明页面底色（与 Haze 底栏/迷你条同源），
                            // 取代原先的纯色块。
                            val pageColor =
                                LocalTitlePageColor.current ?: (Color.White withNight Color.Black)
                            Modifier.drawPlainBackdrop(
                                backdrop = backdrop,
                                shape = { androidx.compose.ui.graphics.RectangleShape },
                                effects = { blur(14.dp.toPx()) },
                                onDrawSurface = { drawRect(pageColor.copy(alpha = 0.7f)) }
                            )
                        }
                    )
                    .fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
            ) {
                Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                    if (onBack != null) {
                        // start 16.5dp：圆钮左缘与设置页卡片（RoundColumn）左缘对齐。
                        Box(Modifier.padding(start = 16.5.dp)) {
                            if (SettingsLibrary.BarBlurEffect) {
                                // 返回键为液态玻璃圆钮（NexioSchedule 材质）：42dp 圆 +
                                // ic_back 18dp、x -2dp 光学偏移（25dp 实测过大，用户反馈缩小）；
                                // 按住可拖动（高光跟手 + 倾斜 + 松手回弹）。图标着色见 LiquidTopBarButton。
                                LiquidTopBarButton(
                                    onClick = onBack,
                                    backdrop = backdrop,
                                    icon = backIcon,
                                    contentDescription = null,
                                    iconSize = 18.dp,
                                    iconOffset = DpOffset((-2).dp, 0.dp),
                                    backdropAlpha = { glassBackdropAlpha.value },
                                    shadowAlpha = { glassShadowAlpha.value },
                                    draggable = true
                                )
                            } else {
                                // 关闭"工具栏液态玻璃"后不再有玻璃圆底/边缘光，退回纯图标
                                // （与原版 flamingo 顶栏返回键一致）。
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onBack
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = backIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(horizontal = 70.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AnimatedVisibility(
                            visible = showSmallTitle.value,
                            enter = fadeIn(animationSpec = tween(120, easing = EaseOut)) +
                                    expandVertically(
                                        expandFrom = Alignment.Top,
                                        clip = false,
                                        animationSpec = tween(120, easing = EaseOut)
                                    ),
                            exit = fadeOut(animationSpec = tween(120, easing = EaseInCirc)) +
                                    shrinkVertically(
                                        shrinkTowards = Alignment.Top,
                                        clip = false,
                                        animationSpec = tween(120, easing = EaseInCirc)
                                    )
                        ) {
                            Text(
                                text = title,
                                fontSize = 18.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (rightBarIcon != null) {
                        Row(Modifier.fillMaxSize().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                            // 关闭"工具栏液态玻璃"时不提供玻璃上下文，TitleBarIcon 退回
                            // flamingo 原始扁平圆钮（无 backdrop/折射/边缘光）。
                            if (SettingsLibrary.BarBlurEffect) {
                                CompositionLocalProvider(
                                    LocalTitleBarGlass provides TitleBarGlassContext(
                                        backdrop = backdrop,
                                        backdropAlpha = { glassBackdropAlpha.value },
                                        shadowAlpha = { glassShadowAlpha.value }
                                    )
                                ) {
                                    rightBarIcon()
                                }
                            } else {
                                rightBarIcon()
                            }
                        }
                    }
                }

                if (!SettingsLibrary.BarBlurEffect) {
                    AnimatedVisibility(
                        visible = barVisible.value,
                        enter = fadeIn(animationSpec = tween(120, easing = EaseOut)),
                        exit = fadeOut(animationSpec = tween(120, easing = EaseInCirc))
                    ) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(0.12f)
                                .height(1.dp)
                                .background(Color.Black withNight Color.White)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleItem(
    title: String,
    subTitle: String?,
    rightIcon: ImageVector?,
    onRightIcon: (() -> Unit)?,
    alpha: State<Float>,
    grid: Boolean = false,
    titleHorizontalPadding: Dp = 20.dp
) {
    Row(
        Modifier
            .padding(horizontal = if (grid) 0.dp else titleHorizontalPadding)
            .padding(bottom = if (grid) 0.dp else 12.dp, top = 8.dp)
            .graphicsLayer {
                //compositingStrategy = CompositingStrategy.Offscreen
                this.alpha = alpha.value
            }) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 35.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 40.sp
            )
            if (subTitle != null) {
                Text(
                    text = subTitle,
                    modifier = Modifier
                        .alpha(0.5f)
                        .padding(horizontal = 2.5.dp)
                )
            }
        }

        if (rightIcon != null) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            enabled = onRightIcon != null,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onRightIcon ?: {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = rightIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
