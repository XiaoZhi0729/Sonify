package yos.music.player.ui.pages.settings.performance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.SongClimaxState
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.effects.pearmesh.isPearMeshSupported

/**
 * 「音乐播放页」设置页。
 *
 * 这些项原先散在「用户界面」页里，和主题色、液态玻璃、屏幕圆角混在一起。判断标准很简单：
 * **只影响播放页的东西放这里，影响四个 Tab 主页/全局外观的留在「用户界面」**。
 * 所以封面取色背景（`TrackBackdropGradient`）不在这里 —— 它涂的是四个 Tab 的底色。
 *
 * ## 排版约定（照 LyricSetting 的写法）
 * 同类项塞进**同一个** RoundColumn、用 Divider 分隔；只有真正需要解释的组才配一句
 * ListHeader，且放在组的下方。多数设置项的标题已经说明了一切，不再逐条加说明 ——
 * 那样会把整页切成一条一条，读起来像规格文档而不是设置页。
 */
@Composable
fun PlaybackSetting(navController: NavController) =
    SettingBackground {
        Title(
            title = stringResource(id = R.string.settings_playback_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("playback") {
                    Column(Modifier.fillMaxSize()) {
                        // ---------------- 显示 ----------------
                        ListHeader(content = stringResource(id = R.string.settings_playback_section_basic))

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_show_volume_bar),
                                onClick = {
                                    SettingsLibrary.NowPlayingShowVolumeBar =
                                        !SettingsLibrary.NowPlayingShowVolumeBar
                                },
                                checkedLambda = { SettingsLibrary.NowPlayingShowVolumeBar }
                            )
                            Divider()
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_show_comments_entry),
                                onClick = {
                                    SettingsLibrary.NowPlayingShowCommentsEntry =
                                        !SettingsLibrary.NowPlayingShowCommentsEntry
                                },
                                checkedLambda = { SettingsLibrary.NowPlayingShowCommentsEntry }
                            )
                            Divider()
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_show_climax_mark),
                                onClick = {
                                    val next = !SettingsLibrary.NowPlayingShowClimaxMark
                                    SettingsLibrary.NowPlayingShowClimaxMark = next
                                    // 立刻响应开关：关掉时清标记，打开时给当前曲补一次请求，
                                    // 否则要等下一次切歌才有数据。
                                    if (next) {
                                        SongClimaxState.onSettingEnabled()
                                    } else {
                                        SongClimaxState.onSettingDisabled()
                                    }
                                },
                                checkedLambda = { SettingsLibrary.NowPlayingShowClimaxMark }
                            )
                        }

                        GroupSpacerMedium()

                        // ---------------- 背景 ----------------
                        ListHeader(content = stringResource(id = R.string.settings_playback_section_background))

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_static_fullscreen_album),
                                onClick = {
                                    SettingsLibrary.NowplayingStaticFullScreenAlbum =
                                        !SettingsLibrary.NowplayingStaticFullScreenAlbum
                                },
                                checkedLambda = { SettingsLibrary.NowplayingStaticFullScreenAlbum }
                            )
                            Divider()
                            BackgroundModeItem()
                        }

                        ListHeader(content = stringResource(id = R.string.settings_playback_background_desc))
                        GroupSpacerMedium()

                        // ---------------- 流体网格 ----------------
                        if (SettingsLibrary.NowplayingBackgroundEffect &&
                            SettingsLibrary.NowplayingBackgroundMode ==
                            SettingsLibrary.BACKGROUND_MODE_MESH
                        ) {
                            MeshSection()
                        }

                        GroupSpacer()
                    }
                }
            }
        )
    }

