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
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect

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
private val MaxBlurRadius = 12.dp

/**
 * 模糊层向顶栏**下方**多画的高度：布局高度不变，只把采样/绘制延伸出去。
 * 渐进衰减与淡出尾巴因此能延伸到顶栏下方，观感上是一条更长的过渡，
 * 而不是在顶栏底边处戛然而止。
 */
private val BlurTail = 40.dp

/**
 * 淡出总长度（相对模糊层底部向上量）。它必须 ≤ 模糊层高度，
 * 且**恰好在层底降到 0**，否则层底边界处会留下一条硬边。
 * 与 [BlurTail] 的差值即"淡出从顶栏底边往上多早开始"（当前 12dp）。
 */
private val FadeLength = 52.dp

/**
 * 页面底色渐变在**顶栏最顶端**的不透明度（底端恒为 0，从下往上线性区平滑递增到这里）。
 * 1.0 = 顶栏最顶端就是页面本色（状态栏区完全不透出内容）；当前取 0.8，使状态栏区
 * 仍能透出一层渐进模糊的内容，顶部不会是一块死板的纯色。越往下越透、
 * 渐渐露出渐进模糊的内容；颜色取自页面背景（[titleBarProgressiveBlur] 的 tint），
 * 因此整条渐变与页面背景同色系，不会出现"顶栏比页面更白/更灰"的色差。
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
 */
@Composable
fun titleBarProgressiveBlur(
    backdrop: Backdrop,
    tintColor: Color
): Modifier {
    val effects: com.kyant.backdrop.BackdropEffectScope.() -> Unit =
        remember(tintColor) {
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 取整数值：backdrop 1.0.5 用 padding.toInt() 开缓冲，而录制端按 float
                    // 平移，非整数 padding 会产生 ≤1px 的采样错位（模糊边缘一圈偏色）。
                    val maxRadiusPx = MaxBlurRadius.roundToPx().toFloat()
                    // 缓冲向外扩一圈，边缘采样可摸到框外真实内容
                    padding = maxRadiusPx
                    val pad = padding
                    val contentW = size.width
                    val contentH = size.height
                    val bufferW = contentW + 2f * pad
                    val bufferH = contentH + 2f * pad
                    // 淡出终点固定在层底（否则层底出现硬边），起点由 FadeLength 决定
                    val fadeStart = (contentH - FadeLength.toPx()).coerceAtLeast(0f)

                    val blurShader =
                        obtainRuntimeShader("ProgressiveBlur", PROGRESSIVE_BLUR_SHADER)
                    blurShader.setFloatUniform("contentOrigin", pad, pad)
                    blurShader.setFloatUniform("contentSize", contentW, contentH)
                    blurShader.setFloatUniform("bufferSize", bufferW, bufferH)
                    blurShader.setFloatUniform("maxRadius", maxRadiusPx)
                    blurShader.setFloatUniform("fadeStart", fadeStart)
                    blurShader.setColorUniform("tint", tintColor.toArgb())
                    blurShader.setFloatUniform("tintMax", TINT_MAX_ALPHA)
                    effect(RenderEffect.createRuntimeShaderEffect(blurShader, "content"))

                    val denoiseShader =
                        obtainRuntimeShader("ProgressiveDenoise", PROGRESSIVE_DENOISE_SHADER)
                    denoiseShader.setFloatUniform("contentOrigin", pad, pad)
                    denoiseShader.setFloatUniform("contentSize", contentW, contentH)
                    denoiseShader.setFloatUniform("bufferSize", bufferW, bufferH)
                    denoiseShader.setFloatUniform("maxRadius", maxRadiusPx)
                    effect(RenderEffect.createRuntimeShaderEffect(denoiseShader, "content"))
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
            effects = effects
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

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

half4 progressiveBlur(float2 coord, float radius) {
    if (radius < 0.5) {
        return content.eval(coord);
    }
    float h = hash12(coord);
    float2 dir = float2(cos(h * 6.2831853), sin(h * 6.2831853));
    float2 g = float2(cos(2.39996323), sin(2.39996323));
    half4 sum = half4(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 32; i++) {
        float fi = float(i);
        float ff = (fi + 0.5) / 32.0;
        float r = radius * sqrt(ff);
        r *= 0.90 + 0.20 * fract(h * 93.9898 + fi * 0.7548776662);
        float2 o = dir * r;
        float w = exp(-ff / 0.85);
        float2 sc = clamp(coord + o, float2(0.0), max(bufferSize - 1.0, float2(0.0)));
        half4 c = content.eval(sc);
        if (c.a > 0.02) {
            sum += c * w;
            wsum += w;
        }
        dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
    }
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
        // 往下逐渐透出渐进模糊的内容；同一曲线也保证整条渐变与页面同色。
        color = mix(color, tint * (1.0 - edge), tintMax * u);
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
