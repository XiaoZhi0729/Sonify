package yos.music.player.ui.pages.library.playlists

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMapNotNull
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.libraries.MusicLibrary.songs
import yos.music.player.data.libraries.PlayList
import yos.music.player.data.libraries.PlayListLibrary
import yos.music.player.data.libraries.PlayListLibrary.playList
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.ui.UI
import yos.music.player.ui.pages.library.LibrarySortMenuButton
import yos.music.player.ui.pages.library.sortAndIndexLibraryItems
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.SonifyDialogTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.rememberIndexScrollOffsetPx
import yos.music.player.ui.widgets.basic.scrollToIndexedItem
import yos.music.player.ui.navigation.popBackStackSafely

@Composable
fun PlayLists(navController: NavController) {
    val sortOrdinal = SettingsLibrary.LocalPlaylistsSort
    val descending = SettingsLibrary.LocalPlaylistsDescending
    val indexedResult = remember(playList, sortOrdinal, descending) {
        sortAndIndexLibraryItems(
            items = playList,
            sortOrdinal = sortOrdinal,
            descending = descending,
            isSearching = false,
            nameSelector = { it.name },
            songCountSelector = { it.songDataList.size },
        )
    }
    val playLists = indexedResult.sortedItems
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val topBarHeightPx = rememberIndexScrollOffsetPx()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var createError by remember { mutableStateOf<String?>(null) }
    Title(
        title = stringResource(id = R.string.page_library_playlists),
        onBack = {
            navController.popBackStackSafely()
        },
        listState = listState,
        rightBarIcon = {
            LibrarySortMenuButton(
                selectedSort = { SettingsLibrary.LocalPlaylistsSort },
                onSelectSort = { SettingsLibrary.LocalPlaylistsSort = it },
                descending = { SettingsLibrary.LocalPlaylistsDescending },
                onSelectDescending = { SettingsLibrary.LocalPlaylistsDescending = it },
            )
        },
        indexBarLabels = indexedResult.labels,
        onIndexSelect = { idx ->
            val target = indexedResult.firstItemIndices.getOrNull(idx) ?: return@Title
            scope.launch {
                listState.scrollToIndexedItem(3 + target, topBarHeightPx)
            }
        },
        content = {
            item("AddList") {
                val targetTitle = context.getString(R.string.page_library_playlists_add_title)
                PlayListItem(playListType = PlayListType.Add, title = targetTitle) {
                    newPlaylistName = ""
                    createError = null
                    showCreateDialog = true
                }

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 102.dp, end = 16.dp)
                        .alpha(0.15f)
                        .height(0.5.dp)
                        .background(Color.Black withNight Color.White)
                )
            }

            item("FavList") {
                val targetTitle = context.getString(R.string.page_library_playlists_fav_title)
                PlayListItem(playListType = PlayListType.Favorite, title = targetTitle) {
                    navController.toUI(UI.LocalFavorites)
                }

                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 102.dp, end = 16.dp)
                        .alpha(0.15f)
                        .height(0.5.dp)
                        .background(Color.Black withNight Color.White)
                )
            }

            itemsIndexed(
                playLists,
                key = { _, playList -> playList.listID },
                contentType = { _, _ -> "playlist_item" }
            ) { index, playList ->
                PlayListItem(playList = playList) {
                    navController.navigate(UI.localPlaylistDetailRoute(playList.listID))
                }
                key(index) {
                    val needDivider = index < playLists.size - 1
                    if (needDivider) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 102.dp, end = 16.dp)
                                .alpha(0.15f)
                                .height(0.5.dp)
                                .background(Color.Black withNight Color.White)
                        )
                    }
                }
            }
        }
    )

    if (showCreateDialog) {
        SonifyDialog(
            onDismissRequest = { showCreateDialog = false },
            title = stringResource(id = R.string.online_playlists_create),
            content = {
                Column {
                    SonifyDialogTextField(
                        value = newPlaylistName,
                        onValueChange = {
                            newPlaylistName = it
                            if (createError != null) createError = null
                        },
                        placeholder = stringResource(id = R.string.online_playlists_create_hint),
                        modifier = Modifier.fillMaxWidth()
                    )
                    createError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            positiveContent = stringResource(id = R.string.common_ok),
            onPositive = {
                val created = PlayListLibrary.createAndReturn(newPlaylistName)
                if (created != null) {
                    showCreateDialog = false
                } else if (newPlaylistName.isNotBlank()) {
                    createError = context.getString(R.string.local_playlist_name_exists)
                }
            },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { showCreateDialog = false },
            positiveEnabled = newPlaylistName.isNotBlank(),
            closeOnPositive = false
        )
    }
}

private fun convertToSongList(
    songDataList: List<Uri>,
    songs: List<YosMediaItem>
): List<YosMediaItem> {
    val byUri = songs.associateBy { it.uri }
    return songDataList.fastMapNotNull { uri -> byUri[uri] }
}

@Stable
private enum class PlayListType {
    Add, Favorite
}

@Composable
private fun LazyItemScope.PlayListItem(playList: PlayList, itemClick: () -> Unit) {
    Row(
        modifier = Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .height(80.dp)
            .fillMaxWidth()
            .clickable {
                itemClick()
            }
            .padding(start = 22.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val shape = YosRoundedCornerShape(4.dp)
        val density = LocalDensity.current

        Image(painter = painterResource(id = R.drawable.placeholder_playlist_default),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                    this.shape = shape
                }
                .drawWithCache {
                                        val outline = shape.createOutline(
                        Size(size.width, size.height),
                        LayoutDirection.Ltr,
                        density
                    )
                    onDrawWithContent {
                        drawContent()
                        drawOutline(
                            outline = outline,
                            color = Color.Gray.copy(alpha = 0.1f),
                            style = Stroke(width = 8f)
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.Gray.copy(alpha = 0.5f),
                            style = Stroke(width = 8f),
                            blendMode = BlendMode.Overlay
                        )
                    }
                })

        Column(
            Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            Text(
                text = playList.name,
                modifier = Modifier.padding(bottom = 1.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_action_next), contentDescription = null,
            modifier = Modifier
                .height(12.dp)
                .alpha(0.3f), tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun LazyItemScope.PlayListItem(playListType: PlayListType, title: String, itemClick: () -> Unit) {
    Row(
        modifier = Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .height(80.dp)
            .fillMaxWidth()
            .clickable {
                itemClick()
            }
            .padding(start = 22.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        println("重组：播放列表 ${playListType.name}")

        val shape = YosRoundedCornerShape(4.dp)
        val density = LocalDensity.current

        Image(painter = painterResource(id = if (playListType == PlayListType.Add) R.drawable.placeholder_playlist_new else R.drawable.placeholder_playlist_default),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                    this.shape = shape
                }
                .drawWithCache {
                                        val outline = shape.createOutline(
                        Size(size.width, size.height),
                        LayoutDirection.Ltr,
                        density
                    )
                    onDrawWithContent {
                        drawContent()
                        drawOutline(
                            outline = outline,
                            color = Color.Gray.copy(alpha = 0.1f),
                            style = Stroke(width = 8f)
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.Gray.copy(alpha = 0.5f),
                            style = Stroke(width = 8f),
                            blendMode = BlendMode.Overlay
                        )
                    }
                })

        Column(
            Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(bottom = 1.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_action_next), contentDescription = null,
            modifier = Modifier
                .height(12.dp)
                .alpha(0.3f), tint = MaterialTheme.colorScheme.onBackground
        )
    }
}