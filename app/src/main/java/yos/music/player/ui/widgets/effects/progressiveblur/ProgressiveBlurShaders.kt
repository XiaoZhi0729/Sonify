package yos.music.player.ui.widgets.effects.progressiveblur

/**
 * Self-contained GLSL ES 1.00 shaders for the single-player mirrored video + 7-level Gaussian
 * progressive blur pipeline (supporting 1D horizontal/vertical and 2D top-left corner modes).
 */
internal object ProgressiveBlurShaders {

    const val FULLSCREEN_VERT = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
"""

    private const val MIRROR_HELPERS = """
float mirrorCoord(float c, float split) {
    float s = max(split, 0.001);
    float uNorm = c / s;
    float period = mod(abs(uNorm), 2.0);
    float folded = (period <= 1.0) ? period : (2.0 - period);
    return clamp(folded, 0.0, 1.0);
}

vec2 computeMirroredSourceUv(
    vec2 texCoord,
    vec2 viewportSize,
    vec2 sourceSize,
    float orientationMode,
    float mirrorSplit,
    float mirrorSplitY,
    float coverOriginX
) {
    vec2 screenUv = vec2(texCoord.x, 1.0 - texCoord.y);
    float splitX = clamp(mirrorSplit, 0.20, 0.85);
    float splitY = clamp(mirrorSplitY, 0.20, 0.85);

    float tx;
    float ty;
    float tileAspect;
    if (orientationMode < 0.5) {
        tx = mirrorCoord(screenUv.x - coverOriginX, splitX);
        ty = screenUv.y;
        tileAspect = (viewportSize.x * splitX) / max(viewportSize.y, 1.0);
    } else if (orientationMode < 1.5) {
        tx = screenUv.x;
        ty = mirrorCoord(screenUv.y, splitY);
        tileAspect = viewportSize.x / max(viewportSize.y * splitY, 1.0);
    } else {
        tx = mirrorCoord(screenUv.x - coverOriginX, splitX);
        ty = mirrorCoord(screenUv.y, splitY);
        tileAspect = (viewportSize.x * splitX) / max(viewportSize.y * splitY, 1.0);
    }

    float sourceAspect = sourceSize.x / max(sourceSize.y, 1.0);
    float srcU;
    float srcV;
    if (sourceAspect > tileAspect) {
        float scaleX = tileAspect / max(sourceAspect, 0.0001);
        srcU = (tx - 0.5) * scaleX + 0.5;
        srcV = ty;
    } else {
        float scaleY = sourceAspect / max(tileAspect, 0.0001);
        srcU = tx;
        srcV = (ty - 0.5) * scaleY + 0.5;
    }

    srcU = clamp(srcU, 0.001, 0.999);
    srcV = clamp(srcV, 0.001, 0.999);
    return vec2(srcU, 1.0 - srcV);
}
"""

    const val MIRROR_VIDEO_OES_FRAG = """
#extension GL_OES_EGL_image_external : require
precision highp float;

uniform samplerExternalOES uVideoTex;
uniform mat4 uTexMatrix;
uniform vec2 uViewportSize;
uniform vec2 uSourceSize;
uniform float uOrientationMode;
uniform float uMirrorSplit;
uniform float uMirrorSplitY;
uniform sampler2D uStaticTex;
uniform mat4 uStaticTexMatrix;
uniform vec2 uStaticSourceSize;
uniform float uVideoMix;
uniform sampler2D uOutgoingTex;
uniform float uCrossfadeMix;

varying vec2 vTexCoord;

$MIRROR_HELPERS

void main() {
    float canonicalOriginX = (uOrientationMode >= 1.5)
        ? max((1.0 - clamp(uMirrorSplit, 0.20, 0.85)) * 0.5, 0.0)
        : 0.0;
    vec2 glUv = computeMirroredSourceUv(
        vTexCoord,
        uViewportSize,
        uSourceSize,
        uOrientationMode,
        uMirrorSplit,
        uMirrorSplitY,
        canonicalOriginX
    );
    vec2 finalUv = (uTexMatrix * vec4(glUv, 0.0, 1.0)).xy;
    vec3 incoming = texture2D(uVideoTex, finalUv).rgb;
    if (uVideoMix < 0.999) {
        vec2 staticGlUv = computeMirroredSourceUv(
            vTexCoord,
            uViewportSize,
            uStaticSourceSize,
            uOrientationMode,
            uMirrorSplit,
            uMirrorSplitY,
            canonicalOriginX
        );
        vec2 staticFinalUv = (uStaticTexMatrix * vec4(staticGlUv, 0.0, 1.0)).xy;
        vec3 staticRgb = texture2D(uStaticTex, staticFinalUv).rgb;
        incoming = mix(staticRgb, incoming, clamp(uVideoMix, 0.0, 1.0));
    }
    vec3 color = incoming;
    if (uCrossfadeMix < 0.999) {
        vec3 outgoing = texture2D(uOutgoingTex, vTexCoord).rgb;
        color = mix(outgoing, incoming, clamp(uCrossfadeMix, 0.0, 1.0));
    }
    gl_FragColor = vec4(color, 1.0);
}
"""

    const val MIRROR_STATIC_2D_FRAG = """
precision highp float;

uniform sampler2D uSourceTex;
uniform mat4 uTexMatrix;
uniform vec2 uViewportSize;
uniform vec2 uSourceSize;
uniform float uOrientationMode;
uniform float uMirrorSplit;
uniform float uMirrorSplitY;
uniform sampler2D uOutgoingTex;
uniform float uCrossfadeMix;

varying vec2 vTexCoord;

$MIRROR_HELPERS

void main() {
    float canonicalOriginX = (uOrientationMode >= 1.5)
        ? max((1.0 - clamp(uMirrorSplit, 0.20, 0.85)) * 0.5, 0.0)
        : 0.0;
    vec2 glUv = computeMirroredSourceUv(
        vTexCoord,
        uViewportSize,
        uSourceSize,
        uOrientationMode,
        uMirrorSplit,
        uMirrorSplitY,
        canonicalOriginX
    );
    vec2 finalUv = (uTexMatrix * vec4(glUv, 0.0, 1.0)).xy;
    vec3 incoming = texture2D(uSourceTex, finalUv).rgb;
    vec3 color = incoming;
    if (uCrossfadeMix < 0.999) {
        vec3 outgoing = texture2D(uOutgoingTex, vTexCoord).rgb;
        color = mix(outgoing, incoming, clamp(uCrossfadeMix, 0.0, 1.0));
    }
    gl_FragColor = vec4(color, 1.0);
}
"""

    const val KAWASE_DOWNSAMPLE_FRAG = """
precision mediump float;

uniform sampler2D uSourceTex;
uniform vec2 uTexelSize;

varying vec2 vTexCoord;

void main() {
    vec2 halfOffset = uTexelSize * 0.5;
    vec3 sum = texture2D(uSourceTex, vTexCoord).rgb * 0.5;
    sum += texture2D(uSourceTex, vTexCoord + vec2(-halfOffset.x, -halfOffset.y)).rgb * 0.125;
    sum += texture2D(uSourceTex, vTexCoord + vec2( halfOffset.x, -halfOffset.y)).rgb * 0.125;
    sum += texture2D(uSourceTex, vTexCoord + vec2(-halfOffset.x,  halfOffset.y)).rgb * 0.125;
    sum += texture2D(uSourceTex, vTexCoord + vec2( halfOffset.x,  halfOffset.y)).rgb * 0.125;
    gl_FragColor = vec4(sum, 1.0);
}
"""

    const val GAUSSIAN_SEPARABLE_FRAG = """
precision mediump float;

uniform sampler2D uSourceTex;
uniform vec2 uDirection;

varying vec2 vTexCoord;

void main() {
    vec2 off1 = uDirection * 1.3846153846;
    vec2 off2 = uDirection * 3.2307692308;
    vec3 sum = texture2D(uSourceTex, vTexCoord).rgb * 0.2270270270;
    sum += (texture2D(uSourceTex, vTexCoord + off1).rgb +
            texture2D(uSourceTex, vTexCoord - off1).rgb) * 0.3162162162;
    sum += (texture2D(uSourceTex, vTexCoord + off2).rgb +
            texture2D(uSourceTex, vTexCoord - off2).rgb) * 0.0702702703;
    gl_FragColor = vec4(sum, 1.0);
}
"""

    const val PROGRESSIVE_COMPOSITE_FRAG = """
precision highp float;

uniform sampler2D uTex0;
uniform sampler2D uTex1;
uniform sampler2D uTex2;
uniform sampler2D uTex3;
uniform sampler2D uTex4;
uniform sampler2D uTex5;
uniform sampler2D uTex6;
uniform sampler2D uTex7;

uniform float uOrientationMode;
uniform float uBlurMode;
uniform float uCurveType;
uniform float uMirrorSplit;
uniform float uBlurStart;
uniform float uBlurEnd;
uniform float uMirrorSplitY;
uniform float uBlurStartY;
uniform float uBlurEndY;
uniform float uMaxBlurLevel;
uniform float uAmbientBlend;
uniform float uCurvePower;
uniform float uScrimMaxAlpha;
uniform float uScrimPower;
uniform float uBlurSaturation;
uniform float uShowGuides;
uniform float uCoverOriginX;
uniform float uPageBlurProgress;
uniform float uAlphaFadeMode;
uniform vec2 uViewportSize;
uniform vec4 uMorphTargetRect;
uniform float uMorphCornerPx;

varying vec2 vTexCoord;

float softerstep(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / max(edge1 - edge0, 0.0001), 0.0, 1.0);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float calcSmoothstep(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / max(edge1 - edge0, 0.0001), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float calcAxisU(float coord, float startEdge, float endEdge, float curveType, float curvePower) {
    float safeEnd = max(endEdge, startEdge + 0.02);
    float s = (curveType < 0.5)
        ? softerstep(startEdge, safeEnd, coord)
        : calcSmoothstep(startEdge, safeEnd, coord);
    return pow(clamp(s, 0.0, 1.0), max(curvePower, 0.1));
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float foldUnitCoord(float x) {
    float period = mod(abs(x), 2.0);
    float folded = (period <= 1.0) ? period : (2.0 - period);
    return clamp(folded, 0.001, 0.999);
}

vec3 samplePyramid(vec2 uv, float lvl) {
    float l = clamp(lvl, 0.0, 7.0);
    if (l <= 0.0001) {
        return texture2D(uTex0, uv).rgb;
    }
    if (l < 1.0) {
        vec3 c0 = texture2D(uTex0, uv).rgb;
        vec3 c1 = texture2D(uTex1, uv).rgb;
        return mix(c0, c1, smoothstep(0.0, 1.0, l));
    }
    if (l < 2.0) {
        vec3 c1 = texture2D(uTex1, uv).rgb;
        vec3 c2 = texture2D(uTex2, uv).rgb;
        return mix(c1, c2, l - 1.0);
    }
    if (l < 3.0) {
        vec3 c2 = texture2D(uTex2, uv).rgb;
        vec3 c3 = texture2D(uTex3, uv).rgb;
        return mix(c2, c3, l - 2.0);
    }
    if (l < 4.0) {
        vec3 c3 = texture2D(uTex3, uv).rgb;
        vec3 c4 = texture2D(uTex4, uv).rgb;
        return mix(c3, c4, l - 3.0);
    }
    if (l < 5.0) {
        vec3 c4 = texture2D(uTex4, uv).rgb;
        vec3 c5 = texture2D(uTex5, uv).rgb;
        return mix(c4, c5, l - 4.0);
    }
    if (l < 6.0) {
        vec3 c5 = texture2D(uTex5, uv).rgb;
        vec3 c6 = texture2D(uTex6, uv).rgb;
        return mix(c5, c6, l - 5.0);
    }
    vec3 c6 = texture2D(uTex6, uv).rgb;
    vec3 c7 = texture2D(uTex7, uv).rgb;
    return mix(c6, c7, l - 6.0);
}

vec4 applyAxisGuides(vec3 color, float axis, float splitPos, float startPos, float endPos) {
    float safeEnd = max(endPos, startPos + 0.02);
    float splitMask = 1.0 - smoothstep(0.001, 0.003, abs(axis - splitPos));
    float startMask = 1.0 - smoothstep(0.001, 0.003, abs(axis - startPos));
    float endMask = 1.0 - smoothstep(0.001, 0.003, abs(axis - safeEnd));
    color = mix(color, vec3(0.15, 0.90, 1.0), splitMask * 0.85);
    color = mix(color, vec3(1.0, 0.78, 0.20), startMask * 0.85);
    color = mix(color, vec3(1.0, 0.35, 0.75), endMask * 0.85);
    float maskAlpha = max(splitMask, max(startMask, endMask)) * 0.85;
    return vec4(color, maskAlpha);
}

vec4 applyLeftMirrorGuides(
    vec3 color,
    float axis,
    float leftSplitPos,
    float leftStartPos,
    float leftEndPos
) {
    float splitMask = (leftSplitPos >= 0.0 && leftSplitPos <= 1.0)
        ? (1.0 - smoothstep(0.001, 0.003, abs(axis - leftSplitPos)))
        : 0.0;
    float startMask = (leftStartPos >= 0.0 && leftStartPos <= 1.0)
        ? (1.0 - smoothstep(0.001, 0.003, abs(axis - leftStartPos)))
        : 0.0;
    float endMask = (leftEndPos >= 0.0 && leftEndPos <= 1.0)
        ? (1.0 - smoothstep(0.001, 0.003, abs(axis - leftEndPos)))
        : 0.0;
    color = mix(color, vec3(0.15, 0.90, 1.0), splitMask * 0.85);
    color = mix(color, vec3(1.0, 0.78, 0.20), startMask * 0.85);
    color = mix(color, vec3(1.0, 0.35, 0.75), endMask * 0.85);
    float maskAlpha = max(splitMask, max(startMask, endMask)) * 0.85;
    return vec4(color, maskAlpha);
}

vec4 applyGuidesComposite(vec3 color, vec2 screenUv, float origin) {
    float guideAlpha = 0.0;
    if (uOrientationMode < 0.5) {
        vec4 g = applyAxisGuides(color, screenUv.x, origin + uMirrorSplit, origin + uBlurStart, origin + uBlurEnd);
        color = g.rgb;
        guideAlpha = g.a;
    } else if (uOrientationMode < 1.5) {
        vec4 g = applyAxisGuides(color, screenUv.y, uMirrorSplitY, uBlurStart, uBlurEnd);
        color = g.rgb;
        guideAlpha = g.a;
    } else {
        float clampedSplitX = clamp(uMirrorSplit, 0.20, 0.85);
        float safeEndX = max(uBlurEnd, uBlurStart + 0.02);
        vec4 gx = applyAxisGuides(color, screenUv.x, origin + clampedSplitX, origin + uBlurStart, origin + safeEndX);
        color = gx.rgb;
        guideAlpha = max(guideAlpha, gx.a);
        if (origin > 0.004) {
            vec4 glx = applyLeftMirrorGuides(
                color,
                screenUv.x,
                origin,
                origin + clampedSplitX - uBlurStart,
                origin + clampedSplitX - safeEndX
            );
            color = glx.rgb;
            guideAlpha = max(guideAlpha, glx.a);
        }
        vec4 gy = applyAxisGuides(color, screenUv.y, uMirrorSplitY, uBlurStartY, uBlurEndY);
        color = gy.rgb;
        guideAlpha = max(guideAlpha, gy.a);
    }
    return vec4(color, guideAlpha);
}

float evalRoundedBoxSdf(vec2 fragPx, vec2 centerPx, vec2 halfSizePx, float rPx) {
    vec2 q = abs(fragPx - centerPx) - (halfSizePx - vec2(rPx));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rPx;
}

vec3 evalProgressiveColor(vec2 uv, float u, float maxLevel, float minMip) {
    vec3 color;
    if (uBlurMode < 0.5) {
        color = samplePyramid(uv, mix(minMip, maxLevel, u));
    } else {
        vec3 sharp = samplePyramid(uv, minMip);
        vec3 heavy = samplePyramid(uv, maxLevel);
        color = mix(sharp, heavy, u);
    }

    if (uAmbientBlend > 0.001) {
        vec3 ambient = texture2D(uTex7, uv).rgb;
        float ambientMix = clamp(uAmbientBlend * pow(u, 1.15), 0.0, 0.90);
        color = mix(color, ambient, ambientMix);
    }

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float sat = mix(1.0, uBlurSaturation, u);
    color = clamp(mix(vec3(luma), color, sat), 0.0, 1.0);

    float scrimAlpha = clamp(uScrimMaxAlpha * pow(u, max(uScrimPower, 0.25)), 0.0, 0.95);
    color = mix(color, vec3(0.0), scrimAlpha);
    return color;
}

void main() {
    vec2 screenUv = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
    float origin = clamp(uCoverOriginX, 0.0, 0.45);
    float safeEndX = max(uBlurEnd, uBlurStart + 0.02);
    float maxLevel = clamp(uMaxBlurLevel, 0.0, 7.0);
    float pageBlur = clamp(uPageBlurProgress, 0.0, 1.0);

    bool portraitMorphActive = (uOrientationMode >= 0.5 && uOrientationMode < 1.5) &&
        uMorphTargetRect.z > 0.005 && uMorphTargetRect.w > 0.005 &&
        uViewportSize.x > 1.0 && uViewportSize.y > 1.0;

    if (portraitMorphActive) {
        float p = pageBlur;
        vec4 rect0 = vec4(0.0, 0.0, 1.0, max(uMirrorSplitY, 0.001));
        vec4 curRect = mix(rect0, uMorphTargetRect, p);
        vec2 localUv = (screenUv - curRect.xy) / max(curRect.zw, vec2(0.0001));

        float safeEndY = max(uBlurEnd, uBlurStart + 0.02);
        float localStart0 = uBlurStart / max(uMirrorSplitY, 0.001);
        float localEnd0 = safeEndY / max(uMirrorSplitY, 0.001);
        float skirtCollapse = smoothstep(0.0, 0.72, p);
        float curLocalStart = mix(localStart0, 1.0, skirtCollapse);
        float curLocalEnd = mix(localEnd0, 1.004, skirtCollapse);
        float coverU = calcAxisU(localUv.y, curLocalStart, curLocalEnd, uCurveType, uCurvePower);

        float boxMaxLocalY = mix(max(1.0, localEnd0), 1.0, skirtCollapse);
        vec2 fragPx = screenUv * uViewportSize;
        vec2 boxTopLeftPx = curRect.xy * uViewportSize;
        vec2 boxSizePx = vec2(curRect.z, curRect.w * boxMaxLocalY) * uViewportSize;
        vec2 boxCenterPx = boxTopLeftPx + 0.5 * boxSizePx;
        float edgePadPx = (1.0 - smoothstep(0.0, 0.06, p)) * 2.5;
        vec2 boxHalfPx = 0.5 * boxSizePx + vec2(edgePadPx);
        float rPx = min(uMorphCornerPx * p, min(boxHalfPx.x, boxHalfPx.y));
        float sdfDist = evalRoundedBoxSdf(fragPx, boxCenterPx, boxHalfPx, rPx);
        float sdfMask = 1.0 - smoothstep(-0.75, 0.75, sdfDist);

        float shadowOffsetPx = 3.0 * p;
        float shadowBlurPx = max(12.0 * p, 1.0);
        float shadowDist = evalRoundedBoxSdf(
            fragPx - vec2(0.0, shadowOffsetPx),
            boxCenterPx,
            boxHalfPx,
            rPx
        );
        float shadowStrength = smoothstep(0.30, 0.85, p);
        float shadowMask = (1.0 - smoothstep(-shadowBlurPx * 0.3, shadowBlurPx, shadowDist))
            * 0.24 * shadowStrength * (1.0 - sdfMask);
        float rimMask = (1.0 - smoothstep(0.0, 1.5, abs(sdfDist + 0.75)))
            * 0.18 * p * (1.0 - coverU);

        vec2 coverScreenUv = vec2(
            clamp(localUv.x, 0.0, 1.0),
            clamp(localUv.y * uMirrorSplitY, 0.0, 1.0)
        );
        vec2 coverTexCoord = vec2(coverScreenUv.x, 1.0 - coverScreenUv.y);
        float minMip = p * 0.48;

        if (uAlphaFadeMode >= 0.5) {
            vec3 coverColor = samplePyramid(coverTexCoord, mix(minMip, min(maxLevel, 3.6), coverU));
            coverColor = mix(coverColor, vec3(0.78), rimMask);
            float coverAlpha = clamp(sdfMask * (1.0 - coverU), 0.0, 1.0);
            if (coverAlpha > 0.002 && coverAlpha < 0.998) {
                coverAlpha = clamp(coverAlpha + (ign(gl_FragCoord.xy) - 0.5) / 255.0, 0.0, 1.0);
            }
            vec3 outRgb = coverColor * coverAlpha;
            float outAlpha = clamp(coverAlpha + shadowMask, 0.0, 1.0);
            if (uShowGuides > 0.5 && p < 0.05) {
                vec4 g = applyGuidesComposite(coverColor, screenUv, origin);
                outRgb = g.rgb * max(outAlpha, g.a);
                outAlpha = max(outAlpha, g.a);
            }
            gl_FragColor = vec4(outRgb, outAlpha);
        } else {
            vec3 coverColor = evalProgressiveColor(coverTexCoord, coverU, maxLevel, minMip);
            coverColor = mix(coverColor, vec3(0.78), rimMask);
            vec3 bgColor = evalProgressiveColor(vTexCoord, 1.0, maxLevel, 0.0);
            bgColor = mix(bgColor, vec3(0.0), shadowMask);
            vec3 color = mix(bgColor, coverColor, sdfMask);
            color += (ign(gl_FragCoord.xy) - 0.5) / 255.0;
            color = clamp(color, 0.0, 1.0);
            if (uShowGuides > 0.5 && p < 0.05) {
                color = applyGuidesComposite(color, screenUv, origin).rgb;
            }
            gl_FragColor = vec4(color, 1.0);
        }
        return;
    }

    float clampedSplitX = clamp(uMirrorSplit, 0.20, 0.85);
    float canonicalOrigin = max((1.0 - clampedSplitX) * 0.5, 0.0);
    vec2 shiftedTexCoord;
    if (uOrientationMode < 0.5) {
        shiftedTexCoord = vec2(clamp(abs(vTexCoord.x - origin), 0.001, 0.999), vTexCoord.y);
    } else if (uOrientationMode < 1.5) {
        shiftedTexCoord = vTexCoord;
    } else {
        shiftedTexCoord = vec2(foldUnitCoord(vTexCoord.x - origin + canonicalOrigin), vTexCoord.y);
    }

    float spatialU;
    if (uOrientationMode < 0.5) {
        spatialU = calcAxisU(screenUv.x, origin + uBlurStart, origin + safeEndX, uCurveType, uCurvePower);
    } else if (uOrientationMode < 1.5) {
        spatialU = calcAxisU(screenUv.y, uBlurStart, uBlurEnd, uCurveType, uCurvePower);
    } else {
        float uxRight = calcAxisU(screenUv.x, origin + uBlurStart, origin + safeEndX, uCurveType, uCurvePower);
        float leftEnable = calcSmoothstep(0.0, 0.008, origin);
        float reflectedX = 2.0 * origin + clampedSplitX - screenUv.x;
        float uxLeft = (leftEnable > 0.0001)
            ? leftEnable * calcAxisU(reflectedX, origin + uBlurStart, origin + safeEndX, uCurveType, uCurvePower)
            : 0.0;
        float ux = 1.0 - (1.0 - uxRight) * (1.0 - uxLeft);
        float uy = calcAxisU(screenUv.y, uBlurStartY, uBlurEndY, uCurveType, uCurvePower);
        spatialU = 1.0 - (1.0 - ux) * (1.0 - uy);
    }

    if (uAlphaFadeMode >= 0.5) {
        vec3 color = samplePyramid(shiftedTexCoord, spatialU * min(maxLevel, 3.6));
        float coverAlpha = clamp((1.0 - spatialU) * (1.0 - pageBlur), 0.0, 1.0);
        if (coverAlpha > 0.002 && coverAlpha < 0.998) {
            coverAlpha = clamp(coverAlpha + (ign(gl_FragCoord.xy) - 0.5) / 255.0, 0.0, 1.0);
        }
        if (uShowGuides > 0.5) {
            vec4 g = applyGuidesComposite(color, screenUv, origin);
            color = g.rgb;
            coverAlpha = max(coverAlpha, g.a);
        }
        gl_FragColor = vec4(color * coverAlpha, coverAlpha);
    } else {
        float u = mix(spatialU, 1.0, pageBlur);
        vec3 color = evalProgressiveColor(shiftedTexCoord, u, maxLevel, 0.0);

        color += (ign(gl_FragCoord.xy) - 0.5) / 255.0;
        color = clamp(color, 0.0, 1.0);

        if (uShowGuides > 0.5) {
            color = applyGuidesComposite(color, screenUv, origin).rgb;
        }

        gl_FragColor = vec4(color, 1.0);
    }
}
"""
}
