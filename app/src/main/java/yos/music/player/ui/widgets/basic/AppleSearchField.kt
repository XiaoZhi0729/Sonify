package yos.music.player.ui.widgets.basic

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.ui.theme.withNight

/**
 * 搜索激活态形变曲线。
 *
 * 与在线歌单页的 `SearchMorphSpec`（PlaylistSearch.kt:100）同源：Flamingo 逆向得出的
 * 页面编排曲线 spring(1.2 / 800 / 0.001)。过阻尼（>1）起手利落、收尾极软，全项目的
 * 页面过渡与共享元素 morph 都用这一条。相比 tween 的匀速推进，观感是"被推出去后
 * 自然停住"，而不是"线性走完"。
 */
val SearchMorphSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = 1.2f, stiffness = 800f, visibilityThreshold = 0.001f)

/**
 * 形变分层的错峰区间。
 *
 * 各层不同时进退：几何先走，内容后到，退场元素先雾化。这样过渡读起来有前后景深，
 * 而不是"所有东西一起淡入淡出"——后者正是观感平庸的根源。
 */
private const val IN_START = 0.32f
private const val IN_END = 0.88f
private const val OUT_BLUR_DP = 11f
private const val IN_BLUR_DP = 11f

private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

private fun remap(fraction: Float, start: Float, end: Float): Float {
    if (end <= start) return if (fraction >= end) 1f else 0f
    return clamp01((fraction - start) / (end - start))
}

/** 进场内容的透明度：在 [IN_START, IN_END] 区间从 0 到 1。 */
fun searchContentAlpha(fraction: Float): Float = remap(fraction, IN_START, IN_END)

/** 进场内容的模糊：从失焦处对焦到清晰（11dp → 0）。 */
fun searchContentBlurDp(fraction: Float): Float =
    IN_BLUR_DP * (1f - remap(fraction, IN_START, IN_END))

/** 退场内容的模糊：前段起雾后维持（0 → 11dp）。 */
fun searchOutgoingBlurDp(fraction: Float): Float = OUT_BLUR_DP * remap(fraction, 0f, 0.75f)

/**
 * 搜索页专用的搜索输入框（Apple Music 语言）。
 *
 * 与 [SearchTextField] 的区别：前置放大镜用项目自己的 ic_magnifying_glass、
 * 多一个框内清除钮、暴露 focus 回调、支持形变进度驱动。
 * 之所以另起一个组件而不是改 [SearchTextField]：后者被本地歌曲/专辑/歌手/收藏专辑/
 * 在线歌手共 5 个页面复用，改它会一并改变那些页面的观感。
 *
 * 清除钮用 [Icon] 而非 Image —— R.drawable.ic_search_clear 内嵌
 * fillColor="#FFFFFF"，只有 Icon 的 tint 能把它覆盖成主题色，否则浅色模式不可见。
 */
@Composable
fun AppleSearchField(
    text: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** 形变进度：0 = 浏览态（框内只有占位），1 = 搜索激活态。由调用方持有。 */
    fraction: Float = 0f,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val fontSize = 17.sp
    val contentColor = Color.Black withNight Color.White
    val clearDescription = stringResource(id = R.string.search_clear_input)

    // 放大镜随形变略微放大并加深：激活态读作"已进入搜索"，浏览态是安静的提示
    val magAlpha = 0.45f + 0.15f * clamp01(fraction)

    // 宽度**不在这里算**：由外层 SearchFieldRow 统一分配（见该组件注释）。
    // 自己再算一遍会与父级约束打架，表现为宽度算错、被挤到中间。
    Row(
        modifier = modifier
            .background(
                (Color.LightGray withNight Color.DarkGray).copy(alpha = 0.25f),
                RoundedCornerShape(10.dp)
            )
            .fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_magnifying_glass),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(17.dp)
                .alpha(magAlpha),
            tint = contentColor
        )
        Spacer(Modifier.width(7.dp))

        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    placeholder,
                    fontSize = fontSize,
                    modifier = Modifier.alpha(0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = contentColor, fontSize = fontSize),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onFocusChanged != null) {
                            Modifier.onFocusChanged { onFocusChanged(it.isFocused) }
                        } else Modifier
                    )
            )
        }

        if (text.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
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
            Spacer(Modifier.width(2.dp))
        }
    }
}

