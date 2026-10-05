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
import yos.music.player.ui.pages.discovery.OnlineAlbumDetail
import yos.music.player.ui.pages.library.artists.ArtistDetail
import yos.music.player.ui.pages.library.artists.ArtistSongsDetail
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail
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
            composable(UI.Search) {
                SearchPage(
                    navController = navController,
                    onOpenSettings = { navigator.openSettings(HouseId.Search) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(
                route = UI.ArtistDetailPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                ArtistDetail(
                    navController = navController,
                    entryId = entry.id,
                    artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty(),
                    artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty(),
                    scrimTransition = scrimTransition()
                )
            }
            composable(
                route = UI.ArtistSongsPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                ArtistSongsDetail(
                    navController = navController,
                    artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty(),
                    artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty()
                )
            }
            composable(UI.OnlineAlbumDetail) {
                OnlineAlbumDetail(
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
