package yos.music.player.ui.pages.settings.performance.userinterface

import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import io.github.alexzhirkevich.cupertino.CupertinoSlider
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.circleEdgeLight
import yos.music.player.ui.widgets.liquid.LiquidSlider
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 预览卡圆角 */
private val PreviewCorner = 20.dp

/** 演示控件的基准模糊半径：与顶栏圆钮同值（4dp），倍率在此之上生效 */
private val DemoBaseBlur = 4.dp

private val demoCapsuleShape = Capsule()

/**
 * 录制层的 onDraw：**必须调 drawContent()**。
 *
 * `rememberLayerBackdrop(onDraw = ...)` 的 onDraw 是"录制进该图层的绘制内容"，不是种子底色 ——
 * 只 drawRect 不 drawContent 会把子内容整段丢掉，图层里只剩一个透明矩形，消费者
 * （drawBackdrop）采样到空白 → 玻璃面在、但永远没有模糊。
 * 且必须是稳定实例：它是 rememberLayerBackdrop 的 remember key，每次重组传新 lambda
 * 会重建 LayerBackdrop，让采样层反复失效。
 */
private val PreviewBackdropOnDraw: ContentDrawScope.() -> Unit = {
    drawContent()
}

/**
 * 玻璃效果调节：一张横幅图 + 图上的玻璃控件（实时预览）+ 模糊强度滑块。
 *
 * 滑块值 [SettingsLibrary.GlassBlurScale] 是**倍率**，只乘玻璃控件的模糊半径
 * （顶栏圆钮 / 底栏容器 / 迷你播放器 / 玻璃开关 / 下拉 / 对话框），
 * 顶部渐进模糊等背景类效果不参与，见 GlassBlurStrength.kt。
 *
 * 本页持有一份本地 [scale] 作为**实时源**：拖动时预览控件直接从参数拿到新值，
 * 不依赖设置项读取的生效时机；同时每次变更写回 [SettingsLibrary] 供全 App 使用。
 */
@Composable
fun GlassEffectSetting(navController: NavController) {
    var scale by remember("GlassEffectSetting_scale") {
        mutableFloatStateOf(SettingsLibrary.GlassBlurScale.coerceIn(0f, 2f))
    }

    SettingBackground {
        Title(title = stringResource(id = R.string.settings_performance_ui_glass_effect_title),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("glass_effect") {
                    Column(Modifier.fillMaxSize()) {
                        GroupSpacerMedium()

                        GlassPreviewCard(scale = scale)

                        GroupSpacerMedium()

                        RoundColumn {
                            GlassBlurSliderItem(
                                // 传 lambda 而不是 Float：LiquidSlider 内部用 snapshotFlow 观察这个读取，
                                // 传值会在首次组合时被闭包固定，重置按钮就推不动它
                                value = { scale },
                                onValueChange = {
                                    scale = it
                                    SettingsLibrary.GlassBlurScale = it
                                }
                            )
                            Divider()
                            LabelItem(
                                title = stringResource(id = R.string.settings_performance_ui_glass_blur_reset),
                                superLink = true
                            ) {
                                scale = 1f
                                SettingsLibrary.GlassBlurScale = 1f
                            }
                        }

                        ListHeader(content = stringResource(id = R.string.settings_performance_ui_glass_effect_desc))

                        // 库在 API<31 上根本不建 RenderEffect：滑块拖了也没观感，先说清楚。
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                            ListHeader(content = stringResource(id = R.string.settings_performance_ui_glass_effect_unsupported))
                        }

                        GroupSpacer()
                    }
                }
            })
    }
}

/**
 * 预览卡：横幅图铺满，玻璃控件浮在图上。
 *
 * **红线**：玻璃控件必须是录制层（`.layerBackdrop(previewBackdrop)`）的**兄弟节点**。
 * 放进录制子树会让消费者采样到含自身的图层 —— RenderThread 自引用递归，秒崩。
 */
@Composable
private fun GlassPreviewCard(scale: Float, modifier: Modifier = Modifier) {
    val previewBackdrop = rememberLayerBackdrop(onDraw = PreviewBackdropOnDraw)
    val isDark = isFlamingoInDarkMode()
    // 横幅浅色偏亮、深色偏暗：玻璃面与前景色跟着主题走，否则白字白面在浅色横幅上糊成一片
    val contentColor = if (isDark) Color.White else Color(0xFF1B1B1D)
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.32f)
    val edgeColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.85f)
    val surface: DrawScope.() -> Unit = remember(surfaceColor) { { drawRect(surfaceColor) } }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .aspectRatio(16f / 9f)
            .clip(YosRoundedCornerShape(PreviewCorner))
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

        // 子②：玻璃控件（兄弟节点），材质与顶栏圆钮/底栏胶囊逐项一致，只是模糊半径由 [scale] 驱动
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DemoGlassButton(
                backdrop = previewBackdrop,
                scale = scale,
                surface = surface,
                contentColor = contentColor,
                edgeColor = edgeColor
            )
            DemoGlassCapsule(
                backdrop = previewBackdrop,
                scale = scale,
                surface = surface,
                contentColor = contentColor,
                edgeColor = edgeColor
            )
        }
    }
}

