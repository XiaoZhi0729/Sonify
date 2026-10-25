package yos.music.player.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import yos.music.player.ui.theme.YosMusicTheme
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.TitleOverlayHost
import yos.music.player.ui.widgets.basic.TitleOverlayHostSlot
import yos.music.player.ui.widgets.dropdown.LiquidDropdownLayout
import yos.music.player.ui.widgets.dropdown.TitleBarGlassMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz

/**
 * 下拉实验室（仅 debug 包）：用 `am start` 直接打开，不需要点屏幕。
 * 用例由 intent extra "case" 选择：
 *   popup = ListPopup 弹窗（歌曲行⋮那类），600ms 后自动展开
 *   glass = 顶栏本体化菜单，600ms 后自动展开
 */
class DropdownLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val case = intent.getStringExtra("case") ?: "popup"
        setContent {
            YosMusicTheme(darkTheme = false) {
                DropdownLabScreen(case)
            }
        }
    }
}

@Composable
private fun DropdownLabScreen(case: String) {
    // 纯红采样底：玻璃画上去必然偏粉（0.72 白），没画就是纯红
    val backdrop = rememberLayerBackdrop(onDraw = {
        drawRect(Color(0xFFE02020))
        drawContent()
    })
    CompositionLocalProvider(LocalTitlePageBackdrop provides backdrop) {
        val host = remember { TitleOverlayHost() }
        CompositionLocalProvider(LocalTitleOverlayHost provides host) {
            Box(Modifier.fillMaxSize().background(Color(0xFFE02020))) {
                val expanded = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(600)
                    expanded.value = true
                }

                if (case == "popup") {
                    Column(Modifier.padding(top = 120.dp, start = 40.dp)) {
                        Box(Modifier.size(40.dp, 40.dp)) {
                            LiquidDropdownLayout(
                                expanded = expanded.value,
                                onDismissRequest = {},
                                backdrop = backdrop,
                                collapseSize = IntSize(130, 130),
                            ) {
                                LiquidDropdownColumn {
                                    LiquidDropdownRow(text = "第一行", selected = true, onClick = {}, isFirst = true)
                                    LiquidDropdownRow(text = "第二行", selected = false, onClick = {})
                                    LiquidDropdownRow(text = "第三行", selected = false, onClick = {}, isLast = true)
                                }
                            }
                        }
                    }
                } else {
                    TitleBarGlassMenu(
                        expanded = expanded.value,
                        onExpand = { },
                        onDismiss = { },
                        triggerIcon = Icons.Rounded.MoreHoriz,
                        modifier = Modifier.padding(top = 60.dp, end = 10.dp),
                    ) {
                        Text("菜单甲", fontSize = 15.sp, modifier = Modifier.padding(6.dp))
                        Text("菜单乙", fontSize = 15.sp, modifier = Modifier.padding(6.dp))
                    }
                    Column(Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 20.dp).width(120.dp)) {
                        Text("case=glass", color = Color.White, fontSize = 12.sp)
                    }
                }
                TitleOverlayHostSlot(host)
            }
        }
    }
}
