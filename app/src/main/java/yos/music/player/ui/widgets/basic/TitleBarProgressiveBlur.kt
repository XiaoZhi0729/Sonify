package yos.music.player.ui.widgets.basic

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.internal.RuntimeShaderEffect

/**
 * 顶栏真·渐进模糊（移植自 NexioSchedule 的 ProgressiveBlurTopBar，AGSL 版）。
 *
 * 与旧实现（整块恒定 blur(4dp) + 一道垂直 alpha 渐变遮罩，即库示例自述的
 * "alpha-masked progressive blur"）的区别：这里模糊**半径本身**随 Y 连续变化，
 * 顶部最大 12dp、向下收到 0，是真正逐像素的渐进散焦，而不是"糊得均匀、只是淡出得渐变"。
 *
 * 管线（API 33+）：AGSL 32 点黄金角抖动多重采样近似高斯散焦 → 8 点小半径去噪。
 * 着色器直接写在离屏缓冲上，缓冲由 backdrop 的 `padding` 向外扩一圈，
 * 使模糊能采样到节点框外的真实内容，边缘不发虚。
 *
 * API < 33 降级为单一半径模糊（与旧实现一致，无渐进）。
 */
private val MaxBlurRadius = 14.dp

/**
 * 模糊层向顶栏**下方**多画的高度：布局高度不变，只把采样/绘制延伸出去。
 * 渐进衰减与淡出尾巴因此能延伸到顶栏下方，观感上是一条更长的过渡，
 * 而不是在顶栏底边处戛然而止。
 */
private val BlurTail = 24.dp

/**
 * 淡出总长度（相对模糊层底部向上量）。它必须 ≤ 模糊层高度，
 * 且**恰好在层底降到 0**，否则层底边界处会留下一条硬边。
 */
private val FadeLength = 42.dp

/**
 * 页面底色渐变在**顶栏最顶端**的不透明度（底端恒为 0，按模糊能量 u² 平滑递增到这里）。
 * 1.0 = 顶栏最顶端就是页面本色（状态栏区完全不透出内容）；当前取 0.8，使状态栏区
 * 仍能透出一层渐进模糊的内容，顶部不会是一块死板的纯色。越往下越透、
 * 渐渐露出渐进模糊的内容；颜色取自页面背景（[titleBarProgressiveBlur] 的 tint），
 * 因此整条渐变与页面背景同色系，不会出现"顶栏比页面更白/更灰"的色差。
 * 曲线取 u²（随模糊能量而非半径）的原因见着色器内注释：保证涂层不先于模糊出现。
 */
private const val TINT_MAX_ALPHA = 0.8f

/**
 * 顶栏渐进模糊的修饰符。
 *
 * 布局高度保持调用处给的原值（不撑高顶栏），但采样/绘制会向下方多延伸 [BlurTail]，
 * 所以淡出尾巴落在顶栏下方而不是被裁掉。
 *
 * @param backdrop 顶栏采样源（见 [rememberTitleBackdrop]）
 * @param tintColor 渐变用页面底色；必须与页面实际背景一致（见 [LocalTitlePageColor]），
 * 否则顶栏渐变会与页面出现色差
 * @param exportedBackdrop 非空时把本层**绘制后的结果**（模糊 + 底色 + 淡出，不含节点内容）
 * 同时录进这个层。顶栏上的玻璃圆钮用它做采样源：圆钮背后的真实像素是这层材质，
 * 而不是 [backdrop] 里的原始内容；只采样原始内容会让钮内画面比周围更清晰、且缺顶栏底色。
 * 录制块与节点的绘制同帧、按节点局部坐标对齐，故与圆钮采样位置天然一致。
 */
