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
import yos.music.player.ui.pages.library.playlists.FavoriteSongsPage
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail

@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FavoritesNavHost(
    navController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = UI.Favorites,
            modifier = Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() }
        ) {
            composable(UI.Favorites) {
                FavoriteSongsPage(
                    navController = navController,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            // 保留歌单详情路由：与其它 house 一致，页内后续跳转有落点
            composable(
                route = UI.OnlinePlaylistDetailPattern,
                arguments = playlistArguments()
            ) { entry ->
                OnlinePlaylistDetail(
                    navController = navController,
                    selection = entry.playlistSelection(),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            settingsGraph(
                navController = navController,
                onOpenLibrarySongs = navigator::openLibrarySongs
            )
        }
    }
}
