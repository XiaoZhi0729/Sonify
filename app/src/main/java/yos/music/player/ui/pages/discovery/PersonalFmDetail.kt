package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 私人FM 页（主页「私人电台」入口卡进入）。
 *
 * 数据链：GET /personal/fm（需 userid+token，authHeader() cookie 带入；绕 apicache 保证每次新鲜）。
 * 上游接口无分页参数、每批固定下发 ~5 首（酷狗电台协议如此，官方/上游 Flutter 也是靠末首
 * hash 作游标边播边补货）。因此补货节奏完全由本地决定，本页策略：
 *   - 首次进入补到 [FM_BUFFER_TARGET]（约 20 首，逐批带末首 hash 游标保证连续性）；
 *   - 视口末尾之后剩余不足 [FM_PREFETCH_REMAINING] 项即预热，一次**连续补多批**直到
 *     视口后积压到 [FM_BUFFER_TARGET] 项——不能"触发一次只加一批"，否则列表会每 5 首停顿一次；
 *   - 哈希去重，空批/整批重复即认为上游暂无更多；
 *   - 「换一批」重置列表重新补货。
 * 红心/跳过上报（action/hash/songid/playtime）依赖播放进度回调，留待后续批次
 * （KugouApiService.getPersonalFm 参数已预留）。
 */

/** 视口末尾之后剩余不足这么多项就提前补货（约一屏，留足网络往返时间）。 */
private const val FM_PREFETCH_REMAINING = 10

/** 单次补货目标：视口后至少积压这么多项（约 2~3 屏），把补货从「每 5 首一次」拉长到「每 20 首一次」。 */
private const val FM_BUFFER_TARGET = 20

/** 单次补货累计上限，防御上游无去重地无限返回。 */
private const val FM_TOPUP_CAP = 40

