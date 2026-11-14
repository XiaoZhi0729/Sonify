package yos.music.player.ui.pages.settings.performance.userinterface

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.liquid.GlassTuningPanel
import yos.music.player.ui.widgets.liquid.activeBlurScale
import yos.music.player.ui.widgets.liquid.setActiveBlurScale

/**
 * 玻璃效果调节：一张横幅图 + 图上的玻璃控件（实时预览）+ 模糊强度滑块。
 *
 * 滑块值 [SettingsLibrary.GlassBlurScale] / [SettingsLibrary.FrostedBlurScale] 是**倍率**，
 * 各自独立保存，互不覆盖。
 */
@Composable
fun GlassEffectSetting(navController: NavController) {
    SettingBackground {
        Title(
            title = stringResource(id = R.string.settings_performance_ui_glass_effect_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("glass_effect") {
                    GlassEffectSettingContent()
                }
            }
        )
    }
}

/**
 * 在 LazyColumn item 的自身重组作用域内直接读取 [SettingsLibrary] 状态，
 * 避免 `settings-datasaver-state-read-scope-trap` 导致切换材质后预览与滑块不重组。
 */
@Composable
private fun GlassEffectSettingContent() {
    val liquid = SettingsLibrary.BarBlurEffect
    val liquidScale = SettingsLibrary.GlassBlurScale.coerceIn(0f, 2f)
    val frostedScale = SettingsLibrary.FrostedBlurScale.coerceIn(0f, 2f)
    val activeScale = activeBlurScale(liquid)

    Column(Modifier.fillMaxSize()) {
        GroupSpacerMedium()

        // ★预览卡 + 滑块来自共享的 GlassTuningPanel（引导页第 5 屏共用同一实现）。
        GlassTuningPanel(
            modifier = Modifier.padding(horizontal = 20.dp),
            scale = activeScale,
            onScaleChange = { newScale ->
                setActiveBlurScale(SettingsLibrary.BarBlurEffect, newScale)
            },
            liquid = liquid,
            onLiquidChange = { SettingsLibrary.BarBlurEffect = it },
            showMaterialChooser = false,
            showReset = false,
            liquidScale = liquidScale,
            frostedScale = frostedScale,
        )

        GroupSpacerMedium()

        val liquidLabel = stringResource(id = R.string.settings_performance_ui_glass_material_liquid)
        val frostedLabel = stringResource(id = R.string.settings_performance_ui_glass_material_frosted)
        RoundColumn {
            SelectItem(
                title = stringResource(id = R.string.settings_performance_ui_glass_material),
                desc = stringResource(id = R.string.settings_performance_ui_glass_material_desc),
                items = listOf(liquidLabel, frostedLabel),
                onValueChange = { picked ->
                    SettingsLibrary.BarBlurEffect = picked == liquidLabel
                },
                value = {
                    if (SettingsLibrary.BarBlurEffect) liquidLabel else frostedLabel
                }
            )
            Divider()
            LabelItem(
                title = stringResource(id = R.string.settings_performance_ui_glass_blur_reset),
                superLink = true
            ) {
                setActiveBlurScale(SettingsLibrary.BarBlurEffect, 1f)
            }
        }

        ListHeader(content = stringResource(id = R.string.settings_performance_ui_glass_effect_desc))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ListHeader(content = stringResource(id = R.string.settings_performance_ui_glass_effect_unsupported))
        }

        GroupSpacer()
    }
}
