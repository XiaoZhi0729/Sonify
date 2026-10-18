package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.navigation.PageScrimTransition
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
 *
 * 重新进入本 Tab（或回前台）只会 bump favoritesRevision 触发一次静默对账：
 * 已有 selection 时保留详情页组合与滚动位置，绝不回到整屏 loading。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FavoriteSongsPage(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // 本 Tab 条目在返回栈里的 id + 进出场进度：透传给详情页注册底栏遮罩色
    entryId: String = "",
    scrimTransition: PageScrimTransition? = null
) {
    val loggedIn = KugouAccountState.isLoggedIn
    val selection = remember("FavoriteSongsPage_selection") { mutableStateOf<PlaylistSelection?>(null) }
    val failed = remember("FavoriteSongsPage_failed") { mutableStateOf(false) }

    // 已有内容时的重新进入/对账走静默路径：不清空 selection（清空会卸载
    // OnlinePlaylistDetail，导致每次点 Tab 闪一次整屏 loading、列表滚动位置归零），
    // 也不把瞬时失败打成错误态；只有首次加载或登出才切 loading / error 分支。
    // 歌曲列表本身由 OnlinePlaylistDetail 的 playlistsRevision 静默 revalidate 更新。
    LaunchedEffect(loggedIn, KugouSyncCoordinator.favoritesRevision.value) {
        val silent = selection.value != null
        if (!loggedIn) {
            selection.value = null
            failed.value = true
            return@LaunchedEffect
        }
        val fav = runCatching { KugouRepository.findMyFavoritePlaylist() }.getOrNull()
        if (fav == null) {
            if (!silent) {
                selection.value = null
                failed.value = true
            }
            return@LaunchedEffect
        }
        val listid = fav.listid.ifEmpty { fav.gid }
        // 封面：接口字段优先；为空时沿用已解析的封面（resolvePlaylistCover 无缓存、每次都走网络）
        var cover = fav.coverUrl.ifEmpty { selection.value?.cover.orEmpty() }
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
        failed.value = false
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

        // key(id)：歌单身份真变了（如换账号）才重建详情页状态，避免残留上一份列表走静默路径
        else -> key(sel.id) {
            OnlinePlaylistDetail(
                navController = navController,
                selection = sel,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                showBackButton = false,
                entryId = entryId,
                scrimTransition = scrimTransition
            )
        }
    }
}
