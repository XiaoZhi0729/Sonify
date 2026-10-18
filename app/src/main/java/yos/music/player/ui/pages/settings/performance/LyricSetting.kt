package yos.music.player.ui.pages.settings.performance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.lrc.LyricRenderWarmup
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

@Composable
fun LyricSetting(navController: NavController) =
    SettingBackground {
        // 样式改动即时生效：排版缓存键已含字重/字号/行平衡，这里按当前曲把新键提前预热，
        // 回到歌词页直接命中，不会走冷排版（空一帧/闪一下）。
        val context = LocalContext.current
        Title(title = stringResource(id = R.string.settings_performance_lyric_title),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        // GroupSpacerMedium()
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_style))

                        RoundColumn {
                            SelectItem(
                                title = stringResource(id = R.string.settings_performance_lyric_style_font_weight),
                                items = listOf(
                                    "Thin",
                                    "ExtraLight",
                                    "Light",
                                    "Regular",
                                    "Medium",
                                    "SemiBold",
                                    "Bold",
                                    "ExtraBold",
                                    "Black"
                                ),
                                value = { SettingsLibrary.LyricFontWeight },
                                onValueChange = {
                                    SettingsLibrary.LyricFontWeight = it
                                    LyricRenderWarmup.onRenderStyleChanged(context)
                                }
                            )
                        }

                        GroupSpacerMedium()

                        // 歌词字号缩放（对齐 Flamingo 新版 LyricFontScale，钳制 0.8~1.2）
                        RoundColumn {
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
                        }

                        GroupSpacerMedium()

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_line_balance),
                                // desc = stringResource(id = R.string.settings_performance_lyric_line_balance_desc),
                                onClick = {
                                    SettingsLibrary.LyricLineBalance =
                                        !SettingsLibrary.LyricLineBalance
                                    LyricRenderWarmup.onRenderStyleChanged(context)
                                },
                                checkedLambda = { SettingsLibrary.LyricLineBalance }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_line_balance_desc))

                        GroupSpacerMedium()

                        // 智能逐字歌词（对齐 Flamingo smart word-by-word）
                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_smart_wbw_lyric),
                                onClick = {
                                    SettingsLibrary.SmartWordByWordLyric =
                                        !SettingsLibrary.SmartWordByWordLyric
                                },
                                checkedLambda = { SettingsLibrary.SmartWordByWordLyric }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_smart_wbw_lyric_desc))

                        GroupSpacerMedium()

                        // 歌词时间偏移：正值 = 歌词提前，负值 = 歌词延后
                        RoundColumn {
                            val offsetSteps = listOf(-500, -300, -200, -100, 0, 100, 200, 300, 500)
                            fun offsetLabel(ms: Int) = when {
                                ms > 0 -> "+$ms ms"
                                else -> "$ms ms"
                            }
                            SelectItem(
                                title = stringResource(id = R.string.settings_performance_lyric_timing_offset),
                                items = offsetSteps.map { offsetLabel(it) },
                                value = {
                                    offsetLabel(
                                        offsetSteps.minByOrNull {
                                            kotlin.math.abs(it - SettingsLibrary.LyricTimingOffset)
                                        } ?: 0
                                    )
                                },
                                onValueChange = { label ->
                                    SettingsLibrary.LyricTimingOffset =
                                        offsetSteps.firstOrNull { offsetLabel(it) == label } ?: 0
                                }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_timing_offset_desc))

                        GroupSpacer()

                        // 设备歌词：把当前行写进会话标题，供车机/蓝牙设备显示
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_device_title))

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_bluetooth_lyric),
                                onClick = {
                                    SettingsLibrary.BluetoothLyricEnabled =
                                        !SettingsLibrary.BluetoothLyricEnabled
                                    // 关闭时立刻把通知栏/车机标题还给歌名，不等下一行歌词
                                    if (!SettingsLibrary.BluetoothLyricEnabled) {
                                        MediaController.clearBluetoothLyric()
                                    }
                                },
                                checkedLambda = { SettingsLibrary.BluetoothLyricEnabled }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_bluetooth_lyric_desc))

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_others))

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_blur_effect),
                                // desc = stringResource(id = R.string.settings_performance_lyric_blur_effect_desc),
                                onClick = {
                                    SettingsLibrary.LyricBlurEffect =
                                        !SettingsLibrary.LyricBlurEffect
                                },
                                checkedLambda = { SettingsLibrary.LyricBlurEffect }
                            )
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_lyric_hdr_effect),
                                onClick = {
                                    SettingsLibrary.LyricHdrEffect =
                                        !SettingsLibrary.LyricHdrEffect
                                },
                                checkedLambda = { SettingsLibrary.LyricHdrEffect }
                            )
                        }

                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_blur_effect_desc))
                        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_hdr_effect_desc))
                        GroupSpacer()
                    }
                }
            }
        )
    }