package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.widgets.basic.Title

/**
 * 底栏「喜爱」Tab 根页：展示酷狗系统「我喜欢」歌单（is_def == 2）的歌曲列表。
 *
 * 数据链：KugouRepository.findMyFavoritePlaylist()（带内存缓存）→ 定位「我喜欢」→
 * 解析封面 → 组装 PlaylistSelection(source=User) → 完整复用 OnlinePlaylistDetail
 * （加载/分页/播放/加删歌）。页面无中间歌单列表，返回键隐藏（Tab 根无需返回）。
 *
 * 登录态用 [KugouAccountState.isLoggedIn] 作 LaunchedEffect key：house 常驻组合，
 * 登录/登出切换时重新解析，避免停在旧的空/错误态。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FavoriteSongsPage(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val loggedIn = KugouAccountState.isLoggedIn
    val selection = remember("FavoriteSongsPage_selection") { mutableStateOf<PlaylistSelection?>(null) }
    val failed = remember("FavoriteSongsPage_failed") { mutableStateOf(false) }

    LaunchedEffect(loggedIn, KugouSyncCoordinator.favoritesRevision.value) {
        selection.value = null
        failed.value = false
        if (!loggedIn) {
            failed.value = true
            return@LaunchedEffect
        }
        val fav = runCatching { KugouRepository.findMyFavoritePlaylist() }.getOrNull()
        if (fav == null) {
            failed.value = true
            return@LaunchedEffect
        }
        val listid = fav.listid.ifEmpty { fav.gid }
        var cover = fav.coverUrl
        if (cover.isEmpty()) {
            cover = runCatching { KugouRepository.resolvePlaylistCover(fav) }.getOrNull().orEmpty()
        }
        if (cover.isEmpty()) {
            cover = runCatching { KugouRepository.resolvePlaylistCoverFromTracks(listid) }
                .getOrNull().orEmpty()
        }
        selection.value = PlaylistSelection(
            source = PlaylistSelection.Source.User,
            id = listid,
            name = fav.name,
            cover = cover,
            songCount = fav.songCount
        )
    }

    when (val sel = selection.value) {
        null -> Title(
            title = stringResource(id = R.string.page_favorites_title),
            onBack = null
        ) {
            item("Status") {
                OnlineStatusItem(
                    status = if (failed.value) {
                        "error:" + stringResource(id = R.string.favorites_not_found)
                    } else {
                        "loading"
                    },
                    loadingText = stringResource(id = R.string.online_playlists_loading),
                    emptyText = ""
                )
            }
        }

        else -> OnlinePlaylistDetail(
            navController = navController,
            selection = sel,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            showBackButton = false
        )
    }
}
