// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.ui.theme.AppAccentColors
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.roundToInt

/** 颜色选择器的默认值 */
object MochiColorPickerDefaults {
    val WellSize: Dp = 28.dp
    const val RecentCount: Int = 8
}

/**
 * 源自 mochi-compose 的色块（iOS ColorPicker 行尾）：中间是当前颜色，外面一圈彩虹环；点一下弹出 [MochiColorPickerSheet]。
 */
@Composable
fun MochiColorWell(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.settings_performance_ui_accent_color_picker_title),
    supportsAlpha: Boolean = true,
    size: Dp = MochiColorPickerDefaults.WellSize,
) {
    val sheet = rememberMochiSheetState(listOf(MochiSheetDetent.Medium, MochiSheetDetent.Large))
    val recents = remember { mutableStateListOf<Color>() }
    val current by rememberUpdatedState(color)
    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) { detectTapGestures { sheet.show() } }
            .semantics {
                role = Role.Button
                contentDescription = title
                onClick {
                    sheet.show()
                    true
                }
            },
    ) {
        val ring = 3.dp.toPx()
        drawCircle(
            Brush.sweepGradient(RainbowStops),
            radius = this.size.minDimension / 2f - ring / 2f,
            style = Stroke(ring),
        )
        val swatch = this.size.minDimension / 2f - ring - 2.dp.toPx()
        if (color.alpha < 1f) drawCheckerCircle(swatch - 1.dp.toPx())
        drawCircle(color, radius = swatch)
    }
    MochiColorPickerSheet(
        state = sheet,
        color = color,
        onColorChange = onColorChange,
        title = title,
        supportsAlpha = supportsAlpha,
        recents = recents,
        onDismiss = {
            val c = current
            recents.remove(c)
            recents.add(0, c)
            while (recents.size > MochiColorPickerDefaults.RecentCount) recents.removeAt(recents.lastIndex)
        },
    )
}

/**
 * 源自 mochi-compose 的选色面板（iOS UIColorPickerViewController 风格）：
 * 顶上分段切换「网格 / 色谱 / 滑块」，下面是不透明度滑条（可选）、当前颜色预览 + Hex 与最近使用。
 */
