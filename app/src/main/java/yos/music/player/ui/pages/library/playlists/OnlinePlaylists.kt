package yos.music.player.ui.pages.library.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PendingPlaylistStore
import yos.music.player.ui.UI
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.navigation.rememberPageData
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.SonifyDialogTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.preloadRawCover

/**
 * 我的在线歌单列表页（正式 UI，视觉已归一）。
 *
 * 数据链：KugouRepository.getMyPlaylists() → /user/playlist（API 层已绕 apicache，
 * 否则新建/删除后 2 分钟内刷新拿到的是旧缓存）。
 *
 * 变更一致性（真机实测酷狗 cloudlist 有 ~5-8 秒传播延迟，官方客户端同样如此）：
 * 1. 创建成功 → [PendingPlaylistStore] 登记「创建中」条目并立即显示可点击的行，
 *    详情页（挂起模式）可马上加歌删歌，绑定真实 listid 后自动补发；
 * 2. 删除成功 → 本地立即移除 + MMKV 挂起（90 秒 TTL），退出重进后依然生效，
 *    load() 合并时「服务端已消失的挂起项」自动清除；
 * 3. 每次变更成功后延迟 8 秒再对账刷新一次。
 *
 * 视觉规范：Title 骨架 + 统一状态行 + MusicList 行高规范（64dp 行 / 52dp 封面）
 * + 统一分割线，与本地歌曲列表一致。
 */

/** 已解析歌单封面持久缓存（listid/gid → url）：「我喜欢」等系统歌单在 /user/playlist
 *  返回里封面字段为空，三级兜底是网络请求，不缓存的话每次进列表页行封面都会先空窗
 *  显示兜底占位图再"弹出"；落盘后进页（含冷启动）即用上次解析结果。 */
private val playlistCoverCache: MMKV by lazy { MMKV.mmkvWithID("kugou_playlist_cover_cache") }

/** 删除传播挂起存储：删除已提交但云端尚未消失（~5s）期间跨页面/跨冷启存续。 */
private val playlistDeletePending: MMKV by lazy { MMKV.mmkvWithID("kugou_playlist_pending_del") }

/** 挂起删除安全 TTL：超过即视为云端已收敛（正常 8 秒对账就会清掉）。 */
private const val PENDING_DEL_TTL_MS = 90_000L

/** 读取挂起删除：(待删除 listid/gid 集, 待删除歌单名集)。超 TTL 的记录丢弃。 */
private fun readPendingDeletes(): Pair<Set<String>, Set<String>> {
    val now = System.currentTimeMillis()
    val ids = mutableSetOf<String>()
    val names = mutableSetOf<String>()
    listOf("ids", "names").forEach { key ->
        playlistDeletePending.decodeString(key)?.split("\n")?.forEach { rec ->
            val parts = rec.split("|")
            if (parts.size < 2) return@forEach
            val ts = parts.last().toLongOrNull() ?: return@forEach
            if (now - ts > PENDING_DEL_TTL_MS) return@forEach
            (if (key == "ids") ids else names) += parts.first()
        }
    }
    return ids to names
}

private fun writePendingDeletes(ids: Set<String>, names: Set<String>) {
    val now = System.currentTimeMillis()
    playlistDeletePending.encode("ids", ids.joinToString("\n") { "$it|$now" })
    playlistDeletePending.encode("names", names.joinToString("\n") { "$it|$now" })
}

