package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid
import yos.music.player.ui.widgets.basic.preloadRawCover

/**
 * 精选歌单大全页（主页「精选歌单」Header 整行点击进入，滚动分页加载）。
 *
 * 数据链：首屏复用 DiscoveryObject.recommendPlaylists（主页已加载的第 1 页，零请求）；
 * 滚动接近底部时按页请求 /top/playlist?page=N 追加，globalCollectionId 去重，
 * 返回空页或全部重复即视为末页停止。进程重建直达本页时从第 1 页拉起。
 * 卡片点击携带精选歌单 route selection，进入共享详情 UI。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RecommendPlaylistsDetail(
    navController: NavController,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    // 首屏数据：主页已加载的第 1 页快照（本页追加不回写 holder，避免影响主页展示条数）
    val playlists = remember { mutableStateOf(DiscoveryObject.recommendPlaylists.value.toList()) }
    val nextPage = remember { mutableStateOf(if (playlists.value.isEmpty()) 1 else 2) }
    val loading = remember { mutableStateOf(playlists.value.isEmpty()) }
    val endReached = remember { mutableStateOf(false) }
    // 非空 = 首拉/追加失败的具体错误，展示为可点击重试的状态行/页脚
    val loadError = remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    fun loadMore() {
        if (loading.value || endReached.value) return
        loading.value = true
        loadError.value = null
        scope.launch {
            KugouRepository.getRecommendPlaylists(page = nextPage.value)
                .onSuccess { page ->
                    val known = playlists.value.map { it.globalCollectionId }.toSet()
                    val fresh = page.filter { it.globalCollectionId !in known }
                    if (fresh.isNotEmpty()) {
                        playlists.value = playlists.value + fresh
                        nextPage.value += 1
                    }
                    // 空页 / 全部重复 → 已到末页
                    endReached.value = fresh.isEmpty()
                }
                .onFailure { e ->
                    loadError.value = e.message ?: e.javaClass.simpleName
                }
            loading.value = false
        }
    }

    LaunchedEffect(Unit) {
        // 进程重建直达本页（holder 为空）时从第 1 页拉起
        if (playlists.value.isEmpty()) loadMore()
    }

    // 滚动加载：最后一个可见项进入「距底部 6 项」范围即触发下一页
    // （totalItemsCount 含标题/页脚占位项，布局变化时 snapshotFlow 自动重发）
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) loadMore()
        }
    }

    TitleWithLazyVerticalGrid(
        title = stringResource(id = R.string.discovery_recommend_playlists_title),
        onBack = { navController.popBackStack() },
        gridState = gridState
    ) {
        // 首拉阶段（列表尚空）：加载中转圈 / 失败错误行 / 末页空态
        if (playlists.value.isEmpty() && (loading.value || endReached.value || loadError.value != null)) {
            item("Status", span = { GridItemSpan(maxLineSpan) }) {
                StatusFooter(
                    text = when {
                        loadError.value != null -> loadError.value.orEmpty()
                        loading.value -> stringResource(id = R.string.online_playlists_loading)
                        else -> stringResource(id = R.string.discovery_recommend_playlists_empty)
                    },
                    showSpinner = loading.value,
                    isError = loadError.value != null,
                    retry = loadError.value != null,
                    onRetry = { loadMore() }
                )
            }
        }

        itemsIndexed(
            playlists.value,
            key = { _, playlist -> playlist.globalCollectionId }
        ) { _, playlist ->
            val context = LocalContext.current
            RecommendPlaylistCard(
                playlist = playlist,
                modifier = Modifier.fillMaxWidth(),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            ) {
                scope.launch {
                    preloadRawCover(context, playlist.artworkUrl)
                    openOnlinePlaylist(
                        PlaylistSelection(
                            source = PlaylistSelection.Source.Recommend,
                            id = playlist.globalCollectionId,
                            name = playlist.name,
                            cover = playlist.artworkUrl,
                            songCount = DiscoveryObject.recommendPlaylistSongCounts[playlist.globalCollectionId]
                                ?: playlist.songCount
                                ?: 0,
                            intro = playlist.intro
                        )
                    )
                }
            }
        }

        // 翻页页脚：追加中转圈 / 失败可点击重试；空闲与末页不渲染
        if (playlists.value.isNotEmpty() && (loading.value || loadError.value != null)) {
            item("Footer", span = { GridItemSpan(maxLineSpan) }) {
                StatusFooter(
                    text = when {
                        loadError.value != null -> stringResource(id = R.string.load_more_failed)
                        else -> stringResource(id = R.string.online_playlists_loading)
                    },
                    showSpinner = loading.value,
                    isError = loadError.value != null,
                    retry = loadError.value != null,
                    onRetry = { loadMore() }
                )
            }
        }
    }
}
