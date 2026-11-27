package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.pages.RecommendCardItem
import yos.music.player.ui.pages.RecommendCardWidth
import yos.music.player.ui.widgets.mochi.mochiHorizontalOverscroll
import yos.music.player.ui.widgets.mochi.rememberMochiSnapFlingBehavior

internal fun calculateStartAlignedRailEndPadding(
    containerWidth: Dp,
    cardWidth: Dp,
    startPadding: Dp = 20.dp
): Dp = (containerWidth - startPadding - cardWidth).coerceAtLeast(startPadding)

@Composable
fun VipRecommendRail(
    songs: List<KugouNewSong>,
    onSongClick: (index: Int) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val keys = remember(songs) { lazyItemKeys(songs) { it.hash } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val endPadding = calculateStartAlignedRailEndPadding(
            containerWidth = maxWidth,
            cardWidth = RecommendCardWidth,
            startPadding = 20.dp
        )
        LazyRow(
            state = listState,
            flingBehavior = rememberMochiSnapFlingBehavior(
                lazyListState = listState,
                snapPosition = SnapPosition.Start
            ),
            modifier = Modifier
                .fillMaxWidth()
                .mochiHorizontalOverscroll(),
            contentPadding = PaddingValues(start = 20.dp, end = endPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                items = songs,
                key = { index, _ -> keys.getOrElse(index) { "oob_$index" } },
                contentType = { _, _ -> "vip_recommend_card" }
            ) { index, song ->
                val mediaItem = remember(song) { KugouRepository.toDisplayMediaItem(song) }
                RecommendCardItem(
                    subTitle = "",
                    music = mediaItem,
                    onClick = { onSongClick(index) }
                )
            }
        }
    }
}
