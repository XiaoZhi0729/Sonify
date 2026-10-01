package yos.music.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.tencent.mmkv.MMKV
import yos.music.player.data.models.MainViewModel
import yos.music.player.data.models.MediaViewModel

abstract class BaseActivity : ComponentActivity() /*{
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}*/

