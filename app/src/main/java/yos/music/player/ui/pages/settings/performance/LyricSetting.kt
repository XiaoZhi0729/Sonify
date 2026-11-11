package yos.music.player.ui.pages.settings.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.github.alexzhirkevich.cupertino.CupertinoSlider
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import kotlin.math.roundToInt
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.lrc.LyricRenderWarmup
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.navigation.popBackStackSafely

@Composable
fun LyricSetting(navController: NavController) =
    SettingBackground {
        // 样式改动即时生效：排版缓存键已含字重/字号/行平衡，这里按当前曲把新键提前预热，
        // 回到歌词页直接命中，不会走冷排版（空一帧/闪一下）。
        val context = LocalContext.current
        Title(title = stringResource(id = R.string.settings_performance_lyric_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        // ---------------- 样式 ----------------
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_style))

                        RoundColumn {
                            // 显示名必须先在这里一次性解析成普通字符串：stringResource 不能在
                            // 下面 onValueChange 那种非 @Composable 的 lambda 里调用。
                            val weightLabels = FONT_WEIGHT_KEYS.map { fontWeightLabel(it) }
                            SelectItem(
                                title = stringResource(id = R.string.settings_performance_lyric_style_font_weight),
                                items = weightLabels,
                                value = { fontWeightLabel(SettingsLibrary.LyricFontWeight) },
                                onValueChange = { label ->
                                    // 按显示名反查存储值：这样换语言也不会把设置写坏
                                    weightLabels.indexOf(label).takeIf { it >= 0 }?.let {
                                        SettingsLibrary.LyricFontWeight = FONT_WEIGHT_KEYS[it]
                                    }
                                    LyricRenderWarmup.onRenderStyleChanged(context)
                                }
                            )

                            Divider()

                            // 歌词字号缩放（对齐 Flamingo 新版 LyricFontScale，钳制 0.8~1.2）
                            SelectItem(
                                title = stringResource(id = R.string.settings_performance_lyric_style_font_size),
                                items = listOf("80%", "90%", "100%", "110%", "120%"),
                                value = {
                                    when (SettingsLibrary.LyricFontScale.coerceIn(0.8f, 1.2f)) {
                                        0.8f -> "80%"
                                        0.9f -> "90%"
                                        1.1f -> "110%"
                                        1.2f -> "120%"
                                        else -> "100%"
                                    }
                                },
                                onValueChange = {
                                    SettingsLibrary.LyricFontScale = when (it) {
                                        "80%" -> 0.8f
                                        "90%" -> 0.9f
                                        "110%" -> 1.1f
                                        "120%" -> 1.2f
                                        else -> 1.0f
                                    }
                                    LyricRenderWarmup.onRenderStyleChanged(context)
                                }
                            )

                            Divider()

                            // 平衡行会重排每行字数，对某些语言有副作用 —— 这属于"反直觉"，
                            // 但它是这一行的属性，所以写成行内小字，不写卡后脚注
                            // （卡后脚注只能描述整张卡，挂在 3 行后面会被当成对三行的共同说明）。
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_line_balance),
                                desc = stringResource(id = R.string.settings_performance_lyric_line_balance_desc),
                                onClick = {
                                    SettingsLibrary.LyricLineBalance =
                                        !SettingsLibrary.LyricLineBalance
                                    LyricRenderWarmup.onRenderStyleChanged(context)
                                },
                                checkedLambda = { SettingsLibrary.LyricLineBalance }
                            )

                            Divider()

                            // 歌词时间偏移：正值 = 歌词提前，负值 = 歌词延后
                            SelectItem(
                                title = stringResource(id = R.string.settings_performance_lyric_timing_offset),
                                desc = stringResource(id = R.string.settings_performance_lyric_timing_offset_desc),
                                items = OFFSET_STEPS_MS.map { offsetLabel(it) },
                                value = {
                                    offsetLabel(
                                        OFFSET_STEPS_MS.minByOrNull {
                                            kotlin.math.abs(it - SettingsLibrary.LyricTimingOffset)
                                        } ?: 0
                                    )
                                },
                                onValueChange = { label ->
                                    SettingsLibrary.LyricTimingOffset =
                                        OFFSET_STEPS_MS.firstOrNull { offsetLabel(it) == label } ?: 0
                                }
                            )
                        }

                        GroupSpacer()

                        // ---------------- 动效 ----------------
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_motion))

                        RoundColumn {
                            // 智能逐字歌词（对齐 Flamingo smart word-by-word）
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_smart_wbw_lyric),
                                onClick = {
                                    SettingsLibrary.SmartWordByWordLyric =
                                        !SettingsLibrary.SmartWordByWordLyric
                                },
                                checkedLambda = { SettingsLibrary.SmartWordByWordLyric }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_blur_effect),
                                onClick = {
                                    SettingsLibrary.LyricBlurEffect =
                                        !SettingsLibrary.LyricBlurEffect
                                },
                                checkedLambda = { SettingsLibrary.LyricBlurEffect }
                            )

                            Divider()

                            // HDR 的硬件前提与耗电提示属于这一行本身，写行内；
                            // 挂在 3 行卡后会变成"整组动效都要求 Android 14"的误读。
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_hdr_effect),
                                desc = stringResource(id = R.string.settings_performance_lyric_hdr_effect_desc),
                                onClick = {
                                    SettingsLibrary.LyricHdrEffect =
                                        !SettingsLibrary.LyricHdrEffect
                                },
                                checkedLambda = { SettingsLibrary.LyricHdrEffect }
                            )
                            // 强度滑杆只在 HDR 打开时露出：关着的时候它没有任何可调观感
                            if (SettingsLibrary.LyricHdrEffect) {
                                Divider()
                                LyricHdrStrengthItem()
                            }
                        }

                        GroupSpacer()

                        // ---------------- 设备 ----------------
                        // 设备歌词：把当前行写进会话标题，通知栏/锁屏/车机一起显示
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_device_title))

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_notification),
                                desc = stringResource(id = R.string.settings_performance_lyric_notification_desc),
                                onClick = {
                                    SettingsLibrary.NotificationLyricEnabled =
                                        !SettingsLibrary.NotificationLyricEnabled
                                    // 关闭时立刻把通知栏/车机标题还给歌名，不等下一行歌词
                                    if (!SettingsLibrary.NotificationLyricEnabled) {
                                        MediaController.clearSessionLyric()
                                    }
                                },
                                checkedLambda = { SettingsLibrary.NotificationLyricEnabled }
                            )
                        }

                        GroupSpacer()
                    }
                }
            }
        )
    }

