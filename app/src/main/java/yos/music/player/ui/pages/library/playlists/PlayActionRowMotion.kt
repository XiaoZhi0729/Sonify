package yos.music.player.ui.pages.library.playlists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.ui.navigation.artworkContentColor

/*
 * 自有歌单的操作行：随机钮 + 播放胶囊 + 搜索框，三个控件由**同一个** fraction 逐控件驱动。
 *
 * 为什么不复用 CompactPlayActionRow：那个组件内部自己算宽度、自己排布，只能整体淡出。
 * 这里要的「挤压铺满」需要胶囊宽度压到 0、内部图标文字提前消失、随机钮滑出并缩小 ——
 * 都是逐控件量，共享组件给不了。非自有歌单（第三格是收藏钮、没有搜索）仍走共享组件。
 *
 * 折叠态必须与 CompactPlayActionRow 逐像素一致，否则收起最后一帧会跳：
 * 位置全部取自 PlayActionGeometry（与共享组件同一套算式），胶囊外观沿用 PlayPillButton 的
 * 44dp / 22dp 圆角 / 页面文字色实心底 + 反色字。
 *
 * ── 关于模糊（用户反馈三轮「像被一个 box 框住」的结论）──────────────────────
 *
 * 查过 Compose 1.10 的字节码：`Modifier.blur` 的实现只是给 graphicsLayer 设
 * `setRenderEffect(BlurEffect)` 与 `setClip(shape)`，**渲染目标就是该图层的矩形**。
 * `BlurredEdgeTreatment.Unbounded` 仅把边缘采样改成 Decal（边缘渐隐），
 * **并不会扩大绘制范围**。所以只要图层尺寸等于内容尺寸，模糊一定会在四边被硬切；
 * 而 LazyColumn 又会裁剪每个条目，把节点向外画出去也照样被切。
 *
 * 唯一可行的做法：让**模糊图层自身**比内容大出一圈（[BlurBleed]），
 * 并把所有需要模糊的控件装进这**同一个**图层 —— 一次外扩，一次 blur，
 * 不会出现两个图层各自衰减导致的接缝。
 */

/**
 * 模糊扩散量：整行上下各留这么多给模糊散开。
 *
 * 依据是模糊半径本身（见 PlayActionMotion.OUT_BLUR_DP）：图层必须比内容大出至少一个
 * 半径，模糊才能真的散开。跟着半径一起调整，避免行高无谓膨胀。
 *
 * 声明为 internal 是给外层调用方算总高度用（见 OnlinePlaylistDetail 的操作行外层）。
 */
internal val ActionRowBlurBleed = PlayActionMotion.OUT_BLUR_DP.dp

/** 操作行上间距（与原来一致：14dp）。 */
internal val ActionRowTopPadding = 14.dp

/** 操作行下间距（与原来一致：10dp）。 */
internal val ActionRowBottomPadding = 10.dp

/**
 * 操作行（自有歌单）。
 *
 * @param fraction 0 = 三控件常态，1 = 搜索框铺满
 * @param onOpenSearch 折叠态点搜索圆钮
 */
