package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import yos.music.player.ui.widgets.basic.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import yos.music.player.ui.widgets.basic.navigationBarsHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.repositories.AlbumArtist
import yos.music.player.data.repositories.KugouAlbumDetail
import yos.music.player.ui.UI
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.library.DetailPageHeader
import yos.music.player.ui.pages.library.DetailSongRowWide
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBar
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.rememberAlpha
import yos.music.player.ui.widgets.basic.rememberShowSmallTitle
import yos.music.player.ui.widgets.basic.rememberTitleBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import yos.music.player.ui.widgets.effects.ShadowType

/**
 * 在线专辑详情页（结构对齐 AlbumInfo / RankDetail / OnlinePlaylistDetail 详情模板）。
 *
 * 数据链：KugouRepository.getAlbumDetail(albumId) → GET /album/detail（头部元数据）
 *         + KugouRepository.getAlbumSongs(albumId) → GET /album/songs 分页拉全量。
 * 播放链（整列表）：全部歌曲映射为队列用 YosMediaItem（uri 为占位符，真实 URL
 * 由播放器惰性解析）→ 现有 MediaController.prepare(clicked, 完整列表) → Media3 队列。
 *
 * 说明：本地 AlbumInfo 以「本地专辑名 → MusicLibrary.Album[albumName]」为数据源，
 * 无法直接承载「albumId → /album/songs」的在线专辑；故按 OnlinePlaylistDetail 同款
 * 既有模式新建本页，并完整复用 AlbumInfo 的视觉组件（ShadowImage/NormalButton/MusicList/分割线）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnlineAlbumDetail(
    navController: NavController,
    albumId: String = "",
    sourceArtistId: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // 按路由里的 albumId 取本页专辑：多层堆叠时各自解析自己的那一张，
    // 不再共用「最后选中」的全局槽（否则返回时全部变成最新那张）。
    val album = OnlineAlbumObject.album(albumId)

    if (album == null) {
        // 边界：无选中专辑（如进程重建后 holder 丢失）
        Title(
            title = stringResource(id = R.string.discovery_new_albums_title),
            onBack = { navController.popBackStack() }
        ) {
            item("NoAlbum") {
                Text(
                    text = stringResource(id = R.string.online_album_detail_missing),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
        }
        return
    }

    val detail = remember("OnlineAlbumDetail_detail") {
        mutableStateOf<KugouAlbumDetail?>(null)
    }
    val tracks = remember("OnlineAlbumDetail_tracks") {
        mutableStateOf<List<KugouNewSong>>(emptyList())
    }
    // 酷狗接口可能返回重复 FileHash，item key 按出现序号唯一化。
    // items 与 keys 派生自同一次读取（约定见 LazyItemKeys.kt）
    val trackItems = tracks.value
    val songKeys = remember(trackItems) { lazyItemKeys(trackItems) { it.hash } }
    // 与 tracks 同步的队列映射（占位符 URI，入队零网络请求）
    val queue = remember("OnlineAlbumDetail_queue") {
        mutableStateOf<List<YosMediaItem>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlineAlbumDetail_status") { mutableStateOf("loading") }
    val scope = rememberCoroutineScope()

    fun load() {
        status.value = "loading"
        scope.launch {
            // 头部元数据失败不影响歌曲列表展示（封面/名称兜底用选中专辑字段）
            KugouRepository.getAlbumDetail(album.albumId)
                .onSuccess { detail.value = it }
                .onFailure { e ->
                    android.util.Log.d("OnlineAlbumDetail", "album detail failed: ${e.message}")
                }

            KugouRepository.getAlbumSongs(album.albumId)
                .onSuccess { list ->
                    tracks.value = list
                    queue.value = list.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (list.isEmpty()) "empty" else "ok"
                    android.util.Log.d(
                        "OnlineAlbumDetail",
                        "albumId=${album.albumId} albumName=${album.name} parsed=${list.size}"
                    )
                }
                .onFailure { e ->
                    tracks.value = emptyList()
                    queue.value = emptyList()
                    status.value = "error:${e.message}"
                }
        }
    }

    // 整列表播放：全部歌曲进 Media3 队列，从 index 处开始；URL 由播放器惰性解析
    fun playAt(index: Int) {
        val list = queue.value
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    // 平板（≥600dp）启用 Apple Music 式头部与三列歌曲行；手机保持旧版布局
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    // 目标封面 key：跟随入口点击时按本专辑 id 记录的来源 key（多层堆叠时各自正确）；
    // 记录缺失或与本专辑不符时回退默认 key。
    val recordedCoverKey = OnlineAlbumObject.coverKeyFor(album.albumId)
        ?.takeIf { it.endsWith("/${album.albumId}") }
    val coverSharedElementKey = recordedCoverKey ?: "album/online/${album.albumId}"

    Box(
        Modifier
            .fillMaxSize()
    ) {
        val state = rememberLazyListState()
        val titleBackdrop = rememberTitleBackdrop()
        val showSmallTitle = rememberShowSmallTitle(rememberAlpha(state), state)

        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(titleBackdrop)
                .overScrollVertical(),
            flingBehavior = rememberOverscrollFlingBehavior { state },
            contentPadding = PaddingValues(bottom = 18.dp, top = 54.dp)
        ) {
            item("AlbumHeader") {
                if (isWideScreen) {
                    // 平板（≥600dp）：Apple Music 式头部（含 歌手/发行日期/简介）
                    DetailPageHeader(
                        title = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
                        coverData = {
                            (detail.value?.coverUrl?.takeIf { it.isNotEmpty() } ?: album.coverUrl)
                                .takeIf { it.isNotEmpty() }
                        },
                        tag = "ALBUM",
                        coverSharedElementKey = coverSharedElementKey,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        countText = "${if (tracks.value.isNotEmpty()) tracks.value.size else album.songCount} " +
                            stringResource(id = R.string.online_playlists_song_unit),
                        intro = detail.value?.intro,
                        extraLines = {
                            // 歌手名：逐个作者单独成链接（见手机头部同款说明）
                            val authors = albumArtists(detail.value, album.singerName)
                            if (authors.isNotEmpty()) {
                                AlbumArtistLinks(
                                    artists = authors,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) { artist ->
                                    navController.navigate(
                                        UI.artistDetailRoute(
                                            artistId = artist.id.takeIf { it.isNotEmpty() },
                                            artistName = artist.name
                                        )
                                    )
                                }
                            }

                            // 发行日期（在线专辑额外展示，本地 AlbumInfo 无此字段）
                            detail.value?.publishDate?.let { date ->
                                Text(
                                    text = date,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .alpha(0.4f)
                                        .padding(top = 2.dp)
                                )
                            }
                        },
                        onPlayAll = { playAt(0) },
                        onShuffle = {
                            val list = queue.value
                            if (list.isNotEmpty()) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                playAt(list.indices.random())
                            }
                        }
                    )
                } else {
                // 手机：保持旧版居中头部
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 9.5.dp)
                        .padding(horizontal = 18.dp)
                        .statusBarsPadding(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 大封面：与 AlbumInfo/RankDetail 同款 ShadowImage 规范；
                    // 详情封面优先，失败/未加载时用选中专辑封面兜底
                    val coverUrl = detail.value?.coverUrl?.takeIf { it.isNotEmpty() } ?: album.coverUrl
                    val coverModifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(horizontal = 54.5.dp)
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(coverSharedElementKey),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                            } else Modifier
                        )
                    Box(coverModifier) {
                        ShadowImage(
                            modifier = Modifier.fillMaxSize(),
                            dataLambda = { coverUrl.takeIf { it.isNotEmpty() } },
                            contentDescription = null,
                            // 转场中圆角从来源封面值渐变到 7dp，静止恒为 7dp
                            cornerRadius = enterCoverCorner(animatedVisibilityScope, 7.dp),
                            imageQuality = ImageQuality.RAW,
                            shadowType = ShadowType.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 歌手名：合辑/多歌手专辑逐个作者单独成链接（各自带自己的名字/id 进对应
                    // 艺人页）；不能把拼接整串当单歌手去搜索，否则解析失败。
                    val authors = albumArtists(detail.value, album.singerName)
                    if (authors.isNotEmpty()) {
                        AlbumArtistLinks(
                            artists = authors,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        ) { artist ->
                            navController.navigate(
                                UI.artistDetailRoute(
                                    artistId = artist.id.takeIf { it.isNotEmpty() },
                                    artistName = artist.name
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // 数量：优先显示实际加载的歌曲数（与列表一致）；加载前用选中专辑 songCount 兑底
                    val songCountDisplay = if (tracks.value.isNotEmpty()) tracks.value.size else album.songCount
                    Text(
                        text = "$songCountDisplay " + stringResource(id = R.string.online_playlists_song_unit),
                        fontSize = 17.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 23.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // 发行日期（在线专辑额外展示，本地 AlbumInfo 无此字段）
                    detail.value?.publishDate?.let { date ->
                        Text(
                            text = date,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .alpha(0.4f)
                                .padding(top = 2.dp)
                        )
                    }

                    Text(
                        text = "ALBUM",
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .alpha(0.4f)
                            .padding(top = 2.dp)
                    )

                    YosWrapper {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 15.dp)
                        ) {
                            // 播放：全部歌曲进队列，从第一首开始（URL 惰性解析，入队零等待）
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_play),
                                label = stringResource(id = R.string.normal_button_play),
                                modifier = Modifier.weight(1f)
                            ) {
                                playAt(0)
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            // 随机：全部歌曲进队列 + Media3 原生 shuffle，从随机位置开始
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_shuffle),
                                label = stringResource(id = R.string.normal_button_shuffle),
                                modifier = Modifier.weight(1f)
                            ) {
                                val list = queue.value
                                if (list.isNotEmpty()) {
                                    MediaController.mediaControl?.shuffleModeEnabled = true
                                    playAt(list.indices.random())
                                }
                            }
                        }
                    }
                }
                }
            }

            item("Status") {
                OnlineStatusItem(
                    status = status.value,
                    loadingText = stringResource(id = R.string.online_playlist_loading_songs),
                    emptyText = stringResource(id = R.string.online_album_songs_empty)
                )
            }

            item {
                OnlineAlbumDivider()
            }

            itemsIndexed(
                trackItems,
                key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } }
            ) { index, song ->
                key(songKeys.getOrElse(index) { "oob_$index" }) {
                    // 统一歌曲 Item；点击 → 全部歌曲进队列，从被点击歌曲开始播放
                    if (isWideScreen) {
                        DetailSongRowWide(music = KugouRepository.toDisplayMediaItem(song)) {
                            playAt(index)
                        }
                    } else {
                        MusicList(KugouRepository.toDisplayMediaItem(song)) {
                            playAt(index)
                        }
                    }
                }

                key("divider_$index") {
                    if (index < trackItems.size - 1) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 右侧与首末分割线一致留 18dp，任何屏幕都不顶到边缘
                                .padding(start = 88.dp, end = 16.dp)
                                .alpha(0.15f)
                                .height(0.5.dp)
                                .background(Color.Black withNight Color.White)
                        )
                    }
                }
            }

            item {
                OnlineAlbumDivider()
            }

            item("navbar") {
                Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
            }
        }

        // 顶栏：滚动离屏浮现小标题 + 模糊，几何与参考页 TitleBar 一致
        TitleBar(
            title = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
            onBack = { navController.popBackStack() },
            showSmallTitle = showSmallTitle,
            backdrop = titleBackdrop
        )
    }
}

/**
 * 专辑作者列表：优先详情接口的逐项作者 [KugouAlbumDetail.artists]；
 * 缺失时把兜底歌手串按枚举分隔符拆开（合辑会拼成「A、B、C」整串）。
 */
