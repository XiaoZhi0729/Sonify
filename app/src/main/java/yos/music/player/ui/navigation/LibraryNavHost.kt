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
import yos.music.player.ui.pages.library.Library
import yos.music.player.ui.pages.library.NormalMusic
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.albums.LocalAlbums
import yos.music.player.ui.pages.library.artists.LocalArtists
import yos.music.player.ui.pages.library.playlists.OnlinePlaylists
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail
import yos.music.player.ui.pages.library.playlists.PlayLists

@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryNavHost(
    navController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier
) {
    SharedTransitionLayout(modifier = modifier) {
        NavHost(
            navController = navController,
            startDestination = UI.Library,
            modifier = Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() }
        ) {
        composable(UI.Library) {
            Library(
                navController = navController,
                onOpenSettings = { navigator.openSettings(HouseId.Library) },
                onOpenOnlinePlaylist = { selection -> navController.navigate(selection.toRoute()) },
            )
        }
        composable(UI.PlayLists) { PlayLists(navController) }
        composable(UI.NormalMusic) { NormalMusic(navController) }
        composable(UI.OnlinePlaylists) {
            OnlinePlaylists(
                navController = navController,
                onOpenOnlinePlaylist = { selection -> navController.navigate(selection.toRoute()) },
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedVisibilityScope = this@composable
            )
        }
        composable(UI.LocalAlbums) {
            LocalAlbums(
                navController = navController,
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedContentScope = this@composable
            )
        }
        composable(UI.LocalArtists) { LocalArtists(navController) }
        composable(UI.AlbumInfo) {
            AlbumInfo(
                navController = navController,
                sharedTransitionScope = this@SharedTransitionLayout,
                animatedContentScope = this@composable
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
