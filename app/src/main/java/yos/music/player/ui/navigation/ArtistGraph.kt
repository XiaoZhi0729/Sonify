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
 *
 * 不变式：全局播放器 overlay 与 AppNavigator 的目标路由，是经
 * `activeHouseController`（=用户当前所在 house）push 出去的，所以**任何会被 overlay
 * 触达的路由，都必须在全部四张 house 图里注册**。歌手名（玩家页）与设置入口
 * （settingsGraph 的 `Settings.Main`）都属此类；当前仅这两条。
 * 四个 house 各自持图，漏注册任一即抛 "Navigation destination ... cannot be found"
 * ——「喜爱」Tab 点歌手名崩溃（Favorites 漏注册本图）即此。
 * [HouseGraphParityTest] 是这条不变式的回归守卫。
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
        // 专辑页自己负责铺底 / 文字色 / 底栏遮罩注册（底色取自专辑封面，
        // 而不是来源歌手；sourceArtistId 只作为转场起点的兜底色）。
        // 因此不再套 ArtistChildBackground——它按 artistId 取色，从发现页/搜索
        // 进入时 artistId 为空，页面会完全没有底色。
        OnlineAlbumDetail(
            navController = navController,
            albumId = entry.arguments?.getString(UI.OnlineAlbumIdArg).orEmpty(),
            sourceArtistId = sourceArtistId,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = this@composable,
            entryId = entry.id,
            scrimTransition = scrimTransition()
        )
    }
}
