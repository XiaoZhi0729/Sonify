package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.BottomBarLayoutLogic
import yos.music.player.data.libraries.BottomBarLayoutStore
import yos.music.player.data.libraries.BottomBarTabConfig
import yos.music.player.data.libraries.BottomBarTabId
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.discovery.HomeDragMath
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.BottomNavigator
import yos.music.player.ui.widgets.basic.NavItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

private val PreviewCorner = 20.dp
private val CardCorner = 16.dp
private val CardHeight = 60.dp
private val CardGap = 10.dp
private val CardStep = CardHeight + CardGap
private val HiddenHeaderHeight = 34.dp
private val EditMinusRed = Color(0xFFFF3B30)
private val EditPlusGreen = Color(0xFF34C759)

private val SlotSettleSpec: FiniteAnimationSpec<Float> = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = 0.5f
)
private val DragSnapSpec: FiniteAnimationSpec<Float> = snap()

/**
 * 预览卡录制层 onDraw（文件级稳定单例，与 GlassTuningPanel 同约定，避免反复重建 LayerBackdrop）。
 */
private val PreviewBackdropOnDraw: ContentDrawScope.() -> Unit = {
    drawContent()
}

@Composable
fun BottomBarSetting(navController: NavController) {
    SettingBackground {
        Title(
            title = stringResource(id = R.string.settings_performance_ui_bottom_bar_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 34.5.dp,
            content = {
                item("bottom_bar") {
                    BottomBarSettingContent()
                }
            }
        )
    }
}

/**
 * 在 LazyColumn item 的自身重组作用域内直接读取 [BottomBarLayoutStore] 状态，
 * 确保排序、显隐、启动页及恢复默认都能即时刷新全部区块。
 */
@Composable
private fun BottomBarSettingContent() {
    val context = LocalContext.current
    val tabs = BottomBarLayoutStore.tabs.value
    val launchTabKey = BottomBarLayoutStore.launchTabKey.value
    val visibleTabIds = remember(tabs) { BottomBarLayoutStore.visibleTabIds() }
    val isDefault = BottomBarLayoutLogic.isDefault(tabs, launchTabKey)

    Column(Modifier.fillMaxSize()) {
        GroupSpacerMedium()

        // 1. 顶部实时底栏预览卡片
        ListHeader(content = stringResource(id = R.string.bottom_bar_setting_preview_label))
        BottomBarPreviewCard(visibleTabIds = visibleTabIds)

        GroupSpacer()

        // 2. 可拖拽排序 + 显隐分组列表
        ListHeader(content = stringResource(id = R.string.bottom_bar_setting_visible_header))
        BottomBarEditableList(
            tabs = tabs,
            onToggle = { id ->
                Vibrator.click(context)
                BottomBarLayoutStore.toggle(id)
            },
            onDragCommit = { from, to ->
                BottomBarLayoutStore.dragCommit(from, to)
            }
        )
        ListHeader(content = stringResource(id = R.string.bottom_bar_setting_hint))

        GroupSpacer()

        // 3. 冷启动默认页选择
        ListHeader(content = stringResource(id = R.string.bottom_bar_setting_launch_header))
        LaunchTabSelectorCard(
            visibleTabIds = visibleTabIds,
            launchTabKey = launchTabKey,
            onSelectLaunchTab = { id ->
                Vibrator.click(context)
                BottomBarLayoutStore.setLaunchTab(id)
            }
        )

        GroupSpacer()

        // 4. 恢复默认
        RoundColumn {
            LabelItem(
                title = stringResource(id = R.string.bottom_bar_setting_reset),
                superLink = true,
                enabled = !isDefault
            ) {
                Vibrator.click(context)
                BottomBarLayoutStore.reset()
            }
        }

        GroupSpacer()
    }
}

@Composable
private fun BottomBarPreviewCard(visibleTabIds: List<BottomBarTabId>) {
    val previewBackdrop = rememberLayerBackdrop(onDraw = PreviewBackdropOnDraw)
    val isDark = isFlamingoInDarkMode()

    var selectedPreviewTab by rememberSaveable {
        mutableStateOf(BottomBarLayoutStore.resolveLaunchTab())
    }
    LaunchedEffect(visibleTabIds) {
        if (selectedPreviewTab !in visibleTabIds) {
            selectedPreviewTab = visibleTabIds.firstOrNull() ?: BottomBarTabId.Home
        }
    }

    val previewItems = visibleTabIds.map { id ->
        NavItem(label = tabTitle(id), iconResId = tabIconRes(id))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(114.dp)
            .clip(RoundedCornerShape(PreviewCorner))
    ) {
        // 兄弟节点 ①：仅录制背景图，绝不把 BottomNavigator 放进 layerBackdrop 子树
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(previewBackdrop)
        ) {
            Image(
                painter = painterResource(
                    id = if (isDark) R.drawable.sonify_banner_dark else R.drawable.sonify_banner_light
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 兄弟节点 ②：真实 BottomNavigator（实时跟随顺序、数量与液态玻璃/磨砂开关）
        val externalIndex: () -> Int = {
            visibleTabIds.indexOf(selectedPreviewTab).coerceAtLeast(0)
        }
        BottomNavigator(
            initialIndex = externalIndex(),
            externalIndex = externalIndex,
            onIndexChange = { index ->
                visibleTabIds.getOrNull(index)?.let { selectedPreviewTab = it }
            },
            items = previewItems,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            backdrop = previewBackdrop
        )
    }
}

@Stable
private class BottomBarDragHolder {
    var active by mutableStateOf(false)
    var draggedId by mutableStateOf<String?>(null)
    var fromIndex by mutableIntStateOf(-1)
    var fingerOffsetPx by mutableFloatStateOf(0f)

    fun begin(from: Int, id: String) {
        fromIndex = from
        draggedId = id
        fingerOffsetPx = 0f
        active = true
    }

    fun end() {
        active = false
        draggedId = null
        fromIndex = -1
        fingerOffsetPx = 0f
    }
}

private fun slotTopPx(
    index: Int,
    layout: List<BottomBarTabConfig>,
    cardStepPx: Float,
    hiddenHeaderHeightPx: Float
): Float {
    val firstHidden = layout.indexOfFirst { !it.visible }
    val headerExtra = if (firstHidden in 0..index) hiddenHeaderHeightPx else 0f
    return index * cardStepPx + headerExtra
}

private fun computeDragTargetIndex(
    fromIndex: Int,
    draggedCenterPx: Float,
    restingLayout: List<BottomBarTabConfig>,
    cardHeightPx: Float,
    cardStepPx: Float,
    hiddenHeaderHeightPx: Float
): Int {
    if (fromIndex !in restingLayout.indices) return 0
    fun restingCenter(idx: Int): Float =
        slotTopPx(idx, restingLayout, cardStepPx, hiddenHeaderHeightPx) + cardHeightPx / 2f

    var target = fromIndex
    while (target < restingLayout.lastIndex) {
        val threshold = (restingCenter(target) + restingCenter(target + 1)) / 2f
        if (draggedCenterPx > threshold) target++ else break
    }
    while (target > 0) {
        val threshold = (restingCenter(target) + restingCenter(target - 1)) / 2f
        if (draggedCenterPx < threshold) target-- else break
    }
    return target
}

@Composable
private fun BottomBarEditableList(
    tabs: List<BottomBarTabConfig>,
    onToggle: (BottomBarTabId) -> Unit,
    onDragCommit: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardHeightPx = with(density) { CardHeight.toPx() }
    val cardStepPx = with(density) { CardStep.toPx() }
    val hiddenHeaderHeightPx = with(density) { HiddenHeaderHeight.toPx() }
    val handleHitWidthPx = with(density) { 64.dp.toPx() }
    val toggleButtonHitWidthPx = with(density) { 64.dp.toPx() }

    val dragHolder = remember { BottomBarDragHolder() }
    var containerWidthPx by remember { mutableIntStateOf(0) }

    val latestTabs = rememberUpdatedState(tabs)
    val latestOnDragCommit = rememberUpdatedState(onDragCommit)

    val liveTargetIndex by remember(tabs, cardHeightPx, cardStepPx, hiddenHeaderHeightPx) {
        derivedStateOf {
            if (!dragHolder.active || dragHolder.fromIndex !in tabs.indices) {
                null
            } else {
                val originCenter =
                    slotTopPx(dragHolder.fromIndex, tabs, cardStepPx, hiddenHeaderHeightPx) +
                        cardHeightPx / 2f
                computeDragTargetIndex(
                    fromIndex = dragHolder.fromIndex,
                    draggedCenterPx = originCenter + dragHolder.fingerOffsetPx,
                    restingLayout = tabs,
                    cardHeightPx = cardHeightPx,
                    cardStepPx = cardStepPx,
                    hiddenHeaderHeightPx = hiddenHeaderHeightPx
                )
            }
        }
    }

    // 每次落点下标变化时给出轻微触感反馈
    var lastHapticTarget by remember { mutableIntStateOf(-1) }
    LaunchedEffect(dragHolder.active, liveTargetIndex) {
        if (!dragHolder.active) {
            lastHapticTarget = -1
        } else {
            val t = liveTargetIndex ?: return@LaunchedEffect
            if (lastHapticTarget != -1 && lastHapticTarget != t) {
                Vibrator.click(context)
            }
            lastHapticTarget = t
        }
    }

    // 拖拽期实时预览布局：非被拖项与「已隐藏」标题立即让位，松手零跳变
    val previewLayout = remember(tabs, dragHolder.active, dragHolder.fromIndex, liveTargetIndex) {
        val target = liveTargetIndex
        if (dragHolder.active && target != null && dragHolder.fromIndex in tabs.indices) {
            BottomBarLayoutLogic.dragCommit(tabs, dragHolder.fromIndex, target)
        } else {
            tabs
        }
    }

    val previewVisibleCount = previewLayout.count { it.visible }
    val hasHidden = previewVisibleCount < previewLayout.size
    val totalHeightDp by animateDpAsState(
        targetValue = CardStep * previewLayout.size - CardGap +
            if (hasHidden) HiddenHeaderHeight else 0.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "bottomBarListHeight"
    )
    val maxListTopPx = with(density) {
        (CardStep * previewLayout.size - CardGap +
            (if (hasHidden) HiddenHeaderHeight else 0.dp) - CardHeight).toPx().coerceAtLeast(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(totalHeightDp)
            .onSizeChanged { containerWidthPx = it.width }
            // 手势挂在固定不位移的外层 Box 上，避免卡片自身 graphicsLayer translationY 引起坐标反馈抖动
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val currentTabs = latestTabs.value
                    val hitIndex = currentTabs.indices.firstOrNull { idx ->
                        val top = slotTopPx(idx, currentTabs, cardStepPx, hiddenHeaderHeightPx)
                        down.position.y >= top && down.position.y < top + cardHeightPx
                    } ?: return@awaitEachGesture

                    // 右侧减/加按钮区交给按钮自身 clickable 处理
                    if (containerWidthPx > 0 && down.position.x >= containerWidthPx - toggleButtonHitWidthPx) {
                        return@awaitEachGesture
                    }

                    val fromCfg = currentTabs[hitIndex]
                    val startedOnHandle = down.position.x <= handleHitWidthPx
                    val dragPointer = if (startedOnHandle) {
                        val slopChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        } ?: return@awaitEachGesture
                        Vibrator.longClick(context)
                        dragHolder.begin(hitIndex, fromCfg.id)
                        slopChange
                    } else {
                        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        Vibrator.longClick(context)
                        dragHolder.begin(hitIndex, fromCfg.id)
                        longPress
                    }

                    val completed = drag(dragPointer.id) { change ->
                        dragHolder.fingerOffsetPx += change.positionChange().y
                        change.consume()
                    }
                    if (completed && dragHolder.active) {
                        val resting = latestTabs.value
                        val originCenter =
                            slotTopPx(hitIndex, resting, cardStepPx, hiddenHeaderHeightPx) +
                                cardHeightPx / 2f
                        val finalTarget = computeDragTargetIndex(
                            fromIndex = hitIndex,
                            draggedCenterPx = originCenter + dragHolder.fingerOffsetPx,
                            restingLayout = resting,
                            cardHeightPx = cardHeightPx,
                            cardStepPx = cardStepPx,
                            hiddenHeaderHeightPx = hiddenHeaderHeightPx
                        )
                        if (finalTarget != hitIndex) {
                            latestOnDragCommit.value(hitIndex, finalTarget)
                        }
                    }
                    dragHolder.end()
                }
            }
    ) {
        // 「已隐藏」分组小标题：随隐藏区边界平滑上下移动并淡入淡出
        val hiddenHeaderAlpha by animateFloatAsState(
            targetValue = if (hasHidden) 1f else 0f,
            animationSpec = tween(durationMillis = 150),
            label = "bottomBarHiddenHeaderAlpha"
        )
        val hiddenHeaderTopPx by animateFloatAsState(
            targetValue = previewVisibleCount * cardStepPx,
            animationSpec = SlotSettleSpec,
            label = "bottomBarHiddenHeaderTop"
        )
        if (hiddenHeaderAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HiddenHeaderHeight)
                    .graphicsLayer {
                        translationY = hiddenHeaderTopPx
                        alpha = hiddenHeaderAlpha
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(id = R.string.bottom_bar_setting_hidden_header),
                    fontSize = 14.sp,
                    color = LocalContentColor.current.copy(alpha = 0.55f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 34.5.dp)
                )
            }
        }

        val committedVisibleCount = tabs.count { it.visible }

        // 永久挂载 4 个固定 key 的标签卡片，避免重排时销毁重建
        for (tabId in BottomBarTabId.entries) {
            key(tabId.key) {
                val isDragging = dragHolder.active && dragHolder.draggedId == tabId.key
                val previewIndex = previewLayout.indexOfFirst { it.id == tabId.key }
                val previewCfg = previewLayout.getOrNull(previewIndex)
                    ?: BottomBarTabConfig(tabId.key, true)
                val restingIndex = tabs.indexOfFirst { it.id == tabId.key }
                val restingCfg = tabs.getOrNull(restingIndex)
                    ?: BottomBarTabConfig(tabId.key, true)

                val targetSlotY = if (isDragging && restingIndex >= 0) {
                    val rawTop = slotTopPx(restingIndex, tabs, cardStepPx, hiddenHeaderHeightPx) +
                        dragHolder.fingerOffsetPx
                    HomeDragMath.rubberBand(rawTop, 0f, maxListTopPx)
                } else {
                    slotTopPx(previewIndex.coerceAtLeast(0), previewLayout, cardStepPx, hiddenHeaderHeightPx)
                }

                val animatedTopY by animateFloatAsState(
                    targetValue = targetSlotY,
                    animationSpec = if (isDragging) DragSnapSpec else SlotSettleSpec,
                    label = "bottomBarCardY_${tabId.key}"
                )
                val scale by animateFloatAsState(
                    targetValue = if (isDragging) 1.02f else 1f,
                    animationSpec = tween(durationMillis = 100),
                    label = "bottomBarCardScale_${tabId.key}"
                )
                val shadowPx by animateFloatAsState(
                    targetValue = if (isDragging) with(density) { 8.dp.toPx() } else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "bottomBarCardShadow_${tabId.key}"
                )

                val effectiveVisible = if (isDragging) previewCfg.visible else restingCfg.visible
                val canToggle = if (restingCfg.visible) {
                    committedVisibleCount > BottomBarLayoutLogic.MIN_VISIBLE_TABS
                } else {
                    true
                }

                BottomBarTabCard(
                    tabId = tabId,
                    visible = effectiveVisible,
                    canToggle = canToggle,
                    buttonsEnabled = !dragHolder.active,
                    shadowPx = shadowPx,
                    onToggle = { onToggle(tabId) },
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = animatedTopY
                            scaleX = scale
                            scaleY = scale
                        }
                )
            }
        }
    }
}

