package yos.music.player.ui.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 手写字「你好」的独立预览页。
 *
 * 按用户口径：**不用玻璃**，只用苹果的黑与白，仪式感全部来自手写动画。
 * 底色取 iOS/macOS 首次开机那张屏的观感：
 *   浅色 = 近白 #FBFBFD + 苹果黑 #1D1D1F
 *   深色 = 纯黑 #000000 + 苹果白 #F5F5F7
 *
 * 这两个值刻意不用纯白做纸、也不用纯黑做字：
 * 纯 #FFFFFF / #000000 在大面积时会显得刺眼且丢掉苹果那种"微微偏暖"的层次。
 *
 * 与玻璃版相比这一版没有任何 backdrop 依赖 —— 没有采样、没有模糊、没有边缘光。
 * 这不是简化，是刻意的：苹果那张开场屏之所以成立，靠的是"它正在被写出来"，
 * 一旦加上材质就会立刻离开那个语境。
 */
@Composable
fun HandwriteHelloPreview(modifier: Modifier = Modifier) {
    /* 每次点击「重新书写」自增，作为 key 让手写动画从头再跑一遍。 */
    var run by remember { mutableIntStateOf(0) }
    /* 深浅手动切换：预览需要一眼对比两套，不能依赖系统设置。 */
    var dark by remember { mutableStateOf(false) }

    val bg = if (dark) Color.Black else Color(0xFFFBFBFD)
    val ink = if (dark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)
    val hint = if (dark) Color(0xFF8E8E93) else Color(0xFF8E8E93)

    Box(
        modifier
            .fillMaxSize()
            .background(bg)
    ) {
        /* ① 主体：字 + 副标题，垂直居中 */
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            key(run) {
                HandwriteHelloText(
                    width = 250.dp,
                    colorLight = ink,
                    colorDark = ink,
                    animate = true,
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                text = "欢迎使用 Sonify",
                color = hint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )
        }

        /* ② 底部：重播 + 深浅切换 + 授权提示 */
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { run++ }) {
                    Text("重新书写", color = hint, fontSize = 13.sp)
                }
                TextButton(onClick = { dark = !dark }) {
                    Text(if (dark) "浅色" else "深色", color = hint, fontSize = 13.sp)
                }
            }
            Text(
                text = "字形来源为第三方非官方复刻字体，仅限个人/非商业用途；" +
                    "公开 release 前须替换 res/raw/hello_glyph_path.txt。",
                color = hint.copy(alpha = 0.5f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 30.dp),
            )
        }
    }
}