@Composable
fun titleBarProgressiveBlur(
    backdrop: Backdrop,
    tintColor: Color,
    exportedBackdrop: LayerBackdrop? = null
): Modifier {
    val effects: com.kyant.backdrop.BackdropEffectScope.() -> Unit =
        remember(tintColor) {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val scale = this.downsampleScale
                    val maxRadiusPx = MaxBlurRadius.roundToPx().toFloat()
                    // 缓冲向外扩一圈，边缘采样可摸到框外真实内容
                    padding = maxRadiusPx
                    val pad = padding * scale
                    val contentW = size.width * scale
                    val contentH = size.height * scale
                    val bufferW = contentW + 2f * pad
                    val bufferH = contentH + 2f * pad
                    // 淡出终点固定在层底（否则层底出现硬边），起点由 FadeLength 决定
                    val fadeStart = ((size.height - FadeLength.toPx()) * scale).coerceAtLeast(0f)

                    val blurShader =
                        obtainRuntimeShader("ProgressiveBlur", PROGRESSIVE_BLUR_SHADER)
                    blurShader.setFloatUniform("contentOrigin", pad, pad)
                    blurShader.setFloatUniform("contentSize", contentW, contentH)
                    blurShader.setFloatUniform("bufferSize", bufferW, bufferH)
                    blurShader.setFloatUniform("maxRadius", maxRadiusPx * scale)
                    blurShader.setFloatUniform("fadeStart", fadeStart)
                    blurShader.setColorUniform("tint", tintColor)
                    blurShader.setFloatUniform("tintMax", TINT_MAX_ALPHA)
                    effect(RuntimeShaderEffect(blurShader, "content"))
                } else {
                    // 无 AGSL：保持旧观感（恒定半径），不扩边
                    blur(4.dp.toPx())
                }
            }
        }

    return Modifier
        // 只放大测量/绘制，不改上报高度：顶栏布局仍是原高度，
        // 多出来的 [BlurTail] 作为出界的淡出尾巴压在下方内容上。
        .layout { measurable, constraints ->
            val tail = BlurTail.roundToPx()
            if (!constraints.hasBoundedHeight || tail <= 0) {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                val barHeight = constraints.maxHeight
                val placeable = measurable.measure(
                    constraints.copy(maxHeight = barHeight + tail)
                )
                layout(placeable.width, barHeight) { placeable.place(0, 0) }
            }
        }
        .drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = effects,
            exportedBackdrop = exportedBackdrop
        )
}

// 两端导数为 0 的 S 曲线，收尾比 smoothstep 更绵
private const val SOFTER_STEP = """
float softerstep(float a, float b, float x) {
    float s = clamp((x - a) / max(b - a, 0.0001), 0.0, 1.0);
    return s * s * s * (s * (s * 6.0 - 15.0) + 10.0);
}
"""

