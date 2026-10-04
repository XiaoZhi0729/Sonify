package yos.music.player.ui.pages.settings.others

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blankj.utilcode.util.AppUtils
import yos.music.player.R
import yos.music.player.ui.UI
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.startWeb
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.update.UpdateSettingsItems

private val bannerCorner = YosRoundedCornerShape(9.dp)

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

                        val coolapkLink = "https://www.coolapk.com/u/35925506"

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.5.dp)
                                .aspectRatio(1080f / 608f)
                                .clip(bannerCorner)
                        ) {
                            Image(
                                painter = painterResource(
                                    id = if (isFlamingoInDarkMode()) R.drawable.sonify_banner_dark
                                    else R.drawable.sonify_banner_light
                                ),
                                contentDescription = "Sonify",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_others_about_info))
                        val appVersion = remember("About_appVersion") {
                            mutableStateOf(AppUtils.getAppVersionName())
                        }

                        RoundColumn {
                            DefaultItem(
                                title = stringResource(id = R.string.app_name),
                                onClick = null,
                                desc = appVersion.value
                            )
                            UpdateSettingsItems()
                        }

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_others_about_developers))
                        RoundColumn {
                            LabelItem(
                                title = "XiaoZhi",
                                desc = stringResource(id = R.string.settings_others_about_developers_yos_x)
                            ) {
                                startWeb(
                                    url = "https://github.com/XiaoZhi0729",
                                    context
                                )
                            }
                        }

                        GroupSpacer()

                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about_acknowledgements)
                            ) {
                                navController.navigate(UI.Settings.Acknowledgements)
                            }
                        }

                        GroupSpacer()

                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about_contact_developer),
                                desc = stringResource(id = R.string.settings_others_about_developer_coolapk),
                                superLink = true
                            ) {
                                startWeb(
                                    url = coolapkLink,
                                    context
                                )
                            }
                        }

                        // POC Debug Entry
                        /*RoundColumn {
                            LabelItem(
                                title = "🧪 POC: Rust Native Online Playback",
                                desc = "Test md3Music's Rust server → ExoPlayer integration"
                            ) {
                                navController.navigate("poc_debug")
                            }
                        }*/
                    }
                }
            }
        )
    }