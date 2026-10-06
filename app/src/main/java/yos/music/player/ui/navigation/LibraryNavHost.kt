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
import yos.music.player.ui.pages.discovery.OnlineAlbumDetail
import yos.music.player.ui.pages.discovery.PersonalFmDetail
import yos.music.player.ui.pages.library.Library
import yos.music.player.ui.pages.library.NormalMusic
import yos.music.player.ui.pages.library.artists.ArtistAlbumsDetail
import yos.music.player.ui.pages.library.artists.ArtistDetail
import yos.music.player.ui.pages.library.artists.ArtistHotSongsDetail
import yos.music.player.ui.pages.library.artists.ArtistSongsDetail
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.albums.LocalAlbums
import yos.music.player.ui.pages.library.artists.LocalArtists
import yos.music.player.ui.pages.library.artists.OnlineArtists
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
        composable(UI.EverydayRecommendDetail) { EverydayRecommendDetail(navController) }
        composable(UI.PersonalFmDetail) { PersonalFmDetail(navController) }
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
        composable(UI.OnlineArtists) { OnlineArtists(navController) }
        // 本地歌手列表点击进入歌手详情：与 HomeNavHost 各自持图，destination 需两侧都注册
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
        // 艺人全部歌曲整页列表：destination 每个独立 NavHost 都要注册（与 ArtistDetail 同理）
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
