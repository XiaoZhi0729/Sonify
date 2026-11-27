package yos.music.player.ui.pages.settings.performance

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.hdr.LyricHdr
import yos.music.player.code.hdr.hdrSolidBrush
import yos.music.player.code.utils.lrc.LyricCustomFontManager
import yos.music.player.code.utils.lrc.LyricRenderWarmup
import yos.music.player.code.utils.lrc.lyricMainTextStyle
import yos.music.player.code.utils.lrc.parseLyricFontWeight
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.liquid.YosSlider
import yos.music.player.ui.widgets.mochi.MochiSegmentedControl
import yos.music.player.ui.widgets.mochi.MochiSegmentedDefaults

private const val WEIGHT_MIN = 100f
private const val WEIGHT_MAX = 900f
private const val WEIGHT_ANCHOR_STEP = 100f

private const val SCALE_PERCENT_MIN = 80f
private const val SCALE_PERCENT_MAX = 120f
private const val SCALE_PERCENT_ANCHOR_STEP = 10f

private const val OFFSET_MIN_MS = -600f
private const val OFFSET_MAX_MS = 600f
private const val OFFSET_ANCHOR_STEP_MS = 150f

/** 与 YosUIConfig.subTextSize 对齐的播放页翻译字号（sp）。 */
private const val PLAYBACK_TRANSLATION_FONT_SIZE_SP = 18f

/** 100% 缩放下预览卡片翻译行高（sp），随字号缩放比例等比例调整。 */
private const val PLAYBACK_TRANSLATION_LINE_HEIGHT_SP = 24f

/**
 * 磁性吸附计算：当数值靠近最近的固定锚点（跨度的 [snapFraction] 内）时自动吸附，
 * 在锚点之外则允许无极停留。返回 `(吸附后数值, 是否命中锚点)`。
 */
internal fun snapToMagneticAnchor(
    raw: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    anchorStep: Float,
    snapFraction: Float = 0.024f,
): Pair<Float, Boolean> {
    val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
    if (anchorStep <= 0f) return clamped to false
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: return clamped to false
    val snapRadius = span * snapFraction
    val nearestIndex = ((clamped - valueRange.start) / anchorStep).roundToInt()
    val nearest = (valueRange.start + nearestIndex * anchorStep)
        .coerceIn(valueRange.start, valueRange.endInclusive)
    return if (abs(clamped - nearest) <= snapRadius) {
        nearest to true
    } else {
        clamped to false
    }
}

/**
 * 计算与 [YosSlider]（[LiquidSlider] / [MochiSlider]）拇指中心完全同源的 X 坐标（px）。
 * - `MochiSlider`：拇指宽 38dp，中心行程为 `[19dp, width - 19dp]`。
 * - `LiquidSlider`：拇指宽 40dp，`translationX = (-20dp + width * f).coerceIn(-10dp, width - 30dp)`，
 *   对应中心坐标 `(width * f).coerceIn(10dp, width - 10dp)`。
 */
internal fun sliderAnchorCenterX(
    widthPx: Float,
    fraction: Float,
    isLiquidGlass: Boolean,
    density: Float,
): Float {
    if (widthPx <= 0f) return 0f
    val f = fraction.coerceIn(0f, 1f)
    return if (isLiquidGlass) {
        val edgeInset = (10f * density).coerceAtMost(widthPx / 2f)
        (widthPx * f).coerceIn(edgeInset, (widthPx - edgeInset).coerceAtLeast(edgeInset))
    } else {
        val halfThumb = 19f * density
        val travel = (widthPx - halfThumb * 2f).coerceAtLeast(1f)
        halfThumb + travel * f
    }
}

