package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.ui.widgets.basic.SkeletonShimmer
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout
import yos.music.player.ui.widgets.basic.skeleton

/**
 * 主页骨架屏：几何逐项对齐真实版式，数据到位时原位交叉淡化，不发生跳动。
 *
 * **所有占位条的高度都取真实文字的「行盒」高度**（fontSize + lineHeight），
 * 而不是凭感觉给的近似值——骨架条比文字行盒矮或高，交叉淡化时就会看到
 * "灰条偏上/偏下"的错位（真机实测过的回归）。
 *
 * 与真实组件的对应关系：
 *  - Header = [DiscoverySectionHeader]（20sp Bold，lineHeight 20sp + 10dp 下间距）
 *  - 方卡横排 = 精选歌单 / 本周新发行 / 排行榜（`calculateAdaptiveImageLayout` 算同宽）
 *  - 分页歌曲列 = 每日推荐 / 新歌精选（每列 4 行、行高 64dp、封面 52dp）
 *
 * 最近播放读本地收听历史，无需等待，因此不做骨架。
 */

/** 板块间距：真实布局各 Section 顶部 padding 都是 24dp。 */
private val SectionTopPadding = 24.dp

/** 方卡横排卡片间距，与真实 Pager 的 `itemSize + 10dp` 一致。 */
private val CardGap = 10.dp

/** 骨架里渲染的卡片数量：真实 Pager 的 `beyondViewportPageCount = 1` 会组合约 3 页。 */
private const val SkeletonCardCount = 3

/** 标题文字的行盒：真实标题 20sp / lineHeight 20sp。 */
private val HeaderTextLineBox = 20.dp

/**
 * Header 整行高度：真实 `DiscoverySectionHeader` 是一个
 * `Row(verticalAlignment = CenterVertically)`，里面装 20sp 标题文本和 **24dp 的箭头图标**，
 * 所以行高由图标决定 = 24dp，而不是标题的 20dp。
 *
 * 骨架这里必须留满 24dp：少留的 4dp 会把下面所有内容整体抬高
 * （真机实测：3.25 密度下正好差 13px，整屏骨架看起来"偏上"）。
 */
private val HeaderRowHeight = 24.dp

/** Header 与内容之间的间距，与 `DiscoverySectionHeader` 的 `Spacer(height = 10.dp)` 一致。 */
private val HeaderToContentGap = 10.dp

/** 方卡骨架的封面圆角，与 `NewAlbumCard` / `RankCardItem` 的 7dp 一致。 */
private val CardCornerRadius = 7.dp

/** 歌曲行封面圆角，与 `MusicList` 的 3.5dp 一致。 */
private val RowCoverCornerRadius = 3.5.dp

/** 每列歌曲行数，与 `DiscoveryPagedSongColumns` 的 songsPerColumn 一致。 */
private const val SongsPerColumn = 4

/** 歌曲行高，与 `MusicList` 的 `height(64.dp)` 一致。 */
private val SongRowHeight = 64.dp

/** 歌曲行封面，与 `MusicList` 的 `size(52.dp)` 一致。 */
private val SongRowCover = 52.dp

/** 歌曲行文字列左侧间距，与 `MusicList` 的 `padding(start = 16.dp)` 一致。 */
private val SongRowTextStart = 16.dp

/**
 * 歌曲标题行盒：`MusicList` 里 `fontSize = 16.sp, lineHeight = 16.sp`。
 *
 * 注意这里 lineHeight 等于 fontSize，行盒本身就贴着墨迹（没有额外行距），
 * 所以下面 [LineBoxInkInset] 的收缩不能太大，否则条会比文字明显细一圈。
 */
private val SongTitleLineBox = 16.dp

/**
 * 歌曲标题与副标题之间的间距：`MusicList` 的 `padding(bottom = 1.dp)`。
 *
 * 这个值只有 1dp，靠它分开两条灰条是不够的——真正让条与条不粘连的是
 * [LineBoxInkInset] 让每条在自己的行盒里上下各留出的空隙。
 */
