package yos.music.player.ui.pages.library.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.UI
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.preloadRawCover

/**
 * 我的在线歌单列表页（正式 UI，视觉已归一）。
 *
 * 数据链：KugouRepository.getMyPlaylists() → /user/playlist（Rust 现有路由）。
 * 视觉规范：Title 骨架 + 统一状态行 + MusicList 行高规范（64dp 行 / 52dp 封面）
 * + 统一分割线，与本地歌曲列表一致。
 */

/** 已解析歌单封面持久缓存（listid/gid → url）：「我喜欢」等系统歌单在 /user/playlist
 *  返回里封面字段为空，三级兜底是网络请求，不缓存的话每次进列表页行封面都会先空窗
 *  显示兜底占位图再"弹出"；落盘后进页（含冷启动）即用上次解析结果。 */
private val playlistCoverCache: MMKV by lazy { MMKV.mmkvWithID("kugou_playlist_cover_cache") }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnlinePlaylists(
    navController: NavController,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    val playlists = remember("OnlinePlaylists_list") { mutableStateOf<List<KugouPlaylist>>(emptyList()) }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlinePlaylists_status") { mutableStateOf("loading") }
    val scope = rememberCoroutineScope()

    fun load(autoOpenIndex: Int? = null) {
        if (status.value == "loading") return
        status.value = "loading"
        scope.launch {
            KugouRepository.getMyPlaylists()
                .onSuccess { rawList ->
                    // 先立即填充上次解析出的封面（MMKV 落盘）：「我喜欢」等系统歌单
                    // /user/playlist 不返回封面，进页瞬间行 coverUrl 为空会先闪兜底占位图，
                    // 种子数据消掉空窗；下方后台兜底仍会重跑，结果变化时行内热更新
                    val list = rawList.map { pl ->
                        if (pl.coverUrl.isEmpty()) {
                            pl.copy(coverUrl = playlistCoverCache.decodeString(pl.listid.ifEmpty { pl.gid }).orEmpty())
                        } else pl
                    }
                    playlists.value = list
                    // 空封面歌单兜底（情况 B）：惰性走 /playlist/detail，不阻塞列表渲染；
                    // 详情接口也无封面时再降级取歌单内第一首有封面的歌（情况 C），
                    // 三级都无则保持空（占位图展示，不伪造 URL）
                    scope.launch {
                        rawList.filter { it.coverUrl.isEmpty() }.forEach { pl ->
                            var cover = KugouRepository.resolvePlaylistCover(pl)
                            if (cover.isNullOrEmpty()) {
                                cover = KugouRepository.resolvePlaylistCoverFromTracks(
                                    pl.listid.ifEmpty { pl.gid }
                                )
                            }
                            if (!cover.isNullOrEmpty()) {
                                playlistCoverCache.encode(pl.listid.ifEmpty { pl.gid }, cover)
                                playlists.value = playlists.value.map {
                                    if (it.listid == pl.listid && it.gid == pl.gid) it.copy(coverUrl = cover) else it
                                }
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        status.value = "empty"
                    } else {
                        status.value = "ok"
                        if (autoOpenIndex != null) {
                            val idx = autoOpenIndex.coerceIn(0, list.size - 1)
                            openOnlinePlaylist(
                                PlaylistSelection(
                                    source = PlaylistSelection.Source.User,
                                    id = list[idx].listid.ifEmpty { list[idx].gid },
                                    name = list[idx].name,
                                    cover = list[idx].coverUrl,
                                    songCount = list[idx].songCount
                                )
                            )
                        }
                    }
                }
                .onFailure { e ->
                    playlists.value = emptyList()
                    status.value = "error:${e.message}"
                }
        }
    }

    LaunchedEffect(Unit) {
        status.value = "idle"
        load()
    }

    Title(
        title = stringResource(id = R.string.page_online_playlists_title),
        onBack = {
            navController.popBackStack()
        }
    ) {
        item("Status") {
            OnlineStatusItem(
                status = status.value,
                loadingText = stringResource(id = R.string.online_playlists_loading),
                emptyText = stringResource(id = R.string.online_playlists_empty)
            )
        }

        itemsIndexed(
            playlists.value,
            key = { _, pl -> pl.listid.ifEmpty { pl.gid } }
        ) { index, pl ->
            val context = LocalContext.current
            val playlistId = pl.listid.ifEmpty { pl.gid }
            val coverKey = sharedPlaylistCoverKey(PlaylistSelection.Source.User, playlistId)
            // 圆角保持列表自身的 3.5dp；转场中的圆角渐变由详情端依据 SharedCoverStyle 完成
            // 固定正方形外壳承载 shared element；点击先预加载详情 RAW 原图，避免首帧放大占位图
            val coverModifier = Modifier
                .size(52.dp)
                .then(
                    if (sharedTransitionScope != null && animatedVisibilityScope != null && coverKey != null) {
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState(key = coverKey),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        }
                    } else Modifier
                )
            Row(
                modifier = Modifier
                    .height(64.dp)
                    .fillMaxWidth()
                    .clickable {
                        // 记录来源圆角，详情端转场据此从 3.5dp 渐变到 7dp
                        SharedCoverStyle.lastSourceCorner = 3.5.dp
                        scope.launch {
                            preloadRawCover(context, pl.coverUrl)
                            openOnlinePlaylist(
                                PlaylistSelection(
                                    source = PlaylistSelection.Source.User,
                                    id = playlistId,
                                    name = pl.name,
                                    cover = pl.coverUrl,
                                    songCount = pl.songCount
                                )
                            )
                        }
                    }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(coverModifier) {
                    ShadowImageWithCache(
                        dataLambda = { pl.coverUrl.takeIf { it.isNotEmpty() } },
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 3.5.dp,
                        shadowAlpha = 0f,
                        imageQuality = ImageQuality.LOW
                    )
                }

                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        text = pl.name,
                        modifier = Modifier.padding(bottom = 1.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        lineHeight = 16.sp
                    )
                    Text(
                        text = "${pl.songCount} " + stringResource(id = R.string.online_playlists_song_unit),
                        modifier = Modifier.alpha(0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        lineHeight = 13.sp
                    )
                }
            }

            if (index < playlists.value.size - 1) {
                OnlineListItemDivider()
            }
        }
    }
}
