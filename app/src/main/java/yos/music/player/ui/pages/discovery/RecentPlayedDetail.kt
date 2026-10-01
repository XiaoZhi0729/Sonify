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
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 最近播放完整列表页（主页「继续收听」→ 查看全部）。
 *
 * 数据源：收听历史（MusicLibrary.recentlyPlayed，最近优先，切歌实时更新），上限 100 条由写入端保证。
 * 播放链：全部歌曲 → MediaController.prepare（直接复用 YosMediaItem，无需占位符转换）。
 */
@Composable
fun RecentPlayedDetail(navController: NavController) {
    // 收听历史（最近优先）；上限 100 条由写入端 recordRecentlyPlayed 保证
    val songs = MusicLibrary.recentlyPlayed
    val scope = rememberCoroutineScope()

    fun playAt(index: Int) {
        if (index !in songs.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(songs[index], songs)
        }
    }

    Title(
        title = stringResource(id = R.string.home_continue_listening),
        onBack = { navController.popBackStack() }
    ) {
        if (songs.isEmpty()) {
            item("Empty") {
                Text(
                    text = stringResource(id = R.string.recent_played_detail_empty),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
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
                    icon = painterResource(id = R.drawable.button_icon_shuffle),
                    label = stringResource(id = R.string.normal_button_shuffle),
                    modifier = Modifier.weight(1f)
                ) {
                    if (songs.isNotEmpty()) {
                        MediaController.mediaControl?.shuffleModeEnabled = true
                        playAt(songs.indices.random())
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
            songs,
            key = { index, song -> song.mediaId ?: index }
        ) { index, song ->
            key(song.mediaId ?: index) {
                MusicList(song) {
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
