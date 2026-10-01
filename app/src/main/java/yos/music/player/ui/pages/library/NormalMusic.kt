package yos.music.player.ui.pages.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.navigation.NavController
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.YosWrapper

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
                                songs.asSequence().filter { song ->
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
            val buttonPosition = remember { mutableStateOf(Offset.Zero) }

            Box(Modifier.fillMaxSize()) {
                YosWrapper {
                    FloatingMenu({ expanded.value }, {
                        expanded.value = it
                    }, buttonPosition.value)
                }

                Title(
                    title = pageInfo.first, onBack = {
                        navController.popBackStack()
                    },
                    rightBarIcon = {
                        TitleBarIcon(
                            modifier = Modifier.onGloballyPositioned {
                                if (buttonPosition.value.y == 0f) {
                                    buttonPosition.value = it.localToRoot(Offset.Zero)
                                }
                            },
                            icon = Icons.Rounded.MoreHoriz,
                            onBack = {
                                expanded.value = true
                            }
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
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        list.value.first(),
                                        list.value
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_shuffle),
                                label = stringResource(id = R.string.normal_button_shuffle),
                                modifier = Modifier.weight(1f)
                            ) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(
                                        list.value.random(),
                                        list.value
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

@Composable
fun FloatingMenu(
    expandedLambda: () -> Boolean,
    expandedOnChanged: (Boolean) -> Unit,
    buttonPosition: Offset = Offset.Zero
) {

    val keepPopup = remember("FloatingMenu_keepPopup") {
        mutableStateOf(false)
    }
    val showPopup = remember("FloatingMenu_showPopup") {
        mutableStateOf(false)
    }

    if (keepPopup.value) {
        Popup(
            //offset = IntOffset(0, buttonPosition.y.toInt()),
            onDismissRequest = {
                expandedOnChanged(false)
            }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        expandedOnChanged(false)
                    }) {
                val animationSpec =
                    spring(dampingRatio = 0.7f, stiffness = 340f, visibilityThreshold = 0.0001f)
                val shadow = animateFloatAsState(
                    targetValue = if (showPopup.value) 225f else 0f,
                    animationSpec = if (showPopup.value) tween(
                        durationMillis = 300,
                        delayMillis = 430
                    ) else tween(durationMillis = 0)
                )
                AnimatedVisibility(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = with(LocalDensity.current) {
                            buttonPosition.y
                                .toDp()
                                .plus(10.dp)
                        }),
                    visible = showPopup.value,
                    enter = fadeIn(animationSpec = animationSpec) + scaleIn(
                        initialScale = 0.618f,
                        animationSpec = animationSpec,
                        transformOrigin = TransformOrigin(0.95f, 0f)
                    ),
                    exit = fadeOut(animationSpec = animationSpec) + scaleOut(
                        targetScale = 0.618f,
                        animationSpec = animationSpec,
                        transformOrigin = TransformOrigin(0.95f, 0f)
                    )
                ) {
                    val shape = RoundedCornerShape(10.dp)
                    val shadowColor = Color(0xB3000000)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Column(
                            Modifier
                                .padding(end = 12.dp)
                                /*.shadow(
                                    spotColor = shadowColor,
                                    shape = shape,
                                    elevation = shadow.value.dp,
                                    clip = false
                                )*/
                                .graphicsLayer {
                                    this.shape = shape
                                    this.spotShadowColor = shadowColor
                                    this.shadowElevation = shadow.value
                                }
                                .graphicsLayer {
                                    this.shape = shape
                                    this.clip = true
                                }
                                .background(Color(0xF2E9E9E9) withNight Color(0xFA161616), shape),
                        ) {
                            FloatingMenuItem(
                                label = stringResource(id = R.string.normal_button_sort_by_name),
                                icon = Icons.AutoMirrored.Outlined.QueueMusic
                            ) {
                                SongSort =
                                    SettingsLibrary.SongSortEnum.MUSIC_TITLE.ordinal
                                println("SongSort: $SongSort")
                            }
                            FloatingMenuItemDivider()
                            FloatingMenuItem(
                                label = stringResource(id = R.string.normal_button_sort_by_artist),
                                icon = Icons.Outlined.Person
                            ) {
                                SongSort =
                                    SettingsLibrary.SongSortEnum.ARTIST_NAME.ordinal
                                println("SongSort: $SongSort")
                            }
                            FloatingMenuDivider()
                            FloatingMenuItem(
                                label = stringResource(id = R.string.normal_button_sort_by_date),
                                icon = Icons.Outlined.AccessTime
                            ) {
                                SongSort =
                                    SettingsLibrary.SongSortEnum.MODIFIED_DATE.ordinal
                                println("SongSort: $SongSort")
                            }
                            FloatingMenuDivider()
                            FloatingMenuItem(
                                label = stringResource(id = R.string.normal_button_sort_ascending),
                                icon = Icons.Rounded.ArrowUpward
                            ) {
                                EnableDescending = false
                                println("SongSort: $EnableDescending")
                            }
                            FloatingMenuItemDivider()
                            FloatingMenuItem(
                                label = stringResource(id = R.string.normal_button_sort_descending),
                                icon = Icons.Rounded.ArrowDownward
                            ) {
                                EnableDescending = true
                                println("SongSort: $EnableDescending")
                            }
                        }
                    }
                }
            }
        }
        // println("Popup 显示")
    } else {
        // println("Popup 隐藏")
    }

    LaunchedEffect(key1 = expandedLambda()) {
        if (expandedLambda()) {
            keepPopup.value = true
            delay(100)
            showPopup.value = true
        } else {
            showPopup.value = false
            delay(300)
            keepPopup.value = false
        }
    }
}


@Composable
fun FloatingMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth(0.618f)
            .height(48.dp)
            .background((Color.White withNight Color.Black).copy(alpha = 0.68f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 17.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alpha(0.9f)
                .padding(end = 18.dp)
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun FloatingMenuItemDivider() =
    Spacer(
        modifier = Modifier
            .fillMaxWidth(0.618f)
            .alpha(0.1f)
            .height(0.65.dp)
            .background(Color.Black withNight Color.White)
    )

@Composable
fun FloatingMenuDivider() =
    Spacer(
        modifier = Modifier.height(8.dp)
    )
