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
import yos.music.player.data.objects.PersonalFmObject
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
    // FM 只有一份队列：状态与补货全部来自 PersonalFmObject 共享单例（与音乐画廊同源）。
    // 任何一端补货/换一批，画廊、播放页、本页三端同步；本页播放时以 fmQueue=true 建队，
    // 播放页换曲钩子会对活动队列边播边补。
    val songs = PersonalFmObject.songs
    val loading = PersonalFmObject.loading
    val loadError = PersonalFmObject.loadError
    val appending = PersonalFmObject.appending
    val endReached = PersonalFmObject.endReached
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // FM 补货批次间可能拿到重复 FileHash，item key 按出现序号唯一化。
    // items 与 keys 派生自同一次读取（约定见 LazyItemKeys.kt）
    val songItems = songs.value
    val songKeys = remember(songItems) { lazyItemKeys(songItems) { it.hash } }

    fun load() = PersonalFmObject.load()

    LaunchedEffect(Unit) { PersonalFmObject.ensureLoaded() }

    // 滚动补货预热：视口末尾之后剩余项数低于阈值即触发（共享对象内部连续补多批）
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount - 1 - lastVisible
        }.distinctUntilChanged().collect { remaining ->
            if (songs.value.isNotEmpty() && remaining < FM_PREFETCH_REMAINING) {
                PersonalFmObject.ensureQueueAhead(
                    (listState.layoutInfo.totalItemsCount - 1 - remaining).coerceAtLeast(0)
                )
            }
        }
    }

    fun playAt(index: Int) {
        val list = songs.value
        if (index !in list.indices) return
        val queue = list.map { KugouRepository.toQueueMediaItem(it) }
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(queue[index], queue, fmQueue = true)
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
