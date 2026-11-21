package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.ui.theme.AppAccentColors
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.MochiAccentSwatch
import yos.music.player.ui.widgets.mochi.MochiBlurText
import yos.music.player.ui.widgets.mochi.MochiColorPickerSheet
import yos.music.player.ui.widgets.mochi.MochiHapticProfile
import yos.music.player.ui.widgets.mochi.MochiHaptics
import yos.music.player.ui.widgets.mochi.MochiSheetDetent
import yos.music.player.ui.widgets.mochi.rememberMochiSheetState

@Composable
fun ThemeColorCard(
    selectedSpecProvider: () -> String,
    recentsProvider: () -> String,
    onSelectPreset: (String) -> Unit,
    onSelectCustom: (Color) -> Unit,
) {
    val spec = selectedSpecProvider()
    val recentsRaw = recentsProvider()
    val dark = isFlamingoInDarkMode()
    val activePreset = AppAccentColors.findPreset(spec)
    val customColor = AppAccentColors.parseCustomColor(spec)
    val resolvedColor = AppAccentColors.resolve(spec, dark)
    val recents = remember(recentsRaw) { AppAccentColors.parseRecents(recentsRaw) }

    val view = LocalView.current
    val haptics = remember(view) { MochiHaptics(view, MochiHapticProfile.IosLike) }
    val sheetState = rememberMochiSheetState(
        detents = listOf(MochiSheetDetent.Medium, MochiSheetDetent.Large),
        initial = MochiSheetDetent.Medium,
        mediumFraction = 0.72f,
    )
    var draft by remember { mutableStateOf(customColor ?: resolvedColor) }
    var dirty by remember { mutableStateOf(false) }

    val label = activePreset?.let { stringResource(it.nameRes) }
        ?: stringResource(R.string.settings_performance_ui_accent_color_custom)
    val hexText = AppAccentColors.formatHex(resolvedColor)
    val customLabel = stringResource(R.string.settings_performance_ui_accent_color_custom)
    val pickerTitle = stringResource(R.string.settings_performance_ui_accent_color_picker_title)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.5.dp, end = 18.5.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MochiBlurText(
                text = label,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = hexText,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        val totalCount = AppAccentColors.Presets.size + 1
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
        ) {
            val swatchSize = 44.dp
            val minGap = 6.dp
            val rawCols = ((maxWidth + minGap) / (swatchSize + minGap)).toInt().coerceIn(1, 6)
            val columns = if (rawCols == 5) 4 else rawCols
            val gap = if (columns > 1) (maxWidth - swatchSize * columns) / (columns - 1) else 0.dp
            val vGap = min(gap, 14.dp).coerceAtLeast(10.dp)

            Column(verticalArrangement = Arrangement.spacedBy(vGap)) {
                (0 until totalCount).chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { i ->
                            if (i < AppAccentColors.Presets.size) {
                                val preset = AppAccentColors.Presets[i]
                                val swatchColor = if (dark) preset.dark else preset.light
                                val selected = activePreset?.id == preset.id
                                MochiAccentSwatch(
                                    color = swatchColor,
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            haptics.tick()
                                            onSelectPreset(preset.id)
                                        }
                                    },
                                    contentDescription = stringResource(preset.nameRes),
                                    modifier = Modifier.size(swatchSize),
                                )
                            } else {
                                MochiAccentSwatch(
                                    color = customColor,
                                    selected = customColor != null,
                                    onClick = {
                                        draft = customColor ?: resolvedColor
                                        dirty = false
                                        sheetState.show()
                                    },
                                    contentDescription = customLabel,
                                    modifier = Modifier.size(swatchSize),
                                    rainbow = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    MochiColorPickerSheet(
        state = sheetState,
        color = draft,
        onColorChange = { picked ->
            draft = picked.copy(alpha = 1f)
            dirty = true
        },
        title = pickerTitle,
        supportsAlpha = false,
        recents = recents,
        onDismiss = {
            if (dirty) {
                haptics.tick()
                onSelectCustom(draft)
                dirty = false
            }
        },
    )
}
