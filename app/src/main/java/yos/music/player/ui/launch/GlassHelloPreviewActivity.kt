package yos.music.player.ui.launch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 手写字预览的宿主 Activity。
 *
 * 只做一件事：把 [HandwriteHelloPreview] 挂到窗口上，方便真机看效果 / 截图验证。
 * 不参与任何生产路径（启动流程仍是 LaunchCurtain，未改动）。
 *
 * 入口名仍是「玻璃字预览」——名字保留是为了不改桌面图标，实际内容已按用户口径
 * 改为「无玻璃 + 苹果黑白 + 手写动画」。
 *
 * 启动：
 *   adb shell am start -n com.sonify.music/yos.music.player.ui.launch.GlassHelloPreviewActivity
 */
class GlassHelloPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(Modifier.fillMaxSize(), color = Color.Unspecified) {
                HandwriteHelloPreview()
            }
        }
    }
}
