package yos.music.player.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import yos.music.player.ui.UI
import yos.music.player.ui.pages.discovery.OnlineAlbumDetail
import yos.music.player.ui.pages.library.artists.ArtistAlbumsDetail
import yos.music.player.ui.pages.library.artists.ArtistDetail
import yos.music.player.ui.pages.library.artists.ArtistHotSongsDetail
import yos.music.player.ui.pages.library.artists.ArtistSongsDetail

/**
 * 艺人域路由：歌手详情 + 三个子页 + 在线专辑详情。
 * 四个 house 各自持图，而播放页歌手名 / 搜索结果 / 专辑作者 / 关注歌手等入口
 * 都在**用户当前所在的房子**内 push，因此每个 house NavHost 都必须注册本图；
 * 漏注册即抛 "Navigation destination ... cannot be found"。
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalSharedTransitionApi::class)
fun NavGraphBuilder.artistGraph(
    navController: NavHostController,
    sharedTransitionScope: SharedTransitionScope
) {
    composable(route = UI.ArtistDetailPattern, arguments = artistDetailArguments()) { entry ->
        ArtistDetail(
            navController = navController,
            entryId = entry.id,
            artistId = entry.arguments?.getString(UI.ArtistDetailIdArg).orEmpty(),
            artistName = entry.arguments?.getString(UI.ArtistDetailNameArg).orEmpty(),
            scrimTransition = scrimTransition(),
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = this@composable
        )
    }
    composable(route = UI.ArtistSongsPattern, arguments = artistDetailArguments()) { entry ->
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
    composable(route = UI.ArtistHotSongsPattern, arguments = artistDetailArguments()) { entry ->
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
    composable(route = UI.ArtistAlbumsPattern, arguments = artistDetailArguments()) { entry ->
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
                sharedTransitionScope = sharedTransitionScope,
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
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = this@composable
            )
        }
    }
}
