package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import kotlinx.coroutines.delay


/*
 * 在线歌单详情页的页内搜索：把操作行右侧的圆钮形变成输入框，输入内容**直接过滤列表本身**。
 *
 * 不搬动头部、不加遮罩层、不锁列表滚动 —— 列表始终按原来的方式滚动与点击，搜索框只是把
 * 那颗 42dp 圆钮在原地拉开成与操作行等宽的一条输入框。这样滚动位置、点击行为、
 * 顶栏模糊采样源全部保持不变，不会出现任何跳变。
 */

/** 折叠态圆钮直径（与 ArtistRoundActionButton 一致）。 */
internal val SearchFieldCollapsedSize = 42.dp

/** 展开态搜索框高度（与 PlayPillButton 的 44dp 对齐）。 */
internal val SearchFieldExpandedHeight = 44.dp

/** 操作行内圆钮间距（与 CompactPlayActionRow 同值）。 */
internal val SearchActionGap = 13.dp

/** 操作行圆钮直径（与 CompactPlayActionRow 同值）。 */
internal val SearchActionButtonSize = 42.dp

/** 播放胶囊最小宽度（与 CompactPlayActionRow 同值，再窄会挤掉图标与文字）。 */
internal val SearchActionMinPillWidth = 140.dp

/**
 * 形变主曲线。
 *
 * 用 Flamingo 逆向得出的页面编排曲线 spring(1.2 / 800 / 0.001)（与 NowPlaying.kt 的
 * MorphFloatSpec 同源）——过阻尼（>1）起手利落、收尾极软，全项目的页面过渡、
 * 歌词滑移、共享元素 morph 都用这一条，同一页内的形变理应同曲线同拍。
 *
 * 之前用的 spring(0.77 / 220) 是下拉菜单的开合曲线：欠阻尼 + 低刚度，
 * 在这条 280dp 的横向形变上表现为「起步拖、中段慢、末尾还在弹」，观感发死。
 */
internal val SearchMorphSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = 1.2f, stiffness = 800f, visibilityThreshold = 0.001f)

/** 动画收敛判据：达到此值视为展开完成（可安全聚焦）。 */
internal const val SearchMorphSettled = 0.995f

/** 形变动画的参考时长（ms）：仅用于「收起后延迟卸载展开层」，不驱动动画本身。 */
internal const val SearchMorphDurationMs = 380L

/**
 * 操作行几何：算出「随机圆钮 / 播放胶囊 / 尾部圆钮」三者在行内的位置。
 *
 * 与 CompactPlayActionRow 的排布算式保持一致（整行居中、胶囊取剩余空间 2/3、下限 140dp），
 * 单独算一次是为了让搜索框形变的起点能与真实圆钮**逐像素重合**。纯函数，可单测。
 */
internal class PlayActionGeometry(maxWidth: Dp) {
    val buttonSize = SearchActionButtonSize
    val gap = SearchActionGap

    /** 尾部槽位宽（圆钮 + 前面的间距），对应 CompactPlayActionRow 的 trailingWidth。 */
    private val trailingWidth = buttonSize + gap

    /** 播放胶囊可用的剩余空间（已扣掉两个圆钮与一个间距）。 */
    private val remaining = maxWidth - buttonSize - gap - trailingWidth

    /** 播放胶囊宽度：剩余空间 2/3，下限 140dp。 */
    val pillWidth = (remaining * 2f / 3f).coerceAtLeast(SearchActionMinPillWidth)

    /** 整行实际宽度（三个控件 + 两个间距）。 */
    private val rowWidth = buttonSize + gap + pillWidth + trailingWidth

    /** 行首相对容器左缘的偏移（整行居中；极窄屏可能为负，与真实布局一致）。 */
    private val rowStart = (maxWidth - rowWidth) / 2f

    /** 随机钮左缘（相对容器左缘）。 */
    val shuffleLeft = rowStart

    /** 播放胶囊左缘（相对容器左缘）。 */
    val pillLeft = rowStart + buttonSize + gap

    /** 尾部圆钮左缘（相对容器左缘）。 */
    val trailingLeft = rowStart + buttonSize + gap + pillWidth + gap

    /** 尾部圆钮中心 X：搜索框折叠态必须落在这一点上。 */
    val trailingCenterX = trailingLeft + buttonSize / 2f

