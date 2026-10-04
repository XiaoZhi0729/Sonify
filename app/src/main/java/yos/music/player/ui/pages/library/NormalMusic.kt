package yos.music.player.ui.pages.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.MusicLibrary.songs
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.SettingsLibrary.EnableDescending
import yos.music.player.data.libraries.SettingsLibrary.SongSort
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsList
import yos.music.player.data.libraries.defaultArtists
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.objects.LibraryObject
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownLayout
import yos.music.player.ui.widgets.basic.LiquidDropdownProgress
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.liquidDropdownAnchorFollow
import yos.music.player.ui.widgets.basic.rememberLiquidDropdownFollowState

@Composable
fun NormalMusic(navController: NavController) {
    Column(
        Modifier
            .fillMaxSize()
        /*.statusBarsPadding()*/
    ) {
        val pageInfo = LibraryObject.getTargetListWithTitle()

        val musicList = pageInfo.second
        val searchText = remember("NormalMusic_searchText") {
            mutableStateOf("")
        }

        val showMusic = remember("NormalMusic_showMusic") {
            derivedStateOf {
                musicList.isEmpty()
            }
        }
        if (showMusic.value) {
            val message =
                if (musicList == null) stringResource(id = R.string.tip_scanning) else stringResource(
                    id = R.string.tip_no_song
                )
            Title(
                title = pageInfo.first, onBack = {
                    navController.popBackStack()
                }
            ) {
                item("tip_no_song") {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                    ) {
                        Text(text = message, fontSize = 18.sp, modifier = Modifier.alpha(0.6f))
                    }
                }
            }
        } else {
            val useSearch = remember { derivedStateOf { searchText.value.isNotEmpty() } }
            val list: MutableState<List<YosMediaItem>> = remember { mutableStateOf(musicList.sortX()) }

            YosWrapper {
                LaunchedEffect(searchText.value, SongSort, EnableDescending) {
                    withContext(Dispatchers.IO) {
                        // if (list.value.isEmpty()) delay(320)
                        val filteredList = withContext(Dispatchers.IO) {
                            if (useSearch.value) {
                                // 只在当前目标列表内过滤（歌曲入口的列表即全库，行为不变）；
                                // 之前过滤全局 songs 会让歌单/艺人视图的搜索跳出范围
                                musicList.asSequence().filter { song ->
                                    (song.title ?: defaultTitle).contains(
                                        searchText.value,
                                        ignoreCase = true
                                    ) ||
                                            (song.artistsList ?: defaultArtists).any { artist ->
                                                artist.contains(
                                                    searchText.value,
                                                    ignoreCase = true
                                                )
                                            }
                                }.toList()
                            } else {
                                musicList
                            }
                        }
                        list.value = filteredList.sortX()
                    }
                }
            }

            val scope = rememberCoroutineScope()

            val expanded = remember { mutableStateOf(false) }

            Box(Modifier.fillMaxSize()) {
                Title(
                    title = pageInfo.first, onBack = {
                        navController.popBackStack()
                    },
                    rightBarIcon = {
                        // 排序菜单的触发按钮与弹层都住在 Title 作用域里：
                        // LocalTitleOverlayHost/LocalTitlePageBackdrop 由 Title 页根提供，
                        // 挪到外面兄弟节点会退回独立 Popup 窗口并丢掉玻璃采样源
                        val anchorBounds = remember { mutableStateOf(IntRect.Zero) }
                        val followState = rememberLiquidDropdownFollowState()

                        TitleBarIcon(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    val pos = coords.positionInWindow()
                                    val size = coords.size
                                    anchorBounds.value = IntRect(
                                        left = pos.x.toInt(),
                                        top = pos.y.toInt(),
                                        right = pos.x.toInt() + size.width,
                                        bottom = pos.y.toInt() + size.height,
                                    )
                                }
                                // 联动必须挂在锚点捕获的下游：按钮会随面板进度
                                // 下沉/收缩，锚点若跟着走会形成逐帧抖动回环
                                .liquidDropdownAnchorFollow(followState),
                            icon = Icons.Rounded.MoreHoriz,
                            onBack = {
                                expanded.value = true
                            }
                        )

                        SongsSortMenu(
                            expanded = expanded.value,
                            anchorBounds = anchorBounds.value,
                            // 选中态必须读在**注册作用域**（这里）而不是菜单 content lambda 里：
                            // content 会被 SideEffect 注册进 TitleOverlayHost 的独立组合树，
                            // 跨组合树读 SettingLibrary 状态不触发宿主重组，勾选会冻住
                            // （2026-10-04 实测；读在这里，写状态→本作用域重组→SideEffect
                            // 重注册→宿主 slot 内容必然换新）
                            selectedSort = SongSort,
                            descending = EnableDescending,
                            onDismiss = { expanded.value = false },
                            onFractionProgress = followState::onProgress,
                        )
                    }
                ) {
                    item("SearchField") {
                        val keyboardController = LocalSoftwareKeyboardController.current

                        SearchTextField(
                            text = searchText.value,
                            placeholder = stringResource(id = R.string.page_library_search_songs),
                            onValueChange = {
                                searchText.value = it
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(top = 5.dp),
                            onSearch = {
                                if (searchText.value.isNotEmpty()) {
                                    keyboardController?.hide()
                                }
                            })
                    }
                    item("Options") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp)
                                .padding(top = 12.dp, bottom = 15.dp)
                        ) {
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_play),
                                label = stringResource(id = R.string.normal_button_play),
                                modifier = Modifier.weight(1f)
                            ) {
                                val currentList = list.value
                                if (currentList.isEmpty()) return@NormalButton
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        currentList.first(),
                                        currentList
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_shuffle),
                                label = stringResource(id = R.string.normal_button_shuffle),
                                modifier = Modifier.weight(1f)
                            ) {
                                val currentList = list.value
                                if (currentList.isEmpty()) return@NormalButton
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        currentList.random(),
                                        currentList
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        list.value,
                        key = { _, music -> music }
                    ) { index, music ->
                        MusicList(
                            music
                        ) {
                            scope.launch(Dispatchers.IO) {
                                MediaController.prepare(
                                    music,
                                    list.value
                                )
                            }
                        }

                        key(index) {
                            val needDivider = index < musicList.size - 1
                            if (needDivider) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 88.dp, end = 16.dp)
                                        .alpha(0.15f)
                                        .height(0.5.dp)
                                        .background(Color.Black withNight Color.White)
                                )
                            }
                        }
                    }

                    /*item("blank") {
                    Spacer(modifier = Modifier.navigationBarsHeight(15.dp))
                }*/
                }
            }
        }
    }
}