@Composable
fun LyricSetting(navController: NavController) =
    SettingBackground {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // 横屏独立字号预览切换（仅在 LyricLandscapeFontScaleIndependent 为 true 时可切换）
        var previewLandscape by remember { mutableStateOf(false) }

        val fontPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    LyricCustomFontManager.importFontFromUri(context, uri)
                }
                when (result) {
                    is LyricCustomFontManager.ImportResult.Success -> {
                        LyricRenderWarmup.onRenderStyleChanged(context)
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.settings_performance_lyric_custom_font_applied,
                                result.displayName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    is LyricCustomFontManager.ImportResult.Failure -> {
                        val msgId = when (result.reason) {
                            LyricCustomFontManager.FailureReason.UNSUPPORTED_FORMAT ->
                                R.string.settings_performance_lyric_custom_font_unsupported
                            else -> R.string.settings_performance_lyric_custom_font_invalid
                        }
                        Toast.makeText(context, msgId, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Title(
            title = stringResource(id = R.string.settings_performance_lyric_title),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 34.5.dp,
            content = {
                item("settings") {
                    LyricSettingContent(
                        previewLandscape = previewLandscape,
                        onSelectPreviewLandscape = { previewLandscape = it },
                        onPickFont = {
                            fontPickerLauncher.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "font/collection",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        }
                    )
                }
            }
        )
    }

@Composable
private fun LyricSettingContent(
    previewLandscape: Boolean,
    onSelectPreviewLandscape: (Boolean) -> Unit,
    onPickFont: () -> Unit,
) {
    val context = LocalContext.current
    var independentLandscape by remember {
        mutableStateOf(SettingsLibrary.LyricLandscapeFontScaleIndependent)
    }
    LaunchedEffect(SettingsLibrary.LyricLandscapeFontScaleIndependent) {
        independentLandscape = SettingsLibrary.LyricLandscapeFontScaleIndependent
    }
    val effectivePreviewLandscape = independentLandscape && previewLandscape
    val activeScale = if (effectivePreviewLandscape) {
        SettingsLibrary.LyricLandscapeFontScale.coerceIn(0.8f, 1.2f)
    } else {
        SettingsLibrary.LyricFontScale.coerceIn(0.8f, 1.2f)
    }
    val activeWeight = parseLyricFontWeight(SettingsLibrary.LyricFontWeight)
    val customFontPath = SettingsLibrary.LyricCustomFontPath
    val customFontName = SettingsLibrary.LyricCustomFontName
    val lineBalance = SettingsLibrary.LyricLineBalance
    val duetAlignment = SettingsLibrary.LyricDuetAlignment
    var showResetFontSizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(independentLandscape) {
        if (!independentLandscape && previewLandscape) {
            onSelectPreviewLandscape(false)
        }
    }

    // 连续拖动滑块时防抖触发后台排版预热，避免每像素拖动重建整首歌词测量协程。
    var hasInitializedWarmup by remember { mutableStateOf(false) }
    LaunchedEffect(
        activeWeight,
        SettingsLibrary.LyricFontScale,
        SettingsLibrary.LyricLandscapeFontScale,
        independentLandscape,
        customFontPath,
        lineBalance,
        duetAlignment,
    ) {
        if (!hasInitializedWarmup) {
            hasInitializedWarmup = true
            return@LaunchedEffect
        }
        delay(250)
        LyricRenderWarmup.onRenderStyleChanged(context)
    }

    Column(Modifier.fillMaxSize()) {
        // 1. 预览区（作为列表首部随页面滚动，深色卡片 + 1:1 真实播放页字号）
        LyricStylePreviewArea(
            weight = activeWeight,
            scale = activeScale,
            customFontPath = customFontPath,
            lineBalance = lineBalance,
            independentLandscape = independentLandscape,
            previewLandscape = effectivePreviewLandscape,
            onSelectPreviewLandscape = onSelectPreviewLandscape,
        )

        GroupSpacerMedium()

        // 第一：独立配置横屏字体大小
        RoundColumn {
            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_landscape_independent),
                onClick = {
                    val next = !independentLandscape
                    independentLandscape = next
                    SettingsLibrary.LyricLandscapeFontScaleIndependent = next
                    if (!next && previewLandscape) {
                        onSelectPreviewLandscape(false)
                    }
                },
                checkedLambda = { independentLandscape }
            )
        }

        GroupSpacerMedium()

        // 第二：字体字重（100~900，每 100 磁性吸附）
        MagneticLyricSliderCard(
            title = stringResource(id = R.string.settings_performance_lyric_style_font_weight),
            valueText = activeWeight.toString(),
            currentValue = activeWeight.toFloat(),
            valueRange = WEIGHT_MIN..WEIGHT_MAX,
            anchorStep = WEIGHT_ANCHOR_STEP,
            onValueCommitted = { snapped ->
                SettingsLibrary.LyricFontWeight = snapped.roundToInt().toString()
            }
        )

        GroupSpacerMedium()

        // 第三：字体大小（80%~120%，每 10% 磁性吸附，卡片内附带红色重置入口）
        val fontSizeTitle = when {
            !independentLandscape ->
                stringResource(id = R.string.settings_performance_lyric_style_font_size)
            effectivePreviewLandscape ->
                stringResource(id = R.string.settings_performance_lyric_font_size_landscape)
            else ->
                stringResource(id = R.string.settings_performance_lyric_font_size_portrait)
        }
        val activePercent = (activeScale * 100f).roundToInt().toFloat()
        MagneticLyricSliderCard(
            title = fontSizeTitle,
            valueText = "${activePercent.roundToInt()}%",
            currentValue = activePercent,
            valueRange = SCALE_PERCENT_MIN..SCALE_PERCENT_MAX,
            anchorStep = SCALE_PERCENT_ANCHOR_STEP,
            syncKey = effectivePreviewLandscape,
            onValueCommitted = { snapped ->
                val nextScale = (snapped.roundToInt() / 100f).coerceIn(0.8f, 1.2f)
                if (effectivePreviewLandscape) {
                    SettingsLibrary.LyricLandscapeFontScale = nextScale
                } else {
                    SettingsLibrary.LyricFontScale = nextScale
                }
            },
            extraContent = {
                Divider()
                LabelItem(
                    title = stringResource(id = R.string.settings_performance_lyric_font_size_reset),
                    superLink = true,
                    showArrow = false,
                    onClick = { showResetFontSizeDialog = true }
                )
            }
        )

        GroupSpacerMedium()

        // 第四：自定义字体
        ListHeader(
            content = stringResource(id = R.string.settings_performance_lyric_custom_section)
        )
        RoundColumn {
            val displayFontName = customFontName.ifBlank {
                stringResource(id = R.string.settings_performance_lyric_custom_font_system_default)
            }
            LabelItem(
                title = stringResource(id = R.string.settings_performance_lyric_custom_font_pick),
                desc = stringResource(
                    id = R.string.settings_performance_lyric_custom_font_desc,
                    displayFontName
                ),
                onClick = onPickFont
            )
            AnimatedVisibility(
                visible = customFontPath.isNotBlank(),
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Divider()
                    LabelItem(
                        title = stringResource(id = R.string.settings_performance_lyric_custom_font_reset),
                        superLink = true,
                        showArrow = false,
                        onClick = {
                            LyricCustomFontManager.clearCustomFont(context)
                            Toast.makeText(
                                context,
                                R.string.settings_performance_lyric_custom_font_reset_done,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }

        GroupSpacerMedium()

        // 7. 歌词时间偏移（-600ms ~ +600ms，每 150ms 磁性吸附）
        val offsetMs = SettingsLibrary.LyricTimingOffset
            .coerceIn(OFFSET_MIN_MS.toInt(), OFFSET_MAX_MS.toInt())
        MagneticLyricSliderCard(
            title = stringResource(id = R.string.settings_performance_lyric_timing_offset),
            valueText = offsetLabel(offsetMs),
            desc = stringResource(id = R.string.settings_performance_lyric_timing_offset_desc),
            currentValue = offsetMs.toFloat(),
            valueRange = OFFSET_MIN_MS..OFFSET_MAX_MS,
            anchorStep = OFFSET_ANCHOR_STEP_MS,
            origin = 0f,
            onValueCommitted = { snapped ->
                SettingsLibrary.LyricTimingOffset = snapped.roundToInt()
            }
        )

        GroupSpacerMedium()

        // 8. 平衡行模式 & 对唱分栏对齐
        RoundColumn {
            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_line_balance),
                desc = stringResource(id = R.string.settings_performance_lyric_line_balance_desc),
                onClick = {
                    SettingsLibrary.LyricLineBalance =
                        !SettingsLibrary.LyricLineBalance
                },
                checkedLambda = { SettingsLibrary.LyricLineBalance }
            )

            Divider()

            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_duet_alignment),
                desc = stringResource(id = R.string.settings_performance_lyric_duet_alignment_desc),
                onClick = {
                    SettingsLibrary.LyricDuetAlignment =
                        !SettingsLibrary.LyricDuetAlignment
                },
                checkedLambda = { SettingsLibrary.LyricDuetAlignment }
            )
        }

        GroupSpacer()

        // ---------------- 动效 ----------------
        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_motion))

        RoundColumn {
            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_smart_wbw_lyric),
                onClick = {
                    SettingsLibrary.SmartWordByWordLyric =
                        !SettingsLibrary.SmartWordByWordLyric
                },
                checkedLambda = { SettingsLibrary.SmartWordByWordLyric }
            )

            Divider()

            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_blur_effect),
                onClick = {
                    SettingsLibrary.LyricBlurEffect =
                        !SettingsLibrary.LyricBlurEffect
                },
                checkedLambda = { SettingsLibrary.LyricBlurEffect }
            )

            Divider()

            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_hdr_effect),
                desc = stringResource(id = R.string.settings_performance_lyric_hdr_effect_desc),
                onClick = {
                    SettingsLibrary.LyricHdrEffect =
                        !SettingsLibrary.LyricHdrEffect
                },
                checkedLambda = { SettingsLibrary.LyricHdrEffect }
            )
            if (SettingsLibrary.LyricHdrEffect) {
                Divider()
                LyricHdrStrengthItem()
            }
        }

        GroupSpacer()

        // ---------------- 设备 ----------------
        ListHeader(content = stringResource(id = R.string.settings_performance_lyric_device_title))

        RoundColumn {
            SwitchItem(
                title = stringResource(id = R.string.settings_performance_lyric_notification),
                desc = stringResource(id = R.string.settings_performance_lyric_notification_desc),
                onClick = {
                    SettingsLibrary.NotificationLyricEnabled =
                        !SettingsLibrary.NotificationLyricEnabled
                    if (!SettingsLibrary.NotificationLyricEnabled) {
                        MediaController.clearSessionLyric()
                    }
                },
                checkedLambda = { SettingsLibrary.NotificationLyricEnabled }
            )
        }

        GroupSpacer()
    }

    if (showResetFontSizeDialog) {
        SonifyDialog(
            title = stringResource(id = R.string.settings_performance_lyric_font_size_reset_confirm_title),
            message = stringResource(id = R.string.settings_performance_lyric_font_size_reset_confirm_message),
            positiveContent = stringResource(id = R.string.settings_performance_lyric_font_size_reset_confirm),
            onPositive = {
                showResetFontSizeDialog = false
                SettingsLibrary.LyricFontScale = 1.0f
                SettingsLibrary.LyricLandscapeFontScale = 1.0f
                Toast.makeText(
                    context,
                    R.string.settings_performance_lyric_font_size_reset_done,
                    Toast.LENGTH_SHORT
                ).show()
            },
            negativeContent = stringResource(id = R.string.settings_performance_lyric_font_size_reset_cancel),
            onNegative = { showResetFontSizeDialog = false },
            onDismissRequest = { showResetFontSizeDialog = false },
            destructive = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    }
}