@Composable
internal fun PlayActionRowMotion(
    fraction: State<Float>,
    foregroundColor: Color,
    playEnabled: Boolean,
    onShuffle: () -> Unit,
    onPlayAll: () -> Unit,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    onSearchClear: () -> Unit,
    searchFocus: FocusRequester,
    searchExpanded: Boolean,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        // 高度 = 内容行高 + 上下各一份模糊扩散量。
        //
        // 外层（OnlinePlaylistDetail）给的是 `.height(68dp).padding(top=14,bottom=10)`，
        // 到这里可用内容高度就是 44dp；**不要**再套一层同样的 height + padding ——
        // 高度会被扣两遍（68 被父级钳到 44，再扣 24 → 内容只剩 20dp），
        // 圆钮会按 42×20 绘制成扁椭圆，而宽度仍按 42/140dp 算所以长度看着没变。
        modifier
            .fillMaxWidth()
            .height(SearchFieldExpandedHeight + ActionRowBlurBleed * 2)
    ) {
        val geometry = remember(maxWidth) { PlayActionGeometry(maxWidth) }
        val f = fraction.value.coerceIn(0f, 1f)
        val outBlurDp = PlayActionMotion.outBlurDp(f)

        // 退场的两个控件装进同一个模糊层（详见文件顶部说明）。
        // 静止态不加图层：outBlurDp 为 0 时走无模糊分支。
        if (outBlurDp > 0.05f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(outBlurDp.dp, BlurredEdgeTreatment.Unbounded)
            ) {
                ActionRowContent(
                    geometry = geometry,
                    foregroundColor = foregroundColor,
                    playEnabled = playEnabled,
                    onShuffle = onShuffle,
                    onPlayAll = onPlayAll,
                    f = f
                )
            }
        } else {
            ActionRowContent(
                geometry = geometry,
                foregroundColor = foregroundColor,
                playEnabled = playEnabled,
                onShuffle = onShuffle,
                onPlayAll = onPlayAll,
                f = f
            )
        }

        // 搜索框（最上层）：折叠态就是那颗 42dp 圆钮，展开态铺满整行。
        // 它是「进场方」，不参与退场模糊，所以画在模糊层之外。
        MorphSearchField(
            fraction = fraction,
            geometry = geometry,
            contentColor = foregroundColor,
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            onClear = onSearchClear,
            focusRequester = searchFocus,
            expanded = searchExpanded,
            onOpen = onOpenSearch
        )
    }
}

/**
 * 会退场的两个控件：随机钮 + 播放胶囊。
 *
 * 抽成独立 composable 是为了让它们能被包进**同一个**模糊层：
 * 每个 blur 节点都需要一圈外扩，装在一起只需要一份外扩，也不会出现两层模糊叠加的接缝。
 */
@Composable
private fun ActionRowContent(
    geometry: PlayActionGeometry,
    foregroundColor: Color,
    playEnabled: Boolean,
    onShuffle: () -> Unit,
    onPlayAll: () -> Unit,
    f: Float
) {
    // ① 随机钮：向行首滑出 + 缩小 + 淡出。
    //
    // 位移区间与胶囊对齐（都在前段就开始走）：早先 slide 从 0.5 才起步、最大只有 35dp，
    // 而搜索框从右向左扩张在 f≈0.3 就盖住它了 —— 看起来「随机钮从不移动，只是被盖住」。
    val shuffleAlpha = PlayActionMotion.shuffleAlpha(f)
    if (shuffleAlpha > 0.001f) {
        val shuffleLeft = geometry.shuffleLeft -
            (geometry.shuffleLeft + geometry.buttonSize) *
            PlayActionMotion.shuffleSlideFraction(f)
        RoundActionButton(
            icon = R.drawable.button_icon_shuffle,
            description = stringResource(id = R.string.normal_button_shuffle),
            contentColor = foregroundColor,
            enabled = playEnabled,
            onClick = onShuffle,
            modifier = Modifier
                .placeAt(
                    left = shuffleLeft,
                    width = geometry.buttonSize,
                    ownHeight = geometry.buttonSize
                )
                .graphicsLayer {
                    val s = PlayActionMotion.shuffleScale(f)
                    scaleX = s
                    scaleY = s
                    alpha = shuffleAlpha
                }
        )
    }

    // ② 播放胶囊：宽度压到 0，左缘跟着左收（像被搜索框推出去），内容提前消失
    val pillAlpha = PlayActionMotion.pillAlpha(f)
    if (pillAlpha > 0.001f) {
        val pillWidth = geometry.pillWidth * PlayActionMotion.pillWidthFraction(f)
        if (pillWidth > 0.5.dp) {
            val pillLeft = geometry.pillLeft * (1f - PlayActionMotion.pillSlideFraction(f))
            val contentAlpha = PlayActionMotion.pillContentAlpha(f)
            Box(
                Modifier
                    .placeAt(
                        left = pillLeft,
                        width = pillWidth,
                        ownHeight = SearchFieldExpandedHeight
                    )
                    .graphicsLayer { alpha = pillAlpha }
            ) {
                // 裁剪放在**内层**：只把「满宽绘制的胶囊」裁到当前压缩宽度，
                // 保证压缩过程中 22dp 圆角不变形、内容被切掉而不是被横向挤扁。
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    PlayPill(
                        enabled = playEnabled,
                        foregroundColor = foregroundColor,
                        contentAlpha = contentAlpha,
                        onClick = onPlayAll,
                        modifier = Modifier.width(geometry.pillWidth)
                    )
                }
            }
        }
    }
}