    /** 展开态宽度：铺满操作行（整行宽度）。 */
    val expandedWidth = maxWidth
}

/**
 * 形变搜索框：折叠态是一颗 42dp 圆钮，展开态是一条铺满操作行的输入框。
 *
 * 几何在 layout 阶段从 [fraction] 读动画值算出（不在组合期读），逐帧只重跑布局与绘制，
 * 不触发重组。折叠态与真实圆钮逐像素一致 —— 因为这里绘制的**就是**那颗钮，不存在接缝。
 *
 * @param expanded 当前是否处于展开态（决定点击是「打开」还是「聚焦」）
 * @param onOpen 折叠态点击：展开并聚焦
 */
@Composable
internal fun MorphSearchField(
    fraction: State<Float>,
    geometry: PlayActionGeometry,
    contentColor: Color,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
    expanded: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hint = stringResource(id = R.string.online_playlist_search_hint)
    val clearDescription = stringResource(id = R.string.online_playlist_search_clear)
    val fill = contentColor.copy(alpha = 0.18f)
    val interaction = remember { MutableInteractionSource() }
    val buttonSizePx = with(density) { geometry.buttonSize.toPx() }
    val expandedWidthPx = with(density) { geometry.expandedWidth.toPx() }
    val trailingCenterPx = with(density) { geometry.trailingCenterX.toPx() }
    val collapsedSizePx = with(density) { SearchFieldCollapsedSize.toPx() }
    val expandedHeightPx = with(density) { SearchFieldExpandedHeight.toPx() }
    val collapsedRadiusPx = collapsedSizePx / 2f
    val expandedRadiusPx = with(density) { 12.dp.toPx() }
    val rimWidthPx = with(density) { 0.75.dp.toPx() }

    // 展开层是否参与组合。折叠态必须为 false（否则隐形输入框会吞掉圆钮的点击），
    // 收起时延后一个动画时长再卸载，让淡出能播完。
    //
    // 展开方向必须**同步**参与组合（不能靠 LaunchedEffect 补一个 true）：
    // 调用方点完搜索钮就要立刻 requestFocus，如果展开层还等下一次重组才出现，
    // 那一帧的 FocusRequester 是悬空的 —— 表现为「点了没反应，得再手动点一下输入框」。
    // 所以展开时直接由 expanded 决定，只有收起方向才走延迟卸载。
    var keepMountedWhileCollapsing by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            keepMountedWhileCollapsing = true
        } else {
            delay(SearchMorphDurationMs)
            keepMountedWhileCollapsing = false
        }
    }
    // expanded 为 true 的那一帧就已经是 true —— FocusRequester 在同帧可用
    val expandedLayerPresent = expanded || keepMountedWhileCollapsing

    // 外层：铺满操作行（调用方已给固定高度），只负责按插值后的坐标摆放内层
    Box(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val f = fraction.value.coerceIn(0f, 1f)
                val widthPx = lerp(buttonSizePx, expandedWidthPx, f)
                val heightPx = lerp(collapsedSizePx, expandedHeightPx, f)
                val centerX = lerp(trailingCenterPx, expandedWidthPx / 2f, f)
                val placeable = measurable.measure(
                    Constraints.fixed(
                        (widthPx + 0.5f).toInt().coerceAtLeast(1),
                        (heightPx + 0.5f).toInt().coerceAtLeast(1)
                    )
                )
                // 父级是有界的高度（调用方固定 44dp）与宽度（fillMaxWidth）。
                // 若约束无界（例如将来被放进别的无界容器），退回控件自身尺寸，                // 绝不把 Int.MAX_VALUE 回填给 layout() —— 那会直接抛
                // "Size(.. x 2147483647) is out of range"。
                val boxWidth =
                    if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                val boxHeight =
                    if (constraints.hasBoundedHeight) constraints.maxHeight else expandedHeightPx.toInt()
                layout(boxWidth, boxHeight) {
                    placeable.place(
                        (centerX - widthPx / 2f + 0.5f).toInt(),
                        ((boxHeight - heightPx) / 2f + 0.5f).toInt()
                    )
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val f = fraction.value.coerceIn(0f, 1f)
                    val radiusPx = lerp(collapsedRadiusPx, expandedRadiusPx, f)
                    // 折叠态是半透明圆钮底（与 ArtistRoundShell 同值）；展开态保持同样的
                    // 半透明观感 —— 因为它不再需要盖住任何东西（下层按钮已随动画淡出）
                    drawRoundRect(color = fill, cornerRadius = CornerRadius(radiusPx))
                    // 圆钮的白色边缘光随展开淡出：展开后是平面搜索条，不该留一圈描边
                    val rimAlpha = 0.10f * (1f - f)
                    if (rimAlpha > 0.004f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = rimAlpha),
                            cornerRadius = CornerRadius(radiusPx),
                            style = Stroke(width = rimWidthPx)
                        )
                    }
                }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    if (!expanded) {
                        onOpen()
                    } else {
                        // 已展开：点框内任意位置只聚焦，不关闭
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    }
                }
        ) {
            // 收起层：居中放大镜（展开时先于展开层淡出，两者不重叠）
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_magnifying_glass),
                    contentDescription = stringResource(id = R.string.common_search),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { alpha = (1f - fraction.value * 2.2f).coerceIn(0f, 1f) },
                    tint = contentColor
                )
            }

            // 展开层（放大镜 + 输入框 + 清空钮）只在展开期间存在。
            //
            // 不能靠 alpha 隐藏：graphicsLayer.alpha 只影响绘制、**不影响命中测试**，
            // 折叠态下那个透明的 BasicTextField 会盖在圆钮上把点击全吃掉 —— 表现就是
            // 「点一下只弹出键盘、搜索框永远展不开，但输入竟然还能过滤列表」
            // （本项目已有同类教训：歌词页的隐形挡板吞事件）。所以折叠态直接不组合它。
            if (expandedLayerPresent) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                        .graphicsLayer {
                            // 透明度沿用「对焦进场」的区间（与 PlayActionMotion.inputAlpha 一致），
                            // 让输入框内容与退场按钮共用同一条时间线
                            alpha = PlayActionMotion.inputAlpha(fraction.value)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_magnifying_glass),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = contentColor.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.text.isEmpty()) {
                            Text(
                                text = hint,
                                fontSize = 16.sp,
                                maxLines = 1,
                                modifier = Modifier.alpha(0.5f),
                                color = contentColor
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
                            cursorBrush = SolidColor(contentColor),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .semantics { contentDescription = hint }
                        )
                    }
                    // 清空钮：有内容才出现（空输入时常驻会变成死按钮）
                    if (query.text.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onClear() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_search_clear),
                                contentDescription = clearDescription,
                                modifier = Modifier.size(19.dp),
                                tint = contentColor.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 平板（≥600dp）用的整行搜索框：宽屏头部被两个 NormalButton 占满，塞不下形变框，
 * 所以退化为「头部圆钮点击 → 下面多出一整行输入框」。视觉与手机展开态一致。
 */
