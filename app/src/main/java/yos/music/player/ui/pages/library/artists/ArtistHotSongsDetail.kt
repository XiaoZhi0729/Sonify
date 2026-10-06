package yos.music.player.ui.pages.library.artists

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.ArtistHotSongsObject
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.widgets.basic.Title

private data class ArtistHotScrollSnapshot(
    val lastVisibleIndex: Int,
    val songCount: Int,
    val loading: Boolean,
    val loadError: String?,
    val endReached: Boolean
)

/**
 * 热门歌曲整页列表（艺人页「热门歌曲」区块标题 → 查看全部）。
 * 数据按 sort=hot 分页，与详情页预览共享 [ArtistHotSongsObject] 状态；
 * 结构对齐 [ArtistSongsDetail]（同一套分页/骨架/错误/播放行为）。
 */
@Composable
fun ArtistHotSongsDetail(
    navController: NavController,
    artistId: String,
    artistName: String
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val state = remember(artistId) { ArtistHotSongsObject.forArtist(artistId) }

    LaunchedEffect(state) {
        state.ensureLoaded()
    }

    LaunchedEffect(listState, state) {
        snapshotFlow {
            val info = listState.layoutInfo
            val songCount = state.songs.value.size
            val lastVisibleSong = info.visibleItemsInfo
                .lastOrNull { it.index in 1..songCount }
                ?.let { it.index - 1 } ?: -1
            ArtistHotScrollSnapshot(
                lastVisibleIndex = lastVisibleSong,
                songCount = songCount,
                loading = state.loading.value,
                loadError = state.loadError.value,
                endReached = state.endReached.value
            )
        }.distinctUntilChanged().collect { snapshot ->
            val nearEnd = snapshot.songCount > 0 && snapshot.lastVisibleIndex >= 0 &&
                snapshot.lastVisibleIndex >= snapshot.songCount - 6
            if (nearEnd && !snapshot.loading && snapshot.loadError == null && !snapshot.endReached) {
                state.requestNextPage()
            }
        }
    }

    val songs = state.songs.value
    val loading = state.loading.value
    val loadError = state.loadError.value
    val endReached = state.endReached.value
    // 这些值都来自同一次 songs 快照，避免 key、行内容和播放队列在追加分页时错位。
    val songKeys = remember(songs) { lazyItemKeys(songs) { it.hash } }

    fun playAt(index: Int) {
        if (index !in songs.indices) return
        val queue = songs.map { KugouRepository.toQueueMediaItem(it) }
        scope.launch {
            MediaController.prepare(queue[index], queue)
        }
    }

    Title(
        // 层级：区块名当大标题、歌手名当灰色小标题
        title = stringResource(R.string.artist_detail_hot_songs),
        subTitle = artistName,
        onBack = { navController.popBackStack() },
        listState = listState
    ) {
        when {
            songs.isEmpty() && (loading || (!endReached && loadError == null)) -> item("Skeleton") {
                ArtistSongsSkeleton()
            }
            songs.isEmpty() && loadError != null -> item("Error") {
                ArtistSongsError(
                    message = loadError,
                    onRetry = state::requestNextPage
                )
            }
            songs.isEmpty() -> item("Empty") {
                Text(
                    text = stringResource(id = R.string.artist_detail_empty),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
            else -> {
                itemsIndexed(
                    items = songs,
                    key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } }
                ) { index, song ->
                    MusicList(KugouRepository.toDisplayMediaItem(song)) {
                        playAt(index)
                    }
                    if (index < songs.lastIndex) {
                        OnlineListItemDivider()
                    }
                }

                when {
                    loading -> item("FooterLoading") { ArtistListFooterLoading() }
                    loadError != null -> item("FooterError") {
                        ArtistSongsError(
                            message = loadError,
                            onRetry = state::requestNextPage,
                            compact = true
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
