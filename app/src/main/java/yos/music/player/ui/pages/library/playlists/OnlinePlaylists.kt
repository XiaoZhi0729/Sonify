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
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.CollectedPlaylistsObject
import yos.music.player.data.objects.PlaylistCoverCache
import android.widget.Toast
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
import yos.music.player.ui.navigation.popBackStackSafely

/**
 * 我的在线歌单列表页（正式 UI，视觉已归一）。
 *
 * 数据链：KugouRepository.getAllUserPlaylistEntries() → /user/playlist（API 层已绕 apicache，
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

/**
 * 删除传播挂起存储：删除已提交但云端尚未消失（~5s）期间跨页面/跨冷启存续。
 */
private val playlistDeletePending: MMKV
    get() = MMKV.mmkvWithID("kugou_playlist_pending_del_${KugouAccountState.userid.orEmpty()}")

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

private fun playlistKey(pl: KugouPlaylist): String = pl.listid.ifEmpty { pl.gid }.ifEmpty { pl.sourceGid }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun OnlinePlaylists(
    navController: NavController,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    val context = LocalContext.current
    val accountUserid = KugouAccountState.userid
    val playlists = rememberPageData<List<KugouPlaylist>>("OnlinePlaylists_list_${accountUserid.orEmpty()}") { emptyList() }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember(accountUserid, "OnlinePlaylists_status") { mutableStateOf(if (playlists.value.isEmpty()) "loading" else "ok") }
    val scope = rememberCoroutineScope()
    // 挂起删除（MMKV 落盘，跨页面/冷启存续；load() 合并时自动收敛清除）
    val pendingDel = remember(accountUserid, "OnlinePlaylists_pending_del") { mutableStateOf(readPendingDeletes()) }
    // 「创建中」歌单日志快照（行徽标与点击行为依赖它；load/create/delete 后刷新）
    val journal = remember(accountUserid, "OnlinePlaylists_journal") { mutableStateOf(PendingPlaylistStore.all().filter { it.userid == accountUserid && !accountUserid.isNullOrBlank() }) }

    // 新建歌单弹层
    val showCreateDialog = remember("OnlinePlaylists_create") { mutableStateOf(false) }
    val newPlaylistName = remember("OnlinePlaylists_create_name") { mutableStateOf("") }
    val creating = remember(accountUserid, "OnlinePlaylists_creating") { mutableStateOf(false) }
    // 弹层内错误文案（status 行不适用于弹层场景）
    val createError = remember("OnlinePlaylists_create_error") { mutableStateOf<String?>(null) }
    // 长按待删除的歌单（null = 不弹确认框）
    val deleteTarget = remember("OnlinePlaylists_delete") { mutableStateOf<KugouPlaylist?>(null) }
    val deleting = remember(accountUserid, "OnlinePlaylists_deleting") { mutableStateOf(false) }

    /**
     * [silent] = true：静默刷新——不置 "loading"（不出现加载中）、不清空/闪动列表、
     * 失败也不改状态；成功后仅在数据确有变化时原位更新列表内容（列表 State 走结构相等，
     * 内容未变时赋值不会触发重组）。用于「返回本页时后台对账」，避免转场中途出现
     * 「加载中」与列表整体消失/封面抖动。
     */
    fun load(autoOpenIndex: Int? = null, silent: Boolean = false) {
        if (accountUserid.isNullOrBlank()) {
            status.value = "empty"
            return
        }
        if (!silent) {
            if (status.value == "loading") return
            status.value = "loading"
        }
        scope.launch {
            if (accountUserid.isNullOrBlank() || accountUserid != KugouAccountState.userid) return@launch
            KugouRepository.getAllUserPlaylistEntries()
                .onSuccess { rawList ->
                    if (accountUserid != KugouAccountState.userid) return@onSuccess
                    // 空的系统「默认收藏」歌单（is_def==1 且 count==0）默认隐藏：它是酷狗
                    // 账号级系统歌单（与「我喜欢」is_def==2 成对，注册时服务端自动创建），
                    // 空壳显示出来只会造成困惑；一旦有歌（count>0）照常出现。
                    // 只按 is_def 判定，勿用名字匹配（酷狗可能返回同名普通歌单）。
                    val visibleRaw = CollectedPlaylistsObject.mergeEntries(rawList).filterNot { it.source == 2 || (it.isDef == 1 && it.songCount == 0) }
                    // 首帧封面：封面规则 =「歌单内最新添加歌曲的封面」，除非用户自定义过封面
                    // （is_custom_pic=1，服务端 pic 就是用户选的图）或这是别人/收藏的歌单。
                    // 命中上次算好的缓存就先用它（零网络），否则先用服务端 pic 顶上，
                    // 避免行封面先空窗再"弹出"；随后后台重算，结果变化时行内热更新。
                    var list = visibleRaw.map { pl ->
                        val cached = PlaylistCoverCache.read(pl)
                        if (cached != null) pl.copy(coverUrl = cached) else pl
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
                    journal.value = PendingPlaylistStore.all().filter { it.userid == accountUserid && !accountUserid.isNullOrBlank() }
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

                    // 封面规整（后台）：自己创建的歌单（含「我喜欢」「默认收藏」）封面 =
                    // 歌单内最新添加歌曲的封面，除非用户自定义过封面。缓存按 listVer+count
                    // 校验，只有歌单内容真变了才重算；算不出来的保留服务端 pic，不清空。
                    scope.launch {
                        visibleRaw.filter { it.usesTrackCover && !it.isCustomPic && it.songCount > 0 }
                            .forEach { pl ->
                                if (accountUserid != KugouAccountState.userid) return@forEach
                                val cover = PlaylistCoverCache.ensure(pl) ?: return@forEach
                                if (accountUserid != KugouAccountState.userid) return@forEach
                                val key = playlistKey(pl)
                                playlists.value = playlists.value.map {
                                    if (playlistKey(it) == key && it.coverUrl != cover) {
                                        it.copy(coverUrl = cover)
                                    } else it
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
                                    id = playlistKey(list[idx]),
                                    name = list[idx].name,
                                    cover = list[idx].coverUrl,
                                    songCount = list[idx].songCount,
                                    sourceGid = list[idx].sourceGid.takeIf { it.isNotBlank() },
                                    canEditTracks = list[idx].canEditTracks || list[idx].listid.startsWith("local_"),
                                    ownerUserid = if (list[idx].isCollectedPlaylist) list[idx].listCreateUserid else accountUserid
                                )
                            )
                        }
                    }
                }
                .onFailure { e ->
                    // 静默刷新失败保留现有内容与状态，不打断界面
                    if (!silent) status.value = "error:${e.message}"
                }
        }
    }

    /** 变更成功后的延迟对账：给酷狗云端 ~5-8 秒传播窗口，把挂起行替换为真实行。 */
    fun scheduleReconcile() {
        scope.launch {
            delay(8_000L)
            if (accountUserid == KugouAccountState.userid && status.value != "loading") {
                status.value = "idle"
                load()
            }
        }
    }

    fun createPlaylist() {
        val name = newPlaylistName.value.trim()
        if (name.isEmpty() || creating.value || accountUserid == null || accountUserid != KugouAccountState.userid) return
        creating.value = true
        scope.launch {
            KugouRepository.createMyPlaylist(name)
                .onSuccess { created ->
                    if (accountUserid != KugouAccountState.userid) return@onSuccess
                    showCreateDialog.value = false
                    newPlaylistName.value = ""
                    // 登记挂起日志：行立即可点击进详情页（挂起模式），加删歌自动暂存补发
                    val entry = PendingPlaylistStore.registerCreated(
                        name = created.name.ifEmpty { name }
                    )
                    journal.value = PendingPlaylistStore.all().filter { it.userid == accountUserid && !accountUserid.isNullOrBlank() }
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
        if (deleting.value || accountUserid != KugouAccountState.userid) return
        if (pl.isCollectedPlaylist && validPlaylistCollectionGid(pl.sourceGid) == null) {
            Toast.makeText(context, R.string.playlist_collect_missing_gid, Toast.LENGTH_SHORT).show()
            return
        }
        deleting.value = true
        scope.launch {
            if (pl.isCollectedPlaylist) {
                CollectedPlaylistsObject.uncollect(pl)
                    .onSuccess { playlists.value = playlists.value.filterNot { it.isCollectedPlaylist && it.sourceGid == pl.sourceGid } }
                    .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.playlist_collect_failed), Toast.LENGTH_SHORT).show() }
            } else if (pl.listid.startsWith("local_")) {
                // 挂起条目：撤日志 + 本地移除即可（若云端最终建成了，下次刷新会以普通行出现）
                PendingPlaylistStore.delete(pl.listid)
                journal.value = PendingPlaylistStore.all().filter { it.userid == accountUserid && !accountUserid.isNullOrBlank() }
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

    // 歌单/收藏等云端变化（含返回本页时重新进入组合）触发对账：
    // 已有数据 → 静默刷新（不置 loading、不清空列表，仅在有变化时原位更新内容，
    // 避免返回时出现「加载中」与列表整体消失/封面抖动）；无数据（首次进入）→ 常规加载。
    LaunchedEffect(accountUserid, KugouSyncCoordinator.playlistsRevision.value) {
        if (accountUserid == null) {
            status.value = "empty"
            return@LaunchedEffect
        }
        if (playlists.value.isEmpty()) {
            status.value = "idle"
            load()
        } else {
            load(silent = true)
        }
    }

    LaunchedEffect(accountUserid) {
        showCreateDialog.value = false
        deleteTarget.value = null
        pendingDel.value = readPendingDeletes()
        journal.value = PendingPlaylistStore.all().filter { it.userid == accountUserid && !accountUserid.isNullOrBlank() }
        if (accountUserid != null) CollectedPlaylistsObject.ensureLoaded()
    }
    // Read the observable entries here so optimistic changes update an already visible list.
    val collectionEntries = CollectedPlaylistsObject.entries.value
    val displayPlaylists = remember(playlists.value, collectionEntries) {
        CollectedPlaylistsObject.mergeEntries(playlists.value)
            .filterNot { it.source == 2 || (it.isDef == 1 && it.songCount == 0) }
    }

    Title(
        title = stringResource(id = R.string.page_online_playlists_title),
        topRightIcon = Icons.Filled.Add,
        onTopRightIcon = {
            if (accountUserid == null) navController.navigate(UI.Settings.KugouLogin)
            else {
                createError.value = null
                showCreateDialog.value = true
            }
        },
        onRefresh = {
            CollectedPlaylistsObject.ensureLoaded(force = true)
            status.value = "idle"
            load()
        },
        refreshing = status.value == "loading",
        onBack = {
            navController.popBackStackSafely()
        }
    ) {
        item("Status") {
            OnlineStatusItem(
                status = if (displayPlaylists.isNotEmpty() && status.value == "empty") "ok" else status.value,
                loadingText = stringResource(id = R.string.online_playlists_loading),
                emptyText = stringResource(id = R.string.online_playlists_empty)
            )
        }

        itemsIndexed(
            displayPlaylists,
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
                                        songCount = pl.songCount,
                                        sourceGid = pl.sourceGid.takeIf { it.isNotBlank() },
                                        canEditTracks = pl.canEditTracks || isLocalPending,
                                        ownerUserid = if (pl.isCollectedPlaylist) pl.listCreateUserid else accountUserid
                                    )
                                )
                            }
                        },
                        // 长按删除：系统歌单（is_def != 0）不响应
                        onLongClick = { if (pl.isCollectedPlaylist || (pl.isDef == 0 && pl.canEditTracks)) deleteTarget.value = pl }
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

            if (index < displayPlaylists.size - 1) {
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
            title = stringResource(id = if (target.isCollectedPlaylist) R.string.playlist_collect_remove else R.string.online_playlists_delete),
            message = stringResource(id = if (target.isCollectedPlaylist) R.string.playlist_uncollect_confirm else R.string.online_playlists_delete_confirm, target.name),
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
