package yos.music.player.ui.pages.library.artists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.objects.FollowedArtistsObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouFollowedArtist
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.ui.UI
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.YosWrapper

/**
 * 在线艺人选择页（资料库「在线音乐」分区入口）。
 *
 * 数据源 = 酷狗云「我关注的歌手」（[FollowedArtistsObject]，MMKV 持久缓存 + 云端同步），
 * 不再从本地歌曲元数据聚合。点击直达酷狗歌手详情（列表自带真实 singerid，无需按名解析）。
 * 未登录时引导去酷狗登录页；登录/登出经 [KugouAccountState] 反应式切换，
 * LaunchedEffect 按 isLoggedIn 键控重拉（登录页是压栈导航，返回时组合不会重建）。
 */
@Composable
fun OnlineArtists(navController: NavController) {
    val account = KugouAccountState

    LaunchedEffect(account.isLoggedIn, KugouSyncCoordinator.artistsRevision.value) {
        if (account.isLoggedIn) FollowedArtistsObject.ensureLoaded(force = true)
    }
    // 下拉刷新指示器：松手后由 loading 归零收起
    val refreshing = remember("OnlineArtists_refreshing") { mutableStateOf(false) }
    LaunchedEffect(FollowedArtistsObject.loading.value) {
        if (!FollowedArtistsObject.loading.value) refreshing.value = false
    }

    val artistsList = FollowedArtistsObject.artists.value

    Column(
        Modifier
            .fillMaxSize()
        /*.statusBarsPadding()*/
    ) {
        val searchText = remember("OnlineArtists_searchText") {
            mutableStateOf("")
        }

        val displayArtists = rememberFilteredFollowedArtists(artistsList, searchText.value)
        if (!account.isLoggedIn) {
            Title(
                title = stringResource(id = R.string.page_library_artists), onBack = {
                    navController.popBackStack()
                }
            ) {
                item("login_tip") {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.online_artists_login_tip),
                            fontSize = 18.sp,
                            modifier = Modifier.alpha(0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { navController.navigate(UI.Settings.KugouLogin) }) {
                            Text(text = stringResource(id = R.string.kugou_phone_login))
                        }
                    }
                }
            }
        } else if (artistsList.isEmpty()) {
            val message = stringResource(id = R.string.online_artists_empty)
            Title(
                title = stringResource(id = R.string.page_library_artists), onBack = {
                    navController.popBackStack()
                }
            ) {
                item("empty_followed") {
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
            Title(
                title = stringResource(id = R.string.page_library_artists), onBack = {
                    navController.popBackStack()
                },
                onRefresh = {
                    refreshing.value = true
                    KugouSyncCoordinator.notifyArtistsChanged()
                },
                refreshing = refreshing.value
            ) {
                item("SearchField") {
                    val keyboardController = LocalSoftwareKeyboardController.current

                    SearchTextField(
                        text = searchText.value,
                        placeholder = stringResource(id = R.string.page_library_search_artists),
                        onValueChange = {
                            searchText.value = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                            .padding(top = 5.dp, bottom = 12.dp),
                        onSearch = {
                            if (searchText.value.isNotEmpty()) {
                                keyboardController?.hide()
                            }
                        })
                }

                itemsIndexed(
                    displayArtists,
                    key = { _, artist -> "artist:${artist.artistId}" }
                ) { index, artist ->
                    OnlineArtistItem(artist = artist) {
                        navController.navigate(
                            UI.artistDetailRoute(artistId = artist.artistId, artistName = artist.name)
                        )
                    }

                    if (index < displayArtists.lastIndex) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 81.dp, end = 16.dp)
                                .alpha(0.15f)
                                .height(0.5.dp)
                                .background(Color.Black withNight Color.White)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 按名字过滤收藏艺人（保留完整条目）。防抖/快照语义与 [rememberFilteredArtists] 一致。
 */
@Composable
private fun rememberFilteredFollowedArtists(
    source: List<KugouFollowedArtist>,
    query: String
): List<KugouFollowedArtist> {
    var filteredArtists by remember { mutableStateOf(source) }

    LaunchedEffect(source, query) {
        if (query.isEmpty()) {
            filteredArtists = source
            return@LaunchedEffect
        }

        delay(250)
        val result = withContext(Dispatchers.Default) {
            source.filter { artist ->
                artist.name.contains(query, ignoreCase = true)
            }
        }
        // LaunchedEffect resumes on Main after the Default calculation.
        filteredArtists = result
    }

    return if (query.isEmpty()) source else filteredArtists
}

@Composable
private fun LazyItemScope.OnlineArtistItem(
    modifier: Modifier = Modifier,
    artist: KugouFollowedArtist,
    onClick: () -> Unit
) =
    Row(
        modifier = Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            YosWrapper {
                val shape = CircleShape

                    val density = LocalDensity.current
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(data = artist.avatarUrl).crossfade(true)
                            .error(R.drawable.songcredits_monogram_person)
                            .placeholder(R.drawable.songcredits_monogram_person)
                            .fallback(R.drawable.songcredits_monogram_person)
                            .allowHardware(true)
                            .precision(Precision.INEXACT)
                            .size(128)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = modifier
                            .size(48.dp)
                            .aspectRatio(1f)
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                clip = true
                                this.shape = shape
                            }
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    val outline = shape.createOutline(
                                        Size(size.width, size.height),
                                        LayoutDirection.Ltr,
                                        density
                                    )
                                    drawOutline(
                                        outline = outline,
                                        color = Color.DarkGray.copy(alpha = 0.08f),
                                        style = Stroke(width = 6f)
                                    )
                                    drawOutline(
                                        outline = outline,
                                        color = Color.DarkGray.copy(alpha = 0.4f),
                                        style = Stroke(width = 6f),
                                        blendMode = BlendMode.Overlay
                                    )
                                }
                            }
                    )
            }
        }
        Spacer(modifier = Modifier.width(15.dp))
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(
                text = artist.name,
                fontSize = 16.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_action_next), contentDescription = null,
            modifier = Modifier
                .height(12.dp).padding(end = 8.dp)
                .alpha(0.3f), tint = MaterialTheme.colorScheme.onBackground
        )
    }
