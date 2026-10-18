package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.HomeSectionConfig
import yos.music.player.data.libraries.HomeSectionId
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownLayout
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.liquidDropdownAnchorFollow
import yos.music.player.ui.widgets.basic.rememberLiquidDropdownFollowState
import yos.music.player.ui.theme.YosRoundedCornerShape

// ---------- 常量动画 spec（绝不读任何会变的状态，否则 spec 变化打断动画 → 闪现） ----------

private val EditPlacementSpec: FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = 0.9f,
    stiffness = 500f
)
private val EditFadeSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 120)
private val EditSettleSpec: FiniteAnimationSpec<Float> = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = 0.01f
)
private val EditSnapSpec: FiniteAnimationSpec<Float> = snap()

private val EditBlockCorner = 16.dp
private val EditMinusRed = Color(0xFFFF3B30)
private val EditPlusGreen = Color(0xFF34C759)

/** 显示组板块的 LazyList item key（用于按 key 命中/定位，规避 "title" 头 item 造成的下标偏移）。 */
internal fun homeEditKey(id: String) = "edit_$id"

/**
 * 编辑态拖拽状态 holder（Compose 状态；由 Discovery 创建，跨重组存活）。
 * geometry 采用「屏幕坐标（视口顶 = 0）」。
 */
@Stable
internal class HomeEditDragHolder {
    var geometry by mutableStateOf<HomeDragGeometry?>(null)
    var draggedId by mutableStateOf<String?>(null)
    var active by mutableStateOf(false)
    var latched by mutableStateOf(false)
    var draggedHeightPx by mutableIntStateOf(0)

    fun begin(fromIndex: Int, originTopScreenPx: Float, heightPx: Int, id: String) {
        geometry = HomeDragGeometry(
            fromIndex = fromIndex,
            originTopPx = originTopScreenPx,
            draggedHeightPx = heightPx
        )
        draggedHeightPx = heightPx
        draggedId = id
        active = true
        latched = true
    }

    /** 松手：保留 fromIndex/height 但把位移归零 → 被拖块以 spring 落回槽位、阴影立即淡出。 */
    fun release() {
        active = false
        latched = false
        geometry = geometry?.copy(fingerOffsetPx = 0f, consumedScrollPx = 0f)
    }

    fun clear() {
        active = false
        latched = false
        geometry = null
        draggedId = null
        draggedHeightPx = 0
    }
}

/** 板块标题（复用现有 string 资源）。 */
@Composable
internal fun homeSectionTitle(id: HomeSectionId): String = stringResource(
    when (id) {
        HomeSectionId.RecentlyPlayed -> R.string.home_continue_listening
        HomeSectionId.EverydayRecommend -> R.string.home_everyday_recommend_title
        HomeSectionId.RecommendPlaylists -> R.string.discovery_recommend_playlists_title
        HomeSectionId.NewSongs -> R.string.discovery_new_songs_title
        HomeSectionId.NewAlbums -> R.string.discovery_new_albums_title
        HomeSectionId.Rank -> R.string.discovery_rank_title
    }
)

/**
 * 右上角「更多」按钮 + 锚定其上的 LiquidDropdown 菜单（编辑主页 / 设置）。
 * 复用 LiquidDropdown 的锚点联动：面板揭示进度回传 [rememberLiquidDropdownFollowState]，
 * 按钮本体随之位移/收缩/淡出，再复位（与播放页「更多」同一套动画）。
 * 渲染在 Title 的 rightBarIcon 槽内（TitleBar 子树，LocalTitlePageBackdrop / LocalTitleOverlayHost 均可用）。
 */