@Composable
fun MochiColorPickerSheet(
    state: MochiSheetState,
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.settings_performance_ui_accent_color_picker_title),
    supportsAlpha: Boolean = true,
    recents: List<Color> = emptyList(),
    onDismiss: () -> Unit = {},
) {
    var mode by remember { mutableIntStateOf(0) }
    val change by rememberUpdatedState(onColorChange)
    val alpha = color.alpha
    val modeGrid = stringResource(R.string.color_picker_mode_grid)
    val modeSpectrum = stringResource(R.string.color_picker_mode_spectrum)
    val modeSliders = stringResource(R.string.color_picker_mode_sliders)
    val closeDesc = stringResource(R.string.color_picker_close)
    val opacityLabel = stringResource(R.string.color_picker_opacity)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val previewShape = remember { ContinuousRoundedRectangle(MochiRoundedCorner) }
    val previewBorder = onSurface.copy(alpha = 0.14f)
    val dividerColor = onSurface.copy(alpha = 0.12f)

    MochiSheet(state, modifier, onDismiss = onDismiss) {
        MochiSheetHeader(
            title = title,
            actions = {
                MochiSheetCloseButton(
                    onClick = { state.hide() },
                    contentDescription = closeDesc,
                )
            },
        )
        MochiSheetContent {
            MochiSegmentedControl(
                selectedIndex = mode,
                onSelect = { mode = it },
                items = listOf(modeGrid, modeSpectrum, modeSliders),
                modifier = Modifier.fillMaxWidth(),
            )
            MochiBlurCrossfade(mode, Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { m ->
                when (m) {
                    0 -> ColorGrid(color.copy(alpha = 1f)) { change(it.copy(alpha = alpha)) }
                    1 -> ColorSpectrum(color.copy(alpha = 1f)) { change(it.copy(alpha = alpha)) }
                    else -> RgbSliders(color) { change(it) }
                }
            }
            if (supportsAlpha) {
                LabeledSlider(
                    label = opacityLabel,
                    shown = (alpha * 100).roundToInt().toLong(),
                    suffix = "%",
                    value = alpha,
                    range = 0f..1f,
                    accent = color.copy(alpha = 1f),
                ) {
                    change(color.copy(alpha = it))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(
                    Modifier
                        .size(48.dp)
                        .clip(previewShape)
                        .border(0.5.dp, previewBorder, previewShape),
                ) {
                    if (color.alpha < 1f) drawCheckerRect()
                    drawRect(color)
                }
                Text(
                    text = AppAccentColors.formatHex(color),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                    color = onSurface.copy(alpha = 0.65f),
                )
                if (recents.isNotEmpty()) {
                    Box(Modifier.width(1.dp).height(32.dp).background(dividerColor))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        recents.take(MochiColorPickerDefaults.RecentCount).forEach { c ->
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(0.5.dp, previewBorder, CircleShape)
                                    .pointerInput(c) { detectTapGestures { change(c) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 莫奈/Apple 风格圆形主题色块：[color] 为 null 时画成彩虹圆 + 加号；[rainbow] 时外环用彩虹色。
 */
@Composable
fun MochiAccentSwatch(
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    rainbow: Boolean = false,
) {
    val isDark = isFlamingoInDarkMode()
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
        label = "accentSwatch",
    )
    val ringColor = color ?: MaterialTheme.colorScheme.primary
    val innerBorder = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    Canvas(
        modifier
            .mochiPressDeform()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                this.selected = selected
                this.contentDescription = contentDescription
            },
    ) {
        val r = size.minDimension / 2f
        val stroke = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val inner = r - (stroke + gap) * sel
        if (rainbow) {
            val ringAlpha = if (color == null || selected) 1f else 0.45f
            drawCircle(
                brush = Brush.sweepGradient(RainbowStops),
                radius = r - stroke / 2f,
                alpha = ringAlpha,
                style = Stroke(stroke),
            )
        } else if (sel > 0f) {
            drawCircle(
                color = ringColor.copy(alpha = sel.coerceIn(0f, 1f)),
                radius = r - stroke / 2f,
                style = Stroke(stroke),
            )
        }
        val disc = if (rainbow) minOf(inner, r - stroke - gap) else inner
        if (color == null) {
            drawCircle(Brush.sweepGradient(RainbowStops), radius = disc)
            drawPlus(disc * 0.42f, Color.White)
        } else {
            drawCircle(color, radius = disc)
            val borderW = 0.5.dp.toPx()
            drawCircle(innerBorder, radius = (disc - borderW / 2f).coerceAtLeast(0f), style = Stroke(borderW))
        }
    }
}

private fun DrawScope.drawPlus(half: Float, color: Color) {
    val w = 2.5.dp.toPx()
    drawLine(color, Offset(center.x - half, center.y), Offset(center.x + half, center.y), w, StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - half), Offset(center.x, center.y + half), w, StrokeCap.Round)
}

/** 网格：第一行灰阶，下面 12 种色相从深到浅 */
@Composable
private fun ColorGrid(selected: Color, onPick: (Color) -> Unit) {
    val cols = 12
    val rows = 10
    val colors = remember { gridColors(cols, rows) }
    val sel = remember(selected) { colors.indexOfFirst { it.toArgb() == selected.toArgb() } }
    val shape = remember { ContinuousRoundedRectangle(MochiRoundedCorner) }
    val scratch = remember { Path() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(cols / rows.toFloat())
            .clip(shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun pickAt(p: Offset) {
                        val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        onPick(colors[r * cols + c])
                    }
                    pickAt(down.position)
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        pickAt(ch.position)
                        ch.consume()
                    }
                }
            },
    ) {
        val cw = size.width / cols
        val ch = size.height / rows
        for (i in colors.indices) {
            drawRect(colors[i], topLeft = Offset((i % cols) * cw, (i / cols) * ch), size = Size(cw + 0.5f, ch + 0.5f))
        }
        if (sel >= 0) {
            val w = 3.dp.toPx()
            val rect = Rect(
                (sel % cols) * cw + w / 2f,
                (sel / cols) * ch + w / 2f,
                (sel % cols + 1) * cw - w / 2f,
                (sel / cols + 1) * ch - w / 2f,
            )
            if (selected.luminance() > 0.8f) {
                drawG2RoundRect(scratch, rect, 4.dp.toPx(), Color.Black.copy(alpha = 0.25f), style = Stroke(w + 1.5.dp.toPx()))
            }
            drawG2RoundRect(scratch, rect, 4.dp.toPx(), Color.White, style = Stroke(w))
        }
    }
}

internal fun gridColors(cols: Int, rows: Int): List<Color> {
    val out = ArrayList<Color>(cols * rows)
    for (c in 0 until cols) {
        val v = 1f - c / (cols - 1f)
        out += Color(v, v, v)
    }
    for (r in 1 until rows) {
        val l = 0.16f + (r - 1) * (0.78f / (rows - 2))
        val s = if (r < rows / 2) 1f else 1f - (r - rows / 2) * 0.06f
        for (c in 0 until cols) out += Color.hsl((200f + c * 30f) % 360f, s, l.coerceIn(0f, 1f))
    }
    return out
}

@Composable
private fun ColorSpectrum(selected: Color, onPick: (Color) -> Unit) {
    var model by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MochiSegmentedControl(model, { model = it }, listOf("HSL", "HCT", "Lab", "LCH"), Modifier.fillMaxWidth())
        MochiBlurCrossfade(model, Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { m ->
            SpectrumPlane(SpectrumModel.entries[m], selected, onPick)
        }
    }
}

internal enum class SpectrumModel(@StringRes val sliderLabelRes: Int, val sliderMax: Int, val sliderSuffix: String) {
    HSL(R.string.color_picker_axis_saturation, 100, "%"),
    HCT(R.string.color_picker_axis_chroma, 150, ""),
    Lab(R.string.color_picker_axis_lightness, 100, ""),
    LCH(R.string.color_picker_axis_chroma, 150, ""),
    ;

    fun argb(x: Float, y: Float, z: Float, scratch: FloatArray = FloatArray(3)): Int {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        val cz = z.coerceIn(0f, 1f)
        return when (this) {
            HSL -> hslToArgb(cx * 360f, cz, 1f - cy)

            HCT -> MochiColorSpaces.hctToArgb(cx * 360.0, cz * 150.0, (1f - cy) * 100.0)

            Lab -> {
                MochiColorSpaces.labToSrgb(cz * 100.0, (cx * 2f - 1f) * 128.0, (1f - cy * 2f) * 128.0, scratch)
                MochiColorSpaces.argb(scratch[0], scratch[1], scratch[2])
            }

            LCH -> {
                MochiColorSpaces.lchToSrgb((1f - cy) * 100.0, cz * 150.0, cx * 360.0, scratch)
                MochiColorSpaces.argb(scratch[0], scratch[1], scratch[2])
            }
        }
    }

    fun locate(c: Color): FloatArray {
        val raw = when (this) {
            HSL -> {
                val hsl = argbToHsl(c)
                floatArrayOf(hsl[0] / 360f, 1f - hsl[2], hsl[1])
            }

            HCT -> {
                val h = MochiColorSpaces.argbToHct(c.toArgb())
                floatArrayOf((h[0] / 360.0).toFloat(), (1.0 - h[2] / 100.0).toFloat(), (h[1] / 150.0).toFloat())
            }

            Lab -> {
                val lab = MochiColorSpaces.srgbToLab(c.red, c.green, c.blue)
                floatArrayOf((lab[1] / 256.0 + 0.5).toFloat(), (0.5 - lab[2] / 256.0).toFloat(), (lab[0] / 100.0).toFloat())
            }

            LCH -> {
                val lch = MochiColorSpaces.srgbToLch(c.red, c.green, c.blue)
                floatArrayOf((lch[2] / 360.0).toFloat(), (1.0 - lch[0] / 100.0).toFloat(), (lch[1] / 150.0).toFloat())
            }
        }
        return floatArrayOf(
            if (raw[0].isFinite()) raw[0].coerceIn(0f, 1f) else 0f,
            if (raw[1].isFinite()) raw[1].coerceIn(0f, 1f) else 0f,
            if (raw[2].isFinite()) raw[2].coerceIn(0f, 1f) else 0f,
        )
    }

    fun render(z: Float, w: Int, h: Int, active: () -> Boolean): IntArray? {
        val out = IntArray(w * h)
        val scratch = FloatArray(3)
        for (j in 0 until h) {
            if (!active()) return null
            val y = (j + 0.5f) / h
            for (i in 0 until w) out[j * w + i] = argb((i + 0.5f) / w, y, z, scratch)
        }
        return out
    }
}

@Composable
private fun SpectrumPlane(model: SpectrumModel, selected: Color, onPick: (Color) -> Unit) {
    var pos by remember(model) { mutableStateOf(model.locate(selected)) }
    var emitted by remember(model) { mutableIntStateOf(selected.toArgb()) }
    if (selected.toArgb() != emitted) {
        emitted = selected.toArgb()
        pos = model.locate(selected)
    }
    fun pick(x: Float, y: Float, z: Float) {
        pos = floatArrayOf(x, y, z)
        val c = Color(model.argb(x, y, z))
        emitted = c.toArgb()
        onPick(c)
    }
    val z = pos[2]
    val plane by produceState<ImageBitmap?>(null, model, z) {
        val pixels = withContext(Dispatchers.Default) { model.render(z, PlaneW, PlaneH) { isActive } } ?: return@produceState
        value = android.graphics.Bitmap.createBitmap(pixels, PlaneW, PlaneH, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val shape = remember { ContinuousRoundedRectangle(MochiRoundedCorner) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(shape)
                .pointerInput(model) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun pickAt(p: Offset) = pick((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f), pos[2])
                        pickAt(down.position)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            pickAt(ch.position)
                            ch.consume()
                        }
                    }
                },
        ) {
            val img = plane
            if (img != null) {
                drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            } else {
                drawRect(Color.Gray.copy(alpha = 0.2f))
            }
            val c = Offset(pos[0] * size.width, pos[1] * size.height)
            val r = 14.dp.toPx()
            drawCircle(selected, r, c)
            drawCircle(Color.White, r, c, style = Stroke(3.dp.toPx()))
        }
        LabeledSlider(
            label = stringResource(model.sliderLabelRes),
            shown = (z * model.sliderMax).roundToInt().toLong(),
            suffix = model.sliderSuffix,
            value = z,
            range = 0f..1f,
            accent = selected,
        ) { pick(pos[0], pos[1], it) }
    }
}

private const val PlaneW = 96
private const val PlaneH = 80

internal fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val hue = if (h.isFinite()) h.mod(360f).coerceIn(0f, 360f) else 0f
    val sat = if (s.isFinite()) s.coerceIn(0f, 1f) else 0f
    val lit = if (l.isFinite()) l.coerceIn(0f, 1f) else 0f
    val c = Color.hsl(hue, sat, lit)
    return c.toArgb()
}

internal fun argbToHsl(c: Color): FloatArray {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = ((max + min) / 2f).coerceIn(0f, 1f)
    val d = max - min
    if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
    val denom = 1f - kotlin.math.abs(2f * l - 1f)
    val s = if (denom <= 1e-6f) 0f else (d / denom).coerceIn(0f, 1f)
    var h = when (max) {
        r -> 60f * (((g - b) / d).mod(6f))
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (h < 0f) h += 360f
    val normalizedHue = if (h.isFinite()) h.mod(360f).coerceIn(0f, 360f) else 0f
    return floatArrayOf(normalizedHue, s, l)
}

@Composable
private fun RgbSliders(color: Color, onChange: (Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledSlider(
            label = stringResource(R.string.color_picker_channel_red),
            shown = (color.red * 255).roundToInt().toLong(),
            suffix = "",
            value = color.red,
            range = 0f..1f,
            accent = Color(0xFFFF3B30),
        ) {
            onChange(color.copy(red = it))
        }
        LabeledSlider(
            label = stringResource(R.string.color_picker_channel_green),
            shown = (color.green * 255).roundToInt().toLong(),
            suffix = "",
            value = color.green,
            range = 0f..1f,
            accent = Color(0xFF34C759),
        ) {
            onChange(color.copy(green = it))
        }
        LabeledSlider(
            label = stringResource(R.string.color_picker_channel_blue),
            shown = (color.blue * 255).roundToInt().toLong(),
            suffix = "",
            value = color.blue,
            range = 0f..1f,
            accent = Color(0xFF007AFF),
        ) {
            onChange(color.copy(blue = it))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    shown: Long,
    suffix: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
            )
            Spacer(Modifier.weight(1f))
            MochiCounterText(
                value = shown,
                color = labelColor,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                format = { "$it$suffix" },
            )
        }
        MochiSlider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = range,
            colors = MochiSliderDefaults.colors(activeTrack = accent),
        )
    }
}

private fun DrawScope.drawCheckerCircle(radius: Float) {
    val clip = Path().apply { addOval(Rect(center, radius)) }
    clipPath(clip) {
        drawCircle(Color.White, radius)
        val cell = radius / 2f
        for (i in -2 until 2) {
            for (j in -2 until 2) {
                if ((i + j) % 2 == 0) continue
                drawRect(Color(0xFFCCCCCC), Offset(center.x + i * cell, center.y + j * cell), Size(cell, cell))
            }
        }
    }
}

private fun DrawScope.drawCheckerRect() {
    drawRect(Color.White)
    val cell = 8.dp.toPx()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(Color(0xFFCCCCCC), Offset(x, y), Size(cell.coerceAtMost(size.width - x), cell.coerceAtMost(size.height - y)))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private val MochiRoundedCorner = 12.dp

internal val RainbowStops = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)
