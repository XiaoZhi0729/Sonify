package yos.music.player.ui.widgets.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 详情页骨架与「骨架 → 内容」过渡的共用原语。
 *
 * 主页那套（`HomeSkeleton.kt`）是为「多板块、可自定义顺序、自适应卡宽」设计的，
 * 详情页是单一列表、几何也不同（22dp 水平内边距、专辑行带序号列），所以单独放这里。
 * 但**扫光本身是共用的**：[SkeletonShimmer] 与 [Modifier.skeleton] 来自 `Skeleton.kt`，
 * 一个页面只建一个 shimmer，整页各块共享同一道光。
 *
 * 所有尺寸都对齐真实组件，交叉淡化时灰条与内容严格重合：
 *  - 歌曲行：64dp 行高、52dp 封面、22dp 水平内边距、16dp 文字起点、88dp 分割线起点
 *  - 行盒：标题 16sp/16sp、副标题 13sp/13sp（`MusicList` 的 lineHeight 等于字号）
 *  - 专辑行：24dp 序号列，窄屏 18dp 内边距 / 宽屏 64dp 行高 + 22dp 内边距
 */

/** 歌曲行高，与 `MusicList` / `DetailSongRowWide` 的 `height(64.dp)` 一致。 */
private val SongRowHeight = 64.dp

/** 歌曲行封面，与 `MusicList` 的 `size(52.dp)` 一致。 */
private val SongRowCover = 52.dp

/** 歌曲行水平内边距，与 `MusicList` 默认 `horizontalPadding = 22.dp` 一致。 */
private val SongRowPadding = 22.dp

/** 封面与文字列间距，与 `MusicList` 的 `padding(start = 16.dp)` 一致。 */
private val SongTextStart = 16.dp

/**
 * 歌曲行分割线起点。
 *
 * 详情页列表用的是内联 `Spacer(start = 88.dp)`（封面 22+52+16≈90，历史定版取 88），
 * 与主页新歌列的 66dp 不同，必须分开取值。
 */
private val SongDividerStart = 88.dp

/** 歌曲行分割线终点，与各详情页内联 Spacer 的 `end = 16.dp` 一致。 */
private val SongDividerEnd = 16.dp

/** 歌曲标题行盒：`MusicList` 的 `fontSize = 16.sp, lineHeight = 16.sp`。 */
private val SongTitleLineBox = 16.dp

/** 标题与副标题间距：`MusicList` 的 `padding(bottom = 1.dp)`。 */
private val SongTitleBottomGap = 1.dp

/** 歌曲副标题行盒：`MusicList` 的 `fontSize = 13.sp, lineHeight = 13.sp`。 */
private val SongSubtitleLineBox = 13.dp

/**
 * 歌曲行占位条的墨迹收缩量（每侧）。
 *
 * 歌曲行 lineHeight 等于字号、行盒本身贴着墨迹，收缩取 2dp：
 * 既让两条灰条之间留出间隙（否则会连成一整块），又不至于比文字明显瘦一圈。
 */
private val SongInkInset = 2.dp

/** 歌曲行封面圆角，与 `MusicList` 的 `cornerRadius = 3.5.dp` 一致。 */
private val SongCoverCorner = 3.5.dp

/** 按钮行高度：`NormalButton` / `PlayPillButton` 都是 44dp。 */
private val ActionButtonHeight = 44.dp

/** 圆钮直径：`ArtistRoundActionButton` / `CompactPlayActionRow` 都是 42dp。 */
private val RoundActionSize = 42.dp

/**
 * 详情页歌曲行骨架（歌单 / 榜单 / 艺人歌曲列表共用）。
 *
 * 几何逐项对齐 `MusicList`。**行数与行高必须与真实内容一致**：lazy 列表里骨架与内容是
 * 不同 key 的 item、纵向排列不重叠，骨架移除的瞬间内容正好补位，行高对齐才不会跳动。
 *
 * @param rows 骨架行数，默认 6，够铺满首屏。
 */
@Composable
fun DetailSongsSkeleton(
    shimmer: SkeletonShimmer,
    rows: Int = 6,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SongRowHeight)
                    .padding(horizontal = SongRowPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(SongRowCover)
                        .skeleton(shimmer, cornerRadius = SongCoverCorner)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = SongTextStart)
                ) {
                    SkeletonLine(shimmer, 0.55f, SongTitleLineBox, SongInkInset)
                    Spacer(Modifier.height(SongTitleBottomGap))
                    SkeletonLine(shimmer, 0.35f, SongSubtitleLineBox, SongInkInset)
                }
            }
            if (index < rows - 1) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = SongDividerStart, end = SongDividerEnd)
                        .height(0.5.dp)
                )
            }
        }
    }
}