private val SongTitleBottomGap = 1.dp

/** 歌曲副标题行盒：`MusicList` 里 `fontSize = 13.sp, lineHeight = 13.sp`。 */
private val SongSubtitleLineBox = 13.dp

/**
 * 歌曲行的墨迹收缩量：比方卡更小。
 *
 * 歌曲行的 lineHeight 等于字号（16/16、13/13），行盒本身已经很紧；
 * 用方卡那套 3dp 收缩会让灰条明显比文字细。取 2dp，既留出条间空隙，
 * 又不至于比文字瘦一圈。
 */
private val SongInkInset = 2.dp

/**
 * 方卡封面与标题之间的间距。
 *
 * 两种真实卡片的结构不同，必须分开传：
 *  - [RankCardItem] 在封面 Box 之后有一个显式 `Spacer(height = 5.dp)` → 5dp；
 *  - [NewAlbumCard] / [RecommendPlaylistCard] 没有 Spacer，那 5dp 是封面 Box **内部**
 *    给 ShadowImage 的 `padding(bottom = 5.dp)`，标题紧贴正方形外壳 → 0dp。
 *
 * 用同一个值会导致其中一类卡片整体差 5dp（真机上看得出灰条与文字不重合）。
 */
private val RankCardCoverToTitleGap = 5.dp
internal val AlbumCardCoverToTitleGap = 0.dp

/**
 * 方卡标题行盒：14sp 文字且未指定 lineHeight，按默认行高约 1.2 倍取 17dp。
 */
private val CardTitleLineBox = 17.dp

/** 方卡副标题行盒：13sp 且显式 `lineHeight = 17.sp`。 */
private val CardSubtitleLineBox = 17.dp

/**
 * 占位条的「墨迹高度」相对行盒的收缩量（每侧）。
 *
 * 行盒 ≠ 墨迹：17dp 的行盒里字形实际只占约 11–12dp，上下各留 2–3dp 空白。
 * 骨架条若撑满整个行盒，相邻两条就会首尾相接连成一整块（真机截图实测：
 * 标题条底边到副标题条顶边只剩 4px ≈ 1.2dp，看起来就是「两个长方形粘在一起」）。
 *
 * 所以占位条按行盒高度**占位**（保证垂直位置与真实文字严格一致），
 * 但条本身只画「墨迹高度」、在行盒内垂直居中，条与条之间自然留出间隙。
 */
private val LineBoxInkInset = 3.dp

@Composable
internal fun HomeSkeletonHeader(shimmer: SkeletonShimmer) {
    // 整行按 24dp 高（真实 Header 由箭头图标撑起），文字条 20dp 垂直居中。
    Box(
        Modifier
            .padding(start = 20.dp)
            .height(HeaderRowHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .width(132.dp)
                .height(HeaderTextLineBox)
                .skeleton(shimmer, cornerRadius = 5.dp)
        )
    }
    Spacer(Modifier.height(HeaderToContentGap))
}

/**
 * 方卡横排骨架（精选歌单 / 本周新发行 / 排行榜共用）。
 *
 * 卡片宽度复用真实布局的自适应算法，因此在任何屏宽（含横屏、分屏）下
 * 骨架卡与真实卡位置完全一致。
 *
 * [coverToTitleGap] 必须与目标真实卡片的间距结构一致，见 [RankCardCoverToTitleGap]。
 */
@Composable
internal fun HomeSkeletonCardRail(
    shimmer: SkeletonShimmer,
    coverToTitleGap: Dp = RankCardCoverToTitleGap
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val railLayout = calculateAdaptiveImageLayout(
            containerWidth = maxWidth,
            startPadding = 20.dp,
            endPadding = 20.dp
        )
        Row(
            Modifier.padding(start = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(CardGap)
        ) {
            repeat(SkeletonCardCount) {
                SkeletonCard(shimmer, railLayout.itemSize, coverToTitleGap)
            }
        }
    }
}

