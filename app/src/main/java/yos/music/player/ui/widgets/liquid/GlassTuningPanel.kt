package yos.music.player.ui.widgets.liquid

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.MochiSlider

/**
 * ══ 玻璃调试面板（**唯一实现**）════════════════════════════════════════════
 *
 * 设置 › 外观 › 玻璃效果调节，与引导页第 4 屏，共用这一个组件。
 *
 * ── 为什么必须共用 ────────────────────────────────────────────────
 * 之前引导页写了一套自己的"玻璃预览舞台"（自绘渐变底 + 自绘迷你条/标签栏 + 自绘模糊），
 * 而设置页另有一套（banner 图 + 真 `drawBackdrop`）。两份实现的结果是：
 * 引导里调出来的观感和设置里调出来的**不是同一个东西**，而用户会以为它是。
 *
 * 现在预览卡（banner + 玻璃兄弟节点）与滑块都是同一份代码，
 * 于是"引导里能看到的"就是"设置里能调的"。
 *
 * ── 红线 ──────────────────────────────────────────────────────────
 * 玻璃控件必须是录制层 `.layerBackdrop(previewBackdrop)` 的**兄弟节点**。
 * 放进录制子树会让消费者采样到含自身的图层 —— RenderThread 自引用递归，秒崩。
 */

/** 预览卡圆角 */
private val PreviewCorner = 20.dp

/** 演示控件的基准模糊半径：与顶栏圆钮同值（4dp），倍率在此之上生效 */
private val DemoBaseBlur = 4.dp

private val demoCapsuleShape = Capsule()

/**
 * 录制层的 onDraw：**必须调 drawContent()**。
 *
 * `rememberLayerBackdrop(onDraw = ...)` 的 onDraw 是"录制进该图层的绘制内容"，
 * 不是种子底色 —— 只 drawRect 不 drawContent 会把子内容整段丢掉，
 * 图层里只剩一个透明矩形，消费者（drawBackdrop）采样到空白 → 玻璃面在、
 * 但永远没有模糊。且必须是稳定实例：它是 rememberLayerBackdrop 的 remember key，
 * 每次重组传新 lambda 会重建 LayerBackdrop，让采样层反复失效。
 */
private val PreviewBackdropOnDraw: ContentDrawScope.() -> Unit = {
    drawContent()
}

/**
 * 玻璃调试面板。
 *
 * @param scale 模糊强度倍率 0f..2f（1f = 100%）
 * @param onScaleChange 拖动/点击时回调；**不要在调用点再包一层 setter**，直接写设置项
 * @param liquid true = 液态玻璃（`BarBlurEffect` 开），false = 混色模糊（磨砂）
 * @param onLiquidChange 材质切换
 * @param showPreview 是否显示上方 banner 预览卡
 * @param showMaterialChooser 是否显示材质二选一（设置页有自己的 SelectItem，传 false）
 * @param showReset 是否显示「重置为 100%」
 */
@Composable
fun GlassTuningPanel(
    modifier: Modifier = Modifier,
    scale: Float,
    onScaleChange: (Float) -> Unit,
    liquid: Boolean,
    onLiquidChange: (Boolean) -> Unit,
    showPreview: Boolean = true,
    showMaterialChooser: Boolean = true,
    showReset: Boolean = true,
    liquidScale: Float = scale,
    frostedScale: Float = scale,
) {
    val currentScaleState = rememberUpdatedState(scale)
    val currentOnScaleChange = rememberUpdatedState(onScaleChange)

    Column(modifier.fillMaxWidth()) {
        if (showPreview) {
            GlassPreviewCard(
                scale = scale,
                liquid = liquid,
                liquidScale = liquidScale,
                frostedScale = frostedScale,
            )
            Spacer(Modifier.height(16.dp))
        }

        if (showMaterialChooser) {
            MaterialChooser(
                liquid = liquid,
                onLiquidChange = onLiquidChange,
            )
            Spacer(Modifier.height(16.dp))
        }

        GlassBlurSliderItem(
            // ★必须读取 Snapshot State（rememberUpdatedState），不能直接闭包普通 Float 参数 `scale`：
            //   否则 LiquidSlider 内永不重启的 LaunchedEffect(snapshotFlow) 观测不到重组后的新值，
            //   导致 targetValue 始终卡在初始值、滑块拖不动。
            value = { currentScaleState.value },
            onValueChange = { currentOnScaleChange.value(it) },
            glassOn = liquid,
            showReset = showReset,
        )
    }
}

