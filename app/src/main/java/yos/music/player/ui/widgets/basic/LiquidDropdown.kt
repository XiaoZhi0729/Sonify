package yos.music.player.ui.widgets.basic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.dropdown.LocalDropdownPanelDragSelect
import yos.music.player.ui.widgets.dropdown.dropdownPanelEntry
import kotlin.math.min
import androidx.compose.ui.graphics.painter.Painter

/**
 * 页面底部被迷你播放器 + 底栏遮住的高度。由 MainActivity 在房子外壳层提供；
 * 下拉的定位与高度上限都会避开这块区域，避免菜单长到迷你条/底栏后面。
 */
val LocalBottomOverlayHeight = androidx.compose.runtime.compositionLocalOf { 0.dp }

// =====================================================================
// 弹层内容列：宽度取前 8 个子项最大固有宽度，clamp 在 200~288dp，支持滚动
// （NexioSchedule 1.6.5 ListPopupColumn 的测量策略逐字移植，额外允许指定 modifier）
// =====================================================================

private const val MaxItemsForWidth = 8

/** 与 CrestoDialogStyle.destructiveColor 同值的危险操作红。 */
private val DropdownDestructiveColor = Color(0xFFFB2C36)

/**
 * 弹层内容列。
 *
 * 宽度规则与原版一致：取前 8 个子项的最大固有宽度、clamp 在 200~288dp；
 * [modifier] 用来钉死宽度（父子两块面板要对齐时传同值）或限制最大高度（超出走内滚）。
 *
 * @param onFitsOnScreen 内容是否一屏装得下（不需要滚动）。跟手滑选据此决定是否启用：
 *   装不下时纵向手势归滚动，否则两种手势互相抢。
 */
