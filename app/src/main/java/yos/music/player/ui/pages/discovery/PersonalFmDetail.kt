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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 私人FM 页（主页「私人电台」入口卡进入，需登录）。
 *
 * 数据链：GET /personal/fm（需 userid+token，authHeader() cookie 带入；绕 apicache 保证每次新鲜）。
 * 简版交互：进入拉一批歌 → 「播放」从第一首播 / 点击任意歌曲以该列表入队 / 「换一批」重新拉取。
 * 上游的黑胶动画、队列剩 N 首自动补货、红心/跳过上报（action/hash/songid/playtime）
 * 依赖播放进度回调，留待后续批次接入（KugouApiService.getPersonalFm 参数已预留）。
 */
@Composable
fun PersonalFmDetail(navController: NavController) {
    val songs = remember { mutableStateOf<List<KugouNewSong>>(emptyList()) }
    val loading = remember { mutableStateOf(true) }
    // 防重入守卫与 UI 态分离：loading 初始为 true（首屏转圈），若直接以 loading 判重会拦掉首拉
    val inFlight = remember { mutableStateOf(false) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (inFlight.value) return
        inFlight.value = true
        loading.value = true
        loadError.value = null
        scope.launch {
            KugouRepository.getPersonalFmSongs()
                .onSuccess { songs.value = it }
                .onFailure { e -> loadError.value = e.message ?: e.javaClass.simpleName }
            inFlight.value = false
            loading.value = false
        }
    }

    LaunchedEffect(Unit) { load() }

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
            songs.value,
            key = { index, song -> song.hash.ifEmpty { "idx_$index" } }
        ) { index, song ->
            key(song.hash.ifEmpty { "idx_$index" }) {
                MusicList(KugouRepository.toDisplayMediaItem(song)) {
                    playAt(index)
                }
            }

            key("divider_$index") {
                if (index < songs.value.size - 1) {
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
    }
}