/**
 * 材质二选一：液态玻璃 / 混色模糊。
 */
@Composable
private fun MaterialChooser(
    liquid: Boolean,
    onLiquidChange: (Boolean) -> Unit,
) {
    val isLight = !isFlamingoInDarkMode()
    val trackColor = if (isLight) Color(0xFF787878).copy(alpha = 0.12f)
    else Color(0xFF787880).copy(alpha = 0.24f)
    val thumbColor = if (isLight) Color.White else Color(0xFF3A3A3C)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(Capsule())
            .background(trackColor)
            .padding(3.dp),
    ) {
        val options = listOf(
            stringResource(id = R.string.settings_performance_ui_glass_material_liquid),
            stringResource(id = R.string.settings_performance_ui_glass_material_frosted),
        )
        options.forEachIndexed { index, label ->
            val selected = (index == 0) == liquid
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onLiquidChange(index == 0) },
                    )
                    .clip(Capsule())
                    .background(if (selected) thumbColor else Color.Transparent)
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        if (isLight) Color(0xFF131316) else Color(0xFFF6F6F7)
                    } else {
                        if (isLight) Color(0xFF6C6C74) else Color(0xFFA2A2AA)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 预览卡：横幅图铺满，按 [liquid] 呈现「液态玻璃（折射+边缘光）」或「混色模糊（高斯模糊+着底色）」。
 *
 * **红线**：玻璃控件必须是录制层（`.layerBackdrop(previewBackdrop)`）的**兄弟节点**。
 * 放进录制子树会让消费者采样到含自身的图层 —— RenderThread 自引用递归，秒崩。
 */
@Composable
fun GlassPreviewCard(
    scale: Float,
    modifier: Modifier = Modifier,
    liquid: Boolean = SettingsLibrary.BarBlurEffect,
    liquidScale: Float = scale,
    frostedScale: Float = scale,
) {
    val previewBackdrop = rememberLayerBackdrop(onDraw = PreviewBackdropOnDraw)
    val isDark = isFlamingoInDarkMode()
    // 横幅浅色偏亮、深色偏暗：玻璃面与前景色跟着主题走，否则白字白面在浅色横幅上糊成一片
    val contentColor = if (isDark) Color.White else Color(0xFF1B1B1D)
    val liquidScaleState = rememberUpdatedState(liquidScale.coerceIn(0f, 2f))
    val frostedScaleState = rememberUpdatedState(frostedScale.coerceIn(0f, 2f))
    val isDarkState = rememberUpdatedState(isDark)

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2.6f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(PreviewCorner))
    ) {
        // 子①：只录图片
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(previewBackdrop)
        ) {
            Image(
                painter = painterResource(
                    id = if (isDark) R.drawable.sonify_banner_dark
                    else R.drawable.sonify_banner_light
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 子②：不用 Crossfade 包裹 drawBackdrop（Crossfade 的 alpha 离屏层会干扰 Backdrop 采样）；
        // 直接用 key(liquid) 为两种材质建独立固定结构的子树，切换时立即整棵重建 RenderEffect 链。
        key(liquid) {
            val activeScaleState = if (liquid) liquidScaleState else frostedScaleState
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DemoGlassButton(
                    backdrop = previewBackdrop,
                    isLiquid = liquid,
                    scaleState = activeScaleState,
                    isDarkState = isDarkState,
                    contentColor = contentColor,
                )
                DemoGlassCapsule(
                    backdrop = previewBackdrop,
                    isLiquid = liquid,
                    scaleState = activeScaleState,
                    isDarkState = isDarkState,
                    contentColor = contentColor,
                )
            }
        }
    }
}

