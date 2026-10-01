package yos.music.player.ui.pages.settings.extend.statusBarLyric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cn.lyric.getter.api.API
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.YosWrapper

@Composable
fun LyricGetter(navController: NavController) =
    YosWrapper {
        val lyricAPI by lazy { API() }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.secondary)
            /*.statusBarsPadding()*/
        ) {
            val apiVersion = remember("apiVersion") {
                mutableIntStateOf(API.API_VERSION)
            }
            Title(title = stringResource(id = R.string.settings_extend_statusbarlyric),
                subTitle = "Lyric Getter API ${apiVersion.intValue}",
                onBack = {
                    navController.popBackStack()
                },
                titleHorizontalPadding = 32.dp,
                content = {
                    item("settings") {
                        Column(Modifier.fillMaxSize()) {
                            // ListHeader(stringResource(id = R.string.settings_others_statusbarlyric_basic))

                            RoundColumn {
                                SwitchItem(
                                    title = stringResource(id = R.string.settings_others_statusbarlyric_basic_switch),
                                    onClick = {
                                        SettingsLibrary.StatusBarLyricEnabled =
                                            !SettingsLibrary.StatusBarLyricEnabled
                                        lyricAPI.clearLyric()
                                    },
                                    checkedLambda = { SettingsLibrary.StatusBarLyricEnabled }
                                )

                                Divider()

                                DefaultItem(
                                    title = stringResource(id = R.string.settings_others_statusbarlyric_basic_status),
                                    onClick = null,
                                    desc = stringResource(id = if (SettingsLibrary.StatusBarLyricHooked) R.string.settings_others_statusbarlyric_basic_status_enabled else R.string.settings_others_statusbarlyric_basic_status_disabled)
                                )
                            }

                            GroupSpacer()

                            ListHeader(stringResource(id = R.string.settings_others_statusbarlyric_debug))

                            RoundColumn {
                                LabelItem(
                                    title = stringResource(id = R.string.settings_others_statusbarlyric_debug_send_lyric),
                                    // desc = stringResource(id = R.string.settings_others_statusbarlyric_debug_send_lyric_desc),
                                    superLink = true
                                ) {
                                    lyricAPI.sendLyric("[${(99999..9999999).random()}] Sonify Lyric Debug")
                                }
                            }
                            ListHeader(content = stringResource(id = R.string.settings_others_statusbarlyric_debug_send_lyric_desc))

                            GroupSpacerMedium()
                            RoundColumn {
                                LabelItem(
                                    title = stringResource(id = R.string.settings_others_statusbarlyric_debug_clear_lyric),
                                    // desc = stringResource(id = R.string.settings_others_statusbarlyric_debug_clear_lyric_desc),
                                    superLink = true
                                ) {
                                    lyricAPI.clearLyric()
                                }
                            }
                            ListHeader(content = stringResource(id = R.string.settings_others_statusbarlyric_debug_clear_lyric_desc))
                        }

                    }
                }
            )
        }
    }