/** 播放胶囊：44dp 高 / 22dp 圆角 / 页面文字色实心底 + 反色字（对齐 PlayPillButton）。 */
@Composable
private fun PlayPill(
    enabled: Boolean,
    foregroundColor: Color,
    contentAlpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val dim = if (enabled) 1f else 0.45f
    val contentColor = artworkContentColor(foregroundColor)
    Row(
        modifier = modifier
            .height(SearchFieldExpandedHeight)
            .clip(CircleShape)
            .background(foregroundColor.copy(alpha = foregroundColor.alpha * dim))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 36.dp),
        // 必须显式居中：原版 PlayPillButton 用的是 Arrangement.Center，
        // 漏掉会变成内容贴左（padding 36dp 看起来像偏左一块）。
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.button_icon_play),
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                // 内容比胶囊本体更早消失：压到一半时图标文字就该没了，
                // 否则会被裁切边缘切掉半个字
                .graphicsLayer { alpha = contentAlpha },
            tint = contentColor.copy(alpha = contentColor.alpha * dim)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(id = R.string.normal_button_play),
            color = contentColor.copy(alpha = contentColor.alpha * dim),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        )
    }
}

/** 42dp 半透明圆钮（与 ArtistRoundShell 同构，位置由外部 layout 决定）。 */
@Composable
private fun RoundActionButton(
    icon: Int,
    description: String,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dim = if (enabled) 1f else 0.45f
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(contentColor.copy(alpha = 0.18f * dim))
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.10f * dim),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // 按压回缩放在内部：外层的 graphicsLayer 已被动画占用（scale/alpha），
        // 两处都写 scaleX/scaleY 会互相覆盖。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (pressed) 0.96f else 1f
                    scaleX = s
                    scaleY = s
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = description,
                modifier = Modifier.size(24.dp),
                tint = contentColor.copy(alpha = dim)
            )
        }
    }
}

/**
 * 按绝对坐标摆放：横向放到 [left]，纵向在行内垂直居中。
 *
 * 用 layout 而不是 offset：offset 只改绘制位置，条目仍占原位，兄弟节点会被推走。
 *
 * 纵向居中与 `CompactPlayActionRow` 的 `CenterVertically` 保持一致：
 * 42dp 圆钮在 44dp 内容行里上下各留 1dp。父级现在比内容高（多了模糊扩散量），
 * 但内容本身仍按 44dp 行居中，所以折起态与无搜索时逐像素对齐。
 */
private fun Modifier.placeAt(
    left: Dp,
    width: Dp,
    ownHeight: Dp
) = this.layout { measurable, constraints ->
    val rowH = if (constraints.hasBoundedHeight) constraints.maxHeight else 44.dp.roundToPx()
    val bleedPx = ((rowH - 44.dp.roundToPx()) / 2).coerceAtLeast(0)
    val ownH = ownHeight.roundToPx()
        .coerceAtMost(rowH - bleedPx * 2)
        .coerceAtLeast(1)
    val w = width.roundToPx().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(w, ownH))
    layout(constraints.maxWidth, rowH) {
        // 以「内容行」（去掉上下扩散量）为基准居中
        val contentRowTop = bleedPx
        val contentRowH = rowH - bleedPx * 2
        placeable.place(left.roundToPx(), contentRowTop + (contentRowH - ownH) / 2)
    }
}