@Composable
private fun BottomBarTabCard(
    tabId: BottomBarTabId,
    visible: Boolean,
    canToggle: Boolean,
    buttonsEnabled: Boolean,
    shadowPx: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = YosRoundedCornerShape(CardCorner)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val surfaceColor = MaterialTheme.colorScheme.onSecondary
    val title = tabTitle(tabId)

    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.5f,
        animationSpec = tween(durationMillis = 160),
        label = "bottomBarContentAlpha"
    )
    val handleAlpha by animateFloatAsState(
        targetValue = if (visible) 0.45f else 0.25f,
        animationSpec = tween(durationMillis = 160),
        label = "bottomBarHandleAlpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(CardHeight)
            .graphicsLayer {
                shadowElevation = shadowPx
                this.shape = shape
                clip = false
            }
            .clip(shape)
            .background(surfaceColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_sort),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .alpha(handleAlpha)
        )
        Spacer(Modifier.width(14.dp))
        Icon(
            painter = painterResource(id = tabIconRes(tabId)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(22.dp)
                .alpha(contentAlpha)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 17.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha)
        )
        BottomBarToggleButton(
            visible = visible,
            canToggle = canToggle,
            enabled = buttonsEnabled && canToggle,
            tabTitle = title,
            onClick = onToggle
        )
    }
}