/**
 * 搜索行：搜索框 + 「取消」，两者宽度由 [fraction] 在同一次布局里手工摆放。
 *
 * 为什么不用 `Row { Field(weight/fillMaxWidth); Cancel() }`：
 * - `Modifier.fillMaxWidth()` 的外层节点会强制把自身上报成满宽，与内部的
 *   `layout{}` 互相打架 → 搜索框宽度算错、被挤到中间，取消按钮分到 0 宽直接消失
 * - 两个兄弟节点的约束分配是顺序扣减的，宽度动画会互相干扰
 *
 * 这里用单层 [Layout] 一次量两个子项、按 px 精确 place：f=0 时取消宽 0、搜索框满宽；
 * f=1 时搜索框 = 满宽 − 取消宽，取消贴右。尺寸完全确定，不依赖任何 weight。
 *
 * 右对齐：搜索框内清除钮距框右缘 2dp，故取消按钮的右内边距也取 2dp，
 * 让「取消」文字右缘与清除钮右缘落在同一条竖线上。
 */
@Composable
fun SearchFieldRow(
    fraction: Float,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
    cancel: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val cancelPx = with(density) { SearchCancelWidth.toPx() }
    val heightPx = with(density) { SearchFieldHeight.roundToPx() }

    Layout(
        contents = listOf(field, cancel),
        modifier = modifier.height(SearchFieldHeight)
    ) { measurables, constraints ->
        val f = clamp01(fraction)
        // 宽度方向正常有界；无界时给一个兜底值，绝不把 Int.MAX_VALUE 传给 Constraints
        val full = if (constraints.hasBoundedWidth) constraints.maxWidth else cancelPx.toInt() * 4
        val cancelW = (cancelPx * f).toInt().coerceIn(0, full)
        val fieldW = (full - cancelW).coerceAtLeast(1)

        // 高度用显式常量：本控件在 LazyColumn 的 item 里，高度方向无界
        // 注意 Layout 的 lambda 收到的是 List<List<Measurable>>（按 contents 分组），
        // 每个 composable 只含一个子节点，故取 [i][0]
        val fieldPlaceable = measurables[0][0].measure(Constraints.fixed(fieldW, heightPx))
        val cancelPlaceable = measurables[1][0].measure(
            Constraints.fixed(cancelW.coerceAtLeast(1), heightPx)
        )
        layout(full, heightPx) {
            fieldPlaceable.place(0, 0)
            cancelPlaceable.place(fieldW, 0)
        }
    }
}

/** 搜索框高度（与「取消」按钮同高，两者基线才对得齐）。 */
val SearchFieldHeight = 44.dp

/**
 * 「取消」按钮占位宽度 —— 搜索框的让位量就按它在 fraction 上插值。
 *
 * 必须贴合内容实际宽度，不能留大余量：这个值直接决定搜索框收缩多少。
 * 构成：左内边距 10dp + 「取消」两字（16sp × 2 ≈ 32dp）+ 右内边距 4dp ≈ 46dp，
 * 取 48dp 留 2dp 字体余量。之前用 62dp 时多缩了 14dp，搜索框看起来偏窄、
 * 与取消按钮之间空出一块。
 */
val SearchCancelWidth = 48.dp

/**
 * 搜索激活态的「取消」按钮：透明度与模糊随 [fraction] 走。
 *
 * 宽度由外层 [SearchFieldRow] 分配（f=0 时宽 0、f=1 时 [SearchCancelWidth]），
 * 这里只负责内容呈现。
 *
 * 右对齐：内容 `CenterEnd` 且右内边距 2dp，与搜索框内清除钮的右缘（距框右缘 2dp）
 * 落在同一条竖线上。容器变窄时由 clipToBounds 裁切——f 很小时内容本就不可见，
 * 裁切不会伤到已可见的文字。
 */
@Composable
fun SearchCancelButton(
    fraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val f = clamp01(fraction)
    val alpha = searchContentAlpha(f)
    val blurDp = searchContentBlurDp(f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .then(
                // blur 半径要在组合期给定；过渡中重组次数有限，人眼对 1dp 级模糊差不敏感
                if (blurDp >= 0.5f) Modifier.blur(blurDp.dp) else Modifier
            ),
        // 右对齐：与搜索框内清除钮右缘对齐（清除钮距框右缘 2dp）
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { this.alpha = alpha }
                .clip(RoundedCornerShape(8.dp))
                // indication = null：不要点击涟漪。这是纯文字按钮，涟漪的圆形扩散
                // 与 iOS 风格的文字按钮语汇不符（项目内其他文字按钮同样关掉）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = alpha > 0.5f,
                    onClick = onClick
                )
                // 右内边距 4dp：与「最近搜索」区头右侧的「清空」按钮右对齐。
                // 区头 padding(start=22, end=14) + 「清空」自身右内边距 8dp ⇒ 文字右缘
                // 距屏幕右缘 22dp；搜索行 padding(horizontal=18)，故 18 + 4 = 22 对齐。
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(id = R.string.common_cancel),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
