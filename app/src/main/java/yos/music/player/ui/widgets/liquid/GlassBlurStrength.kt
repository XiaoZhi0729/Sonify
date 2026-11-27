package yos.music.player.ui.widgets.liquid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import yos.music.player.data.libraries.SettingsLibrary

/**
 * 玻璃模糊强度倍率（0f..2f，1f = 原样），设置 › 外观 › 用户界面 ›「玻璃效果调节」。
 *
 * 只乘在**玻璃控件**的模糊半径上，口径集中在这里，新增玻璃控件时务必接上：
 * - [yos.music.player.ui.widgets.basic.LiquidTopBarButton]（顶栏圆钮，含自适应亮度分支）
 * - [LiquidBottomTabs] 玻璃容器 + tabsBackdrop 生产者行的 4dp（后者是选中 Tab 玻璃
 *   指示器静止态唯一的模糊来源；两处同源必须同乘，非玻璃磨砂分支的 12dp 不乘）
 * - MainActivity `PlayerShell`（迷你播放器 / 播放页外壳）
 * - [yos.music.player.ui.widgets.liquid.LiquidSwitch] 玻璃拇指
 * - [yos.music.player.ui.widgets.basic.LiquidDropdown] 下拉面板
 * - [yos.music.player.ui.widgets.basic.SonifyDialog] 对话框玻璃面
 * - [yos.music.player.ui.pages.gallery.MusicGalleryPage] 顶栏圆钮 + 底部播放药丸
 *   （采样源是该页自己的页内 `LayerBackdrop`，只录背景 + scrim + 4 张封面卡）
 *
 * 非玻璃（混色模糊）顶栏（`Title`、`ArtistDetail`）、[yos.music.player.ui.widgets.basic.TitleBarIcon]
 * 混色模糊分支与 [yos.music.player.ui.widgets.dropdown.LiquidGlassDropdownMenu] 混色模糊分支
 * 共用 [TitleBarFrostedBlurDp] 并同乘 [frostedBlurScale]。
 *
 * **不乘**：TitleBarProgressiveBlur 的渐进模糊、艺人页倒影、静态封面/浮光位图模糊。
 *
 * 读取位置：小组合体在组合体内读（拖动滑块时当场重组）；体量过大的组合体
 * （MainActivity PlayerShell）在 `drawBackdrop` 的 effects 内读 —— 库对 effects 内的
 * 状态读取走 observeReads，值变即重建 RenderEffect 链。
 */
val TitleBarFrostedBlurDp = 14.dp
val TitleBarFrostedBorderWidth = 0.75.dp

fun frostedTopBarSurfaceColor(isLightTheme: Boolean): Color =
    if (isLightTheme) Color.White.copy(alpha = 0.72f)
    else Color(0xFF1E1E24).copy(alpha = 0.66f)

fun frostedTopBarBorderColor(isLightTheme: Boolean): Color =
    if (isLightTheme) Color.Black.copy(alpha = 0.08f)
    else Color.White.copy(alpha = 0.16f)

fun frostedTopBarIconTint(isLightTheme: Boolean): Color =
    if (isLightTheme) Color.Black.copy(alpha = 0.85f)
    else Color.White.copy(alpha = 0.85f)

val glassBlurScale: Float
    get() = SettingsLibrary.GlassBlurScale

/**
 * 混色模糊（磨砂）强度倍率（0f..2f，1f = 原样），与 [glassBlurScale] 相互独立，互不覆盖。
 */
val frostedBlurScale: Float
    get() = SettingsLibrary.FrostedBlurScale

fun activeBlurScale(liquid: Boolean = SettingsLibrary.BarBlurEffect): Float =
    (if (liquid) glassBlurScale else frostedBlurScale).coerceIn(0f, 2f)

fun setActiveBlurScale(liquid: Boolean, value: Float) {
    val clamped = value.coerceIn(0f, 2f)
    if (liquid) {
        SettingsLibrary.GlassBlurScale = clamped
    } else {
        SettingsLibrary.FrostedBlurScale = clamped
    }
}
