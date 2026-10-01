package yos.music.player.ui.pages.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import yos.music.player.ui.widgets.basic.LocalTitlePageColor

/**
 * 设置页统一背景：分组灰（colorScheme.secondary）。
 * 同时把它提供给 [LocalTitlePageColor]，使顶栏模糊的采样底色与 tint 与页面一致
 * ——否则顶栏会用白/黑种子铺底，在灰底页面上出现色差。
 */
@Composable
fun SettingBackground(content: @Composable BoxScope.() -> Unit) {
    val pageColor = MaterialTheme.colorScheme.secondary
    CompositionLocalProvider(LocalTitlePageColor provides pageColor) {
        Box(
            modifier = Modifier.fillMaxSize().background(color = pageColor),
            content = content
        )
    }
}
