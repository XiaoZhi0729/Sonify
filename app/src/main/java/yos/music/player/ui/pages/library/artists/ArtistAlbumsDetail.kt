package yos.music.player.ui.pages.library.artists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import yos.music.player.R
import yos.music.player.data.objects.ArtistAlbumsObject
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.pages.discovery.NewAlbumCard
import yos.music.player.ui.pages.discovery.StatusFooter
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid

/**
 * 专辑大全页（艺人页「专辑」区块标题 → 查看全部，网格 + 滚动分页）。
 * 分页状态按艺人 id 共享于 [ArtistAlbumsObject]；结构对齐 NewAlbumsDetail。
 * 卡片点击 → OnlineAlbumObject.setSelected → OnlineAlbumDetail（与其它专辑入口一致）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistAlbumsDetail(
    navController: NavController,
    artistId: String,
    artistName: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val state = remember(artistId) { ArtistAlbumsObject.forArtist(artistId, artistName) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(state) {
        state.ensureLoaded()
    }

    // 滚动加载：最后一个可见项进入「距底部 6 项」范围即触发下一页
    LaunchedEffect(gridState, state) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) state.requestNextPage()
        }
    }

    val albums = state.albums.value
    val loading = state.loading.value
    val loadError = state.loadError.value
    val endReached = state.endReached.value

    TitleWithLazyVerticalGrid(
        // 层级：区块名当大标题、歌手名当灰色小标题
        title = stringResource(id = R.string.artist_detail_albums_section),
        subTitle = artistName,
        onBack = { navController.popBackStack() },
        gridState = gridState
    ) {
        if (albums.isEmpty() && (loading || endReached || loadError != null)) {
            item("Status", span = { GridItemSpan(maxLineSpan) }) {
                StatusFooter(
                    text = when {
                        loadError != null -> loadError
                        loading -> stringResource(id = R.string.online_playlists_loading)
                        else -> stringResource(id = R.string.artist_detail_albums_empty)
                    },
                    showSpinner = loading,
                    isError = loadError != null,
                    retry = loadError != null,
                    onRetry = state::requestNextPage
                )
            }
        }

        itemsIndexed(
            albums,
            key = { index, album -> "artist-album-page-$index-${album.albumId}" }
        ) { _, album ->
            NewAlbumCard(
                album = album,
                modifier = Modifier.fillMaxWidth(),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            ) {
                NavGuard.run {
                    OnlineAlbumObject.setSelected(album)
                    // 从专辑大全再进专辑详情：来源歌手仍是本页 artistId，背景继续跟随
                    navController.navigate(UI.onlineAlbumRoute(album.albumId, artistId))
                }
            }
        }

        if (albums.isNotEmpty() && (loading || loadError != null)) {
            item("Footer", span = { GridItemSpan(maxLineSpan) }) {
                StatusFooter(
                    text = when {
                        loadError != null -> stringResource(id = R.string.load_more_failed)
                        else -> stringResource(id = R.string.online_playlists_loading)
                    },
                    showSpinner = loading,
                    isError = loadError != null,
                    retry = loadError != null,
                    onRetry = state::requestNextPage
                )
            }
        }
    }
}
