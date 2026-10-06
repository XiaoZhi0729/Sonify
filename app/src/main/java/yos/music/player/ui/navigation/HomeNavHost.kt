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
import yos.music.player.ui.pages.discovery.OnlineAlbumDetail
import yos.music.player.ui.pages.discovery.PersonalFmDetail
import yos.music.player.ui.pages.discovery.RankDetail
import yos.music.player.ui.pages.discovery.RankListDetail
import yos.music.player.ui.pages.discovery.RecentPlayedDetail
import yos.music.player.ui.pages.discovery.RecommendPlaylistsDetail
import yos.music.player.ui.pages.library.artists.ArtistAlbumsDetail
import yos.music.player.ui.pages.library.artists.ArtistDetail
import yos.music.player.ui.pages.library.artists.ArtistHotSongsDetail
import yos.music.player.ui.pages.library.artists.ArtistSongsDetail
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
            composable(UI.HomePage) {
                Discovery(
                    navController = navController,
                    onOpenSettings = { navigator.openSettings(HouseId.Home) },
                    onOpenOnlinePlaylist = { selection -> navController.navigate(selection.toRoute()) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(UI.RecentPlayedDetail) { RecentPlayedDetail(navController) }
            composable(UI.EverydayRecommendDetail) { EverydayRecommendDetail(navController) }
            composable(UI.PersonalFmDetail) { PersonalFmDetail(navController) }
            composable(
                route = UI.ArtistDetailPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                ArtistDetail(
                    navController = navController,
                    entryId = entry.id,
                    artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty(),
                    artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty(),
                    scrimTransition = scrimTransition(),
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }
            composable(
                route = UI.ArtistSongsPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                val artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty()
                ArtistChildBackground(
                    navController = navController,
                    entryId = entry.id,
                    artistId = artistId,
                    scrimTransition = scrimTransition()
                ) {
                    ArtistSongsDetail(
                        navController = navController,
                        artistId = artistId,
                        artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty()
                    )
                }
            }
            composable(
                route = UI.ArtistHotSongsPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                val artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty()
                ArtistChildBackground(
                    navController = navController,
                    entryId = entry.id,
                    artistId = artistId,
                    scrimTransition = scrimTransition()
                ) {
                    ArtistHotSongsDetail(
                        navController = navController,
                        artistId = artistId,
                        artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty()
                    )
                }
            }
            composable(
                route = UI.ArtistAlbumsPattern,
                arguments = artistDetailArguments()
            ) { entry ->
                val artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty()
                ArtistChildBackground(
                    navController = navController,
                    entryId = entry.id,
                    artistId = artistId,
                    scrimTransition = scrimTransition()
                ) {
                    ArtistAlbumsDetail(
                        navController = navController,
                        artistId = artistId,
                        artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty(),
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
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
            composable(
                route = UI.OnlineAlbumDetailPattern,
                arguments = onlineAlbumArguments()
            ) { entry ->
                val sourceArtistId = entry.arguments?.getString(UI.OnlineAlbumSourceArtistArg)
                    ?.takeIf { it.isNotBlank() }
                ArtistChildBackground(
                    navController = navController,
                    entryId = entry.id,
                    artistId = sourceArtistId,
                    scrimTransition = scrimTransition()
                ) {
                    OnlineAlbumDetail(
                        navController = navController,
                        albumId = entry.arguments?.getString(UI.OnlineAlbumIdArg).orEmpty(),
                        sourceArtistId = sourceArtistId,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable
                    )
                }
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
