package yos.music.player.ui.pages.settings.others

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blankj.utilcode.util.AppUtils
import yos.music.player.R
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.startWeb
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

@Composable
fun About(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_others_about),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        val context = LocalContext.current

                        ListHeader(content = stringResource(id = R.string.settings_others_about_info))
                        val appVersion = remember("About_appVersion") {
                            mutableStateOf(AppUtils.getAppVersionName())
                        }
                        val telegramLink = "aHR0cHM6Ly90Lm1lL0ZsYW1pbmdvVXBkYXRlQW5kTmV3cw=="

                        RoundColumn {
                            DefaultItem(
                                title = stringResource(id = R.string.app_name),
                                onClick = null,
                                desc = appVersion.value
                            )
                        }

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_others_about_developers))
                        RoundColumn {
                            LabelItem(
                                title = "Yos-X (MULTIPLY STUDIO)",
                                desc = stringResource(id = R.string.settings_others_about_developers_yos_x)
                            ) {
                                startWeb(
                                    url = "https://github.com/Yos-X",
                                    context
                                )
                            }
                        }

                        GroupSpacer()

                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about_contact_us),
                                superLink = true
                            ) {
                                val linkResult = Base64.decode(telegramLink, Base64.URL_SAFE).decodeToString()
                                startWeb(
                                    url = linkResult,
                                    context
                                )
                            }
                        }
                        ListHeader(content = stringResource(id = R.string.settings_others_about_contact_us_desc))

                        GroupSpacer()

                        // POC Debug Entry
                        RoundColumn {
                            LabelItem(
                                title = "🧪 POC: Rust Native Online Playback",
                                desc = "Test md3Music's Rust server → ExoPlayer integration"
                            ) {
                                navController.navigate("poc_debug")
                            }
                        }
                    }
                }
            }
        )
    }