/**
 * 字重的**存储值**（写进 [SettingsLibrary.LyricFontWeight]，渲染侧按这些名字解析）。
 * 界面显示走 [fontWeightLabel]：直接把 "SemiBold" 摊给用户看等于让他读类型名。
 */
private val FONT_WEIGHT_KEYS = listOf(
    "Thin",
    "ExtraLight",
    "Light",
    "Regular",
    "Medium",
    "SemiBold",
    "Bold",
    "ExtraBold",
    "Black"
)

/** 存储值 → 本地化显示名。未知值原样返回，避免旧数据被显示成空白。 */
@Composable
private fun fontWeightLabel(key: String): String = stringResource(
    id = when (key) {
        "Thin" -> R.string.settings_performance_lyric_weight_thin
        "ExtraLight" -> R.string.settings_performance_lyric_weight_extra_light
        "Light" -> R.string.settings_performance_lyric_weight_light
        "Regular" -> R.string.settings_performance_lyric_weight_regular
        "Medium" -> R.string.settings_performance_lyric_weight_medium
        "SemiBold" -> R.string.settings_performance_lyric_weight_semi_bold
        "Bold" -> R.string.settings_performance_lyric_weight_bold
        "ExtraBold" -> R.string.settings_performance_lyric_weight_extra_bold
        "Black" -> R.string.settings_performance_lyric_weight_black
        else -> R.string.settings_performance_lyric_weight_regular
    }
)

/** 时间偏移档位（毫秒）。正值让歌词提前显示。 */
private val OFFSET_STEPS_MS = listOf(-500, -300, -200, -100, 0, 100, 200, 300, 500)

private fun offsetLabel(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"

/**
 * HDR 高光强度：用掉屏幕可用亮度余量的多少（0–100%）。
 *
 * 本地状态作为拖动期间的实时源：CupertinoSlider 内部用动画插值，直接读设置项
 * 会在手指还没抬起来时被持久化值拽回去，滑起来一顿一顿的。
 */
@Composable
private fun LyricHdrStrengthItem() {
    var value by remember("LyricHdrStrengthItem") {
        mutableFloatStateOf(SettingsLibrary.LyricHdrStrength.coerceIn(0f, 1f))
    }
    val interactionSource = remember("LyricHdrStrengthItem_interaction") {
        MutableInteractionSource()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.settings_performance_lyric_hdr_strength),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(value * 100f).roundToInt()}%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        CupertinoTheme {
            CupertinoSlider(
                value = value,
                onValueChange = {
                    value = it
                    SettingsLibrary.LyricHdrStrength = it
                },
                valueRange = 0f..1f,
                thumb = {
                    Spacer(
                        Modifier
                            .size(23.dp)
                            .hoverable(interactionSource = interactionSource)
                            .shadow(
                                8.dp,
                                CircleShape,
                                clip = false,
                                spotColor = Color.Black.copy(alpha = 0.55f)
                            )
                            .background(Color.White, CircleShape)
                    )
                },
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}