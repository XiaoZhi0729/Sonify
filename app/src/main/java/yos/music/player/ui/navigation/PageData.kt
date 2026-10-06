package yos.music.player.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import yos.music.player.data.objects.ArtistPresentationCache
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.LocalTitlePageColor

internal class PageDataViewModel : ViewModel() {
    private val values = mutableMapOf<String, MutableState<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, initialValue: () -> T): MutableState<T> =
        values.getOrPut(key) { mutableStateOf(initialValue()) } as MutableState<T>
}

// Keep loaded rows with their back-stack entry so a restored scroll anchor is not
// clamped against an empty list or just the first page during navigation back.
@Composable
internal fun <T> rememberPageData(key: String, initialValue: () -> T): MutableState<T> {
    val holder: PageDataViewModel = viewModel()
    return holder.state(key, initialValue)
}

/**
 * 艺人配色桥（进程级）：登记「artistId → 提取色缓存键（paletteKey）」，
 * 供从艺人页进入的任意深度子页面读取该歌手的**实时**配色。
 *
 * 关键（本轮修复）：配色来源是 [ArtistPresentationCache] 持有的调色板 State，
 * 而不是艺人页组合期写入的快照。此前艺人页离开组合（快速点进专辑、转场结束）会
 * 取消其调色板 LaunchedEffect，配色永久停在未解析的占位白（0xFFF2F2F4），
 * 表现为「专辑详情背景变白、要退出重进才恢复」。改为读缓存 State 后：调色板计算
 * 由缓存自身的 CoroutineScope 持有，页面销毁不中断；算完写回同一 State，
 * 子页面因读该 State 自动重组上色，与艺人页是否仍在组合无关。
 */
internal object ArtistBackdrop {
    private val keys = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun bind(artistId: String, paletteKey: String) {
        if (artistId.isBlank() || paletteKey.isBlank()) return
        keys[artistId] = paletteKey
    }

    fun paletteKey(artistId: String?): String? = artistId?.let { keys[it] }
}

/**
 * 子页面读取艺人配色：调色板未就绪时返回 (null, null)（不铺底色、不注册遮罩），
 * 就绪后自动生效。读的是缓存持有的 State，故与艺人页是否仍在组合无关。
 */
@Composable
internal fun rememberArtistBackdropColors(artistId: String?): Pair<Color?, Color?> {
    val isDark = isFlamingoInDarkMode()
    val key = ArtistBackdrop.paletteKey(artistId)
    val rgb = key?.let { ArtistPresentationCache.palette(it) }?.value
    val colors = androidx.compose.runtime.remember(rgb, isDark) {
        rgb?.let { ArtistArtworkColors.fromRgb(it, isDark) }
    }
    return colors?.accent to colors?.content
}

/**
 * 二级/三级页面脚手架：按**来源 artistId** 实时取艺人页配色，铺底色、沿用文字色、
 * 并为本条目注册底栏遮罩。
 *
 * 不再依赖「上一返回栈条目是谁」——按 [artistId] 从 [ArtistBackdrop] 指向的缓存
 * 调色板 State 取色，与栈结构、时机、页面存活全部解耦。
 * [artistId] 为空（非艺人来源）时不介入，行为与原页面完全一致。
 */
@Composable
internal fun ArtistChildBackground(
    navController: NavController,
    entryId: String,
    artistId: String?,
    scrimTransition: PageScrimTransition,
    content: @Composable () -> Unit
) {
    val (background, artistContent) = rememberArtistBackdropColors(artistId)
    val fallbackContent = LocalContentColor.current
    val foreground = artistContent ?: fallbackContent
    if (background != null) {
        // 本页作为当前活跃条目注册遮罩色；转场进度取自本页自身进出场，与艺人页同机制
        val colorState = rememberUpdatedState(background)
        DisposableEffect(entryId, scrimTransition) {
            val registration = ArtistPageAppearance.register(entryId, colorState, scrimTransition)
            onDispose { ArtistPageAppearance.unregister(entryId, registration) }
        }
    }
    CompositionLocalProvider(
        LocalTitlePageColor provides background,
        LocalContentColor provides foreground
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (background != null) Modifier.background(background) else Modifier)
        ) {
            content()
        }
    }
}