@Composable
private fun SkeletonCard(
    shimmer: SkeletonShimmer,
    width: Dp,
    coverToTitleGap: Dp
) {
    Column(Modifier.width(width)) {
        // 真实卡片的封面外壳是 fillMaxWidth().aspectRatio(1f) 的正方形，
        // 骨架也用正方形，交叉淡化时两者外框完全重合。
        Box(
            Modifier
                .fillMaxWidth()
                .height(width)
                .skeleton(shimmer, cornerRadius = CardCornerRadius)
        )
        if (coverToTitleGap > 0.dp) Spacer(Modifier.height(coverToTitleGap))
        SkeletonTextBar(
            shimmer = shimmer,
            widthFraction = 0.7f,
            lineBoxHeight = CardTitleLineBox
        )
        SkeletonTextBar(
            shimmer = shimmer,
            widthFraction = 0.45f,
            lineBoxHeight = CardSubtitleLineBox
        )
    }
}

/**
 * 分页歌曲列骨架（每日推荐 / 新歌精选共用）。
 *
 * 一列 = 4 行 64dp；行内 52dp 封面 + 16dp 间距 + 两行文字条，
 * 行间分割线用真实 `OnlineListItemDivider` 的 66dp 起点（`horizontalPadding = 0` 场景）。
 */
@Composable
internal fun HomeSkeletonSongColumn(shimmer: SkeletonShimmer) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 39.dp)
    ) {
        repeat(SongsPerColumn) { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(SongRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(SongRowCover)
                        .skeleton(shimmer, cornerRadius = RowCoverCornerRadius)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = SongRowTextStart)
                ) {
                    // 与 MusicList 一致：标题行盒 → 1dp 间距 → 副标题行盒，
                    // 整列由父 Row 的 CenterVertically 垂直居中（真实布局同款）。
                    // 歌曲行的 lineHeight 等于字号，行盒本身很紧，收缩量取更小的 SongInkInset。
                    SkeletonTextBar(
                        shimmer = shimmer,
                        widthFraction = 0.55f,
                        lineBoxHeight = SongTitleLineBox,
                        inkInset = SongInkInset
                    )
                    Spacer(Modifier.height(SongTitleBottomGap))
                    SkeletonTextBar(
                        shimmer = shimmer,
                        widthFraction = 0.35f,
                        lineBoxHeight = SongSubtitleLineBox,
                        inkInset = SongInkInset
                    )
                }
            }
            if (row < SongsPerColumn - 1) {
                HomeSkeletonDivider()
            }
        }
    }
}

/** 行间分割线占位：与 `OnlineListItemDivider(startPadding = 66.dp)` 同位置同粗细。 */
@Composable
private fun HomeSkeletonDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = 66.dp, end = 16.dp)
            .height(0.5.dp)
    )
}

/**
 * 文字占位条。
 *
 * [lineBoxHeight] 必须是真实文字的**行盒高度**（lineHeight），不是字号：
 * 用字号会在垂直方向差几 dp，交叉淡化时肉眼能看出灰条与文字不重合。
 *
 * 条本身按 [inkInset] 收缩并在行盒内垂直居中——行盒要占满（定位准），
 * 条要收缩（条与条之间才有间隙，不会连成一整块）。
 */
@Composable
private fun SkeletonTextBar(
    shimmer: SkeletonShimmer,
    widthFraction: Float,
    lineBoxHeight: Dp,
    inkInset: Dp = LineBoxInkInset
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
 * 一个板块的完整骨架（Header + 内容）。
 *
 * 真实布局里每个板块顶部都有 24dp（最近播放自己有 12dp，但它不做骨架）。
 */
@Composable
internal fun HomeSkeletonSection(
    shimmer: SkeletonShimmer,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = SectionTopPadding)
    ) {
        HomeSkeletonHeader(shimmer)
        content()
    }
}
