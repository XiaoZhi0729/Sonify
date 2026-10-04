/*
 * Cresto thin-material recipe and shader, Apache-2.0.
 * Modified for Sonify: only thin material retained, theme injected, API 23 fallback.
 * License and attribution: assets/licenses/cresto-NOTICE.txt.
 */
package yos.music.player.ui.widgets.basic

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeRenderEffect

@Composable
internal fun rememberDialogMaterial(dark: Boolean): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 33) return null
    return remember(dark) {
        runCatching {
            val shader = RuntimeShader(DialogMaterialShader)
            val curve = if (dark) floatArrayOf(0.2f, 0.21f, 0.1f, 0.15f)
                else floatArrayOf(0.725f, 0.825f, 0.76f, 0.73f)
            curve.forEachIndexed { index, value -> shader.setFloatUniform("p$index", value) }
            shader.setFloatUniform("mapIntensity", 0.6f)
            shader.setFloatUniform("saturation", 1.35f)
            shader.setFloatUniform("brightness", if (dark) 0f else 0.12f)
            shader.setFloatUniform("ditherStrength", 1f)
            RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
        }.getOrNull()
    }
}

private const val DialogMaterialShader = """
uniform shader image;
uniform float p0, p1, p2, p3;
uniform float mapIntensity;
uniform float saturation;
uniform float brightness;
uniform float ditherStrength;
const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
float bezierMap(float x) {
    float invX = 1.0 - x;
    return invX * invX * invX * p0
        + 3.0 * invX * invX * x * p1
        + 3.0 * invX * x * x * p2
        + x * x * x * p3;
}
float interleavedGradientNoise(vec2 position) {
    return fract(52.9829189 * fract(dot(position, vec2(0.06711056, 0.00583715))));
}
vec4 main(vec2 fragCoord) {
    vec3 rgb = image.eval(fragCoord).rgb;
    float mappedLuma = bezierMap(dot(rgb, LUMA_WEIGHTS));
    vec3 colorMapped = mix(rgb, vec3(mappedLuma), mapIntensity);
    vec3 colorSaturated = mix(vec3(dot(colorMapped, LUMA_WEIGHTS)), colorMapped, saturation);
    float dither = (interleavedGradientNoise(fragCoord) - 0.5) * ditherStrength / 255.0;
    return vec4(clamp(colorSaturated + vec3(brightness + dither), 0.0, 1.0), 1.0);
}
"""
