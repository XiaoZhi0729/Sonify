package yos.music.player.ui.widgets.basic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.primary
import yos.music.player.ui.theme.primaryDark
import yos.music.player.ui.widgets.liquid.LiquidBottomTab
import yos.music.player.ui.widgets.liquid.LiquidBottomTabs

@Stable
data class NavItem(val label: String, val iconResId: Int)

@Composable
fun BottomNavigator(
    initialIndex: Int,
    externalIndex: () -> Int,
    onIndexChange: (Int) -> Unit,
    onTabReselected: (Int) -> Unit = {},
    items: List<NavItem>,
    modifier: Modifier,
    backdrop: Backdrop,
    enableInteractiveHighlight: Boolean = true,
    /** 底栏玻璃的三个子项，逐一对应消融位 navcontainer / navhidden / navtab。 */
    containerGlassEnabled: Boolean = true,
    hiddenProducerEnabled: Boolean = true,
    tabGlassEnabled: Boolean = true,
    /** 容器玻璃面的提取色 tint（见 LiquidBottomTabs.containerTintProvider）。 */
    containerTintProvider: (() -> Color)? = null
) {
    // 单一状态源：selectedIndex 由底栏自身持有，
    // 路由变化通过 externalIndex 在 LaunchedEffect 中异步回投，
    // 拖动/点击/路由三者不竞争同一帧。
    var selectedIndex by rememberSaveable(items) {
        mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex))
    }
    LaunchedEffect(externalIndex()) {
        val target = externalIndex()
        // target < 0 表示当前路由不属于任何主 Tab（二级页/设置等），
        // 保持来处高亮不变，与底栏点击行为互不干扰
        if (target in 0..items.lastIndex && target != selectedIndex) {
            selectedIndex = target
        }
    }
    val dark = isFlamingoInDarkMode()
    // 玻璃容器启用自适应亮度时（LocalGlassContentColor 非 null），Tab 图标/文字
    // 跟随背后亮度黑↔白；否则保持主题静态色
    val contentColor = LocalGlassContentColor.current ?: if (dark) Color.White else Color.Black
    val accentColor = if (dark) primaryDark else primary

    val selectTab: (Int) -> Unit = { index ->
        if (selectedIndex != index) {
            selectedIndex = index
            onIndexChange(index)
        } else {
            // iOS 惯例：再点当前 Tab 回到该 Tab 根页面（逃生门）
            onTabReselected(index)
        }
    }

    // 关闭"工具栏液态玻璃"时，底栏退化为"无玻璃材质的磨砂胶囊"：保留胶囊几何与
    // 拖动/点击，去掉折射/透镜/色散，blur(12dp) 磨砂面 + 共用白高光/内阴影延伸层
    // （与迷你播放器关玻璃材质对齐），选中 Tab 改用主题强调色图标标识。
    // （原先用于对照实验的 flamingo 原版扁平底栏已废弃、删除。）
    val solidCapsule = !SettingsLibrary.BarBlurEffect

    Column(modifier.fillMaxWidth()) {
        LiquidBottomTabs(
            selectedTabIndex = { selectedIndex },
            onTabSelected = { index ->
                if (selectedIndex != index) {
                    selectedIndex = index
                    onIndexChange(index)
                }
            },
            // 玻璃高亮胶囊叠在选中 Tab 上方并拦截点击，
            // "再点当前 Tab"由胶囊手势层的 onTap 上报，事件到不了下面的按钮
            onTabReselected = { onTabReselected(selectedIndex) },
            backdrop = backdrop,
            tabsCount = items.size,
            modifier = Modifier.fillMaxWidth(),
            enableInteractiveHighlight = enableInteractiveHighlight,
            solidCapsule = solidCapsule,
            containerGlassEnabled = containerGlassEnabled,
            hiddenProducerEnabled = hiddenProducerEnabled,
            tabGlassEnabled = tabGlassEnabled,
            containerTintProvider = containerTintProvider
        ) {
            items.forEachIndexed { index, item ->
                LiquidBottomTab(onClick = { selectTab(index) }) {
                    val selected = index == selectedIndex
                    // 纯色胶囊没有玻璃采样着色，选中的图标/文字直接用强调色。
                    val tint = if (solidCapsule && selected) accentColor else contentColor
                    Icon(
                        painter = painterResource(item.iconResId),
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        item.label,
                        color = tint,
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
