package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.google.accompanist.insets.navigationBarsHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.RankObject
import yos.music.player.data.repositories.KugouRankSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
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
 * 榜单详情页（结构对齐 OnlinePlaylistDetail / AlbumInfo 详情模板）。
 *
 * 数据链：KugouRepository.getRankSongs(rankId) → GET /rank/audio 分页拉全量
 * （Rust rank.rs 现有路由，零修改；Rust 已把响应扁平化，duration 实测秒）。
 * 播放链（整列表）：全部歌曲映射为队列用 YosMediaItem（uri 为占位符，真实 URL
 * 由播放器惰性解析）→ 现有 MediaController.prepare(clicked, 完整列表) → Media3 队列。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RankDetail(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val rank = RankObject.getSelected()

    if (rank == null) {
        // 边界：无选中榜单（如进程重建后 holder 丢失）
        Title(
            title = stringResource(id = R.string.discovery_rank_title),
            onBack = { navController.popBackStack() }
        ) {
            item("NoRank") {
                // 原版列表空态规范（18sp / α0.6）
                Text(
                    text = stringResource(id = R.string.rank_detail_missing),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
        }
        return
    }

    val tracks = remember("RankDetail_tracks") {
        mutableStateOf<List<KugouRankSong>>(emptyList())
    }
    // 酷狗榜单可能返回重复 FileHash，item key 按出现序号唯一化。
    // items 与 keys 派生自同一次读取（约定见 LazyItemKeys.kt）
    val trackItems = tracks.value
    val songKeys = remember(trackItems) { lazyItemKeys(trackItems) { it.hash } }
    // 与 tracks 同步的队列映射（占位符 URI，入队零网络请求）
    val queue = remember("RankDetail_queue") {
        mutableStateOf<List<YosMediaItem>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("RankDetail_status") { mutableStateOf("loading") }
    val scope = rememberCoroutineScope()

    fun loadSongs() {
        status.value = "loading"
        scope.launch {
            KugouRepository.getRankSongs(rank.rankId)
                .onSuccess { list ->
                    tracks.value = list
                    queue.value = list.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (list.isEmpty()) "empty" else "ok"
                    android.util.Log.d("RankDetail", "rankId=${rank.rankId} rankName=${rank.name} rank.songCount=${rank.songCount} parsed=${list.size}")
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
        loadSongs()
    }

    // 平板（≥600dp）启用 Apple Music 式头部与三列歌曲行；手机保持旧版布局
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600

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
            item("RankHeader") {
                if (isWideScreen) {
                    // 平板（≥600dp）：Apple Music 式头部（封面左上/信息右侧/按钮沉底）
                    DetailPageHeader(
                        title = rank.name,
                        coverData = { rank.coverUrl.takeIf { it.isNotEmpty() } },
                        tag = "RANK",
                        coverSharedElementKey = "rank/${rank.rankId}",
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        countText = "${if (tracks.value.isNotEmpty()) tracks.value.size else rank.songCount} " +
                            stringResource(id = R.string.online_playlists_song_unit),
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
                    // 大封面：固定正方形外壳承载 shared element；ShadowImage 只在外壳内部绘制
                    val coverModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 54.5.dp)
                        .aspectRatio(1f)
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(
                                            key = "rank/${rank.rankId}"
                                        ),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                }
                            } else Modifier
                        )
                    Box(coverModifier) {
                        ShadowImage(
                            modifier = Modifier.fillMaxSize(),
                            dataLambda = { rank.coverUrl.takeIf { it.isNotEmpty() } },
                            contentDescription = null,
                            // 转场中圆角从来源封面值渐变到 7dp，静止恒为 7dp
                            cornerRadius = enterCoverCorner(animatedVisibilityScope, 7.dp),
                            imageQuality = ImageQuality.RAW,
                            shadowType = ShadowType.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = rank.name,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // 数量：优先显示实际加载的歌曲数（与列表一致）；
                    // 加载前用 /rank/list 的 songCount 兑底（含 extra.resp.all_total）
                    val songCountDisplay = if (tracks.value.isNotEmpty()) tracks.value.size else rank.songCount
                    Text(
                        text = "$songCountDisplay " + stringResource(id = R.string.online_playlists_song_unit),
                        fontSize = 17.5.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 23.5.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "RANK",
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
                    emptyText = stringResource(id = R.string.rank_songs_empty)
                )
            }

            item {
                RankDetailDivider()
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
                                // 右侧与首末分割线一致留 16dp，任何屏幕都不顶到边缘
                                .padding(start = 88.dp, end = 16.dp)
                                .alpha(0.15f)
                                .height(0.5.dp)
                                .background(Color.Black withNight Color.White)
                        )
                    }
                }
            }

            item {
                RankDetailDivider()
            }

            item("navbar") {
                Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
            }
        }

        // 顶栏：滚动离屏浮现小标题 + 模糊，几何与参考页 TitleBar 一致
        TitleBar(
            title = rank.name,
            onBack = { navController.popBackStack() },
            showSmallTitle = showSmallTitle,
            backdrop = titleBackdrop
        )
    }
}

/**
 * 详情页横向分割线（对齐 AlbumInfo 的 AlbumDivider 规范）。
 */
@Composable
private fun RankDetailDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp)
            .alpha(0.2f)
            .height(0.5.dp)
            .background(Color.Black withNight Color.White)
    )
