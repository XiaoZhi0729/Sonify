package yos.music.player.data.objects

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

@Stable
object MainViewModelObject {
    /*val statusBarLyricEnabled =
        mutableStateOf(SettingData.get("settings_others_statusbarlyric_basic_switch", false))*/
    // val statusBarLyricHooked = mutableStateOf(false)
    // val statusBarLyricVersion = mutableIntStateOf(0)

    val syncLyricIndex = mutableIntStateOf(-1)

    /**
     * 拖动进度条时的临时播放位置（毫秒）。
     * 非 null 时歌词用该位置驱动扫色与跟随滚动；松手 seek 生效后置回 null。
     */
    val scrubPositionMs = mutableStateOf<Int?>(null)

    // val nowPage = mutableStateOf(NowPlayingPage.Album)
}