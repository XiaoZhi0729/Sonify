package yos.music.player.data.objects

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import yos.music.player.code.utils.lrc.LyricEntry

@Stable
object MediaViewModelObject {
    val lrcEntries: MutableState<List<LyricEntry>> = mutableStateOf(listOf())
    val lyricMediaId: MutableState<String?> = mutableStateOf(null)
    val lyricLoading: MutableState<Boolean> = mutableStateOf(false)
    val otherSideForLines = mutableStateListOf<Boolean>()

    // var mainLyricLines = mutableStateListOf<AnnotatedString>()

    val bitmap: MutableState<Uri?> = mutableStateOf(null)

    val isPlaying: MutableState<Boolean> = mutableStateOf(false)

    // Media3 播放模式的唯一 Compose 状态源；由 Player.Listener 从真实播放器回流更新。
    val shuffleModeEnabled: MutableState<Boolean> = mutableStateOf(false)
    val repeatMode = mutableIntStateOf(androidx.media3.common.Player.REPEAT_MODE_OFF)

    val bitrate = mutableIntStateOf(0)
    val samplingRate = mutableIntStateOf(0)
    val isDolby = mutableStateOf(false)

    // val songSort = mutableStateOf(SettingData.getString("yos_player_song_sort", "MUSIC_TITLE"))
    // val enableDescending = mutableStateOf(SettingData.get("yos_player_enable_descending", false))
}