private fun List<YosMediaItem>.sortX() =
    this.sortedBy { song ->
        when (SongSort) {
            SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal -> Pinyin.toPinyin(
                (song.title ?: defaultTitle)[0]
            )

            SettingsLibrary.SongSortEnum.MUSIC_DURATION.ordinal -> song.duration
            SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal -> Pinyin.toPinyin(
                (song.artistsList ?: defaultArtists).first()[0]
            )

            SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal -> song.modifiedDate ?: 0
            else -> Pinyin.toPinyin((song.title ?: defaultTitle)[0])
        }.toString()
    }.let {
        if (EnableDescending) {
            it.reversed()
        } else {
            it
        }
    }

/**
 * 歌曲页排序玻璃下拉：与播放页音质/「更多」菜单同套 [LiquidDropdownLayout] 引擎
 * （surfaceAlpha 0.6 同值），摆位用默认 OverlayAnchor——面板从按钮处生长并覆盖它。
 *
 * 排序方式与顺序是两个独立维度，所以点完不收（保持打开连着调）。
 * [selectedSort]/[descending] 由调用方在注册作用域读好后传入（原因见调用处注释），
 * 行尾勾据此实时反映。分割线把两组分开（左缘与行文字对齐 22dp）。
 * 动画与新版 NexioSchedule 的右上角菜单同款：面板从按钮方块沿锚点角双向长成。
 *
 * **@NonSkippableComposable 是刻意的**：菜单开着时点选项，本函数的 Boolean 参数
 * 已变化但 Compose 的 skip 检查仍误判"未变化"（2026-10-04 真机埋点实测：调用点
 * 执行了、函数体被跳过），导致 SideEffect 不重注册、勾号冻住；禁用 skip 强制
 * 函数体随调用点执行——重注册链路（host.set 换新 content）已验证能即时刷勾，
 * 且 slotKey 走 remember 稳定持有，重建的只是注册 lambda，面板动画状态不受影响。
 */
@NonSkippableComposable
@Composable
private fun SongsSortMenu(
    expanded: Boolean,
    anchorBounds: IntRect,
    selectedSort: Int,
    descending: Boolean,
    onDismiss: () -> Unit,
    onFractionProgress: (LiquidDropdownProgress) -> Unit,
) {
    LiquidDropdownLayout(
        expanded = expanded,
        anchorBounds = anchorBounds,
        onDismissRequest = onDismiss,
        surfaceAlpha = 0.6f,
        backdrop = LocalTitlePageBackdrop.current,
        onFractionProgress = onFractionProgress,
    ) {
        LiquidDropdownColumn {
            LiquidDropdownRow(
                text = stringResource(id = R.string.normal_button_sort_by_name),
                selected = selectedSort == SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal,
                onClick = {
                    SongSort = SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal
                },
                isFirst = true,
            )
            LiquidDropdownRow(
                text = stringResource(id = R.string.normal_button_sort_by_artist),
                selected = selectedSort == SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal,
                onClick = {
                    SongSort = SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal
                },
            )
            LiquidDropdownRow(
                text = stringResource(id = R.string.normal_button_sort_by_date),
                selected = selectedSort == SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal,
                onClick = {
                    SongSort = SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal
                },
            )
            SongsSortMenuDivider()
            LiquidDropdownRow(
                text = stringResource(id = R.string.normal_button_sort_ascending),
                selected = !descending,
                onClick = {
                    EnableDescending = false
                },
            )
            LiquidDropdownRow(
                text = stringResource(id = R.string.normal_button_sort_descending),
                selected = descending,
                onClick = {
                    EnableDescending = true
                },
                isLast = true,
            )
        }
    }
}

/** 排序方式与顺序两组之间的细分割线：左缘与行文字对齐（行 8dp 内缩 + 14dp 内边距）。 */
@Composable
private fun SongsSortMenuDivider() =
    Spacer(
        modifier = Modifier
            .padding(start = 22.dp, end = 14.dp)
            .padding(vertical = 5.dp)
            .alpha(0.1f)
            .height(0.65.dp)
            .background(Color.Black withNight Color.White)
    )
