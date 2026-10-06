package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.rememberPageData
import yos.music.player.ui.UI
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid

/**
 * 本周新发行大全页（主页「本周新发行」Header 整行点击进入，滚动分页加载）。
 *
 * 数据链：首屏复用 DiscoveryObject.newAlbums（主页已加载的第 1 页，零请求）；
 * 滚动接近底部时按页请求 /top/album?page=N 追加，albumId 去重，
 * 返回空页或全部重复即视为末页停止。进程重建直达本页时从第 1 页拉起。
 * 卡片点击 → OnlineAlbumObject.setSelected → OnlineAlbumDetail（与主页专辑卡一致）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NewAlbumsDetail(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // 首屏数据：主页已加载的第 1 页快照（本页追加不回写 holder，避免影响主页展示条数）
    val albums = rememberPageData("new_albums") { DiscoveryObject.newAlbums.value.toList() }
    val nextPage = rememberPageData("albums_next_page") { if (albums.value.isEmpty()) 1 else 2 }
    val loading = remember { mutableStateOf(false) }
    val endReached = rememberPageData("albums_end_reached") { false }
    // 非空 = 首拉/追加失败的具体错误，展示为可点击重试的状态行/页脚
    val loadError = rememberPageData<String?>("albums_load_error") { null }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    fun loadMore() {
        if (loading.value || endReached.value) return
        loading.value = true
        loadError.value = null
        scope.launch {
            KugouRepository.getTopAlbums(page = nextPage.value)
                .onSuccess { page ->
                    // 跨地区（chn/eur/jpn/kor）分页可能重复，按 albumId 去重后追加
                    val known = albums.value.map { it.albumId }.toSet()
                    val fresh = page.filter { it.albumId !in known }
                    if (fresh.isNotEmpty()) {
                        albums.value = albums.value + fresh
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
        if (albums.value.isEmpty()) loadMore()
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
        title = stringResource(id = R.string.discovery_new_albums_title),
        onBack = { navController.popBackStack() },
        gridState = gridState
    ) {
        // 首拉阶段（列表尚空）：加载中转圈 / 失败错误行 / 末页空态
        if (albums.value.isEmpty() && (loading.value || endReached.value || loadError.value != null)) {
            item("Status", span = { GridItemSpan(maxLineSpan) }) {
                StatusFooter(
                    text = when {
                        loadError.value != null -> loadError.value.orEmpty()
                        loading.value -> stringResource(id = R.string.online_playlists_loading)
                        else -> stringResource(id = R.string.new_albums_detail_empty)
                    },
                    showSpinner = loading.value,
                    isError = loadError.value != null,
                    retry = loadError.value != null,
                    onRetry = { loadMore() }
                )
            }
        }

        itemsIndexed(
            albums.value,
            key = { _, album -> album.albumId }
        ) { _, album ->
            NewAlbumCard(
                album,
                Modifier.fillMaxWidth(),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            ) {
                NavGuard.run {
                    OnlineAlbumObject.setSelected(album)
                    navController.navigate(UI.onlineAlbumRoute(album.albumId))
                }
            }
        }

        // 翻页页脚：追加中转圈 / 失败可点击重试；空闲与末页不渲染
        if (albums.value.isNotEmpty() && (loading.value || loadError.value != null)) {
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

/**
 * Grid 页通栏状态行（首拉状态与翻页页脚共用）：
 * loading 转圈 + 文案；error 红字提示，整行点击重试。
 * 视觉对齐 OnlineStatusItem（13sp / 水平 22dp 边距）。
 */
@Composable
internal fun StatusFooter(
    text: String,
    showSpinner: Boolean,
    isError: Boolean,
    retry: Boolean,
    onRetry: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = retry, onClick = onRetry)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (showSpinner) Modifier.padding(start = 10.dp) else Modifier
        )
    }
}
