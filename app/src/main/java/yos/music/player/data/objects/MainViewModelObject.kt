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

    // val nowPage = mutableStateOf(NowPlayingPage.Album)
}