@Composable
internal fun HomeMenuButton(
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onEditHome: () -> Unit,
    onSettings: () -> Unit
) {
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val backdrop = LocalTitlePageBackdrop.current
    val follow = rememberLiquidDropdownFollowState()
    // onGloballyPositioned 必须挂在上游（未变换）节点：变换下沉到内层子节点，避免锚点被带着走 → 回环抖动
    Box(
        Modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            val size = coords.size
            anchorBounds = IntRect(
                left = pos.x.toInt(),
                top = pos.y.toInt(),
                right = pos.x.toInt() + size.width,
                bottom = pos.y.toInt() + size.height
            )
        }
    ) {
        Box(Modifier.liquidDropdownAnchorFollow(follow)) {
            TitleBarIcon(icon = Icons.Filled.MoreVert, onBack = { onExpandChange(true) })
        }
        LiquidDropdownLayout(
            expanded = expanded,
            anchorBounds = anchorBounds,
            onDismissRequest = { onExpandChange(false) },
            backdrop = backdrop,
            onFractionProgress = follow::onProgress
        ) {
            LiquidDropdownColumn {
                LiquidDropdownRow(
                    text = stringResource(R.string.home_menu_edit),
                    selected = false,
                    showSelectedIcon = false,
                    isFirst = true,
                    onClick = onEditHome
                )
                LiquidDropdownRow(
                    text = stringResource(R.string.home_menu_settings),
                    selected = false,
                    showSelectedIcon = false,
                    isLast = true,
                    onClick = onSettings
                )
            }
        }
    }
}

/**
 * 编辑态列表项：显示组 → 分割线 → 「已隐藏」→ 隐藏组。
 * 所有 item（含分割线/标题）均带 animateItem，避免隐藏时瞬间跳 = 闪现。
 */
internal fun LazyListScope.homeEditItems(
    layout: List<HomeSectionConfig>,
    listState: LazyListState,
    dragHolder: HomeEditDragHolder,
    resetAnimating: Boolean,
    onToggle: (HomeSectionId) -> Unit
) {
    val allKeys = layout.map { homeEditKey(it.id) }
    val totalVisible = layout.count { it.visible }
    layout.forEachIndexed { index, cfg ->
        // 「已隐藏」小标题（无分割线）并入首个隐藏项：随该胶囊一起实时位移
        val showHeader = !cfg.visible && (index == 0 || layout[index - 1].visible)
        item(key = homeEditKey(cfg.id), contentType = "block") {
            HomeEditBlockItem(
                cfg = cfg,
                showHiddenHeader = showHeader,
                totalVisible = totalVisible,
                allKeys = allKeys,
                listState = listState,
                dragHolder = dragHolder,
                resetAnimating = resetAnimating,
                onToggle = onToggle
            )
        }
    }
}

@Composable
private fun LazyItemScope.HomeEditBlockItem(
    cfg: HomeSectionConfig,
    showHiddenHeader: Boolean,
    totalVisible: Int,
    allKeys: List<String>,
    listState: LazyListState,
    dragHolder: HomeEditDragHolder,
    resetAnimating: Boolean,
    onToggle: (HomeSectionId) -> Unit
) {
    val id = cfg.id
    val key = homeEditKey(id)
    val section = HomeSectionId.fromKey(id)
    val title = section?.let { homeSectionTitle(it) } ?: id
    val dragging = dragHolder.latched && dragHolder.draggedId == id
    val geom = dragHolder.geometry
    val myIndex = allKeys.indexOf(key)

    // 拖拽落点（显示/隐藏两组都能拖）：供让位位移 + 被拖项实时预览显隐
    val dragTarget = if (dragHolder.latched && geom != null && myIndex >= 0) {
        val info = listState.layoutInfo
        val centers = info.visibleItemsInfo
            .mapNotNull { item ->
                val k = item.key as? String ?: return@mapNotNull null
                val vi = allKeys.indexOf(k)
                if (vi < 0) null else vi to (item.offset - info.viewportStartOffset + item.size / 2f)
            }
            .sortedBy { it.first }
        HomeDragMath.targetIndex(
            itemCentersPx = centers,
            draggedCenterPx = geom.draggedTopPx + geom.draggedHeightPx / 2f,
            maxIndex = (allKeys.size - 1).coerceAtLeast(0)
        )
    } else null

    // 让位位移（仅非被拖项）
    val displacement = if (dragTarget != null && !dragging && myIndex >= 0 && geom != null) {
        HomeDragMath.displacementFor(myIndex, geom.fromIndex, dragTarget, geom.draggedHeightPx)
    } else 0f

    // 被拖项实时预览：落点在显示区（<= 边界）→ 显示态，否则隐藏态（松手即按此落定）
    val boundary = if (cfg.visible) totalVisible - 1 else totalVisible
    val effectiveVisible = if (dragging && dragTarget != null) dragTarget <= boundary else cfg.visible

    // 被拖块位移（跟手 + 自滚补偿，越界阻尼）
    val draggedTarget = if (dragging && geom != null) {
        val info = listState.layoutInfo
        HomeDragMath.draggedTranslationY(
            draggedTopPx = geom.draggedTopPx,
            untransformedTopPx = geom.untransformedTopPx,
            draggedHeightPx = geom.draggedHeightPx,
            viewportHeightPx = info.viewportEndOffset - info.viewportStartOffset
        )
    } else 0f

    val animatedDisplacement by animateFloatAsState(
        targetValue = if (dragging) draggedTarget else displacement,
        animationSpec = if (dragging && dragHolder.active) EditSnapSpec else EditSettleSpec,
        label = "homeEditDisp_$id"
    )
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.02f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "homeEditScale_$id"
    )
    // 阴影淡入淡出：拖起时 shadowElevation 渐增、松手渐隐（graphicsLayer 逐帧平滑，不用 Modifier.shadow 的层增删）
    val density = LocalDensity.current
    val shadowPx by animateFloatAsState(
        targetValue = if (dragging) with(density) { 8.dp.toPx() } else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "homeEditShadow_$id"
    )

    Column(
        Modifier
            .animateItem(
                placementSpec = EditPlacementSpec,
                fadeInSpec = EditFadeSpec,
                fadeOutSpec = EditFadeSpec
            )
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = animatedDisplacement
                scaleX = scale
                scaleY = scale
            }
    ) {
        if (showHiddenHeader) {
            yos.music.player.ui.pages.settings.ListHeader(
                stringResource(R.string.home_edit_hidden_header)
            )
        }
        HomeEditBlock(
            title = title,
            visible = effectiveVisible,
            shadowPx = shadowPx,
            buttonsEnabled = !dragHolder.active && !resetAnimating && !dragging,
            onToggle = { section?.let(onToggle) },
            modifier = Modifier
        )
    }
}

