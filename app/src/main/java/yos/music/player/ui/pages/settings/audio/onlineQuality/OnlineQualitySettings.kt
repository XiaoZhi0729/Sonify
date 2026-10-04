package yos.music.player.ui.pages.settings.audio.onlineQuality

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.repositories.IntentSource
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.PerSongQualityIntent
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

/**
 * 在线音质设置：WiFi/流量分设档位 + 降级提示开关。
 *
 * 这里是全 App **唯一**能写偏好（[SettingsLibrary.OnlineQualityWifi] /
 * [SettingsLibrary.OnlineQualityMobile]）的地方；播放页只能写本曲覆盖。
 * 这样分设不会被另一个入口默默抹掉（断言 A4/A5）。
 * 改完立即生效仍走同一条"先探测、有变化才重开"的事务（见 MediaController.reapplyOnlineQuality）。
 */
@Composable
fun OnlineQualitySettings(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_audio_online_quality),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        val scope = rememberCoroutineScope()
                        val context = LocalContext.current

                        // 选项与标签用下标双向映射：旧实现拿本地化文案反查常量，
                        // 反查失败就静默写回"标准 128K"——换语言/改文案会把用户设置打掉
                        val qualityOptions = remember {
                            listOf(
                                KugouQuality.STANDARD to R.string.quality_standard,
                                KugouQuality.HIGH to R.string.quality_high,
                                KugouQuality.LOSSLESS to R.string.quality_lossless,
                                KugouQuality.HIRES to R.string.quality_hires,
                            )
                        }
                        val labels = qualityOptions.map { stringResource(id = it.second) }
                        // 标签在组合期一次性解析成普通数据，局部函数里不再调 @Composable 的 stringResource
                        val labelByTier = qualityOptions.mapIndexed { index, option ->
                            option.first to labels[index]
                        }.toMap()
                        fun tierAt(label: String): String? =
                            labels.indexOf(label).takeIf { it >= 0 }?.let { qualityOptions[it].first }
                        fun labelOf(tier: String): String = labelByTier[tier] ?: tier

                        // 改完偏好后的就地说明：只存"当时生效的是哪个网络"，文案在组合期解析
                        var noticeForSource by remember { mutableStateOf<IntentSource?>(null) }

                        ListHeader(stringResource(id = R.string.settings_audio_online_quality))
                        RoundColumn {
                            /**
                             * 偏好写入唯一路径：全局改动视为最强意图，
                             * 顺便清掉所有本曲例外（否则会出现"设置了却不生效"的黑盒）。
                             */
                            fun applyPreference(isWifi: Boolean, tier: String) {
                                val current =
                                    if (isWifi) SettingsLibrary.OnlineQualityWifi
                                    else SettingsLibrary.OnlineQualityMobile
                                if (tier == current) return
                                SettingsLibrary.updateOnlineQuality(
                                    wifi = if (isWifi) tier else null,
                                    mobile = if (isWifi) null else tier
                                )
                                PerSongQualityIntent.clearAll()
                                val governing = KugouQuality.preferenceForNetwork(context)
                                if (governing.tier != tier) {
                                    // 改的不是当前网络那一格：不发无意义的重解析。
                                    // 旧实现先清缓存再重开，而重开时读的是另一格——
                                    // 用户听到一次卡顿却什么也没变（"改了没反应"）
                                    noticeForSource = governing.source
                                    return
                                }
                                noticeForSource = null
                                scope.launch { MediaController.reapplyOnlineQuality() }
                            }

                            SelectItem(
                                title = stringResource(id = R.string.settings_audio_online_quality_wifi),
                                items = labels,
                                value = { labelOf(SettingsLibrary.OnlineQualityWifi) },
                                onValueChange = { label ->
                                    tierAt(label)?.let { applyPreference(isWifi = true, tier = it) }
                                }
                            )

                            Divider()

                            SelectItem(
                                title = stringResource(id = R.string.settings_audio_online_quality_mobile),
                                items = labels,
                                value = { labelOf(SettingsLibrary.OnlineQualityMobile) },
                                onValueChange = { label ->
                                    tierAt(label)?.let { applyPreference(isWifi = false, tier = it) }
                                }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_audio_online_quality_downgrade_toast),
                                onClick = {
                                    SettingsLibrary.ShowQualityDowngradeToast =
                                        !SettingsLibrary.ShowQualityDowngradeToast
                                },
                                checkedLambda = { SettingsLibrary.ShowQualityDowngradeToast }
                            )
                        }

                        // 两档同值：历史上会被播放页一并写死，这里诚实提醒可分开设置（不伪造恢复）
                        if (SettingsLibrary.OnlineQualityWifi == SettingsLibrary.OnlineQualityMobile) {
                            ListHeader(content = stringResource(id = R.string.quality_pref_same_value_hint))
                        }
                        noticeForSource?.let { source ->
                            ListHeader(
                                content = stringResource(
                                    id = R.string.quality_pref_note_other_network,
                                    stringResource(
                                        id = if (source == IntentSource.MOBILE_PREF)
                                            R.string.settings_network_mobile_short
                                        else R.string.settings_network_wifi_short
                                    )
                                )
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_audio_online_quality_downgrade_toast_desc))

                        GroupSpacer()
                    }
                }
            }
        )
    }
