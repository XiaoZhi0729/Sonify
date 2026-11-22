package yos.music.player.ui.pages.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.capsule.ContinuousRoundedRectangle
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.YosSwitch
import yos.music.player.ui.widgets.mochi.MochiSegmentedControl
import yos.music.player.ui.widgets.mochi.MochiSegmentedDefaults
import yos.music.player.ui.widgets.mochi.MochiSheet
import yos.music.player.ui.widgets.mochi.MochiSheetCloseButton
import yos.music.player.ui.widgets.mochi.MochiSheetContent
import yos.music.player.ui.widgets.mochi.MochiSheetDetent
import yos.music.player.ui.widgets.mochi.MochiSheetHeader
import yos.music.player.ui.widgets.mochi.MochiSheetState

internal const val GallerySettingsEstimatedHeightDp = 360f

internal data class GallerySheetSpec(
    val detents: List<MochiSheetDetent>,
    val initial: MochiSheetDetent,
    val mediumFraction: Float,
)

/**
 * 根据设置内容预估高度与当前窗口高度裁定使用半屏或全屏底部面板：
 * - 内容可在 56% 窗口高度内舒展呈现时使用半屏悬浮面板（露出上半屏画廊实时预览）；
 * - 横屏、分屏小窗或内容超出半屏上限时自动切到全屏底部面板，避免裁剪。
 */
internal fun resolveGallerySettingsSheetSpec(
    contentHeightEstimateDp: Float,
    windowHeightDp: Float,
): GallerySheetSpec {
    val safeHeight = windowHeightDp.coerceAtLeast(1f)
    val neededFraction = contentHeightEstimateDp / safeHeight
    return if (neededFraction <= 0.56f) {
        GallerySheetSpec(
            detents = listOf(MochiSheetDetent.Medium),
            initial = MochiSheetDetent.Medium,
            mediumFraction = neededFraction.coerceIn(0.46f, 0.56f),
        )
    } else {
        GallerySheetSpec(
            detents = listOf(MochiSheetDetent.Large),
            initial = MochiSheetDetent.Large,
            mediumFraction = 0.5f,
        )
    }
}

/**
 * 解析音乐画廊最终生效的背景模式（返回 `0/1/2` 与 [SettingsLibrary.GALLERY_BG_STATIC] /
 * [SettingsLibrary.GALLERY_BG_DRIFT] / [SettingsLibrary.GALLERY_BG_MESH] 一致）。
 */
internal fun resolveGalleryBackgroundMode(
    followPlayback: Boolean,
    customGalleryMode: Int,
    playbackEffectEnabled: Boolean,
    playbackMode: Int,
    meshSupported: Boolean,
): Int {
    val raw = if (followPlayback) {
        when {
            !playbackEffectEnabled -> SettingsLibrary.GALLERY_BG_STATIC
            playbackMode == SettingsLibrary.BACKGROUND_MODE_MESH -> SettingsLibrary.GALLERY_BG_MESH
            else -> SettingsLibrary.GALLERY_BG_DRIFT
        }
    } else {
        customGalleryMode.coerceIn(
            SettingsLibrary.GALLERY_BG_STATIC,
            SettingsLibrary.GALLERY_BG_MESH,
        )
    }
    return if (raw == SettingsLibrary.GALLERY_BG_MESH && !meshSupported) {
        SettingsLibrary.GALLERY_BG_DRIFT
    } else {
        raw
    }
}

/**
 * 音乐画廊设置面板（MOCHI 底部弹出面板）。
 */
