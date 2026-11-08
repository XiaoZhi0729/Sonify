#version 300 es

precision highp float;

uniform sampler2D uLyricsBackdrop;
uniform sampler2D uOrdinaryBackdrop;
uniform float uBlackScrimAlpha;
uniform float uLyricsModeMix;
uniform float uDitherStrength;
uniform int uMaterialMode;
in vec2 vTexCoord;
in vec2 vOrdinaryTexCoord;
layout(location = 0) out vec4 outColor;

vec3 applySaturation(vec3 color, float saturation) {
    vec3 redColumn = vec3(
        0.2126 + 0.7873 * saturation,
        0.2126 - 0.2126 * saturation,
        0.2126 - 0.2126 * saturation
    );
    vec3 greenColumn = vec3(
        0.7152 - 0.7152 * saturation,
        0.7152 + 0.2848 * saturation,
        0.7152 - 0.7152 * saturation
    );
    vec3 blueColumn = vec3(
        0.0722 - 0.0722 * saturation,
        0.0722 - 0.0722 * saturation,
        0.0722 + 0.9278 * saturation
    );
    return redColumn * color.r + greenColumn * color.g + blueColumn * color.b;
}

vec3 treated(sampler2D source, vec2 coordinate) {
    vec3 color = texture(source, coordinate).rgb;
    color = applySaturation(color, 1.4);
    color = clamp(color, vec3(-0.752941), vec3(1.25098));
    // Updated Lyricify material reduces saturation again before composition. Original is 0.70.
    color = applySaturation(color, 0.70);
    // Apply a non-linear plus darker black scrim. Makes it more like Apple Music.
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float brightnessCurve = pow(max(luminance, 0.0), 1.25);
    float subtraction = uBlackScrimAlpha * brightnessCurve;
    color = max(color - vec3(subtraction), vec3(0.0));
    return color;
}

void main() {
    vec3 color;
    if (uMaterialMode == 0) {
        color = treated(uOrdinaryBackdrop, vOrdinaryTexCoord);
    } else if (uMaterialMode == 1) {
        color = treated(uLyricsBackdrop, vTexCoord);
    } else if (uMaterialMode == 2) {
        vec3 ordinary = treated(uOrdinaryBackdrop, vOrdinaryTexCoord);
        vec3 lyrics = treated(uLyricsBackdrop, vTexCoord);
        color = mix(ordinary, lyrics, uLyricsModeMix);
    } else {
        vec3 ordinary = treated(uOrdinaryBackdrop, vOrdinaryTexCoord);
        color = mix(ordinary, vec3(0.0), uLyricsModeMix);
    }

    float dither = fract(
        52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
    ) - 0.5;
    float ditherStrength = uDitherStrength;
    if (uMaterialMode == 3) {
        ditherStrength *= 1.0 - uLyricsModeMix;
    }
    color += dither * (ditherStrength / 255.0);
    outColor = vec4(clamp(color, 0.07, 0.97), 1.0);
}
