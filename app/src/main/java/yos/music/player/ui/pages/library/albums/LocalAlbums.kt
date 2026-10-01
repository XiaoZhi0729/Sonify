package yos.music.player.ui.pages.library.albums

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.LibraryObject
import yos.music.player.ui.UI
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.preloadRawCover

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LocalAlbums(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedContentScope: AnimatedContentScope
) {
    Column(
        Modifier
            .fillMaxSize()
        /*.statusBarsPadding()*/
    ) {
        val albumsList = remember("LocalAlbums_albumsList") {
            MusicLibrary.albums
        }

        val searchText = remember("LocalAlbums_searchText") {
            mutableStateOf("")
        }

        val hideMusic = remember("LocalAlbums_showMusic") {
            derivedStateOf {
                albumsList.isEmpty()
            }
        }
        if (hideMusic.value) {
            val message =
                stringResource(
                    id = R.string.tip_no_song
                )
            Title(
                title = stringResource(id = R.string.page_library_albums), onBack = {
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
            val list = remember { mutableStateOf(albumsList) }

            YosWrapper {
                LaunchedEffect(searchText.value) {
                    withContext(Dispatchers.IO) {
                        val filteredList = withContext(Dispatchers.IO) {
                            if (useSearch.value) {
                                MusicLibrary.albums.asSequence().filter { album ->
                                    album.contains(searchText.value, ignoreCase = true)
                                }.toList()
                            } else {
                                albumsList
                            }
                        }
                        list.value = filteredList
                    }
                }
            }

            TitleWithLazyVerticalGrid(
                title = stringResource(id = R.string.page_library_albums), onBack = {
                    navController.popBackStack()
                }
            ) {
                item("SearchField", span = { GridItemSpan(maxLineSpan) }) {
                    val keyboardController = LocalSoftwareKeyboardController.current

                    SearchTextField(
                        text = searchText.value,
                        placeholder = stringResource(id = R.string.page_library_search_album),
                        onValueChange = {
                            searchText.value = it
                        },
                        modifier = Modifier
                            .fillMaxWidth(),
                        onSearch = {
                            if (searchText.value.isNotEmpty()) {
                                keyboardController?.hide()
                            }
                        })
                }
                itemsIndexed(
                    list.value,
                    key = { _, album -> album }/*,
                    contentType = { _, _ -> "LocalAlbums_item" }*/
                ) { _, album ->
                    val context = LocalContext.current
                    val prefetchScope = rememberCoroutineScope()
                    AlbumItems(
                        albumName = album,
                        animatedContentScope = animatedContentScope,
                        sharedTransitionScope = sharedTransitionScope
                    ) {
                        // 详情封面为排序后首轨的 RAW 原图；预加载命中同一 RAW 缓存槽，
                        // 避免 shared element 首帧放大占位图
                        prefetchScope.launch {
                            preloadRawCover(
                                context,
                                MusicLibrary.Album[album]
                                    .sortedWith(
                                        compareBy<YosMediaItem> { it.trackNumber ?: 0 }.thenBy { it.title }
                                    )
                                    .firstOrNull()?.thumb?.toString() ?: ""
                            )
                            LibraryObject.setTargetAlbumName(album)
                            navController.toUI(UI.AlbumInfo)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LazyGridItemScope.AlbumItems(
    albumName: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedContentScope: AnimatedContentScope,
    onClick: () -> Unit
) {
    val songs = MusicLibrary.Album[albumName]
    Column(
        Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
    ) {
        // 固定正方形外壳承载 shared element；ShadowImage 只在外壳内部绘制，
        // aspectRatio/阴影/裁剪不参与共享元素的测量与 bounds 插值
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (sharedTransitionScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(
                                key = "album/local/$albumName"
                            ),
                            animatedVisibilityScope = animatedContentScope
                        )
                    }
                } else Modifier
            )
        Box(coverModifier) {
            ShadowImage(
                dataLambda = { songs.getOrNull(0)?.thumb },
                contentDescription = "Album",
                modifier = Modifier.fillMaxSize(),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = albumName,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )

        Text(
            text = stringResource(id = R.string.page_library_album_desc, songs.size),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.6f),
            lineHeight = 17.sp
        )
    }
}