@Composable
internal fun MusicGallerySettingsSheet(
    state: MochiSheetState,
    meshSupported: Boolean,
    onDismiss: () -> Unit = {},
) {
    MochiSheet(
        state = state,
        onDismiss = onDismiss,
    ) {
        // 必须直接在 MochiSheet（InWindowOverlay 独立 ComposeView）组合作用域内读取 SettingsLibrary，
        // 否则设置切换时面板内容不会即时重组。
        val followPlayback = SettingsLibrary.GalleryFollowPlaybackBackground
        val customMode = SettingsLibrary.GalleryBackgroundMode
        val playbackEffect = SettingsLibrary.NowplayingBackgroundEffect
        val playbackMode = SettingsLibrary.NowplayingBackgroundMode

        val effectiveMode = resolveGalleryBackgroundMode(
            followPlayback = followPlayback,
            customGalleryMode = customMode,
            playbackEffectEnabled = playbackEffect,
            playbackMode = playbackMode,
            meshSupported = meshSupported,
        )

        val staticLabel = stringResource(R.string.page_music_gallery_settings_bg_static)
        val driftLabel = stringResource(R.string.settings_performance_ui_nowplaying_background_mode_drift)
        val meshLabel = stringResource(R.string.settings_performance_ui_nowplaying_background_mode_mesh)
        val modeOptions = remember(meshSupported, staticLabel, driftLabel, meshLabel) {
            if (meshSupported) {
                listOf(staticLabel, driftLabel, meshLabel)
            } else {
                listOf(staticLabel, driftLabel)
            }
        }
        val selectedIndex = effectiveMode.coerceIn(0, modeOptions.lastIndex)
        val currentModeLabel = modeOptions[selectedIndex]

        val context = LocalContext.current
        val isDark = isFlamingoInDarkMode()
        val onSurface = MaterialTheme.colorScheme.onSurface
        val cardColor = if (isDark) Color(0xFF2C2C2E) else Color.White
        val cardShape = remember { ContinuousRoundedRectangle(18.dp) }
        val drawSwitchCard: DrawScope.() -> Unit = remember(cardColor) { { drawRect(cardColor) } }
        val switchBackdrop = rememberCanvasBackdrop(drawSwitchCard)

        fun setFollowPlayback(follow: Boolean) {
            if (!follow && followPlayback) {
                // 关闭联动瞬间锁定当前生效样式，避免背景样式突然跳变
                SettingsLibrary.GalleryBackgroundMode = effectiveMode
            }
            SettingsLibrary.GalleryFollowPlaybackBackground = follow
        }

        MochiSheetHeader(
            title = stringResource(R.string.page_music_gallery_settings_title),
            actions = {
                MochiSheetCloseButton(
                    onClick = { state.hide() },
                    contentDescription = stringResource(R.string.color_picker_close),
                )
            },
        )

        // 半屏内容未溢出时禁用 verticalScroll 手势，避免与 MochiSheet 下拉关闭手势抢事件。
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState, enabled = scrollState.maxValue > 0),
        ) {
            MochiSheetContent(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.page_music_gallery_settings_section_background),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(cardColor),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    Vibrator.click(context)
                                    setFollowPlayback(!followPlayback)
                                },
                        ) {
                            Text(
                                text = stringResource(R.string.page_music_gallery_settings_follow_playback),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (followPlayback) {
                                    stringResource(
                                        R.string.page_music_gallery_settings_follow_playback_on,
                                        currentModeLabel,
                                    )
                                } else {
                                    stringResource(R.string.page_music_gallery_settings_follow_playback_off)
                                },
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = onSurface.copy(alpha = 0.55f),
                            )
                        }
                        YosSwitch(
                            checked = followPlayback,
                            onCheckedChange = { follow ->
                                if (SettingsLibrary.BarBlurEffect) {
                                    Vibrator.click(context)
                                }
                                setFollowPlayback(follow)
                            },
                            checkedState = { SettingsLibrary.GalleryFollowPlaybackBackground },
                            contentBackdrop = switchBackdrop,
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(0.5.dp)
                            .background(onSurface.copy(alpha = 0.08f)),
                    )

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.page_music_gallery_settings_bg_style),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurface.copy(alpha = if (followPlayback) 0.55f else 1f),
                        )
                        // 显式指定中性 iOS/Mochi 配色，避免 DarkColorScheme 默认 surfaceVariant 偏紫
                        MochiSegmentedControl(
                            selectedIndex = selectedIndex,
                            onSelect = { index ->
                                SettingsLibrary.GalleryBackgroundMode = index
                            },
                            items = modeOptions,
                            enabled = !followPlayback,
                            colors = MochiSegmentedDefaults.colors(
                                track = if (isDark) Color(0xFF1C1C1E) else Color(0xFF787880).copy(alpha = 0.12f),
                                thumb = if (isDark) Color(0xFF48484A) else Color.White,
                                selectedContent = onSurface,
                                content = onSurface.copy(alpha = 0.72f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.page_music_gallery_settings_bg_mesh_hint),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}