private fun albumArtists(detail: KugouAlbumDetail?, fallbackSinger: String): List<AlbumArtist> {
    val fromDetail = detail?.artists.orEmpty().filter { it.name.isNotBlank() }
    if (fromDetail.isNotEmpty()) return fromDetail
    return fallbackSinger
        .split('、', '，', ',', '；', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { AlbumArtist("", it) }
}

/**
 * 逐作者可点链接：每个作者独立承载点击（各自名字/id 进对应艺人页），
 * 用 FlowRow 在宽度不足时自动换行；分隔符「·」不可点。整体 60% 透明度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumArtistLinks(
    artists: List<AlbumArtist>,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    onClick: (AlbumArtist) -> Unit
) {
    FlowRow(
        modifier = modifier.alpha(0.6f),
        horizontalArrangement = if (textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start
    ) {
        artists.forEachIndexed { index, artist ->
            if (index > 0) {
                Text(" · ", fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text(
                text = artist.name,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    NavGuard.run { onClick(artist) }
                }
            )
        }
    }
}

/**
 * 详情页横向分割线（对齐 AlbumInfo 的 AlbumDivider / RankDetail 的 RankDetailDivider 规范）。
 */
@Composable
private fun OnlineAlbumDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp)
            .alpha(0.2f)
            .height(0.5.dp)
            .background(Color.Black withNight Color.White)
    )