private fun playlistKey(pl: KugouPlaylist): String = pl.listid.ifEmpty { pl.gid }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun OnlinePlaylists(
    navController: NavController,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    val playlists = rememberPageData<List<KugouPlaylist>>("OnlinePlaylists_list") { emptyList() }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlinePlaylists_status") { mutableStateOf(if (playlists.value.isEmpty()) "loading" else "ok") }
    val scope = rememberCoroutineScope()
    // 挂起删除（MMKV 落盘，跨页面/冷启存续；load() 合并时自动收敛清除）
    val pendingDel = remember("OnlinePlaylists_pending_del") { mutableStateOf(readPendingDeletes()) }
    // 「创建中」歌单日志快照（行徽标与点击行为依赖它；load/create/delete 后刷新）
    val journal = remember("OnlinePlaylists_journal") { mutableStateOf(PendingPlaylistStore.all()) }

    // 新建歌单弹层
    val showCreateDialog = remember("OnlinePlaylists_create") { mutableStateOf(false) }
    val newPlaylistName = remember("OnlinePlaylists_create_name") { mutableStateOf("") }
    val creating = remember("OnlinePlaylists_creating") { mutableStateOf(false) }
    // 弹层内错误文案（status 行不适用于弹层场景）
    val createError = remember("OnlinePlaylists_create_error") { mutableStateOf<String?>(null) }
    // 长按待删除的歌单（null = 不弹确认框）
    val deleteTarget = remember("OnlinePlaylists_delete") { mutableStateOf<KugouPlaylist?>(null) }
    val deleting = remember("OnlinePlaylists_deleting") { mutableStateOf(false) }

    fun load(autoOpenIndex: Int? = null) {
        if (status.value == "loading") return
        status.value = "loading"
        scope.launch {
            KugouRepository.getMyPlaylists()
                .onSuccess { rawList ->
                    // 先立即填充上次解析出的封面（MMKV 落盘）：「我喜欢」等系统歌单
                    // /user/playlist 不返回封面，进页瞬间行 coverUrl 为空会先闪兜底占位图，
                    // 种子数据消掉空窗；下方后台兜底仍会重跑，结果变化时行内热更新
                    var list = rawList.map { pl ->
                        if (pl.coverUrl.isEmpty()) {
                            pl.copy(coverUrl = playlistCoverCache.decodeString(pl.listid.ifEmpty { pl.gid }).orEmpty())
                        } else pl
                    }
                    // ---- 挂起删除合并 ----
                    // id/名字仍在服务端列表 → 继续过滤；已消失 → 清除挂起
                    val (delIds, delNames) = pendingDel.value
                    val keepDelIds = delIds.filter { id -> list.any { playlistKey(it) == id } }.toSet()
                    val keepDelNames = delNames.filter { name -> list.any { it.name == name } }.toSet()
                    list = list.filter { pl ->
                        playlistKey(pl) !in keepDelIds && pl.name !in keepDelNames
                    }
                    pendingDel.value = keepDelIds to keepDelNames
                    writePendingDeletes(keepDelIds, keepDelNames)

                    // ---- 「创建中」歌单合并（journal 为单一事实源） ----
                    // 名字已出现在服务端 → 后台绑定真实 listid（并补发详情页暂存的加删歌）；
                    // 未出现（传播中/已失败）→ 追加可点击的挂起行
                    journal.value = PendingPlaylistStore.all()
                    journal.value.filter { !it.isBound }.forEach { entry ->
                        if (list.any { it.name == entry.name }) {
                            scope.launch { PendingPlaylistStore.tryBind(entry) }
                        } else {
                            list = list + KugouPlaylist(
                                listid = entry.localId,
                                gid = "",
                                name = entry.name,
                                coverUrl = "",
                                songCount = 0,
                                isDef = 0
                            )
                        }
                    }

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
                    playlists.value = list
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
                    status.value = "error:${e.message}"
                }
        }
    }

    /** 变更成功后的延迟对账：给酷狗云端 ~5-8 秒传播窗口，把挂起行替换为真实行。 */
    fun scheduleReconcile() {
        scope.launch {
            delay(8_000L)
            if (status.value != "loading") {
                status.value = "idle"
                load()
            }
        }
    }

    fun createPlaylist() {
        val name = newPlaylistName.value.trim()
        if (name.isEmpty() || creating.value) return
        creating.value = true
        scope.launch {
            KugouRepository.createMyPlaylist(name)
                .onSuccess { created ->
                    showCreateDialog.value = false
                    newPlaylistName.value = ""
                    // 登记挂起日志：行立即可点击进详情页（挂起模式），加删歌自动暂存补发
                    val entry = PendingPlaylistStore.registerCreated(
                        name = created.name.ifEmpty { name }
                    )
                    journal.value = PendingPlaylistStore.all()
                    playlists.value = playlists.value + KugouPlaylist(
                        listid = entry.localId,
                        gid = "",
                        name = entry.name,
                        coverUrl = "",
                        songCount = 0,
                        isDef = 0
                    )
                    scheduleReconcile()
                    KugouSyncCoordinator.notifyPlaylistsChanged()
                }
                .onFailure { e ->
                    // 失败保留弹层与输入，便于改名词典重试；错误经 status 不适合弹层场景，直接对话框文案
                    createError.value = e.message ?: e.javaClass.simpleName
                }
            creating.value = false
        }
    }

    fun deletePlaylist(pl: KugouPlaylist) {
        if (deleting.value) return
        deleting.value = true
        scope.launch {
            if (pl.listid.startsWith("local_")) {
                // 挂起条目：撤日志 + 本地移除即可（若云端最终建成了，下次刷新会以普通行出现）
                PendingPlaylistStore.delete(pl.listid)
                journal.value = PendingPlaylistStore.all()
                playlists.value = playlists.value.filter { it !== pl }
            } else {
                KugouRepository.deleteMyPlaylist(pl)
                    .onSuccess {
                        // 乐观移除 + 记挂起：云端传播完成前 reload 仍会返回旧行，靠合并过滤
                        val key = playlistKey(pl)
                        pendingDel.value = (pendingDel.value.first + key) to (pendingDel.value.second + pl.name)
                        writePendingDeletes(pendingDel.value.first, pendingDel.value.second)
                        playlists.value = playlists.value.filter { it !== pl }
                        scheduleReconcile()
                        KugouSyncCoordinator.notifyPlaylistsChanged()
                    }
                    .onFailure { e ->
                        status.value = "error:${e.message}"
                    }
            }
            deleting.value = false
            deleteTarget.value = null
        }
    }

    LaunchedEffect(KugouSyncCoordinator.playlistsRevision.value) {
        status.value = "idle"
        load()
    }

    Title(
        title = stringResource(id = R.string.page_online_playlists_title),
        topRightIcon = Icons.Filled.Add,
        onTopRightIcon = {
            createError.value = null
            showCreateDialog.value = true
        },
        onRefresh = {
            status.value = "idle"
            load()
        },
        refreshing = status.value == "loading",
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
            key = { _, pl -> playlistKey(pl).ifEmpty { pl.name } }
        ) { index, pl ->
            val context = LocalContext.current
            val playlistId = playlistKey(pl)
            val isLocalPending = pl.listid.startsWith("local_")
            val pendingEntry = if (isLocalPending) journal.value.firstOrNull { it.localId == pl.listid } else null
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
                    .combinedClickable(
                        onClick = {
                            // 挂起行（local_ id）也可进入详情页（挂起模式，加删歌暂存补发）
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
                        },
                        // 长按删除：系统歌单（is_def != 0）不响应
                        onLongClick = { if (pl.isDef == 0) deleteTarget.value = pl }
                    )
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
                        text = when {
                            pendingEntry != null && pendingEntry.failed ->
                                stringResource(id = R.string.online_playlists_pending_failed)
                            pendingEntry != null ->
                                stringResource(id = R.string.online_playlists_pending_creating)
                            else -> "${pl.songCount} " + stringResource(id = R.string.online_playlists_song_unit)
                        },
                        color = if (pendingEntry?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = if (pendingEntry != null) Modifier.alpha(0.7f) else Modifier.alpha(0.5f),
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

    // 新建歌单弹层
    if (showCreateDialog.value) {
        SonifyDialog(
            onDismissRequest = { if (!creating.value) showCreateDialog.value = false },
            title = stringResource(id = R.string.online_playlists_create),
            content = {
                Column {
                    SonifyDialogTextField(
                        value = newPlaylistName.value,
                        onValueChange = { newPlaylistName.value = it },
                        placeholder = stringResource(id = R.string.online_playlists_create_hint),
                        enabled = !creating.value,
                        modifier = Modifier.fillMaxWidth()
                    )
                    createError.value?.let { err ->
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
            onPositive = { createPlaylist() },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { showCreateDialog.value = false },
            positiveEnabled = !creating.value && newPlaylistName.value.isNotBlank(),
            negativeEnabled = !creating.value,
            dismissEnabled = !creating.value,
            closeOnPositive = false
        )
    }

    // 删除确认弹层（长按歌单触发）
    deleteTarget.value?.let { target ->
        SonifyDialog(
            onDismissRequest = { if (!deleting.value) deleteTarget.value = null },
            title = stringResource(id = R.string.online_playlists_delete),
            message = stringResource(id = R.string.online_playlists_delete_confirm, target.name),
            positiveContent = stringResource(id = R.string.common_ok),
            onPositive = { deletePlaylist(target) },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { deleteTarget.value = null },
            positiveEnabled = !deleting.value,
            negativeEnabled = !deleting.value,
            dismissEnabled = !deleting.value,
            destructive = true,
            closeOnPositive = false
        )
    }
}
