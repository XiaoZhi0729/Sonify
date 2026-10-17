package yos.music.player.ui.pages.library.albums

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.CollectedAlbumsObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouCollectedAlbum
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.pages.discovery.StatusFooter
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid
import yos.music.player.ui.widgets.basic.preloadRawCover

/**
 * 资料库›专辑：酷狗账号下**收藏的在线专辑**。
 *
 * 样式 1:1 对齐本地专辑页（[LocalAlbums]）：大标题 + 返回 + 网格首行搜索框 +
 * 150dp 自适应方卡（7dp 圆角 / 14sp 专辑名 / 13sp「N 首」）。
 *
 * 数据源 [CollectedAlbumsObject]：/user/playlist 过滤 source=2（收藏专辑条目），
 * MMKV 快照 + 云端同步。收藏入口在 [yos.music.player.ui.pages.discovery.OnlineAlbumDetail]
 * 操作行右侧的「+」（点后变「✓」）。
 *
 * 点卡片 → 在线专辑详情（复用 album/collected/{albumId} 共享封面转场）。
 * 条目给不出数字 albumId 时先按名反查（对齐 md3Music 的做法），解析失败给提示而不是静默。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CollectedAlbums(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val account = KugouAccountState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(account.isLoggedIn, KugouSyncCoordinator.albumsRevision.value) {
        if (account.isLoggedIn) CollectedAlbumsObject.ensureLoaded()
    }

    // 下拉刷新指示器：松手后由 loading 归零收起
    val refreshing = remember("CollectedAlbums_refreshing") { mutableStateOf(false) }
    LaunchedEffect(CollectedAlbumsObject.loading.value) {
        if (!CollectedAlbumsObject.loading.value) refreshing.value = false
    }

    if (!account.isLoggedIn) {
        Title(
            title = stringResource(id = R.string.page_library_albums),
            onBack = { navController.popBackStack() }
        ) {
            item("login_tip") {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.online_albums_login_tip),
                        fontSize = 18.sp,
                        modifier = Modifier.alpha(0.6f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navController.navigate(UI.Settings.KugouLogin) }) {
                        Text(text = stringResource(id = R.string.kugou_phone_login))
                    }
                }
            }
        }
        return
    }

    val albums = CollectedAlbumsObject.albums.value
    val loading = CollectedAlbumsObject.loading.value
    val error = CollectedAlbumsObject.error.value
    val searchText = remember("CollectedAlbums_searchText") { mutableStateOf("") }
    // 条目里没有歌手字段（/user/playlist 不返回），只能按专辑名过滤
    val filtered = if (searchText.value.isBlank()) {
        albums
    } else {
        albums.filter { it.name.contains(searchText.value, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        TitleWithLazyVerticalGrid(
            title = stringResource(id = R.string.page_library_albums),
            onBack = { navController.popBackStack() },
            onRefresh = {
                refreshing.value = true
                CollectedAlbumsObject.ensureLoaded(force = true)
            },
            refreshing = refreshing.value
        ) {
            item("SearchField", span = { GridItemSpan(maxLineSpan) }) {
                val keyboardController = LocalSoftwareKeyboardController.current
                SearchTextField(
                    text = searchText.value,
                    placeholder = stringResource(id = R.string.page_library_search_album),
                    onValueChange = { searchText.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    onSearch = {
                        if (searchText.value.isNotEmpty()) keyboardController?.hide()
                    }
                )
            }

            if (filtered.isEmpty()) {
                item("Status", span = { GridItemSpan(maxLineSpan) }) {
                    val text = when {
                        error != null -> error
                        loading -> stringResource(id = R.string.online_playlists_loading)
                        searchText.value.isNotBlank() ->
                            stringResource(id = R.string.online_albums_search_empty)
                        else -> stringResource(id = R.string.online_albums_empty)
                    }
                    StatusFooter(
                        text = text,
                        showSpinner = loading,
                        isError = error != null,
                        retry = error != null,
                        onRetry = { CollectedAlbumsObject.ensureLoaded(force = true) }
                    )
                }
            } else {
                itemsIndexed(
                    filtered,
                    key = { index, album -> "collected-album-$index-${album.albumId}-${album.name}" }
                ) { _, album ->
                    CollectedAlbumCard(
                        album = album,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    ) {
                        scope.launch {
                            val albumId = CollectedAlbumsObject.resolveAlbumId(album)
                            if (albumId.isNullOrBlank()) {
                                Toast.makeText(
                                    context,
                                    R.string.album_open_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            SharedCoverStyle.lastSourceCorner = 7.dp
                            // 与详情页配对：详情页只接受以 "/{albumId}" 结尾的来源 key
                            OnlineAlbumObject.setSharedCoverKey(albumId, "album/collected/$albumId")
                            OnlineAlbumObject.setSelected(
                                KugouNewAlbum(
                                    albumId = albumId,
                                    name = album.name,
                                    // 歌手名条目里没有；详情接口返回后自动补全
                                    singerName = "",
                                    coverUrl = album.coverUrl,
                                    songCount = album.songCount,
                                    publishTime = null
                                )
                            )
                            preloadRawCover(context, album.coverUrl)
                            // 单层防抖：组件内部不设，只在这里包一次
                            NavGuard.run {
                                navController.navigate(UI.onlineAlbumRoute(albumId))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LazyGridItemScope.CollectedAlbumCard(
    album: KugouCollectedAlbum,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
    ) {
        // 固定正方形外壳承载 shared element；ShadowImage 只在外壳内部绘制。
        // albumId 缺失的条目拿不到稳定 key，直接不挂共享元素（否则 key 会撞成同一串）。
        val sharedKey = album.albumId.takeIf { it.isNotBlank() }
            ?.let { "album/collected/$it" }
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null && sharedKey != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(sharedKey),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(coverModifier) {
            ShadowImage(
                dataLambda = { album.coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = album.name,
                modifier = Modifier.fillMaxSize(),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = album.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )
        // 数量未知（0）时不显示，避免出现「0 首」
        if (album.songCount > 0) {
            Text(
                text = stringResource(id = R.string.page_library_album_desc, album.songCount),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.6f),
                lineHeight = 17.sp
            )
        }
    }
}
