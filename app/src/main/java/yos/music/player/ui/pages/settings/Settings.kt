package yos.music.player.ui.pages.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.others.BatteryOptimization
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.utils.player.CrossfadePolicy
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.repositories.KugouVipRepository
import yos.music.player.ui.UI
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(navController: NavController) =
    SettingBackground {
        val context = LocalContext.current
        val showResetConfirm = remember { mutableStateOf(false) }
        Title(title = stringResource(id = R.string.page_settings_title),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        // 酷狗账号（在线音乐登录入口，状态读取全局 KugouAccountState）
                        ListHeader(stringResource(id = R.string.settings_online_title))
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_online_kugou_account),
                                desc = stringResource(
                                    id = if (yos.music.player.data.objects.KugouAccountState.isLoggedIn)
                                        R.string.kugou_login_status_logged_in
                                    else R.string.kugou_login_status_not_logged_in
                                )
                            ) {
                                navController.toUI(UI.Settings.KugouLogin)
                            }
                        }

                        GroupSpacer()
                        // GroupSpacerMedium()
                        ListHeader(stringResource(id = R.string.settings_library_title))
                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_library_show_local_library),
                                onClick = {
                                    SettingsLibrary.ShowLocalLibrary =
                                        !SettingsLibrary.ShowLocalLibrary
                                },
                                checkedLambda = { SettingsLibrary.ShowLocalLibrary }
                            )

                            Divider()
                            SwitchItem(
                                title = stringResource(id = R.string.settings_library_refresh_everytime),
                                onClick = {
                                    SettingsLibrary.RefreshEveryTime =
                                        !SettingsLibrary.RefreshEveryTime
                                },
                                checkedLambda = { SettingsLibrary.RefreshEveryTime }
                            )

                            Divider()
                            LabelItem(title = stringResource(id = R.string.settings_library_overview)) {
                                navController.toUI(UI.Settings.LibraryOverview)
                            }
                        }

                        GroupSpacerMedium()
                        RoundColumn {
                            val scope = rememberCoroutineScope()
                            LabelItem(
                                title = stringResource(id = R.string.settings_library_refresh_now),
                                superLink = true
                            ) {
                                scope.launch(Dispatchers.Main) {
                                    var toast = Toast.makeText(
                                        context,
                                        R.string.tip_scanning,
                                        Toast.LENGTH_SHORT
                                    )
                                    toast.show()
                                    withContext(Dispatchers.IO) {
                                        MusicLibrary.scanMedia(context)
                                    }
                                    toast.cancel()
                                    val size = MediaController.mainMusicList.size
                                    if (size == 0) {
                                        toast = Toast.makeText(
                                            context,
                                            R.string.tip_no_song,
                                            Toast.LENGTH_SHORT
                                        )
                                    } else {
                                        val msg =
                                            context.getString(R.string.tip_scan_finished, size)
                                        toast = Toast.makeText(context, msg, Toast.LENGTH_SHORT)
                                    }
                                    toast.show()
                                }
                            }
                        }

                        GroupSpacer()
                        ListHeader(stringResource(id = R.string.settings_performance))
                        RoundColumn {
                            LabelItem(title = stringResource(id = R.string.settings_performance_lyric_title)) {
                                navController.toUI(UI.Settings.LyricSetting)
                            }
                            Divider()
                            LabelItem(title = stringResource(id = R.string.settings_performance_ui_title)) {
                                navController.toUI(UI.Settings.UserInterfaceSetting)
                            }
                            Divider()
                            LabelItem(title = stringResource(id = R.string.settings_performance_notification_title)) {
                                navController.toUI(UI.Settings.NotificationSetting)
                            }
                            Divider()
                            BatteryOptimizationItem()
                        }
                        ListHeader(content = stringResource(id = R.string.settings_performance_battery_miui_hint))

                        GroupSpacer()
                        ListHeader(stringResource(id = R.string.settings_audio))
                        RoundColumn {
                            LabelItem(title = stringResource(id = R.string.settings_audio_online_quality)) {
                                navController.toUI(UI.Settings.OnlineQualitySetting)
                            }
                            Divider()
                            LabelItem(title = stringResource(id = R.string.settings_audio_exoplayer)) {
                                navController.toUI(UI.Settings.ExoplayerSetting)
                            }
                        }

                        GroupSpacer()
                        ListHeader(stringResource(id = R.string.settings_play))
                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_play_history),
                                // desc = stringResource(id = R.string.settings_play_history_desc),
                                onClick = { },
                                checkedLambda = { SettingsLibrary.ListenHistory }
                            )

                            Divider()

                            SwitchItem(
                                title = stringResource(id = R.string.settings_play_crossfade),
                                desc = stringResource(id = R.string.settings_play_crossfade_desc),
                                onClick = {
                                    SettingsLibrary.CrossfadeEnabled =
                                        !SettingsLibrary.CrossfadeEnabled
                                },
                                checkedLambda = { SettingsLibrary.CrossfadeEnabled }
                            )

                            Divider()

                            // 时长档位表取 CrossfadePolicy 里同一张：避免"UI 能选、引擎不认"；
                            // 开关关掉时整行置灰不可点，不让用户改一个当前无效的参数
                            val crossfadeSeconds = CrossfadePolicy.DurationOptionsSec
                            val crossfadeLabels = crossfadeSeconds.map {
                                stringResource(id = R.string.settings_play_crossfade_sec, it)
                            }
                            SelectItem(
                                enabledProvider = { SettingsLibrary.CrossfadeEnabled },
                                title = stringResource(id = R.string.settings_play_crossfade_duration),
                                items = crossfadeLabels,
                                value = {
                                    stringResource(
                                        id = R.string.settings_play_crossfade_sec,
                                        SettingsLibrary.CrossfadeDuration.toIntOrNull()
                                            ?: CrossfadePolicy.DEFAULT_DURATION_SEC
                                    )
                                },
                                onValueChange = { label ->
                                    crossfadeSeconds.getOrNull(crossfadeLabels.indexOf(label))
                                        ?.let { SettingsLibrary.CrossfadeDuration = it.toString() }
                                }
                            )
                        }
                        ListHeader(content = stringResource(id = R.string.settings_play_history_desc))

                        GroupSpacer()
                        ListHeader(stringResource(id = R.string.settings_extend))
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_extend_statusbarlyric),
                                // desc = stringResource(id = R.string.settings_extend_statusbarlyric_desc)
                            ) {
                                navController.toUI(UI.Settings.LyricGetter)
                            }
                            SwitchItem(
                                title = stringResource(id = R.string.settings_extend_superislandlyric),
                                desc = stringResource(id = R.string.settings_extend_superislandlyric_desc),
                                onClick = {
                                    val newValue = !SettingsLibrary.SuperIslandLyricEnabled
                                    SettingsLibrary.SuperIslandLyricEnabled = newValue
                                    if (newValue) {
                                        MediaController.publishSuperIslandLyric(
                                            MediaViewModelObject.lyricMediaId.value,
                                            MediaViewModelObject.lrcEntries.value
                                        )
                                    } else {
                                        MediaController.removeSuperIslandLyric()
                                    }
                                },
                                checkedLambda = { SettingsLibrary.SuperIslandLyricEnabled }
                            )
                        }

                        GroupSpacer()
                        ListHeader(stringResource(id = R.string.settings_others))
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about),
                                // desc = stringResource(id = R.string.settings_others_about_desc)
                            ) {
                                navController.toUI(UI.Settings.About)
                            }
                            Divider()
                            DiagLogSwitchItem()
                            Divider()
                            DiagExportItem()
                        }
                        ListHeader(content = stringResource(id = R.string.settings_others_diag_hint))

                        // 一键重置所有设置为默认值（登录凭证在独立 MMKV，不受影响）
                        GroupSpacerMedium()
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_reset),
                                superLink = true
                            ) {
                                showResetConfirm.value = true
                            }
                        }
                        if (showResetConfirm.value) {
                            OptionDialog(
                                icon = {},
                                title = stringResource(id = R.string.settings_others_reset),
                                subTitle = stringResource(id = R.string.settings_others_reset_confirm),
                                content = null,
                                positiveContent = stringResource(id = R.string.settings_others_reset),
                                negativeContent = stringResource(id = R.string.common_cancel),
                                destructive = true,
                                onPositive = {
                                    showResetConfirm.value = false
                                    SettingsLibrary.resetAllToDefaults()
                                    // 这个开关也存放在 settings MMKV，但声明在 KugouVipRepository，就地复位
                                    KugouVipRepository.AutoReceiveVip = true
                                    // 诊断模块读的是内存副本，不同步就会"重置了还在按旧值写/不写"
                                    YosDiagnostics.setLoggingEnabled(SettingsLibrary.DiagLogEnabled)
                                    Toast.makeText(
                                        context,
                                        R.string.settings_others_reset_done,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onNegative = { showResetConfirm.value = false },
                                onDismissRequest = { showResetConfirm.value = false }
                            )
                        }
                    }
                }

                /*item("blank") {
                    Spacer(modifier = Modifier.height(15.dp))
                }*/
            })
    }