private fun Modifier.demoMaterialBackdrop(
    backdrop: Backdrop,
    shape: Shape,
    isLiquid: Boolean,
    scaleState: State<Float>,
    isDarkState: State<Boolean>,
): Modifier = if (isLiquid) {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur((DemoBaseBlur.value * scaleState.value).dp.toPx())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(12.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
            }
        },
        highlight = {
            if (isDarkState.value) {
                Highlight.Default.copy(alpha = 0.55f)
            } else {
                classyabaBlackRim()
            }
        },
        shadow = { Shadow.Default },
        onDrawSurface = {
            val surface = if (isDarkState.value) {
                Color.White.copy(alpha = 0.08f)
            } else {
                Color.White.copy(alpha = 0.5f)
            }
            drawRect(surface)
        },
    ).then(
        if (!isDarkState.value) Modifier.classyabaWhiteEdge(shape) else Modifier
    )
} else {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur((14f * scaleState.value).dp.toPx())
        },
        highlight = { null },
        shadow = { null },
        onDrawSurface = {
            val surface = if (isDarkState.value) {
                Color(0xFF1E1E24).copy(alpha = 0.66f)
            } else {
                Color.White.copy(alpha = 0.72f)
            }
            drawRect(surface)
        },
    )
}

@Composable
private fun DemoGlassButton(
    backdrop: Backdrop,
    isLiquid: Boolean,
    scaleState: State<Float>,
    isDarkState: State<Boolean>,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkState.value
    val frostedBorderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    Box(
        modifier
            .size(42.dp)
            .demoMaterialBackdrop(
                backdrop = backdrop,
                shape = CircleShape,
                isLiquid = isLiquid,
                scaleState = scaleState,
                isDarkState = isDarkState,
            )
            .then(
                if (!isLiquid) Modifier.border(0.75.dp, frostedBorderColor, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_cube_dotted),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 演示胶囊：液态玻璃 vs 混色模糊 */
@Composable
private fun DemoGlassCapsule(
    backdrop: Backdrop,
    isLiquid: Boolean,
    scaleState: State<Float>,
    isDarkState: State<Boolean>,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkState.value
    val frostedBorderColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    Box(
        modifier
            .height(44.dp)
            .demoMaterialBackdrop(
                backdrop = backdrop,
                shape = demoCapsuleShape,
                isLiquid = isLiquid,
                scaleState = scaleState,
                isDarkState = isDarkState,
            )
            .then(
                if (!isLiquid) Modifier.border(0.75.dp, frostedBorderColor, demoCapsuleShape)
                else Modifier
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 模糊强度滑块：0%–200%。
 *
 * 两种形态，按材质切换：
 * - 混色模糊：MochiSlider（源自 mochi-compose 的 38×24 胶囊拇指 + 6dp 细轨）
 * - 液态玻璃：LiquidSlider（AndroidLiquidGlass catalog 的玻璃滑条），拇指是 40×24dp 玻璃胶囊
 *
 * 玻璃条的采样源用本行卡片底色的画布 backdrop 兜底：调用点在 `Title` 的录制子树内时，
 * 采页面 backdrop 会自引用递归（LiquidSwitch 同款处理）。
 */
@Composable
private fun GlassBlurSliderItem(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    glassOn: Boolean,
    showReset: Boolean,
) {
    val current = value()
    val interactionSource = remember("GlassTuningPanel_sliderInteraction") {
        MutableInteractionSource()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.settings_performance_ui_glass_blur_strength),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(current * 100f).roundToInt()}%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        GlassBlurSliderControl(
            value = value,
            onValueChange = onValueChange,
            glassOn = glassOn,
        )

        if (showReset) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.settings_performance_ui_glass_blur_reset),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(Capsule())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onValueChange(1f) }
                    .padding(vertical = 6.dp, horizontal = 2.dp)
            )
        }
    }
}

@Composable
@androidx.compose.runtime.NonRestartableComposable
fun GlassBlurSliderControl(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    glassOn: Boolean,
    mochiColors: yos.music.player.ui.widgets.mochi.MochiSliderColors =
        yos.music.player.ui.widgets.mochi.MochiSliderDefaults.colors(),
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (glassOn) {
            val cardColor = MaterialTheme.colorScheme.onSecondary
            val sliderBackdrop = rememberCanvasBackdrop { drawRect(cardColor.copy(alpha = 0.08f)) }
            LiquidSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..2f,
                backdrop = sliderBackdrop,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            MochiSlider(
                value = value(),
                onValueChange = onValueChange,
                valueRange = 0f..2f,
                colors = mochiColors,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