/** 演示圆钮：与 LiquidTopBarButton 同配方（vibrancy + blur + API33 折射 + 边缘光） */
@Composable
private fun DemoGlassButton(
    backdrop: Backdrop,
    scale: Float,
    surface: DrawScope.() -> Unit,
    contentColor: Color,
    edgeColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(42.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(DemoBaseBlur.toPx() * scale)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lens(8.dp.toPx(), 24.dp.toPx())
                    }
                },
                highlight = { null },
                shadow = { null },
                onDrawSurface = surface
            )
            .circleEdgeLight(color = edgeColor, width = 0.15.dp, blurRadius = 0.5.dp),
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

/** 演示胶囊：与顶栏圆钮同配方，只把形状换成胶囊 */
@Composable
private fun DemoGlassCapsule(
    backdrop: Backdrop,
    scale: Float,
    surface: DrawScope.() -> Unit,
    contentColor: Color,
    edgeColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .height(44.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { demoCapsuleShape },
                effects = {
                    vibrancy()
                    blur(DemoBaseBlur.toPx() * scale)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        lens(8.dp.toPx(), 24.dp.toPx())
                    }
                },
                highlight = { null },
                shadow = { null },
                onDrawSurface = surface
            )
            .capsuleEdgeLight(color = edgeColor, width = 0.15.dp, blurRadius = 0.5.dp)
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
 * 两种形态，按「液态玻璃效果」开关切换：
 * - 关：CupertinoSlider，与设置页既有滑条（屏幕圆角校准对话框）同一套写法（白拇指 + 投影）
 * - 开：LiquidSlider（AndroidLiquidGlass catalog 的玻璃滑条），拇指是 40×24dp 玻璃胶囊
 *
 * 玻璃条的采样源用本行卡片底色的画布 backdrop 兜底：本页在 `Title` 的录制子树内，
 * 采页面 backdrop 会自引用递归（LiquidSwitch 同款处理）。
 */
@Composable
private fun GlassBlurSliderItem(
    value: () -> Float,
    onValueChange: (Float) -> Unit
) {
    val current = value()
    val glassOn = SettingsLibrary.BarBlurEffect
    val interactionSource = remember("GlassEffectSetting_sliderInteraction") {
        MutableInteractionSource()
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

        if (glassOn) {
            val cardColor = MaterialTheme.colorScheme.onSecondary
            val sliderBackdrop = rememberCanvasBackdrop { drawRect(cardColor) }
            LiquidSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..2f,
                backdrop = sliderBackdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, start = 6.dp, end = 6.dp)
            )
        } else {
            CupertinoTheme {
                CupertinoSlider(
                    value = current,
                    onValueChange = onValueChange,
                    valueRange = 0f..2f,
                    thumb = {
                        Spacer(
                            Modifier
                                .size(23.dp)
                                .hoverable(interactionSource = interactionSource)
                                .shadow(
                                    8.dp,
                                    CircleShape,
                                    clip = false,
                                    spotColor = Color.Black.copy(alpha = 0.55f)
                                )
                                .background(Color.White, CircleShape)
                        )
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * 胶囊边缘光：`circleEdgeLight` 的圆角矩形版（描边画在轮廓上再裁剪进内部，只显示内半圈），
 * NORMAL 模糊 + ADD 混合，参数与顶栏圆钮一致。
 */
private fun Modifier.capsuleEdgeLight(
    color: Color,
    width: Dp,
    blurRadius: Dp
): Modifier = drawWithContent {
    drawContent()
    if (color.alpha <= 0f || width <= 0.dp) return@drawWithContent
    val radius = size.height / 2f
    val strokePx = ceil(width.toPx().coerceAtMost(radius)) * 2f
    val blurPx = blurRadius.toPx()
    val path = Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, radius, radius, Path.Direction.CW)
    }
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx
            if (blurPx > 0f) {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        }
        canvas.save()
        canvas.nativeCanvas.clipPath(path)
        canvas.nativeCanvas.drawPath(path, paint)
        canvas.restore()
    }
}