/**
 * 带序号列的专辑歌曲行骨架（`AlbumTrackRow` / `AlbumTrackRowWide` 同构）。
 *
 * 这两类行没有封面，行首是 24dp 序号框：用 [DetailSongsSkeleton] 会完全对不上位置。
 *
 * @param wide 宽屏版：行高 64dp、水平内边距 22dp、序号到文字 16dp、分割线 62dp 起点。
 *   窄屏版没有固定行高（真实行是 `padding(vertical = 10.dp)` 由内容撑开）。
 */
@Composable
fun AlbumTrackSkeleton(
    shimmer: SkeletonShimmer,
    rows: Int = 6,
    wide: Boolean = false,
    modifier: Modifier = Modifier
) {
    val horizontal = if (wide) 22.dp else 18.dp
    val numberGap = if (wide) 16.dp else 10.dp
    val dividerStart = if (wide) 62.dp else 50.dp
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (wide) Modifier.height(SongRowHeight)
                        else Modifier.padding(vertical = 10.dp)
                    )
                    .padding(horizontal = horizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 序号列：真实行是 24dp 宽的序号文本
                Box(
                    Modifier
                        .width(24.dp)
                        .height(SongTitleLineBox)
                        .skeleton(shimmer, cornerRadius = 4.dp)
                )
                Spacer(Modifier.width(numberGap))
                Column(Modifier.weight(1f)) {
                    SkeletonLine(shimmer, 0.6f, SongTitleLineBox, SongInkInset)
                    if (wide) {
                        Spacer(Modifier.height(SongTitleBottomGap))
                        SkeletonLine(shimmer, 0.35f, SongSubtitleLineBox, SongInkInset)
                    }
                }
            }
            if (index < rows - 1) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = dividerStart, end = SongDividerEnd)
                        .height(0.5.dp)
                )
            }
        }
    }
}

/**
 * 详情页页头骨架：大封面 + 标题 + meta + 按钮行。
 *
 * 按窄屏三种头部（`RankDetail` / `OnlineAlbumDetail` / `OnlinePlaylistDetail`）的共同结构取尺寸：
 * 封面水平缩进、标题 20sp 居中、meta 12.5sp 居中、按钮行 44dp。
 *
 * @param coverHorizontalPadding 封面左右缩进。Album/Playlist 是 50dp，Rank 是 54.5dp。
 * @param coverHeight 封面高度。真实封面是正方形，宽度 = 屏宽 - 2×缩进，
 *   骨架这里给固定高度避免依赖测量（各页差异不大，且头部骨架只在极短时间可见）。
 */
@Composable
fun DetailHeaderSkeleton(
    shimmer: SkeletonShimmer,
    modifier: Modifier = Modifier,
    coverHorizontalPadding: Dp = 50.dp,
    coverHeight: Dp = 232.dp,
    titleWidthFraction: Float = 0.62f,
    metaWidthFraction: Float = 0.34f,
    showActions: Boolean = true
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = coverHorizontalPadding)
                .height(coverHeight)
                .skeleton(shimmer, cornerRadius = 7.dp)
        )
        Spacer(Modifier.height(14.dp))
        // 标题居中（真实头部 TextAlign.Center）
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(titleWidthFraction)
                    .height(20.dp)
                    .skeleton(shimmer, cornerRadius = 5.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth(metaWidthFraction)
                    .height(13.dp)
                    .skeleton(shimmer, cornerRadius = 4.dp)
            )
        }
        if (showActions) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(RoundActionSize)
                        .skeleton(shimmer, cornerRadius = RoundActionSize / 2)
                )
                // 播放胶囊占剩余宽度
                Box(
                    Modifier
                        .weight(1f)
                        .height(ActionButtonHeight)
                        .skeleton(shimmer, cornerRadius = 10.dp)
                )
            }
        }
    }
}

/**
 * 卡片横排骨架（艺人页「热门专辑」用）。
 *
 * 卡片宽 150dp、间距 12dp、左右内边距 20dp——与 `ArtistAlbumRail` 的几何逐字一致
 * （该横排刻意不做自适应变宽，卡宽锁死 150dp）。
 */
@Composable
fun CardRailSkeleton(
    shimmer: SkeletonShimmer,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    cardGap: Dp = 12.dp,
    cardCount: Int = 3,
    coverHeight: Dp = 150.dp,
    titleWidth: Dp = 110.dp,
    subtitleWidth: Dp = 80.dp
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(cardGap)
    ) {
        repeat(cardCount) {
            Column(Modifier.width(cardWidth)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(coverHeight)
                        .skeleton(shimmer, cornerRadius = 7.dp)
                )
                Spacer(Modifier.height(9.dp))
                Box(
                    Modifier
                        .width(titleWidth)
                        .height(12.dp)
                        .skeleton(shimmer, cornerRadius = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(subtitleWidth)
                        .height(10.dp)
                        .skeleton(shimmer, cornerRadius = 4.dp)
                )
            }
        }
    }
}

/**
 * 文字占位条。
 *
 * [lineBoxHeight] 是真实文字的**行盒高度**（lineHeight），[inkInset] 是条相对行盒每侧的收缩量：
 * 行盒占满（垂直定位才准），条收缩（条与条之间才有间隙，不会连成一整块）。
 */