@Composable
private fun HomeEditBlock(
    title: String,
    visible: Boolean,
    shadowPx: Float,
    buttonsEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = YosRoundedCornerShape(EditBlockCorner)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    // 不透明胶囊：拖动时完全盖住下方块，不透视（编辑态背景为纯色）
    val surfaceColor = MaterialTheme.colorScheme.onSecondary
    // 实时显隐样式（拖拽跨组时淡入淡出）
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0.5f,
        animationSpec = tween(durationMillis = 160),
        label = "homeEditContentAlpha"
    )
    val handleAlpha by animateFloatAsState(
        targetValue = if (visible) 0.4f else 0.25f,
        animationSpec = tween(durationMillis = 160),
        label = "homeEditHandleAlpha"
    )
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            // 阴影仅在拖起时出现，松手 shadowElevation→0 淡出；graphicsLayer 逐帧平滑
            .graphicsLayer {
                shadowElevation = shadowPx
                this.shape = shape
                clip = false
            }
            .clip(shape)
            .background(surfaceColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_sort),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).alpha(handleAlpha)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 20.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).alpha(contentAlpha)
        )
        // 隐藏块的加号保持 1.0 不透明度（不随块灰显）
        HomeEditToggleButton(
            visible = visible,
            enabled = buttonsEnabled,
            sectionTitle = title,
            onClick = onToggle
        )
    }
}

