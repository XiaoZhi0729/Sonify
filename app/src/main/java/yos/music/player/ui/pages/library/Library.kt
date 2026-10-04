package yos.music.player.ui.pages.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.PersonCropCircle
import yos.music.player.R
import yos.music.player.data.libraries.MusicLibrary.songs
import yos.music.player.data.objects.LibraryObject
import yos.music.player.ui.UI
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.toUI
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.YosWrapper

/**
 * 资料库枢纽（本地 / 在线两分区）。
 *
 * 信息架构：本地音乐（歌曲/专辑/歌手/播放列表）+ 在线音乐（在线艺人/我的在线歌单）。
 * 酷狗账号入口保留在 Settings（在线音乐分组，带登录状态），此处不重复制造入口。
 * 分区标题复用 Settings 的 ListHeader；条目沿用原版 SmallLabelItem + LibraryDivider
 * 平铺语言（Library 原生即为无边距卡片平铺，与 Settings 的 RoundColumn 卡片语言区分）。
 */
@Composable
fun Library(
    navController: NavController,
    onOpenSettings: (() -> Unit)? = null,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null
) {
    val openSettings = onOpenSettings ?: { navController.toUI(UI.Settings.Main) }
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    Column(
        Modifier
            .fillMaxSize()
        /*.statusBarsPadding()*/
    ) {
        Title(
            title = stringResource(id = R.string.page_library_title),
            topRightIcon = Icons.Filled.MoreVert,
            onTopRightIcon = {
                openSettings()
            },
            extraTopPadding = 57.dp,
            titleHorizontalPadding = 32.dp
        ) {
            item("Library") {
                Column(
                    Modifier
                        .fillMaxSize(),
                ) {
                    // ---------- 本地音乐 ----------
                    ListHeader(content = stringResource(id = R.string.library_section_local))

                    YosWrapper {
                        val targetTitle = stringResource(
                            id = R.string.page_library_songs
                        )
                        val targetList = songs
                        SmallLabelItem(
                            icon = painterResource(id = R.drawable.ic_library_link_icon_songs),
                            label = targetTitle
                        ) {
                            LibraryObject.setTargetListWithTitle(targetTitle, targetList)
                            navController.toUI(UI.NormalMusic)
                        }
                    }

                    LibraryDivider()

                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_album),
                        label = stringResource(
                            id = R.string.page_library_albums
                        )
                    ) {
                        navController.toUI(UI.LocalAlbums)
                    }

                    LibraryDivider()

                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_artists),
                        label = stringResource(
                            id = R.string.page_library_artists
                        )
                    ) {
                        navController.toUI(UI.LocalArtists)
                    }

                    LibraryDivider()

                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_playlists),
                        label = stringResource(
                            id = R.string.page_library_playlists
                        )
                    ) {
                        navController.toUI(UI.PlayLists)
                    }

                    // ---------- 在线音乐 ----------
                    GroupSpacer()
                    ListHeader(content = stringResource(id = R.string.settings_online_title))

                    // 在线艺人：选择 UI 与本地艺人一致，点击走酷狗歌手详情（artistId 空按名解析）
                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_artists),
                        label = stringResource(id = R.string.page_library_artists)
                    ) {
                        navController.toUI(UI.OnlineArtists)
                    }

                    LibraryDivider()

                    // 我的在线歌单（酷狗 Rust 链路，第二阶段：歌单 → 歌曲 → /song/url → Media3）；
                    // 在线搜索已迁移至一级 Search Tab
                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_album),
                        label = stringResource(id = R.string.page_library_online_playlists)
                    ) {
                        navController.toUI(UI.OnlinePlaylists)
                    }

                    LibraryDivider()

                    SmallLabelItem(
                        icon = painterResource(id = R.drawable.ic_library_link_icon_heart),
                        label = stringResource(id = R.string.home_everyday_recommend_title),
                        iconTint = Color(0xFFF54047)
                    ) {
                        navController.toUI(UI.EverydayRecommendDetail)
                    }

                    LibraryDivider()

                    SmallLabelItem(
                        icon = rememberVectorPainter(Icons.Outlined.Radio),
                        label = stringResource(id = R.string.home_personal_fm_title),
                        iconPadding = 8.dp
                    ) {
                        navController.toUI(UI.PersonalFmDetail)
                    }
                }
            }
        }
    }
}