@Composable
private fun SkeletonLine(
    shimmer: SkeletonShimmer,
    widthFraction: Float,
    lineBoxHeight: Dp,
    inkInset: Dp
) {
    val barHeight = (lineBoxHeight - inkInset * 2).coerceAtLeast(4.dp)
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(lineBoxHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .skeleton(shimmer, cornerRadius = 4.dp)
        )
    }
}

/**
 * 「骨架 → 内容」统一过渡：内容渐入，骨架延迟退场。
 *
 * 时序与主页 `DiscoverySectionSlot` 一致：内容 220ms 渐入，骨架延迟 80ms 后 280ms 淡出。
 * 骨架退场时内容已接近全亮，不会出现「两者都半透明、背景透出一截发灰」。
 *
 * **只适用于「整个区块装在一个容器里」的场景**（艺人页的歌曲区/专辑区/头部状态行）。
 * LazyColumn 里的骨架与内容是不同 key 的 item、纵向排列不重叠，做不了这种叠加淡化——
 * 那种场景用 `DetailSongsSkeleton` 对齐行高 + 页面级共享 alpha 淡入内容即可。
 *
 * 三个关键实现细节（与主页同源）：
 *  1. **首帧初值取当前 [hasData]**：组合时已有数据就直接 1f，回访页面不会凭空淡入一次。
 *  2. **用 `CompositingStrategy.ModulateAlpha`**：默认 Auto 会给父级建全屏离屏缓冲
 *     （项目里反复记的 `alpha caused saveLayer`）。ModulateAlpha 把 alpha 乘进每条绘制指令。
 *  3. **只在动画期间挂 layer**：结束后 Modifier 链与无动画时完全一致，
 *     静止态不保留额外绘制节点。
 *
 * 注意：**alpha 必须包在含 `overlayEffect`（BlendMode.Plus）的内容外层**，
 * 且只能用 graphicsLayer 不能用 `Modifier.alpha`——后者写在 overlayEffect 之前会让
 * Plus 在 alpha 生效前的图层里混合，结果偏亮。
 */
@Composable
fun FadeInContent(
    hasData: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val contentAlpha = remember { Animatable(if (hasData) 1f else 0f) }
    var animating by remember { mutableStateOf(false) }
    LaunchedEffect(hasData) {
        if (hasData && contentAlpha.value < 1f) {
            animating = true
            try {
                contentAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            } finally {
                animating = false
            }
        }
    }

    Box(modifier) {
        Box(
            if (animating) {
                Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = contentAlpha.value
                }
            } else {
                Modifier
            }
        ) {
            if (hasData) content()
        }
        AnimatedVisibility(
            visible = !hasData,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(280, delayMillis = 80, easing = FastOutSlowInEasing))
        ) {
            skeleton()
        }
    }
}

/**
 * 页面级内容淡入 alpha（lazy 列表页用）。
 *
 * LazyColumn 里骨架和内容是不同 key 的 item、纵向排列不重叠，骨架无法"叠在内容上淡出"
 * （淡出期间内容会被推到屏幕外）。所以列表页的做法是：骨架行高对齐内容行高、
 * 移除瞬间内容正好补位，然后让**所有内容行共享一个 alpha** 同时渐入。
 *
 * 返回的 alpha 在动画结束后为 1f 且不再变化——因此滚动加载更多的新行不会重新淡入
 * （它们组合时读到的就是 1f）。
 *
 * 用法：
 * ```
 * val contentAlpha = rememberContentFadeAlpha(hasData = tracks.isNotEmpty())
 * ...
 * Modifier.graphicsLayer {
 *     compositingStrategy = CompositingStrategy.ModulateAlpha
 *     alpha = contentAlpha()
 * }
 * ```
 */
@Composable
fun rememberContentFadeAlpha(hasData: Boolean): () -> Float {
    val alpha = remember { Animatable(if (hasData) 1f else 0f) }
    LaunchedEffect(hasData) {
        if (hasData && alpha.value < 1f) {
            alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        }
    }
    return { alpha.value }
}

/**
 * 骨架扫光的页面级开关（lazy 列表页用）。
 *
 * 与主页同样的道理：无限动画会持续请求重绘，数据到位后骨架已不在屏上，动画必须停。
 * 关闭要宽限一个完整退场时长，否则骨架退场途中扫光会突然定格。
 *
 * @param pending 当前是否还有骨架会显示。
 */
@Composable
fun rememberSkeletonShimmerForPage(pending: Boolean): SkeletonShimmer {
    var enabled by remember { mutableStateOf(pending) }
    LaunchedEffect(pending) {
        if (pending) {
            enabled = true
        } else {
            kotlinx.coroutines.delay(400)
            enabled = false
        }
    }
    return rememberSkeletonShimmer(enabled = enabled)
}