/**
 * 歌词预览卡片（模拟播放页暗色背景，1:1 使用播放页 37.5sp × scale 主词字号与 18sp 翻译字号）。
 */
@Composable
private fun LyricStylePreviewArea(
    weight: Int,
    scale: Float,
    customFontPath: String,
    lineBalance: Boolean,
    independentLandscape: Boolean,
    previewLandscape: Boolean,
    onSelectPreviewLandscape: (Boolean) -> Unit,
) {
    val mainStyle = remember(weight, scale, customFontPath, lineBalance) {
        lyricMainTextStyle(
            scaleOverride = scale,
            weightOverride = weight,
            customFontPath = customFontPath,
        )
    }
    val clampedScale = scale.coerceIn(0.8f, 1.2f)
    val translationStyle = remember(mainStyle.fontFamily, clampedScale) {
        TextStyle(
            fontSize = (PLAYBACK_TRANSLATION_FONT_SIZE_SP * clampedScale).sp,
            lineHeight = (PLAYBACK_TRANSLATION_LINE_HEIGHT_SP * clampedScale).sp,
            fontWeight = FontWeight.Normal,
            fontFamily = mainStyle.fontFamily,
        )
    }
    val cardShape = ContinuousRoundedRectangle(22.dp)
    val hdrEffect = SettingsLibrary.LyricHdrEffect
    val hdrStrength = SettingsLibrary.LyricHdrStrength
    val hdrBoost = if (hdrEffect) LyricHdr.boost(hdrStrength) else 1f
    val hdrBrush = remember(hdrBoost) {
        if (hdrBoost > 1.001f) hdrSolidBrush(Color.White, hdrBoost) else null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(cardShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1D1E26),
                        Color(0xFF121318),
                    )
                )
            )
            .border(0.75.dp, Color.White.copy(alpha = 0.08f), cardShape)
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PreviewLyricPair(
                lyric = "I know, I know I've let you down",
                translation = "我知道，我让你失望了",
                mainStyle = mainStyle,
                translationStyle = translationStyle,
                mainColor = Color.White,
                translationColor = Color.White.copy(alpha = 0.62f),
                hdrBrush = hdrBrush,
                plusGlowFallback = hdrEffect && hdrBrush == null,
            )
            PreviewLyricPair(
                lyric = "I've been a fool to myself",
                translation = "我一直在自欺欺人",
                mainStyle = mainStyle,
                translationStyle = translationStyle,
                mainColor = Color.White.copy(alpha = 0.55f),
                translationColor = Color.White.copy(alpha = 0.42f),
            )

            AnimatedContent(
                targetState = independentLandscape,
                transitionSpec = {
                    (fadeIn(tween(200)) togetherWith fadeOut(tween(150)))
                        .using(SizeTransform(clip = false))
                },
                label = "preview_mode_transition"
            ) { showSegmented ->
                if (showSegmented) {
                    MochiSegmentedControl(
                        selectedIndex = if (previewLandscape) 1 else 0,
                        onSelect = { index -> onSelectPreviewLandscape(index == 1) },
                        items = listOf(
                            stringResource(id = R.string.settings_performance_lyric_preview_option_portrait),
                            stringResource(id = R.string.settings_performance_lyric_preview_option_landscape),
                        ),
                        colors = MochiSegmentedDefaults.colors(
                            track = Color.White.copy(alpha = 0.14f),
                            thumb = Color.White.copy(alpha = 0.24f),
                            selectedContent = Color.White,
                            content = Color.White.copy(alpha = 0.68f),
                            shadow = Color.Black,
                        ),
                        behavior = MochiSegmentedDefaults.behavior(
                            height = 32.dp,
                            segmentPadding = 14.dp,
                        ),
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.settings_performance_lyric_preview_shared),
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewLyricPair(
    lyric: String,
    translation: String,
    mainStyle: TextStyle,
    translationStyle: TextStyle,
    mainColor: Color,
    translationColor: Color,
    hdrBrush: Brush? = null,
    plusGlowFallback: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = lyric,
            style = if (hdrBrush != null) mainStyle.copy(brush = hdrBrush) else mainStyle,
            color = if (hdrBrush != null) Color.Unspecified else mainColor,
            modifier = if (plusGlowFallback) {
                Modifier.graphicsLayer {
                    blendMode = BlendMode.Plus
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            } else {
                Modifier
            },
        )
        Text(
            text = translation,
            style = translationStyle,
            color = translationColor,
        )
    }
}

/**
 * 带固定锚点刻度与磁性吸附的无极滑块卡片（无加减按钮）。
 */
@Composable
private fun MagneticLyricSliderCard(
    title: String,
    valueText: String,
    currentValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    anchorStep: Float,
    onValueCommitted: (Float) -> Unit,
    desc: String? = null,
    origin: Float? = null,
    syncKey: Any? = Unit,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    var sliderValue by remember(syncKey) {
        mutableFloatStateOf(currentValue.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    var lastSnappedAnchor by remember(syncKey) { mutableStateOf<Float?>(null) }

    // 仅在外部发生显著变化（如点击“重置字体大小”或切换横竖屏）时同步，避免拖动取整引起微跳。
    LaunchedEffect(currentValue, syncKey) {
        val target = currentValue.coerceIn(valueRange.start, valueRange.endInclusive)
        if (abs(sliderValue - target) >= 1f) {
            sliderValue = target
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "slider_title_transition",
                modifier = Modifier.weight(1f)
            ) { animatedTitle ->
                Text(
                    text = "$animatedTitle · $valueText",
                    fontSize = 13.5.sp,
                    color = LocalContentColor.current.copy(alpha = 0.65f),
                )
            }
        }

        RoundColumn {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                YosSlider(
                    value = { sliderValue },
                    onValueChange = { raw ->
                        val (snapped, hitAnchor) = snapToMagneticAnchor(
                            raw = raw,
                            valueRange = valueRange,
                            anchorStep = anchorStep,
                        )
                        if (hitAnchor) {
                            if (lastSnappedAnchor != snapped) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                lastSnappedAnchor = snapped
                            }
                        } else {
                            lastSnappedAnchor = null
                        }
                        sliderValue = snapped
                        onValueCommitted(snapped)
                    },
                    valueRange = valueRange,
                    origin = origin,
                    modifier = Modifier.fillMaxWidth()
                )

                AnchorTickTrack(
                    valueRange = valueRange,
                    anchorStep = anchorStep,
                    currentValue = sliderValue,
                )

                if (desc != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = desc,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        color = LocalContentColor.current.copy(alpha = 0.5f)
                    )
                }
            }
            if (extraContent != null) {
                extraContent()
            }
        }
    }
}