/** 背景模式三档：关 / 慢速漂移 / 流体网格。 */
@Composable
private fun BackgroundModeItem() {
    // 后两者共用一个「开」的布尔，样式由 NowplayingBackgroundMode 决定，
    // 所以这里必须用下拉选择而不是开关：开关表达不了两个不同的开态。
    val offLabel = stringResource(id = R.string.settings_performance_ui_nowplaying_background_mode_off)
    val driftLabel = stringResource(id = R.string.settings_performance_ui_nowplaying_background_mode_drift)
    val meshLabel = stringResource(id = R.string.settings_performance_ui_nowplaying_background_mode_mesh)
    val meshSupported = isPearMeshSupported(LocalContext.current)
    val options = remember(meshSupported) {
        if (meshSupported) listOf(offLabel, driftLabel, meshLabel)
        else listOf(offLabel, driftLabel)
    }
    SelectItem(
        title = stringResource(id = R.string.settings_performance_ui_nowplaying_background_effect),
        items = options,
        value = {
            if (!SettingsLibrary.NowplayingBackgroundEffect) offLabel
            else if (SettingsLibrary.NowplayingBackgroundMode ==
                SettingsLibrary.BACKGROUND_MODE_MESH && meshSupported
            ) meshLabel else driftLabel
        },
        onValueChange = { selected ->
            when (selected) {
                offLabel -> {
                    SettingsLibrary.NowplayingBackgroundEffect = false
                }

                meshLabel -> {
                    SettingsLibrary.NowplayingBackgroundEffect = true
                    SettingsLibrary.NowplayingBackgroundMode =
                        SettingsLibrary.BACKGROUND_MODE_MESH
                }

                else -> {
                    SettingsLibrary.NowplayingBackgroundEffect = true
                    SettingsLibrary.NowplayingBackgroundMode =
                        SettingsLibrary.BACKGROUND_MODE_DRIFT
                }
            }
        }
    )
}

/**
 * 流体网格的全部细项。
 *
 * 分三组：外观（网格样式）、动效（流动/律动）、画质（模糊/渲染）。
 * 每组一个 RoundColumn，组内用 Divider 分隔 —— 这样 11 个项只产生 3 个视觉块。
 */
@Composable
private fun MeshSection() {
    // ---------------- 外观 ----------------
    ListHeader(content = stringResource(id = R.string.settings_playback_section_mesh_look))

    val portraitLabels = listOf(
        stringResource(id = R.string.settings_playback_preset_calm),
        stringResource(id = R.string.settings_playback_preset_flow),
        stringResource(id = R.string.settings_playback_preset_bulge),
        stringResource(id = R.string.settings_playback_preset_stretch),
    )
    val landscapeLabels = listOf(
        stringResource(id = R.string.settings_playback_preset_calm),
        stringResource(id = R.string.settings_playback_preset_flow),
        stringResource(id = R.string.settings_playback_preset_bulge),
        stringResource(id = R.string.settings_playback_preset_stretch),
        stringResource(id = R.string.settings_playback_preset_sweep),
    )

    RoundColumn {
        // 预设内部索引不一定按形变强度递增，这里用一张映射表把「界面上的第 N 项」
        // 对到「预设数组里的下标」，保证列表按由静到动的顺序排。
        SelectItem(
            title = stringResource(id = R.string.settings_playback_mesh_preset_portrait),
            items = portraitLabels,
            value = {
                val order = PORTRAIT_ORDER
                val saved = SettingsLibrary.NowplayingBackgroundPortraitPreset
                    .coerceIn(0, order.lastIndex)
                portraitLabels[saved]
            },
            onValueChange = { selected ->
                val uiIndex = portraitLabels.indexOf(selected)
                if (uiIndex >= 0) {
                    SettingsLibrary.NowplayingBackgroundPortraitPreset = PORTRAIT_ORDER[uiIndex]
                }
            }
        )
        Divider()
        SelectItem(
            title = stringResource(id = R.string.settings_playback_mesh_preset_landscape),
            items = landscapeLabels,
            value = {
                val order = LANDSCAPE_ORDER
                val saved = SettingsLibrary.NowplayingBackgroundLandscapePreset
                    .coerceIn(0, order.lastIndex)
                landscapeLabels[saved]
            },
            onValueChange = { selected ->
                val uiIndex = landscapeLabels.indexOf(selected)
                if (uiIndex >= 0) {
                    SettingsLibrary.NowplayingBackgroundLandscapePreset =
                        LANDSCAPE_ORDER[uiIndex]
                }
            }
        )
        Divider()
        SwitchItem(
            title = stringResource(id = R.string.settings_playback_mesh_preset_random),
            onClick = {
                SettingsLibrary.NowplayingBackgroundRandomPreset =
                    !SettingsLibrary.NowplayingBackgroundRandomPreset
            },
            checkedLambda = { SettingsLibrary.NowplayingBackgroundRandomPreset }
        )
    }

    GroupSpacerMedium()

    // ---------------- 动效 ----------------
    ListHeader(content = stringResource(id = R.string.settings_playback_section_mesh_motion))

    val flowLabels = listOf(
        stringResource(id = R.string.settings_playback_flow_slow),
        stringResource(id = R.string.settings_playback_flow_standard),
        stringResource(id = R.string.settings_playback_flow_strong),
    )
    RoundColumn {
        SelectItem(
            title = stringResource(id = R.string.settings_playback_flow_speed),
            items = flowLabels,
            value = {
                flowLabels[
                    SettingsLibrary.NowplayingBackgroundFlowSpeed
                        .coerceIn(0, flowLabels.lastIndex)
                ]
            },
            onValueChange = { selected ->
                val index = flowLabels.indexOf(selected)
                if (index >= 0) {
                    SettingsLibrary.NowplayingBackgroundFlowSpeed = index
                }
            }
        )
        Divider()
        SwitchItem(
            title = stringResource(id = R.string.settings_performance_ui_nowplaying_background_pulse),
            onClick = {
                SettingsLibrary.NowplayingBackgroundPulse =
                    !SettingsLibrary.NowplayingBackgroundPulse
            },
            checkedLambda = { SettingsLibrary.NowplayingBackgroundPulse }
        )
        // 律动强度只在律动开着时才有意义。必须留在**同一个** RoundColumn 内用 Divider 分隔：
        // 另起一个 RoundColumn 会让两个 9dp 圆角面直接相贴，中间凹出两道弧线。
        if (SettingsLibrary.NowplayingBackgroundPulse) {
            Divider()
            PulseStrengthItem()
        }
    }

    ListHeader(content = stringResource(id = R.string.settings_playback_pulse_desc))
    GroupSpacerMedium()

    // ---------------- 画质 ----------------
    ListHeader(content = stringResource(id = R.string.settings_playback_section_mesh_quality))

    val blurLabels = listOf(
        stringResource(id = R.string.settings_playback_blur_light),
        stringResource(id = R.string.settings_playback_tier_standard),
        stringResource(id = R.string.settings_playback_blur_rich),
    )
    val qualityLabels = listOf(
        stringResource(id = R.string.settings_playback_quality_saving),
        stringResource(id = R.string.settings_playback_quality_balanced),
        stringResource(id = R.string.settings_playback_quality_smooth),
    )
    RoundColumn {
        SelectItem(
            title = stringResource(id = R.string.settings_playback_blur_strength),
            items = blurLabels,
            value = {
                blurLabels[
                    SettingsLibrary.NowplayingBackgroundBlurStrength
                        .coerceIn(0, blurLabels.lastIndex)
                ]
            },
            onValueChange = { selected ->
                val index = blurLabels.indexOf(selected)
                if (index >= 0) {
                    SettingsLibrary.NowplayingBackgroundBlurStrength = index
                }
            }
        )
        Divider()
        SelectItem(
            title = stringResource(id = R.string.settings_playback_render_quality),
            items = qualityLabels,
            value = {
                qualityLabels[
                    SettingsLibrary.NowplayingBackgroundRenderQuality
                        .coerceIn(0, qualityLabels.lastIndex)
                ]
            },
            onValueChange = { selected ->
                val index = qualityLabels.indexOf(selected)
                if (index >= 0) {
                    SettingsLibrary.NowplayingBackgroundRenderQuality = index
                }
            }
        )
    }

    ListHeader(content = stringResource(id = R.string.settings_playback_quality_hint))
}