@Composable
private fun BottomBarToggleButton(
    visible: Boolean,
    canToggle: Boolean,
    enabled: Boolean,
    tabTitle: String,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (visible) EditMinusRed else EditPlusGreen,
        animationSpec = tween(durationMillis = 160),
        label = "bottomBarToggleBg"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (canToggle) 1f else 0.28f,
        animationSpec = tween(durationMillis = 160),
        label = "bottomBarToggleAlpha"
    )
    val desc = stringResource(
        if (visible) R.string.bottom_bar_setting_hide_tab else R.string.bottom_bar_setting_show_tab,
        tabTitle
    )
    Box(
        Modifier
            .size(28.dp)
            .alpha(buttonAlpha)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = visible,
            transitionSpec = {
                fadeIn(tween(durationMillis = 140)) togetherWith fadeOut(tween(durationMillis = 140))
            },
            label = "bottomBarToggleIcon"
        ) { isVisible ->
            Icon(
                painter = painterResource(id = if (isVisible) R.drawable.ic_minus else R.drawable.ic_add),
                contentDescription = desc,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun LaunchTabSelectorCard(
    visibleTabIds: List<BottomBarTabId>,
    launchTabKey: String?,
    onSelectLaunchTab: (BottomBarTabId?) -> Unit
) {
    val firstVisibleTab = visibleTabIds.firstOrNull() ?: BottomBarTabId.Home
    val firstVisibleTitle = tabTitle(firstVisibleTab)

    RoundColumn {
        DefaultItem(
            title = stringResource(id = R.string.bottom_bar_setting_launch_first),
            desc = stringResource(id = R.string.bottom_bar_setting_launch_first_desc, firstVisibleTitle),
            onClick = { onSelectLaunchTab(null) }
        ) {
            LaunchTabCheckmark(selected = launchTabKey.isNullOrEmpty())
        }

        visibleTabIds.forEach { tabId ->
            Divider()
            DefaultItem(
                title = tabTitle(tabId),
                leading = {
                    Icon(
                        painter = painterResource(id = tabIconRes(tabId)),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onSelectLaunchTab(tabId) }
            ) {
                LaunchTabCheckmark(selected = launchTabKey == tabId.key)
            }
        }
    }
}

@Composable
private fun LaunchTabCheckmark(selected: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "launchTabCheckAlpha"
    )
    if (alpha > 0.01f) {
        Icon(
            painter = painterResource(id = R.drawable.ic_checkmark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(16.dp)
                .alpha(alpha)
        )
    }
}

@Composable
private fun tabTitle(id: BottomBarTabId): String = stringResource(
    when (id) {
        BottomBarTabId.Home -> R.string.page_home_title
        BottomBarTabId.Favorites -> R.string.page_favorites_title
        BottomBarTabId.Library -> R.string.page_library_title
        BottomBarTabId.Search -> R.string.page_search_title
    }
)

private fun tabIconRes(id: BottomBarTabId): Int = when (id) {
    BottomBarTabId.Home -> R.drawable.ic_uitabbar_home
    BottomBarTabId.Favorites -> R.drawable.ic_uitabbar_favorites
    BottomBarTabId.Library -> R.drawable.ic_uitabbar_library
    BottomBarTabId.Search -> R.drawable.ic_uitabbar_search
}
