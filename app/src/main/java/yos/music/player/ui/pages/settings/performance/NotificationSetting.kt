package yos.music.player.ui.pages.settings.performance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.MediaController.mediaControl
import yos.music.player.code.YosPlaybackService
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.navigation.popBackStackSafely

/**
 * 设置 > 媒体通知。
 *
 * 只有两个开关，而且都是"通知栏长什么样"，所以是一张卡两行 —— 不需要分区标题，
 * 也不需要卡后脚注（每行自带一句短说明，说清它改的是什么）。
 */
@Composable
fun NotificationSetting(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_performance_notification_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 34.5.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_notification_basic_enable_icon_title),
                                desc = stringResource(id = R.string.settings_performance_ui_notification_basic_enable_icon_desc),
                                onClick = {
                                    SettingsLibrary.NotificationEnableIcon =
                                        !SettingsLibrary.NotificationEnableIcon
                                    MediaController.mediaControl?.let {
                                        YosPlaybackService().setCustomButtons(
                                            it
                                        )
                                    }
                                },
                                checkedLambda = { SettingsLibrary.NotificationEnableIcon }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_notification_others_smaller_icon_title),
                                desc = stringResource(id = R.string.settings_performance_ui_notification_others_smaller_icon_desc),
                                onClick = {
                                    SettingsLibrary.NotificationSmallerIcon =
                                        !SettingsLibrary.NotificationSmallerIcon
                                    mediaControl?.let { YosPlaybackService().setCustomButtons(it) }
                                },
                                checkedLambda = { SettingsLibrary.NotificationSmallerIcon }
                            )
                        }

                        GroupSpacer()
                    }
                }
            }
        )
    }