@Composable
private fun PulseStrengthItem() {
    val pulseLabels = listOf(
        stringResource(id = R.string.settings_playback_tier_soft),
        stringResource(id = R.string.settings_playback_tier_standard),
        stringResource(id = R.string.settings_playback_tier_strong),
    )
    SelectItem(
        title = stringResource(id = R.string.settings_performance_ui_nowplaying_background_pulse_strength),
        items = pulseLabels,
        value = {
            pulseLabels[
                SettingsLibrary.NowplayingBackgroundPulseStrength
                    .coerceIn(0, pulseLabels.lastIndex)
            ]
        },
        onValueChange = { selected ->
            val index = pulseLabels.indexOf(selected)
            if (index >= 0) {
                SettingsLibrary.NowplayingBackgroundPulseStrength = index
            }
        }
    )
}

/**
 * 界面列表顺序 → 预设数组下标。
 *
 * 预设数组是上游导出的原始顺序，**不是**按形变强度排的：竖屏数组里最静的是 idx2、
 * 最动的是 idx3，所以直接用数组下标当列表项会让用户看到「1 比 2 动」这种反直觉顺序。
 * 这里按平均顶点位移从小到大排好（数值见 PearMeshPresets 的顶点数据），
 * 让列表从「平静」到「拉伸」单调递增。
 */
private val PORTRAIT_ORDER = listOf(2, 0, 1, 3)
private val LANDSCAPE_ORDER = listOf(2, 4, 0, 3, 1)
