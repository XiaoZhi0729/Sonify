package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

@Composable
fun UserInterfaceSetting(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_performance_ui_title),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        // ListHeader(content = stringResource(id = R.string.settings_performance_ui_basic))

                        val systemInDarkTheme = isSystemInDarkTheme()

                        RoundColumn {
                            ColorModeOptions(
                                currentModeProvider = { SettingsLibrary.CustomTheme },
                                systemInDarkTheme = systemInDarkTheme,
                                onModeChange = {
                                    SettingsLibrary.CustomTheme = it
                                }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_theme_auto),
                                onClick = {
                                    // 关闭自动时落回系统当前生效的模式（同 Cresto 行为）
                                    SettingsLibrary.CustomTheme =
                                        if (SettingsLibrary.CustomTheme == "Auto") {
                                            if (systemInDarkTheme) "Dark" else "Light"
                                        } else {
                                            "Auto"
                                        }
                                },
                                checkedLambda = { SettingsLibrary.CustomTheme == "Auto" }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_blur_effect_title),
                                // desc = stringResource(id = R.string.settings_performance_ui_blur_effect_desc),
                                onClick = {
                                    SettingsLibrary.BarBlurEffect = !SettingsLibrary.BarBlurEffect
                                },
                                checkedLambda = { SettingsLibrary.BarBlurEffect }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_ui_blur_effect_desc))

                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                            GroupSpacerMedium()

                            val showCornerSetDialog = remember("UserInterfaceSetting_showCornerSetDialog") {
                                mutableStateOf(false)
                            }

                            RoundColumn {
                                LabelItem(
                                    title = stringResource(id = R.string.settings_performance_ui_screen_corner_title),
                                    superLink = true
                                ) {
                                    showCornerSetDialog.value = true
                                }
                            }
                            ListHeader(content = stringResource(id = R.string.settings_performance_ui_screen_corner_desc))

                            if (showCornerSetDialog.value) {
                                ScreenCornerSetDialog {
                                    showCornerSetDialog.value = false
                                }
                            }
                        }

                        GroupSpacerMedium()

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_show_volume_bar),
                                // desc = stringResource(id = R.string.settings_performance_ui_nowplaying_show_volume_bar_desc),
                                onClick = {
                                    SettingsLibrary.NowPlayingShowVolumeBar =
                                        !SettingsLibrary.NowPlayingShowVolumeBar
                                },
                                checkedLambda = { SettingsLibrary.NowPlayingShowVolumeBar }
                            )
                        }

                        ListHeader(content = stringResource(id = R.string.settings_performance_ui_nowplaying_show_volume_bar_desc))
                        GroupSpacerMedium()

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_background_effect),
                                // desc = stringResource(id = R.string.settings_performance_ui_nowplaying_background_effect_desc),
                                onClick = {
                                    SettingsLibrary.NowplayingBackgroundEffect =
                                        !SettingsLibrary.NowplayingBackgroundEffect
                                },
                                checkedLambda = { SettingsLibrary.NowplayingBackgroundEffect }
                            )
                        }

                        ListHeader(content = stringResource(id = R.string.settings_performance_ui_nowplaying_background_effect_desc))

                        GroupSpacer()
                    }
                }
            }
        )
    }