@Composable
private fun HomeEditToggleButton(
    visible: Boolean,
    enabled: Boolean,
    sectionTitle: String,
    onClick: () -> Unit
) {
    // 红/绿底淡入淡出 + 图标（减/加）交叉淡化
    val bg by animateColorAsState(
        targetValue = if (visible) EditMinusRed else EditPlusGreen,
        animationSpec = tween(durationMillis = 160),
        label = "homeEditToggleBg"
    )
    val desc = stringResource(
        if (visible) R.string.home_edit_hide_section else R.string.home_edit_show_section,
        sectionTitle
    )
    Box(
        Modifier
            .size(28.dp)
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
            label = "homeEditToggleIcon"
        ) { v ->
            Icon(
                painter = painterResource(id = if (v) R.drawable.ic_minus else R.drawable.ic_add),
                contentDescription = desc,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 编辑态拖拽手势 + 边缘自动滚动。挂在编辑列表外层 Box 上（父级，事件先经子级）。
 * - 命中判定按 item **key**（allKeys），规避 Title 的 "title" 头 item 造成的下标偏移（长按错位）。
 * - 判定期不 consume：手指滑动超过 touch slop 时 awaitLongPressOrCancellation 返回 null，交回列表滚动。
 */
@Composable
internal fun Modifier.homeEditDragGesture(
    enabled: Boolean,
    listState: LazyListState,
    dragHolder: HomeEditDragHolder,
    allKeys: List<String>,
    onCommit: (Int, Int) -> Unit
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 80.dp.toPx() }
    val maxSpeedPxPerSecond = with(density) { 480.dp.toPx() }

    LaunchedEffect(dragHolder.active) {
        if (!dragHolder.active) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (dragHolder.active) {
            val geom = dragHolder.geometry ?: break
            val info = listState.layoutInfo
            val draggedCenter = geom.draggedTopPx + dragHolder.draggedHeightPx / 2f
            val now = withFrameNanos { it }
            val elapsed = if (lastFrameNanos == 0L) 0f
            else ((now - lastFrameNanos).coerceIn(0L, 100_000_000L) / 1_000_000_000f)
            lastFrameNanos = now
            val delta = HomeDragMath.edgeScrollDelta(
                draggedCenterPx = draggedCenter,
                viewportStartPx = 0f,
                viewportEndPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
                edgeThresholdPx = edgeThresholdPx,
                maxDeltaPx = maxSpeedPxPerSecond * elapsed,
                canScrollBackward = listState.canScrollBackward,
                canScrollForward = listState.canScrollForward
            )
            if (delta != 0f) {
                var consumed = 0f
                listState.scroll { consumed = scrollBy(delta) }
                if (consumed != 0f && dragHolder.active) {
                    dragHolder.geometry = dragHolder.geometry?.afterScroll(consumed)
                }
            }
        }
    }

    return this.pointerInput(enabled, allKeys, listState) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val info = listState.layoutInfo
            val viewportTop = info.viewportStartOffset
            val hit = info.visibleItemsInfo.firstOrNull { item ->
                val k = item.key as? String ?: return@firstOrNull false
                k in allKeys &&
                    down.position.y >= item.offset - viewportTop &&
                    down.position.y < item.offset - viewportTop + item.size
            } ?: return@awaitEachGesture
            val hitKey = hit.key as String
            val fromIndex = allKeys.indexOf(hitKey)
            if (fromIndex < 0) return@awaitEachGesture
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            Vibrator.longClick(context)
            dragHolder.begin(
                fromIndex = fromIndex,
                originTopScreenPx = hit.offset.toFloat() - viewportTop,
                heightPx = hit.size,
                id = hitKey.removePrefix("edit_")
            )
            val completed = drag(longPress.id) { change ->
                val geom = dragHolder.geometry?.afterFingerMove(change.position.y - change.previousPosition.y)
                dragHolder.geometry = geom
                if (!dragHolder.active && geom != null &&
                    HomeDragMath.hasCrossedTouchSlop(geom.fingerOffsetPx, viewConfiguration.touchSlop)
                ) {
                    dragHolder.active = true
                    dragHolder.latched = true
                }
                change.consume()
            }
            val geom = dragHolder.geometry
            if (completed && dragHolder.active && geom != null) {
                val curInfo = listState.layoutInfo
                val centers = curInfo.visibleItemsInfo
                    .mapNotNull { item ->
                        val k = item.key as? String ?: return@mapNotNull null
                        val vi = allKeys.indexOf(k)
                        if (vi < 0) null else vi to (item.offset - curInfo.viewportStartOffset + item.size / 2f)
                    }
                    .sortedBy { it.first }
                val target = HomeDragMath.targetIndex(
                    itemCentersPx = centers,
                    draggedCenterPx = geom.draggedTopPx + geom.draggedHeightPx / 2f,
                    maxIndex = (allKeys.size - 1).coerceAtLeast(0)
                )
                if (target != geom.fromIndex) onCommit(geom.fromIndex, target)
            }
            dragHolder.release()
        }
    }
}
