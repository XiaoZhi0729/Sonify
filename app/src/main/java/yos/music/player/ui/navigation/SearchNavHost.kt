package yos.music.player.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import yos.music.player.ui.UI
import yos.music.player.ui.pages.discovery.EverydayRecommendDetail
import yos.music.player.ui.pages.discovery.NewAlbumsDetail
import yos.music.player.ui.pages.discovery.NewSongsDetail
import yos.music.player.ui.pages.discovery.PersonalFmDetail
import yos.music.player.ui.pages.discovery.RankDetail
import yos.music.player.ui.pages.discovery.RankListDetail
import yos.music.player.ui.pages.discovery.RecommendPlaylistsDetail
import yos.music.player.ui.pages.gallery.MusicGalleryPage
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail
import yos.music.player.ui.pages.recognition.SongRecognitionPage
import yos.music.player.ui.pages.search.SearchPage

@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SearchNavHost(
    navController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = UI.Search,
            modifier = Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() }
        ) {
            composable(UI.Search) { entry ->
                // 封面取色渐变只作用于本 Tab 根页；二级页不包，继续用各自提取色。
                // entryId + scrimTransition 让底栏换色与本页淡入淡出同帧同曲线。
                PageGradientRoot(
                    entryId = entry.id,
                    scrimTransition = scrimTransition()
                ) {
                    SearchPage(
                        navController = navController,
                        onOpenSettings = { navigator.openSettings(HouseId.Search) },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
            }
            // 艺人域路由：与各 house 共享同一张图（漏注册即 "destination cannot be found"）
            artistGraph(navController, this@SharedTransitionLayout)
            // 本地专辑页：播放页「前往该歌曲专辑」会往当前所在房子 push，
            // 本地歌从本 Tab 进来时必须能找到该目的地
            composable(UI.AlbumInfo) {
                AlbumInfo(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable
                )
            }
            // 听歌识曲：只从搜索页进入，故只在本 house 注册
            composable(UI.SongRecognition) {
                SongRecognitionPage(navController = navController)
            }
            // ---------- 搜索页「浏览分类」砖块的目标 ----------
            // 这些页面原本只在 Home/Library 的图里注册。搜索页的分类砖块会往**当前
            // house** push，漏注册即 "destination cannot be found" 崩溃。
            // 注意 RankListDetail 内部还会再 push UI.RankDetail（点某张榜单），
            // 所以 RankDetail 也必须一并注册，否则二级跳转崩。
            composable(UI.NewSongsDetail) { NewSongsDetail(navController) }
            composable(UI.EverydayRecommendDetail) { EverydayRecommendDetail(navController) }
            composable(UI.PersonalFmDetail) { PersonalFmDetail(navController) }
            composable(UI.MusicGallery) { MusicGalleryPage(navController) }
            composable(UI.RankDetail) {
                RankDetail(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(UI.RankListDetail) {
                RankListDetail(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(UI.NewAlbumsDetail) {
                NewAlbumsDetail(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(UI.RecommendPlaylistsDetail) {
                RecommendPlaylistsDetail(
                    navController = navController,
                    onOpenOnlinePlaylist = { selection -> navController.navigate(selection.toRoute()) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(
                route = UI.OnlinePlaylistDetailPattern,
                arguments = playlistArguments()
            ) { entry ->
                OnlinePlaylistDetail(
                    navController = navController,
                    selection = entry.playlistSelection(),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    entryId = entry.id,
                    scrimTransition = scrimTransition()
                )
            }
            settingsGraph(
                navController = navController,
                onOpenLibrarySongs = navigator::openLibrarySongs
            )
        }
    }
}
