package yos.music.player.ui.widgets.liquid

import yos.music.player.data.libraries.SettingsLibrary

/**
 * 玻璃模糊强度倍率（0f..2f，1f = 原样），设置 › 外观 › 用户界面 ›「玻璃效果调节」。
 *
 * 只乘在**玻璃控件**的模糊半径上，口径集中在这里，新增玻璃控件时务必接上：
 * - [yos.music.player.ui.widgets.basic.LiquidTopBarButton]（顶栏圆钮，含自适应亮度分支）
 * - [LiquidBottomTabs] 玻璃容器（非玻璃磨砂分支的 12dp 不乘）
 * - MainActivity `PlayerShell`（迷你播放器 / 播放页外壳）
 * - [yos.music.player.ui.widgets.liquid.LiquidSwitch] 玻璃拇指
 * - [yos.music.player.ui.widgets.basic.LiquidDropdown] 下拉面板
 * - [yos.music.player.ui.widgets.basic.SonifyDialog] 对话框玻璃面
 *
 * **不乘**：TitleBarProgressiveBlur 的渐进模糊、各处非玻璃磨砂回退分支（Title 14dp /
 * ArtistDetail 14dp / PlayerShell 12dp）、艺人页倒影、静态封面/浮光位图模糊。
 *
 * 读取位置：小组合体在组合体内读（拖动滑块时当场重组）；体量过大的组合体
 * （MainActivity PlayerShell）在 `drawBackdrop` 的 effects 内读 —— 库对 effects 内的
 * 状态读取走 observeReads，值变即重建 RenderEffect 链。
 */
val glassBlurScale: Float
    get() = SettingsLibrary.GlassBlurScale