private const val PROGRESSIVE_BLUR_SHADER = """
uniform shader content;
uniform float2 contentOrigin;
uniform float2 contentSize;
uniform float2 bufferSize;
uniform float maxRadius;
uniform float fadeStart;
layout(color) uniform half4 tint;
uniform float tintMax;

$SOFTER_STEP

float ign(float2 p) {
    return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

half4 progressiveBlur(float2 coord, float radius) {
    if (radius < 0.5) {
        return content.eval(coord);
    }
    float ang = ign(coord) * 6.2831853;
    float ca = cos(ang);
    float sa = sin(ang);
    float2 u = float2(ca, sa) * radius;
    float2 v = float2(-sa, ca) * radius;
    float2 maxCoord = max(bufferSize - 1.0, float2(0.0));
    half4 sum = half4(0.0);
    float wsum = 0.0;
    half4 c;
    c = content.eval(coord);
    if (c.a > 0.02) { sum += c * 0.07571; wsum += 0.07571; }
    c = content.eval(clamp(coord + u * -0.20194 + v * 0.18499, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.07077; wsum += 0.07077; }
    c = content.eval(clamp(coord + u * 0.03091 + v * -0.35220, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.06765; wsum += 0.06765; }
    c = content.eval(clamp(coord + u * 0.25453 + v * 0.33199, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.06468; wsum += 0.06468; }
    c = content.eval(clamp(coord + u * -0.46709 + v * -0.08262, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.06183; wsum += 0.06183; }
    c = content.eval(clamp(coord + u * 0.44247 + v * -0.28146, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.05911; wsum += 0.05911; }
    c = content.eval(clamp(coord + u * -0.14800 + v * 0.55054, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.05651; wsum += 0.05651; }
    c = content.eval(clamp(coord + u * -0.28225 + v * -0.54345, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.05402; wsum += 0.05402; }
    c = content.eval(clamp(coord + u * 0.61236 + v * 0.22363, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.05165; wsum += 0.05165; }
    c = content.eval(clamp(coord + u * -0.63706 + v * 0.26297, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.04937; wsum += 0.04937; }
    c = content.eval(clamp(coord + u * 0.30711 + v * -0.65627, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.04720; wsum += 0.04720; }
    c = content.eval(clamp(coord + u * 0.22694 + v * 0.72353, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.04512; wsum += 0.04512; }
    c = content.eval(clamp(coord + u * -0.68401 + v * -0.39640, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.04314; wsum += 0.04314; }
    c = content.eval(clamp(coord + u * 0.80242 + v * -0.17641, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.04124; wsum += 0.04124; }
    c = content.eval(clamp(coord + u * -0.48971 + v * 0.69656, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03942; wsum += 0.03942; }
    c = content.eval(clamp(coord + u * -0.11313 + v * -0.87304, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03769; wsum += 0.03769; }
    c = content.eval(clamp(coord + u * 0.69453 + v * 0.58535, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03603; wsum += 0.03603; }
    c = content.eval(clamp(coord + u * -0.93462 + v * 0.03865, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03445; wsum += 0.03445; }
    c = content.eval(clamp(coord + u * 0.68173 + v * -0.67841, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03293; wsum += 0.03293; }
    c = content.eval(clamp(coord + u * -0.04561 + v * 0.98637, float2(0.0), maxCoord));
    if (c.a > 0.02) { sum += c * 0.03148; wsum += 0.03148; }
    if (wsum < 0.0001) {
        return content.eval(coord);
    }
    return sum / wsum;
}

half4 main(float2 coord) {
    // padding 边距不上屏：直接直通
    float2 local = coord - contentOrigin;
    if (local.x < 0.0 || local.y < 0.0 ||
        local.x >= contentSize.x || local.y >= contentSize.y) {
        return content.eval(coord);
    }
    float t = clamp(local.y / max(contentSize.y, 1.0), 0.0, 1.0);
    float u = 1.0 - smoothstep(0.0, 1.0, t);
    float radius = maxRadius * u;
    half4 color = progressiveBlur(coord, radius);
    // 淡出用像素定位（而非层高的比例），这样顶栏高度变化时，
    // 尾巴与顶栏底边的相对关系保持不变
    float edge = softerstep(fadeStart, contentSize.y, local.y);
    color *= (1.0 - edge);
    if (tintMax > 0.0) {
        // 页面底色渐变：层底透明 → 层顶 tintMax。
        // 顶部基本被页面本色盖住（状态栏区与页面融为一体），
        // 往下逐渐透出渐进模糊的内容。
        //
        // 强度曲线用 u*u（模糊能量）而非 u（模糊半径）：交叉淡入的前半程
        // 涂层可见度按 alpha 线性走、而模糊要 alpha^2 才可感知，若涂层跟半径
        // 同形（u），在半径小到看不出模糊的栏中段/尾巴会单独闪出一块
        // "没有模糊的半透明涂层"（涂层先于模糊出现）；改随能量后，
        // 模糊看不出来的地方涂层同步趋零，两者必然一起显形。
        //
        // presence = 采样内容不透明度：采样层还没内容时（首帧/过渡中间态）
        // 涂层随模糊一起隐没；采样齐全（种子铺满）时恒为 1，不影响稳态。
        float presence = clamp(color.a, 0.0, 1.0);
        float tintCurve = u * u;
        color = mix(color, tint * ((1.0 - edge) * presence), tintMax * tintCurve * presence);
    }
    return color;
}
"""

/** 小半径盒式平均，压掉上一阶段抖动细噪；半径与模糊强度成正比。 */
private const val PROGRESSIVE_DENOISE_SHADER = """
uniform shader content;
uniform float2 contentOrigin;
uniform float2 contentSize;
uniform float2 bufferSize;
uniform float maxRadius;

half4 main(float2 coord) {
    float2 local = coord - contentOrigin;
    if (local.x < 0.0 || local.y < 0.0 ||
        local.x >= contentSize.x || local.y >= contentSize.y) {
        return content.eval(coord);
    }
    float t = clamp(local.y / max(contentSize.y, 1.0), 0.0, 1.0);
    float r = maxRadius * (1.0 - smoothstep(0.0, 1.0, t)) * 0.18;
    if (r < 0.4) {
        return content.eval(coord);
    }
    half4 sum = content.eval(coord);
    float wsum = 1.0;
    float2 dir = float2(1.0, 0.0);
    float2 g = float2(cos(0.7853981634), sin(0.7853981634));
    for (int i = 0; i < 8; i++) {
        float2 sc = clamp(coord + dir * r, float2(0.0), max(bufferSize - 1.0, float2(0.0)));
        half4 c = content.eval(sc);
        if (c.a > 0.02) {
            sum += c;
            wsum += 1.0;
        }
        dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
    }
    return sum / wsum;
}
"""