@Composable
private fun BatteryOptimizationItem() {
    val context = LocalContext.current
    val ignoring = remember { mutableStateOf(BatteryOptimization.isIgnoringBatteryOptimization(context)) }
    // 弹系统框回来（onResume）后刷新状态描述
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring.value = BatteryOptimization.isIgnoringBatteryOptimization(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LabelItem(
        title = stringResource(id = R.string.settings_performance_battery_title),
        desc = stringResource(
            id = if (ignoring.value) R.string.settings_performance_battery_desc_on
            else R.string.settings_performance_battery_desc_off
        ),
        superLink = false
    ) {
        BatteryOptimization.requestIgnore(context)
    }
}

/**
 * 诊断日志落盘开关。关掉只是不再写新行，已落盘的历史仍可导出
 * （否则一关就自断了正要查的那段取证，见 SettingsLibrary.DiagLogEnabled）。
 */
@Composable
private fun DiagLogSwitchItem() {
    SwitchItem(
        title = stringResource(id = R.string.settings_others_diag_title),
        desc = stringResource(id = R.string.settings_others_diag_desc),
        onClick = {
            val newValue = !SettingsLibrary.DiagLogEnabled
            SettingsLibrary.DiagLogEnabled = newValue
            // 诊断模块读的是内存副本，不同步就会"关了还在写 / 开了还是不写"
            YosDiagnostics.setLoggingEnabled(newValue)
        },
        checkedLambda = { SettingsLibrary.DiagLogEnabled }
    )
}

/**
 * 一键导出诊断报告：排空写队列落盘 → 生成单文件报告（策略快照 + 关键事件 + 日志尾部）
 * → 拉起系统分享面板。读尾部几千行放 IO 线程；行尾实时显示已落盘字节数，
 * 避免"点了以后到底有没有在记"这种无法自检的按钮。
 */
@Composable
private fun DiagExportItem() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    val stats = remember { mutableStateOf(YosDiagnostics.stats(context)) }
    LabelItem(
        title = stringResource(id = R.string.settings_others_diag_export),
        desc = if (busy.value) stringResource(id = R.string.diag_export_busy)
        else stringResource(id = R.string.diag_export_size, humanSize(stats.value.first)),
        superLink = false
    ) {
        if (busy.value) return@LabelItem
        busy.value = true
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { YosDiagnostics.export(context) }.getOrNull()
            }
            busy.value = false
            stats.value = YosDiagnostics.stats(context)
            if (report == null) {
                Toast.makeText(
                    context,
                    R.string.diag_export_fail,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val uri = YosDiagnostics.shareUri(context, report)
            if (uri == null) {
                // FileProvider 拿不到 uri（目录不在白名单）：至少告诉用户文件在哪，adb 仍可直接拉
                Toast.makeText(context, context.getString(R.string.diag_export_path, report.parent), Toast.LENGTH_LONG).show()
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            safeStartActivity(
                context,
                Intent.createChooser(send, context.getString(R.string.settings_others_diag_export)),
                null
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun safeStartActivity(context: Context, intent: Intent, options: Bundle?) {
    if (intent.resolveActivity(context.packageManager) != null) {
        ContextCompat.startActivity(context, intent, options)
    } else {
        Toast.makeText(
            context,
            context.getString(R.string.tip_intent_resolve_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}

fun startWeb(url: String, context: Context) {
    try {
        val uri: Uri =
            Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        safeStartActivity(context, intent, null)
    } catch (_: Exception) {
    }
}
