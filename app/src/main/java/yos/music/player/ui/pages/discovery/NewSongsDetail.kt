package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 新歌精选完整列表页（Discovery「新歌精选」→ 查看全部）。
 *
 * 数据链：直接复用 DiscoveryObject.newSongs（新发现 Tab 已加载的全量数据），
 * 进入本页不重新请求接口；进程重建后 holder 为空时展示空态并引导返回。
 * 播放链（整列表）：与 Discovery.playNewSongsAt 同款 —— 全部歌曲映射为队列用
 * YosMediaItem（uri 为占位符，真实 URL 由播放器惰性解析）→ MediaController.prepare。
 */
@Composable
fun NewSongsDetail(navController: NavController) {
    val songs = DiscoveryObject.newSongs.value
    val scope = rememberCoroutineScope()

    // 整列表播放：全部歌曲进 Media3 队列，从 index 处开始；URL 由播放器惰性解析
    fun playAt(index: Int) {
        if (index !in songs.indices) return
        val queue = songs.map { KugouRepository.toQueueMediaItem(it) }
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(queue[index], queue)
        }
    }

    Title(
        title = stringResource(id = R.string.discovery_new_songs_title),
        onBack = { navController.popBackStack() }
    ) {
        if (songs.isEmpty()) {
            // 边界：进程重建后 holder 丢失（与 RankDetail 空态规范一致：18sp / α0.6）
            item("Empty") {
                Text(
                    text = stringResource(id = R.string.new_songs_detail_empty),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
            return@Title
        }

        item("Actions") {
            // 播放 / 随机：与 RankDetail 同款操作行（播放全部 / 随机播放）
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
                    icon = painterResource(id = R.drawable.button_icon_shuffle),
                    label = stringResource(id = R.string.normal_button_shuffle),
                    modifier = Modifier.weight(1f)
                ) {
                    val list = songs
                    if (list.isNotEmpty()) {
                        MediaController.mediaControl?.shuffleModeEnabled = true
                        playAt(list.indices.random())
                    }
                }
            }
        }

        item("DividerTop") {
            // 操作行与歌曲列表之间的细分隔（对齐 RankDetail 分割线规范）
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
            songs,
            key = { _, song -> song.hash }
        ) { index, song ->
            key(song.hash) {
                // 统一歌曲 Item：与本地/榜单列表同款 MusicList 视觉规范；
                // 点击 → 全部歌曲进队列，从被点击歌曲开始播放
                MusicList(KugouRepository.toDisplayMediaItem(song)) {
                    playAt(index)
                }
            }

            key("divider_$index") {
                if (index < songs.size - 1) {
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
