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
import yos.music.player.data.objects.DynamicCoverState
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
            titleHorizontalPadding = 34.5.dp,
            content = {
                item("playback") {
                    Column(Modifier.fillMaxSize()) {
                        // 显示 + 背景合成一张卡：这五项都是"播放页上出现什么"，
                        // 拆成两张卡再各配一个标题，等于用两条标题解释同一件事。
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
                            Divider()
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_static_fullscreen_album),
                                onClick = {
                                    SettingsLibrary.NowplayingStaticFullScreenAlbum =
                                        !SettingsLibrary.NowplayingStaticFullScreenAlbum
                                },
                                checkedLambda = { SettingsLibrary.NowplayingStaticFullScreenAlbum }
                            )
                            Divider()
                            SwitchItem(
                                title = stringResource(id = R.string.settings_performance_ui_nowplaying_dynamic_cover),
                                onClick = {
                                    SettingsLibrary.NowPlayingDynamicCover =
                                        !SettingsLibrary.NowPlayingDynamicCover
                                    DynamicCoverState.onSettingChanged()
                                },
                                checkedLambda = { SettingsLibrary.NowPlayingDynamicCover }
                            )
                            if (SettingsLibrary.NowPlayingDynamicCover) {
                                Divider()
                                SwitchItem(
                                    title = stringResource(id = R.string.settings_performance_ui_nowplaying_dynamic_cover_mobile),
                                    onClick = {
                                        SettingsLibrary.NowPlayingDynamicCoverMobile =
                                            !SettingsLibrary.NowPlayingDynamicCoverMobile
                                        DynamicCoverState.onSettingChanged()
                                    },
                                    checkedLambda = { SettingsLibrary.NowPlayingDynamicCoverMobile }
                                )
                            }
                            Divider()
                            BackgroundModeItem()
                        }

                        GroupSpacer()

                        // ---------------- 流体网格（保持内联） ----------------
                        if (SettingsLibrary.NowplayingBackgroundEffect &&
                            SettingsLibrary.NowplayingBackgroundMode ==
                            SettingsLibrary.BACKGROUND_MODE_MESH
                        ) {
                            MeshSection()
                        }
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
        // 三档里"流体网格"对多数用户是陌生概念，只有这种时候才写一句；
        // 原来那句"由原版 Pear Wall 壁纸移植而来"讲的是出身不是效果，已删。
        desc = stringResource(id = R.string.settings_performance_ui_nowplaying_background_mode_mesh_desc),
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
 *
 * 说明只留两条，且都是"不写就会误判"的：
 * - 律动：关掉后不再做音频分析（行为变化）
 * - 画质：省电档连模糊都跟着变糊（反直觉副作用）
 * "流体网格来自 Pear Wall 移植"这种来源说明已删 —— 用户要的是选哪个好看，
 * 不是这东西的出身。
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
                // 存的是预设数组下标，labels 是界面顺序 —— 必须反查，不能直接当下标用。
                val uiIndex = MeshPresetOrder.uiIndexForPreset(
                    MeshPresetOrder.PORTRAIT,
                    SettingsLibrary.NowplayingBackgroundPortraitPreset,
                )
                portraitLabels[uiIndex]
            },
            onValueChange = { selected ->
                val uiIndex = portraitLabels.indexOf(selected)
                if (uiIndex >= 0) {
                    SettingsLibrary.NowplayingBackgroundPortraitPreset =
                        MeshPresetOrder.presetForUiIndex(MeshPresetOrder.PORTRAIT, uiIndex)
                }
            }
        )
        Divider()
        SelectItem(
            title = stringResource(id = R.string.settings_playback_mesh_preset_landscape),
            items = landscapeLabels,
            value = {
                val uiIndex = MeshPresetOrder.uiIndexForPreset(
                    MeshPresetOrder.LANDSCAPE,
                    SettingsLibrary.NowplayingBackgroundLandscapePreset,
                )
                landscapeLabels[uiIndex]
            },
            onValueChange = { selected ->
                val uiIndex = landscapeLabels.indexOf(selected)
                if (uiIndex >= 0) {
                    SettingsLibrary.NowplayingBackgroundLandscapePreset =
                        MeshPresetOrder.presetForUiIndex(MeshPresetOrder.LANDSCAPE, uiIndex)
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
