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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * VIP 专属推荐详情页（主页「VIP专属推荐」入口进入）。
 *
 * 数据链：GET /top/card/youth?card_id=3006。优先复用主页已拉取的 [DiscoveryObject.vipRecommendSongs]，
 * 若为空则直接拉取并同步回写，进入即展示完整推荐列表。
 */
@Composable
fun VipRecommendDetail(navController: NavController) {
    val cachedSongs = DiscoveryObject.vipRecommendSongs.value
    val songs = remember { mutableStateOf<List<KugouNewSong>>(cachedSongs) }
    val loading = remember { mutableStateOf(cachedSongs.isEmpty()) }
    val inFlight = remember { mutableStateOf(false) }
    val loadError = remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val songItems = songs.value
    val songKeys = remember(songItems) { lazyItemKeys(songItems) { it.hash } }

    fun load() {
        if (inFlight.value) return
        inFlight.value = true
        loading.value = true
        loadError.value = null
        scope.launch {
            KugouRepository.getVipRecommendSongs()
                .onSuccess { list ->
                    songs.value = list
                    DiscoveryObject.vipRecommendSongs.value = list
                    DiscoveryObject.vipRecommendSongsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e -> loadError.value = e.message ?: e.javaClass.simpleName }
            inFlight.value = false
            loading.value = false
        }
    }

    LaunchedEffect(Unit) {
        if (songs.value.isEmpty()) {
            load()
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
        title = stringResource(id = R.string.home_vip_recommend_title),
        subTitle = stringResource(id = R.string.home_vip_recommend_subtitle),
        onBack = { navController.popBackStackSafely() }
    ) {
        if (songs.value.isEmpty()) {
            item("Status") {
                val text = when {
                    loadError.value != null -> loadError.value.orEmpty()
                    loading.value -> stringResource(id = R.string.online_playlists_loading)
                    else -> stringResource(id = R.string.vip_recommend_empty)
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
                    MediaController.mediaControl?.shuffleModeEnabled = false
                    playAt(0)
                }
                Spacer(modifier = Modifier.width(15.dp))
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_shuffle),
                    label = stringResource(id = R.string.normal_button_shuffle),
                    modifier = Modifier.weight(1f)
                ) {
                    if (songs.value.isNotEmpty()) {
                        MediaController.mediaControl?.shuffleModeEnabled = true
                        playAt(songs.value.indices.random())
                    }
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
            key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } },
            contentType = { _, _ -> "vip_recommend_song" }
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
    }
}