@Composable
fun PersonalFmDetail(navController: NavController) {
    val songs = remember { mutableStateOf<List<KugouNewSong>>(emptyList()) }
    val loading = remember { mutableStateOf(true) }
    // 防重入守卫与 UI 态分离：loading 初始为 true（首屏转圈），若直接以 loading 判重会拦掉首拉
    val inFlight = remember { mutableStateOf(false) }
    val loadError = remember { mutableStateOf<String?>(null) }
    // 追加补货中（与首拉 loading 分开，页脚单独转圈）
    val appending = remember { mutableStateOf(false) }
    // 空批/整批重复 → 上游暂无更多可补
    val endReached = remember { mutableStateOf(false) }
    // 补货游标：上一批末首歌 hash（对齐上游 FmRefill._fetch 的 cursor 语义）
    val cursorHash = remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // FM 补货批次间可能拿到重复 FileHash，item key 按出现序号唯一化。
    // items 与 keys 派生自同一次读取（约定见 LazyItemKeys.kt）
    val songItems = songs.value
    val songKeys = remember(songItems) { lazyItemKeys(songItems) { it.hash } }
    // 首拉全部落空且无异常时的空态文案（协程内不能用 stringResource，提前解析）
    val fmEmptyText = stringResource(id = R.string.personal_fm_empty)

    fun load() {
        if (inFlight.value) return
        inFlight.value = true
        loading.value = true
        loadError.value = null
        endReached.value = false
        songs.value = emptyList()
        cursorHash.value = ""
        scope.launch {
            // 首拉补到 FM_BUFFER_TARGET（约 20 首）：与滚动补货同一目标，
            // 避免进入后首屏就再次触发补货造成重复请求。任一批为空/无新歌即停。
            var firstError: String? = null
            var attempts = 0
            while (!endReached.value && songs.value.size < FM_BUFFER_TARGET && attempts < FM_TOPUP_CAP) {
                attempts++
                val result = KugouRepository.getPersonalFmSongs(hash = cursorHash.value)
                val fresh = result.getOrDefault(emptyList())
                val known = songs.value.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    if (songs.value.isEmpty()) {
                        firstError = result.exceptionOrNull()?.message ?: fmEmptyText
                    } else {
                        endReached.value = true
                    }
                    break
                }
                songs.value = songs.value + batch
                cursorHash.value = batch.last().hash
            }
            loadError.value = firstError
            inFlight.value = false
            loading.value = false
        }
    }

    /**
     * 补货：上游每批仅 ~5 首，这里连续拉多批，直到视口后积压 >= [FM_BUFFER_TARGET]
     * 或上游无更多（endReached）或达 [FM_TOPUP_CAP] 为止。
     * [remainingAtTrigger] 为触发时视口末尾之后的剩余项数，用于判断还差多少。
     */
    fun appendMore(remainingAtTrigger: Int) {
        if (inFlight.value || appending.value || endReached.value) return
        appending.value = true
        scope.launch {
            var remaining = remainingAtTrigger
            var added = 0
            while (!endReached.value && remaining < FM_BUFFER_TARGET && added < FM_TOPUP_CAP) {
                val fresh = KugouRepository.getPersonalFmSongs(hash = cursorHash.value).getOrDefault(emptyList())
                val known = songs.value.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    endReached.value = true
                    break
                }
                songs.value = songs.value + batch
                cursorHash.value = batch.last().hash
                added += batch.size
                remaining += batch.size
            }
            appending.value = false
        }
    }

    LaunchedEffect(Unit) { load() }

    // 滚动补货预热：视口末尾之后剩余项数低于阈值即触发（不是「每触发一次只加一批」）
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // 列表尾部下方还剩多少项（末尾为 0）；比最后可见项索引更稳，无需关心头部槽位
            info.totalItemsCount - 1 - lastVisible
        }.distinctUntilChanged().collect { remaining ->
            if (songs.value.isNotEmpty() && remaining < FM_PREFETCH_REMAINING) {
                appendMore(remaining)
            }
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

    Title(
        title = stringResource(id = R.string.home_personal_fm_title),
        subTitle = stringResource(id = R.string.home_personal_fm_subtitle),
        listState = listState,
        onBack = { navController.popBackStack() }
    ) {
        // 不做硬登录门控：真机实测未登录（userid=0）上游也返回推荐歌曲，
        // 能拉到就展示；仅空态时按登录态给出引导文案。
        if (songs.value.isEmpty()) {
            item("Status") {
                val text = when {
                    loadError.value != null -> loadError.value.orEmpty()
                    loading.value -> stringResource(id = R.string.online_playlists_loading)
                    KugouAccountState.isLoggedIn -> stringResource(id = R.string.personal_fm_empty)
                    else -> stringResource(id = R.string.personal_fm_login_hint)
                }
                Text(
                    text = text,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
            if (loadError.value != null) {
                item("Retry") {
                    Text(
                        text = stringResource(id = R.string.load_more_failed),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 22.dp, vertical = 4.dp)
                            .clickable { load() }
                    )
                }
            }
            return@Title
        }

        item("Actions") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 15.dp)
                    .padding(horizontal = 22.dp)
            ) {
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_play),
                    label = stringResource(id = R.string.normal_button_play),
                    modifier = Modifier.weight(1f)
                ) {
                    playAt(0)
                }
                Spacer(modifier = Modifier.width(15.dp))
                NormalButton(
                    icon = painterResource(id = R.drawable.ic_clock_cycle),
                    label = stringResource(id = R.string.personal_fm_refresh),
                    modifier = Modifier.weight(1f)
                ) {
                    load()
                }
            }
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

        itemsIndexed(
            songItems,
            key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } }
        ) { index, song ->
            key(songKeys.getOrElse(index) { "oob_$index" }) {
                MusicList(KugouRepository.toDisplayMediaItem(song)) {
                    playAt(index)
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

        if (appending.value) {
            item("AppendFooter") {
                Text(
                    text = stringResource(id = R.string.online_playlists_loading),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .alpha(0.5f)
                )
            }
        }
    }
}
