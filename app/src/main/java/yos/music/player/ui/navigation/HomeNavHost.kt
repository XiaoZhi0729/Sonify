package yos.music.player.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import yos.music.player.ui.UI
import yos.music.player.ui.pages.discovery.Discovery
import yos.music.player.ui.pages.discovery.EverydayRecommendDetail
import yos.music.player.ui.pages.discovery.NewAlbumsDetail
import yos.music.player.ui.pages.discovery.NewSongsDetail
import yos.music.player.ui.pages.discovery.PersonalFmDetail
import yos.music.player.ui.pages.discovery.RankDetail
import yos.music.player.ui.pages.discovery.RankListDetail
import yos.music.player.ui.pages.discovery.RecentPlayedDetail
import yos.music.player.ui.pages.discovery.RecommendPlaylistsDetail
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail

@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeNavHost(
    navController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = UI.HomePage,
            modifier = Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() }
        ) {
            composable(UI.HomePage) { entry ->
                // 封面取色渐变只作用于本 Tab 根页；同 NavHost 下的二级页不包，
                // 继续用各自的提取色（艺人页 / 专辑页 / 歌单页）。
                // entryId + scrimTransition 用于把本页底色注册进底栏遮罩混合，
                // 使底栏换色与页面淡入淡出同帧同曲线。
                PageGradientRoot(
                    entryId = entry.id,
                    scrimTransition = scrimTransition()
                ) {
                    Discovery(
                        navController = navController,
                        onOpenSettings = { navigator.openSettings(HouseId.Home) },
                        onOpenOnlinePlaylist = { selection -> navController.navigate(selection.toRoute()) },
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
            }
            composable(UI.RecentPlayedDetail) { RecentPlayedDetail(navController) }
            composable(UI.EverydayRecommendDetail) { EverydayRecommendDetail(navController) }
            composable(UI.PersonalFmDetail) { PersonalFmDetail(navController) }
            artistGraph(navController, this@SharedTransitionLayout)
            // 本地专辑页：播放页「前往该歌曲专辑」会往**当前所在房子**push，本地歌从
            // 主页/搜索/喜爱进来时会抛 "destination cannot be found"，故四张图都要注册
            composable(UI.AlbumInfo) {
                AlbumInfo(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedContentScope = this@composable
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
            composable(UI.NewSongsDetail) { NewSongsDetail(navController) }
            composable(UI.NewAlbumsDetail) {
                NewAlbumsDetail(
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
            composable(UI.RankDetail) {
                RankDetail(
                    navController = navController,
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
