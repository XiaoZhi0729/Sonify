package yos.music.player.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.widgets.liquid.GlassBlurSliderControl
import yos.music.player.ui.widgets.liquid.GlassPreviewCard
import yos.music.player.ui.widgets.liquid.activeBlurScale
import yos.music.player.ui.widgets.liquid.setActiveBlurScale

/**
 * 屏 4 · 玻璃效果调节。
 *
 * 与原型 `sonify_ios.html` 完全对齐：
 * - 上方实时预览卡（按真实 `BarBlurEffect` 呈现液态玻璃折射 vs 混色模糊）
 * - 平滑滑动的分段控件（液态玻璃 / 混色模糊）
 * - 一张圆角卡片收拢「模糊强度 + 滑块 + 分隔线 + 重置为 100%」，两种材质各自保存独立强度
 */
@Composable
internal fun GlassSettingsScreen(colors: OnboardingColors) {
    val context = LocalContext.current
    val liquid = SettingsLibrary.BarBlurEffect
    val liquidScale = SettingsLibrary.GlassBlurScale.coerceIn(0f, 2f)
    val frostedScale = SettingsLibrary.FrostedBlurScale.coerceIn(0f, 2f)
    val currentScale = activeBlurScale(liquid)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Ob.Gutter)
    ) {
        Spacer(Modifier.height(Ob.S8))

        ScreenTitle(
            title = stringResource(id = R.string.onboarding_glass_title),
            desc = stringResource(id = R.string.onboarding_glass_desc),
            colors = colors,
        )

        Spacer(Modifier.height(Ob.S16))

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            GlassPreviewCard(
                scale = currentScale,
                liquid = liquid,
                liquidScale = liquidScale,
                frostedScale = frostedScale,
            )

            Spacer(Modifier.height(Ob.S14))

            OnboardingTextSegmented(
                options = listOf(
                    stringResource(id = R.string.settings_performance_ui_glass_material_liquid),
                    stringResource(id = R.string.settings_performance_ui_glass_material_frosted),
                ),
                selected = if (liquid) 0 else 1,
                colors = colors,
                onSelect = { index ->
                    SettingsLibrary.BarBlurEffect = (index == 0)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Ob.S14))

            ControlCard(colors = colors) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Ob.S4)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(id = R.string.settings_performance_ui_glass_blur_strength),
                            color = colors.ink,
                            fontSize = Ob.RowSize,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(currentScale * 100f).roundToInt()}%",
                            color = colors.brand,
                            fontSize = Ob.RowSize,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    GlassBlurSliderControl(
                        value = { activeBlurScale(SettingsLibrary.BarBlurEffect) },
                        onValueChange = { setActiveBlurScale(SettingsLibrary.BarBlurEffect, it) },
                        glassOn = liquid,
                        mochiColors = yos.music.player.ui.widgets.mochi.MochiSliderDefaults.colors(
                            activeTrack = colors.brand,
                            inactiveTrack = colors.track,
                        ),
                    )
                }

                Hairline(colors = colors)

                val atDefault = kotlin.math.abs(currentScale - 1f) < 0.005f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !atDefault,
                        ) {
                            Vibrator.click(context)
                            setActiveBlurScale(liquid, 1f)
                        }
                        .padding(vertical = Ob.S12),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_performance_ui_glass_blur_reset),
                        color = if (atDefault) colors.ink3 else colors.brand,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(Ob.S10))

            StageFootnote(
                text = stringResource(id = R.string.onboarding_glass_footnote),
                colors = colors,
            )

            Spacer(Modifier.height(Ob.S40))
        }
    }
}