@Composable
fun LiquidDropdownColumn(
    modifier: Modifier = Modifier,
    onFitsOnScreen: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val measurePolicy = remember {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints,
            ): MeasureResult {
                val minPx = 200.dp.roundToPx()
                val maxPx = 288.dp.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(constraints.maxHeight)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val parentMin = constraints.minWidth
                val parentMax = constraints.maxWidth
                val upper = maxOf(maxPx, parentMin).coerceAtMost(parentMax)
                val lower = maxOf(minPx, parentMin).coerceAtMost(upper)
                val listWidth = maxIntrinsic.coerceIn(lower, upper)

                val childConstraints = constraints.copy(
                    minWidth = listWidth,
                    maxWidth = listWidth,
                    minHeight = 0,
                )
                val placeables = ArrayList<Placeable>(measurables.size)
                var listHeight = 0
                for (i in measurables.indices) {
                    val p = measurables[i].measure(childConstraints)
                    placeables.add(p)
                    listHeight += p.height
                }
                return layout(listWidth, listHeight) {
                    var currentY = 0
                    for (p in placeables) {
                        p.placeRelative(0, currentY)
                        currentY += p.height
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int {
                val minPx = 200.dp.roundToPx()
                val maxPx = 288.dp.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(Int.MAX_VALUE)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val listWidth = maxIntrinsic.coerceIn(minPx, maxPx)
                return measurables.take(min(MaxItemsForWidth, measurables.size))
                    .sumOf { it.minIntrinsicHeight(listWidth) }
            }
        }
    }

    Layout(
        content = content,
        modifier = modifier
            .height(IntrinsicSize.Min)
            .verticalScroll(state = scrollState),
        measurePolicy = measurePolicy,
    )

    if (onFitsOnScreen != null) {
        // 上报是否需要滚动。用 snapshotFlow 而非 onSizeChanged：maxValue 还会在
        // 「内容没变、只是被 maxHeight 压缩」时变化，那次 onSizeChanged 不触发。
        LaunchedEffect(scrollState, onFitsOnScreen) {
            snapshotFlow { scrollState.maxValue <= 1 }
                .collect { onFitsOnScreen(it) }
        }
    }
}

// =====================================================================
// 菜单行（移植 Nexio DropdownImpl）：17dp squircle 高亮、选中 primary 着色 + 勾
// =====================================================================

/**
 * @param selected 是否为当前选中项（primary 着色 + 勾图标）
 * @param isFirst/isLast 行首行尾额外 8dp 上下内边距（Nexio DropdownImpl 同规则）
 * @param enabled false 时置灰且不响应点击（用于"已证明不可用"的档位）
 * @param chip 文本尾部小胶囊：语义标注**不得**再拼进 [text]——拼进去会让那一行比
 *             其他行长一截，用户把它读成图标，而且标记会随状态在不同行之间跳。
 * @param trailing 自定义尾部插槽（只读摘要行用它显示当前值），与 [chip] 共存时 chip 在前
 * @param modifier 挂在整行最外层（先于行自身 8dp 内缩进）：二级子面板里的行用它做缩进
 * @param showSelectedIcon false 时不渲染尾部勾选位：入口行（点开子面板那种）没有"选中"
 *                         语义，留着会白占 20dp，把 trailing 内容往左挤一截
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidDropdownRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    chip: String? = null,
    modifier: Modifier = Modifier,
    showSelectedIcon: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    /** 行首自定义内容（如歌单封面缩略图），与 [leadingIcon] 二选一 */
    leading: (@Composable () -> Unit)? = null,
    /** 行首图标（菜单动作项用）：统一 20dp，随行文字色着色 */
    leadingIcon: Painter? = null,
    /** 行内上下内边距：默认 10.5dp；歌曲菜单传更小的值收紧行距 */
    verticalPadding: Dp = 10.5.dp,
    /** 文字超长时单行横向滚动（歌单名这类可变长文本用），不换行、不省略 */
    marquee: Boolean = false,
    /** 危险操作（如从歌单移除）：文字与前置图标统一用项目 destructive 红 */
    destructive: Boolean = false,
) {
    // 文字统一单色：深色模式白、浅色模式黑，不随选中态换 primary（选中只靠尾部的勾表达）。
    val contentColor = if (destructive) DropdownDestructiveColor
    else Color.Black withNight Color.White
    val selectedColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    // 按下遮罩：M3 涟漪在这层玻璃上会画出方形色斑所以 indication 关掉，但行内补一块
    // 同形状的按下暗化，否则用户得不到"已按下去"的反馈（2026-10-04 用户反馈）
    val pressed by interactionSource.collectIsPressedAsState()
    // 跟手滑选：面板下发状态时，行不再挂 clickable（两个手势状态互相抢事件正是
    // 「先按住再滑动会跳状态」的根因），改由面板手势层统一命中 + 执行 + 画高亮
    val dragSelect = LocalDropdownPanelDragSelect.current
    val rowShape = YosRoundedCornerShape(17.dp)

    Row(
        modifier = modifier
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirst) 8.dp else 0.dp,
                bottom = if (isLast) 8.dp else 0.dp,
            )
            .clip(rowShape)
            .then(
                if (dragSelect != null) {
                    Modifier.dropdownPanelEntry(
                        enabled = enabled,
                        highlightShape = rowShape,
                        action = onClick,
                    )
                } else {
                    Modifier
                        // 按压层明确以与行容器相同的形状裁剪，避免透明反馈绘制到圆角外侧。
                        .drawWithContent {
                            drawContent()
                            if (pressed) {
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.10f),
                                    cornerRadius = CornerRadius(17.dp.toPx()),
                                )
                            }
                        }
                        .clickable(
                            enabled = enabled,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                }
            )
            .padding(horizontal = 14.dp, vertical = verticalPadding)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(10.dp))
        }
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (destructive) contentColor else contentColor.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = 15.6.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier
                .weight(1f)
                .then(if (marquee) Modifier.basicMarquee() else Modifier),
            maxLines = if (marquee) 1 else Int.MAX_VALUE,
            softWrap = !marquee,
        )
        if (chip != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(YosRoundedCornerShape(4.dp))
                    .background(contentColor.copy(alpha = 0.10f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chip,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }
        if (trailing != null) {
            if (chip == null) Spacer(modifier = Modifier.width(6.dp))
            trailing()
        }
        if (showSelectedIcon) {
            Icon(
                painter = painterResource(id = R.drawable.ic_checkmark),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) selectedColor else Color.Transparent,
            )
        }
    }
}
