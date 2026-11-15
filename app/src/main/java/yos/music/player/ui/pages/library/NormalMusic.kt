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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.rememberIndexScrollOffsetPx
import yos.music.player.ui.widgets.basic.scrollToIndexedItem
import yos.music.player.ui.widgets.dropdown.LiquidGlassDropdownMenuItem
import yos.music.player.ui.widgets.dropdown.TitleBarGlassMenu
import yos.music.player.ui.widgets.mochi.IndexedListResult
import yos.music.player.ui.navigation.popBackStackSafely

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
                    navController.popBackStackSafely()
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
            val indexedState: MutableState<IndexedListResult<YosMediaItem>> = remember(musicList) {
                mutableStateOf(
                    sortAndIndexSongs(
                        items = musicList,
                        sortOrdinal = SongSort,
                        descending = EnableDescending,
                        isSearching = false,
                    )
                )
            }

            YosWrapper {
                LaunchedEffect(musicList, searchText.value, SongSort, EnableDescending) {
                    val query = searchText.value
                    val sortOrdinal = SongSort
                    val descending = EnableDescending
                    val searching = query.isNotEmpty()
                    val result = withContext(Dispatchers.IO) {
                        val filteredList = if (searching) {
                            // 只在当前目标列表内过滤（歌曲入口的列表即全库，行为不变）；
                            // 之前过滤全局 songs 会让歌单/艺人视图的搜索跳出范围
                            musicList.asSequence().filter { song ->
                                (song.title ?: defaultTitle).contains(
                                    query,
                                    ignoreCase = true
                                ) ||
                                    (song.artistsList ?: defaultArtists).any { artist ->
                                        artist.contains(
                                            query,
                                            ignoreCase = true
                                        )
                                    }
                            }.toList()
                        } else {
                            musicList
                        }
                        sortAndIndexSongs(
                            items = filteredList,
                            sortOrdinal = sortOrdinal,
                            descending = descending,
                            isSearching = searching,
                        )
                    }
                    indexedState.value = result
                }
            }

            val indexedResult = indexedState.value
            val currentItems = indexedResult.sortedItems
            val scope = rememberCoroutineScope()
            val listState = rememberLazyListState()
            val topBarHeightPx = rememberIndexScrollOffsetPx()

            val expanded = remember { mutableStateOf(false) }

            Box(Modifier.fillMaxSize()) {
                Title(
                    title = pageInfo.first,
                    onBack = {
                        navController.popBackStackSafely()
                    },
                    listState = listState,
                    rightBarIcon = {
                        // 「更多」是本体化玻璃菜单（NexioSchedule 1.6.5）：
                        // 顶栏槽只放 42dp 占位，菜单本体（收起态就是这颗钮）住全屏弹层。
                        TitleBarGlassMenu(
                            expanded = expanded.value,
                            onExpand = { expanded.value = true },
                            onDismiss = { expanded.value = false },
                            triggerIcon = Icons.Rounded.MoreHoriz,
                            modifier = Modifier.padding(end = 10.dp),
                        ) {
                            SongsSortMenuContent(
                                selectedSort = SongSort,
                                descending = EnableDescending,
                                onDismiss = { expanded.value = false },
                            )
                        }
                    },
                    indexBarLabels = indexedResult.labels,
                    onIndexSelect = { idx ->
                        val target = indexedResult.firstItemIndices.getOrNull(idx) ?: return@Title
                        scope.launch {
                            listState.scrollToIndexedItem(3 + target, topBarHeightPx)
                        }
                    },
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
                                if (currentItems.isEmpty()) return@NormalButton
                                // 「播放」= 顺序播放：显式关闭随机（随机为进程级粘性，必须关）
                                MediaController.mediaControl?.shuffleModeEnabled = false
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        currentItems.first(),
                                        currentItems
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_shuffle),
                                label = stringResource(id = R.string.normal_button_shuffle),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (currentItems.isEmpty()) return@NormalButton
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        currentItems.random(),
                                        currentItems
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        currentItems,
                        key = { _, music -> music },
                        contentType = { _, _ -> "normal_music_item" }
                    ) { index, music ->
                        MusicList(
                            music
                        ) {
                            scope.launch(Dispatchers.IO) {
                                MediaController.prepare(
                                    music,
                                    currentItems
                                )
                            }
                        }

                        key(index) {
                            val needDivider = index < currentItems.size - 1
                            if (needDivider) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 88.dp, end = 16.dp)
                                        .height(0.5.dp)
                                        .background((Color.Black withNight Color.White).copy(alpha = 0.15f))
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

private fun sortAndIndexSongs(
    items: List<YosMediaItem>,
    sortOrdinal: Int,
    descending: Boolean,
    isSearching: Boolean,
): IndexedListResult<YosMediaItem> {
    if (items.isEmpty()) return IndexedListResult(emptyList(), emptyList(), emptyList())
    return when (sortOrdinal) {
        SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal -> buildAlphabetIndexedItems(
            items = items,
            descending = descending,
            isSearching = isSearching,
            keySelector = { it.title ?: defaultTitle },
        )

        SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal -> buildAlphabetIndexedItems(
            items = items,
            descending = descending,
            isSearching = isSearching,
            keySelector = { (it.artistsList ?: defaultArtists).firstOrNull().orEmpty() },
        )

        SettingsLibrary.SongSortEnum.MUSIC_DURATION.ordinal -> {
            val sorted = items.sortedBy { it.duration }.let { if (descending) it.asReversed() else it }
            IndexedListResult(sorted, emptyList(), emptyList())
        }

        SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal -> {
            val sorted = items.sortedBy { it.modifiedDate ?: 0L }.let { if (descending) it.asReversed() else it }
            IndexedListResult(sorted, emptyList(), emptyList())
        }

        else -> buildAlphabetIndexedItems(
            items = items,
            descending = descending,
            isSearching = isSearching,
            keySelector = { it.title ?: defaultTitle },
        )
    }
}

/**
 * 歌曲页排序玻璃菜单的内容（NexioSchedule 1.6.5 本体化菜单的菜单项）。
 *
 * 排序方式与顺序是两个独立维度，所以点完不收（保持打开连着调）。行尾勾实时反映
 * 调用方读好的 [selectedSort]/[descending]。分割线把两组分开（左缘与行文字对齐 22dp）。
 */
@Composable
@NonSkippableComposable
private fun SongsSortMenuContent(
    selectedSort: Int,
    descending: Boolean,
    onDismiss: () -> Unit,
) {
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_by_name),
        onClick = { SongSort = SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal },
        selected = selectedSort == SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_by_artist),
        onClick = { SongSort = SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal },
        selected = selectedSort == SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_by_date),
        onClick = { SongSort = SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal },
        selected = selectedSort == SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal,
    )
    SongsSortMenuDivider()
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_ascending),
        onClick = { EnableDescending = false },
        selected = !descending,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_descending),
        onClick = { EnableDescending = true },
        selected = descending,
    )
}

/** 排序方式与顺序两组之间的细分割线：左缘与行文字对齐（行 8dp 内缩 + 14dp 内边距）。 */
@Composable
private fun SongsSortMenuDivider() =
    Spacer(
        modifier = Modifier
            .padding(start = 22.dp, end = 14.dp)
            .padding(vertical = 5.dp)
            .height(0.65.dp)
            .background((Color.Black withNight Color.White).copy(alpha = 0.1f))
    )
