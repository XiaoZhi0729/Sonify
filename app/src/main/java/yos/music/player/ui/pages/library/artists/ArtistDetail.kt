package yos.music.player.ui.pages.library.artists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouArtistDetailData
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.Title

/**
 * 歌手详情页（激活上游 artist 系路由死代码；入口：本地歌手列表点击）。
 *
 * 解析链：入参只有歌手名（本地歌手无在线 id）时先 /search/artist 解析出 singerid
 * （同名精确匹配优先，否则取第一条），再拉 /artist/detail 头部 + /artist/audios 分页歌曲。
 * 歌曲分页滚动加载（30/页，短页即末页）；页内搜索框对已加载歌曲本地过滤（对齐上游 artist_detail_page）。
 * 关注按钮需登录；关注/取关走 /artist/follow|unfollow（乐观更新，失败回滚）。
 */
@Composable
fun ArtistDetail(
    navController: NavController,
    artistId: String?,
    artistName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val detail = remember { mutableStateOf<KugouArtistDetailData?>(null) }
    val resolvedId = remember { mutableStateOf(artistId.orEmpty()) }
    val songs = remember { mutableStateOf<List<KugouNewSong>>(emptyList()) }
    val page = remember { mutableStateOf(1) }
    val loadingSongs = remember { mutableStateOf(false) }
    val endReached = remember { mutableStateOf(false) }
    // 非空 = 歌手解析（搜索）/首拉失败的具体错误；歌曲追加失败沿用短页重试
    val loadError = remember { mutableStateOf<String?>(null) }
    val headerLoaded = remember { mutableStateOf(artistId != null) }
    val isFollowed = remember { mutableStateOf(false) }
    val followBusy = remember { mutableStateOf(false) }
    val searchText = remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun loadSongsPage(targetId: String) {
        if (loadingSongs.value || endReached.value) return
        loadingSongs.value = true
        scope.launch {
            KugouRepository.getArtistAudios(targetId, page = page.value)
                .onSuccess { fresh ->
                    val known = songs.value.map { it.hash }.toSet()
                    val add = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                    songs.value = songs.value + add
                    if (fresh.size < 30 || add.isEmpty()) {
                        endReached.value = true
                    } else {
                        page.value += 1
                    }
                }
                .onFailure { e ->
                    if (songs.value.isEmpty()) loadError.value = e.message ?: e.javaClass.simpleName
                    else endReached.value = true
                }
            loadingSongs.value = false
        }
    }

    // 歌手解析/详情/歌曲首拉（LaunchedEffect 首次调用；错误态「重试」复用）
    fun resolve() {
        loadError.value = null
        val id = resolvedId.value
        if (id.isNotEmpty()) {
            // 直达模式（带 id 入参）：头部 + 歌曲并行拉
            headerLoaded.value = true
            scope.launch {
                KugouRepository.getArtistDetail(id)
                    .onSuccess {
                        detail.value = it
                        isFollowed.value = it.isFollowed
                    }
            }
            loadSongsPage(id)
        } else {
            // 名字解析模式：/search/artist → 同名精确匹配优先
            scope.launch {
                val brief = KugouRepository.searchArtists(artistName).getOrNull()
                    ?.let { list ->
                        list.firstOrNull { it.name.equals(artistName, ignoreCase = true) } ?: list.firstOrNull()
                    }
                if (brief == null) {
                    loadError.value = context.getString(R.string.artist_detail_not_found)
                    headerLoaded.value = true
                    return@launch
                }
                resolvedId.value = brief.singerId
                detail.value = KugouArtistDetailData(
                    artistId = brief.singerId,
                    name = brief.name,
                    avatarUrl = brief.avatarUrl,
                    intro = null,
                    songCount = 0,
                    albumCount = 0,
                    isFollowed = false
                )
                headerLoaded.value = true
                // 头像/计数/关注态用详情接口补全
                scope.launch {
                    KugouRepository.getArtistDetail(brief.singerId)
                        .onSuccess {
                            detail.value = it
                            isFollowed.value = it.isFollowed
                        }
                }
                loadSongsPage(brief.singerId)
            }
        }
    }

    LaunchedEffect(Unit) { resolve() }

    // 滚动加载：最后一个可见项进入「距底部 6 项」范围即触发下一页
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad && songs.value.isNotEmpty()) loadSongsPage(resolvedId.value)
        }
    }

    fun playAt(index: Int) {
        val list = songs.value
        if (index !in list.indices) return
        val queue = list.map { KugouRepository.toQueueMediaItem(it) }
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(queue[index], queue)
        }
    }

    fun toggleFollow() {
        val id = resolvedId.value
        if (id.isEmpty() || followBusy.value || !KugouAccountState.isLoggedIn) return
        followBusy.value = true
        val target = !isFollowed.value
        isFollowed.value = target
        scope.launch {
            val result = if (target) KugouRepository.followArtist(id) else KugouRepository.unfollowArtist(id)
            result.onFailure { isFollowed.value = !target }
            followBusy.value = false
        }
    }

    // 页内搜索：对已加载歌曲本地过滤（大小写不敏感，标题/歌手任一命中）
    val displaySongs by remember {
        derivedStateOf {
            val query = searchText.value.trim()
            if (query.isEmpty()) songs.value
            else songs.value.filter {
                it.name.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
            }
        }
    }
    // Capture items and keys together for deferred lazy layout callbacks.
    val songItems = displaySongs
    // 酷狗接口可能返回重复 FileHash，item key 按出现序号唯一化
    val songKeys = remember(songItems) { lazyItemKeys(songItems) { it.hash } }

    Title(
        title = artistName,
        onBack = { navController.popBackStack() }
    ) {
        if (loadError.value != null && songs.value.isEmpty()) {
            item("Error") {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                    Text(
                        text = loadError.value.orEmpty(),
                        fontSize = 18.sp,
                        modifier = Modifier.alpha(0.6f)
                    )
                    // 未找到歌手属上游无数据的终态，仅请求类失败提供重试
                    if (loadError.value != context.getString(R.string.artist_detail_not_found)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(id = R.string.load_more_failed),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                loadError.value = null
                                endReached.value = false
                                resolve()
                            }
                        )
                    }
                }
            }
            return@Title
        }

        // 头部：头像 + 名字 + 计数 + 关注
        item("Header") {
            val d = detail.value
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(d?.avatarUrl)
                        .error(R.drawable.songcredits_monogram_person)
                        .placeholder(R.drawable.songcredits_monogram_person)
                        .fallback(R.drawable.songcredits_monogram_person)
                        .allowHardware(true)
                        .precision(Precision.INEXACT)
                        .size(256)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = d?.name?.ifEmpty { artistName } ?: artistName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val counts = buildString {
                        if ((d?.songCount ?: 0) > 0) {
                            append(stringResource(id = R.string.artist_detail_songs, d!!.songCount))
                        }
                        if ((d?.albumCount ?: 0) > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(id = R.string.artist_detail_albums, d!!.albumCount))
                        }
                    }
                    if (counts.isNotEmpty()) {
                        Text(
                            text = counts,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .alpha(0.55f)
                        )
                    }
                }
                if (KugouAccountState.isLoggedIn && headerLoaded.value) {
                    Spacer(Modifier.width(12.dp))
                    FollowButton(
                        followed = isFollowed.value,
                        busy = followBusy.value,
                        onClick = { toggleFollow() }
                    )
                }
            }
            d?.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                Text(
                    text = intro,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 22.dp)
                        .alpha(0.55f)
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        // 页内搜索
        item("SearchField") {
            val keyboardController = LocalSoftwareKeyboardController.current
            SearchTextField(
                text = searchText.value,
                placeholder = stringResource(id = R.string.artist_detail_search_hint),
                onValueChange = { searchText.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 2.dp, bottom = 12.dp),
                onSearch = { keyboardController?.hide() }
            )
        }

        item("DividerTop") {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 16.dp)
                    .alpha(0.15f)
                    .height(0.5.dp)
                    .background(Color.Black withNight Color.White)
            )
        }

        if (songs.value.isEmpty()) {
            item("SongsStatus") {
                Text(
                    text = if (loadingSongs.value) stringResource(id = R.string.online_playlists_loading)
                    else stringResource(id = R.string.artist_detail_empty),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
        } else {
            if (songItems.isEmpty()) {
                item("FilteredEmpty") {
                    Text(
                        text = stringResource(id = R.string.artist_detail_search_empty),
                        fontSize = 16.sp,
                        modifier = Modifier
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                            .alpha(0.6f)
                    )
                }
            }
            itemsIndexed(
                songItems,
                key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } }
            ) { index, song ->
                key(songKeys.getOrElse(index) { "oob_$index" }) {
                    MusicList(KugouRepository.toDisplayMediaItem(song)) {
                        // 过滤态下点击播放过滤前列表中的同一首
                        val realIndex = songs.value.indexOfFirst { it.hash == song.hash }
                        playAt(if (realIndex >= 0) realIndex else index)
                    }
                }

                key("divider_$index") {
                    if (index < songItems.size - 1) {
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

            if (loadingSongs.value) {
                item("Footer") {
                    Text(
                        text = stringResource(id = R.string.online_playlists_loading),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                            .alpha(0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowButton(followed: Boolean, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (followed) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primary
            )
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(
            text = stringResource(
                id = if (followed) R.string.artist_detail_unfollow else R.string.artist_detail_follow
            ),
            fontSize = 13.sp,
            color = if (followed) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}
