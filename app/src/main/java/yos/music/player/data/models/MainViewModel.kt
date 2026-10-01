package yos.music.player.data.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

@Stable
class MainViewModel : ViewModel() /*{
    val blurEffect = mutableStateOf(SettingData.get("settings_performance_blur_effect", false))
}*/