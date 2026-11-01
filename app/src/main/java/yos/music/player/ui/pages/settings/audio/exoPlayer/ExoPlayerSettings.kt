package yos.music.player.ui.pages.settings.audio.exoPlayer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.navigation.popBackStackSafely

@Composable
fun ExoPlayerSettings(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_audio_exoplayer),
            subTitle = stringResource(id = R.string.settings_audio_exoplayer_sub),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {

                        ListHeader(stringResource(id = R.string.settings_audio_exoplayer_behaviors))
                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_audio_exoplayer_behaviors_audio_attributes),
                                // desc = stringResource(id = R.string.settings_audio_exoplayer_behaviors_audio_attributes_desc),
                                onClick = {
                                    SettingsLibrary.AudioAttributes =
                                        !SettingsLibrary.AudioAttributes
                                },
                                checkedLambda = { SettingsLibrary.AudioAttributes }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_audio_exoplayer_behaviors_audio_attributes_desc))

                        GroupSpacer()

                        ListHeader(stringResource(id = R.string.settings_audio_exoplayer_decode))
                        RoundColumn {
                            SelectItem(
                                title = stringResource(id = R.string.settings_audio_exoplayer_decode_codec),
                                items = listOf(
                                    "Auto",
                                    "FFmpeg",
                                    "System"
                                ),
                                value = { SettingsLibrary.Codec },
                                onValueChange = {
                                    SettingsLibrary.Codec = it
                                }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_audio_exoplayer_decode_codec_desc))

                        GroupSpacerMedium()

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_audio_exoplayer_decode_hardware_audio_track_playback_params),
                                onClick = {
                                    SettingsLibrary.HardwareAudioTrackPlayBackParams =
                                        !SettingsLibrary.HardwareAudioTrackPlayBackParams
                                },
                                checkedLambda = { SettingsLibrary.HardwareAudioTrackPlayBackParams }
                            )
                        }

                        // 「浮点音频输出」整项已移除（2026-11-01）。它在本工程的两个方向都是坏的：
                        // 日常 16-bit 音源根本进不了 media3 的 float 分支（开关看着没反应），
                        // 而一旦遇到高解析 PCM，该分支会静默丢弃整个人声分离处理器（实测
                        // 8 Elite Gen 5 那台机器上功能直接失效）。收益为零、触发即破坏。
                        // 与其在界面留一个永久置灰的开关，不如不展示——置灰项只会让人反复尝试。
                        // 设置字段 SettingsLibrary.AudioFloatOutput 仍保留：旧安装的已存值要能安全
                        // 读回，且 YosRenderFactory 里那段强制 false 的埋点还在用它记录上游请求值。
                        // 若将来媒体栈升到 1.8+（该分支已修）并要真正支持高解析输出，再加回入口。

                        GroupSpacer()
                    }

                }
            }
        )
    }