@Composable
private fun AnchorTickTrack(
    valueRange: ClosedFloatingPointRange<Float>,
    anchorStep: Float,
    currentValue: Float,
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: return
    if (anchorStep <= 0f) return
    val tickColor = LocalContentColor.current.copy(alpha = 0.28f)
    val activeTickColor = MaterialTheme.colorScheme.primary
    val count = ((span / anchorStep).roundToInt()).coerceAtLeast(1)
    val isLiquidGlass = SettingsLibrary.BarBlurEffect
    val density = LocalDensity.current.density

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val cy = size.height / 2f
        for (i in 0..count) {
            val anchor = (valueRange.start + i * anchorStep)
                .coerceIn(valueRange.start, valueRange.endInclusive)
            val fraction = ((anchor - valueRange.start) / span).coerceIn(0f, 1f)
            val x = sliderAnchorCenterX(
                widthPx = size.width,
                fraction = fraction,
                isLiquidGlass = isLiquidGlass,
                density = density,
            )
            val isActive = abs(currentValue - anchor) < 0.5f
            drawCircle(
                color = if (isActive) activeTickColor else tickColor,
                radius = if (isActive) 2.5.dp.toPx() else 1.8.dp.toPx(),
                center = Offset(x, cy)
            )
        }
    }
}

private fun offsetLabel(ms: Int): String = if (ms > 0) "+$ms ms" else "$ms ms"

/**
 * HDR 高光强度：用掉屏幕可用亮度余量的多少（0–100%）。
 */
@Composable
private fun LyricHdrStrengthItem() {
    var value by remember("LyricHdrStrengthItem") {
        mutableFloatStateOf(SettingsLibrary.LyricHdrStrength.coerceIn(0f, 1f))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.settings_performance_lyric_hdr_strength),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(value * 100f).roundToInt()}%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        YosSlider(
            value = { value },
            onValueChange = {
                value = it
                SettingsLibrary.LyricHdrStrength = it
            },
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
    }
}