@Composable
internal fun WideSearchFieldRow(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    contentColor: Color,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(id = R.string.online_playlist_search_hint)
    val clearDescription = stringResource(id = R.string.online_playlist_search_clear)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(SearchFieldExpandedHeight)
            .background(
                contentColor.copy(alpha = 0.18f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_magnifying_glass),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor.copy(alpha = 0.55f)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.text.isEmpty()) {
                Text(
                    text = hint,
                    fontSize = 16.sp,
                    maxLines = 1,
                    modifier = Modifier.alpha(0.5f),
                    color = contentColor
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
                cursorBrush = SolidColor(contentColor),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = hint }
            )
        }
        if (query.text.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onClear() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search_clear),
                    contentDescription = clearDescription,
                    modifier = Modifier.size(19.dp),
                    tint = contentColor.copy(alpha = 0.55f)
                )
            }
        }
    }
}

/**
 * 歌单空态（资料库同款图标 + 提示文字）。
 * 歌单为空与搜索无结果共用同一套视觉，只有文案不同。
 */
@Composable
internal fun PlaylistEmptyState(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(id = R.drawable.ic_uitabbar_library),
            contentDescription = null,
            modifier = Modifier
                .size(70.dp)
                .alpha(0.3f),
            tint = LocalContentColor.current
        )
        Text(
            text = text,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 12.dp)
                .alpha(0.55f)
        )
    }
}
