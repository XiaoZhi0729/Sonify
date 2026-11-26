package yos.music.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsList
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.UI
import yos.music.player.ui.widgets.basic.kugouHash

/**
 * 歌曲行「前往艺人」的导航通道。
 *
 * 歌曲行组件散落在各 house 的页面里、拿不到 NavHostController，所以由 [AppTabsShell]
 * 按当前 house 的 controller 提供；行内只读这个 CompositionLocal，不新增逐层回调。
 */
val LocalArtistNavigator = compositionLocalOf<((YosMediaItem) -> Unit)?> { null }

/**
 * 歌曲 → 艺人主页。解析链路与播放页歌手名点击完全一致：
 * - 在线曲：优先用歌曲自带的 singerid（[KugouRepository.performerIdFor]）直达，查不到才按名搜索
 *   （[UI.artistDetailRoute] 的 artistId 传空即退化成按名搜索）。
 * - 本地曲：若在资料库 house 内且本地媒体库存在该艺人歌曲，优先打开本地艺人歌曲页；
 *   否则回落在线艺人详情页。
 */
@Composable
fun rememberArtistNavigator(navController: NavHostController): (YosMediaItem) -> Unit =
    remember(navController) {
        { music ->
            val name = music.artistsList?.firstOrNull()?.trim().orEmpty()
            if (name.isNotEmpty() && name != defaultArtistsName) {
                val hash = music.kugouHash()
                val canOpenLocalArtist = hash == null &&
                    MusicLibrary.Artist[name].isNotEmpty() &&
                    navController.graph.findNode(UI.LocalArtistSongsPattern) != null
                if (canOpenLocalArtist) {
                    val topEntry = navController.currentBackStackEntry
                    val alreadyThere =
                        topEntry?.destination?.route == UI.LocalArtistSongsPattern &&
                            topEntry.arguments?.getString(UI.LocalArtistSongsArg) == name
                    if (!alreadyThere) {
                        NavGuard.run {
                            navController.navigate(UI.localArtistSongsRoute(name))
                        }
                    }
                } else {
                    val performerId = hash?.let {
                        KugouRepository.performerIdFor(it, name)
                    }
                    NavGuard.run {
                        navController.navigate(
                            UI.artistDetailRoute(
                                artistId = performerId?.toString(),
                                artistName = name
                            )
                        )
                    }
                }
            }
        }
    }
