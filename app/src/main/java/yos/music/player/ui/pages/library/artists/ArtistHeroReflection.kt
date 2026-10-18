package yos.music.player.ui.pages.library.artists

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.os.Build
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 艺人页 Hero 倒影配方（Apple Music 式头像→提取色过渡）：
 * 照片底部 [ArtistReflectionFraction] 上下镜像紧贴照片下边（像素级无缝），
 * 倒影不透明度自上而下 100% → 0，且与模糊共用同一条过渡路径（同起点、同终点、同 t² 节奏）。
 *
 * 模糊引擎与顶栏同源：[ArtistHeroReflectionShader] 即 [yos.music.player.ui.widgets.basic]
 * TitleBarProgressiveBlur 的 32 点黄金角抖动多重采样 AGSL 渐进模糊（半径沿 y 以 t² 递增），
 * 经 RenderNode + RenderEffect + HardwareRenderer 在后台线程一次性烘焙成位图——
 * 滚动期间只做位图平移，零逐帧着色器成本（实时 RenderEffect 层每帧重执行着色器
 * 是此前滑动卡顿的根源）。
 *
 * API < 33（无 AGSL）降级为金字塔降采样-回放分条模糊（[bakeHeroReflection] 内分支）。
 */
const val ArtistReflectionFraction = 0.5f

/** 模糊与渐隐共用路径的起点：在照片底边之上、占倒影带高的比例（越大过渡越长）。 */
const val ArtistReflectionPhotoOverlapFraction = 0.8f

/** 倒影带底部（最糊处）的模糊半径，烘焙时换算成像素。 */
const val ArtistReflectionMaxBlurDp = 60f

/**
 * 烘焙配方版本，必须拼进调用侧的缓存键（[yos.music.player.code.utils.artist.HeroBakeCache]）。
 * 改动着色器/渐隐/几何公式后 +1：磁盘缓存的键不含任何算法指纹，不换版会让旧的
 * 已烘焙 PNG 被无限期复用，等于这次修复在回访用户身上不可见。
 * v2：带内渐隐从着色器移到读回后的 DST_IN（硬件路径输出不透明，旧图带尾为实色）。
 * v3：渐隐与渐进模糊对齐到同一条路径（原先是带内 1→0.2 再 0.2→0 的两段式）。
 */
const val ArtistHeroBakeRecipeVersion = 3

/** 金字塔降级模糊的分条数（仅 API < 33 回退分支使用）。 */
private const val FallbackStrips = 32

/**
 * 32 点黄金角抖动多重采样渐进模糊（移植自 TitleBarProgressiveBlur，去掉
 * tint/presence）。半径自 blurStart（照片下缘上方）向带底以 t² 递增：接缝处近似 0。
 * 采样坐标 clamp 在图层内：输入出界是透明像素，不 clamp 会在边缘出现黑边。
 *
 * [edgeMode]（uniform）：手机为 0——带内画的是照片的**镜像**（上下翻转的同一张图），
 * 与照片在接缝两侧内容相同，过渡天然无痕；平板为 1——带内不画镜像（否则正方形 hero
 * 会把下半张人脸翻转贴上来，观感像第二张脸），改为把采样 y 夹在照片底边之内，
 * 于是带内是「照片底边纵向延展」的模糊底色，再沿同一条路径溶入提取色。
 * 渐隐**不在着色器里做**：RenderEffect 的输出会合成进 ImageReader 的不透明硬件缓冲，
 * alpha 被固化成 255，带尾变成不透明实色（平板正方形 hero 上就是一条与提取色硬分割的
 * 纯黑带）。着色器只负责采样与模糊，渐隐在 [applyReflectionFade] 里用 DST_IN 打在
 * 读回后的位图上，且与这里 blurStart → bandBottom 的路径逐点对齐。
 */
const val ArtistHeroReflectionShader = """
uniform shader content;
uniform float2 layerSize;
uniform float photoBottom;
uniform float bandBottom;
uniform float blurStart;
uniform float maxRadius;
uniform float edgeMode;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 sampleCoord(float2 sc, float2 bounds, float clampTop) {
    sc = clamp(sc, float2(0.0), bounds);
    sc.y = min(sc.y, clampTop);
    return sc;
}

half4 main(float2 coord) {
    float2 bounds = max(layerSize - 1.0, float2(0.0));
    float clampTop = edgeMode > 0.5 ? (photoBottom - 1.0) : bounds.y;
    float span = max(bandBottom - blurStart, 1.0);
    float t = clamp((coord.y - blurStart) / span, 0.0, 1.0);
    float radius = maxRadius * t * t;
    half4 color;
    if (radius < 0.5) {
        color = content.eval(sampleCoord(coord, bounds, clampTop));
    } else {
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
            half4 c = content.eval(sampleCoord(coord + o, bounds, clampTop));
            sum += c * w;
            wsum += w;
            dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
        }
        color = wsum < 0.0001 ? content.eval(sampleCoord(coord, bounds, clampTop)) : sum / wsum;
    }
    return color;
}
"""

/**
 * 去噪第二道（与 TitleBarProgressiveBlur 同款）：大半径下 32 采样会出现抖动细噪，
 * 8 点小半径盒式平均压噪，半径与局部模糊强度成正比。
 */
const val ArtistHeroDenoiseShader = """
uniform shader content;
uniform float2 layerSize;
uniform float photoBottom;
uniform float blurStart;
uniform float bandBottom;
uniform float maxRadius;
uniform float edgeMode;

half4 main(float2 coord) {
    float2 bounds = max(layerSize - 1.0, float2(0.0));
    float clampTop = edgeMode > 0.5 ? (photoBottom - 1.0) : bounds.y;
    float span = max(bandBottom - blurStart, 1.0);
    float t = clamp((coord.y - blurStart) / span, 0.0, 1.0);
    float r = maxRadius * t * t * 0.18;
    float2 c0 = clamp(coord, float2(0.0), bounds);
    c0.y = min(c0.y, clampTop);
    if (r < 0.4) {
        return content.eval(c0);
    }
    half4 sum = content.eval(c0);
    float wsum = 1.0;
    float2 dir = float2(1.0, 0.0);
    float2 g = float2(cos(0.7853981634), sin(0.7853981634));
    for (int i = 0; i < 8; i++) {
        float2 sc = clamp(coord + dir * r, float2(0.0), bounds);
        sc.y = min(sc.y, clampTop);
        sum += content.eval(sc);
        wsum += 1.0;
        dir = float2(dir.x * g.x - dir.y * g.y, dir.x * g.y + dir.y * g.x);
    }
    return sum / wsum;
}
"""

/** 照片等比放大居中裁切铺满照片区。[mirror] 为 true 时再关于照片底边翻转画一份镜像。 */
private fun drawPhotoAndMirror(
    canvas: Canvas,
    src: Bitmap,
    widthPx: Int,
    photoHeightPx: Int,
    mirror: Boolean
) {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val scale = maxOf(widthPx.toFloat() / src.width, photoHeightPx.toFloat() / src.height)
    val dw = src.width * scale
    val dh = src.height * scale
    val dst = android.graphics.RectF(
        (widthPx - dw) / 2f, (photoHeightPx - dh) / 2f,
        (widthPx + dw) / 2f, (photoHeightPx + dh) / 2f
    )
    canvas.drawBitmap(src, null, dst, paint)
    if (!mirror) return
    // 镜像：关于照片底边翻转（y → 2·photoBottom − y），与照片底行像素连续
    canvas.save()
    canvas.translate(0f, 2f * photoHeightPx)
    canvas.scale(1f, -1f)
    canvas.drawBitmap(src, null, dst, paint)
    canvas.restore()
}

/**
 * 无镜像（平板）时把照片底部一细条纵向拉伸填满倒影带——照片底色的延展，
 * 不是第二张图；仅 API<33 金字塔回退分支需要（AGSL 分支由 shader 的 edgeMode 合成）。
 */
private fun drawEdgeExtendedBand(
    canvas: Canvas,
    src: Bitmap,
    widthPx: Int,
    photoHeightPx: Int,
    bandHeightPx: Int
) {
    if (bandHeightPx <= 0) return
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val sliceSrcH = (src.height * 0.03f).roundToInt().coerceIn(1, src.height)
    canvas.drawBitmap(
        src,
        Rect(0, src.height - sliceSrcH, src.width, src.height),
        android.graphics.RectF(
            0f, photoHeightPx.toFloat(),
            widthPx.toFloat(), (photoHeightPx + bandHeightPx).toFloat()
        ),
        paint
    )
}

/**
 * 烘焙「照片 + 镜像倒影」位图（宽 [widthPx]，上部 [photoHeightPx] 照片区、
 * 下部 [bandHeightPx] 倒影带）。[shader] 非 null 且 API≥33 走 AGSL 真渐进模糊
 * （HardwareRenderer 离屏渲染，质量与顶栏同源）；否则退化为金字塔降采样-回放
 * 分条模糊（近似质量，仅低版本回退）。必须在后台线程调用。
 *
 * 带内渐隐统一在模糊之后由 [applyReflectionFade] 打在位图 alpha 上——硬件路径的
 * RenderEffect 输出只有不透明缓冲，着色器里的 alpha 会被丢弃（见 [ArtistHeroReflectionShader]）。
 */
fun bakeHeroReflection(
    src: Bitmap,
    widthPx: Int,
    photoHeightPx: Int,
    bandHeightPx: Int,
    maxBlurRadiusPx: Float,
    shader: RuntimeShader?,
    edgeReflection: Boolean = false
): Bitmap {
    if (shader != null && Build.VERSION.SDK_INT >= 33) {
        // 渐隐失败（硬件位图无法复制）时整条硬件结果作废，回落到能保留 alpha 的软件路径
        val faded = runCatching {
            val hardware = bakeWithHardwareRenderer(src, widthPx, photoHeightPx, bandHeightPx, maxBlurRadiusPx, shader, edgeReflection)
            applyReflectionFade(hardware, photoHeightPx, bandHeightPx)
        }.getOrNull()
        if (faded != null) return faded
        android.util.Log.w("ArtistDetail", "hero reflection: hardware bake/fade failed, falling back to pyramid blur")
    }
    val pyramid = bakeWithPyramidBlur(src, widthPx, photoHeightPx, bandHeightPx, maxBlurRadiusPx, edgeReflection)
    return applyReflectionFade(pyramid, photoHeightPx, bandHeightPx) ?: pyramid
}

/**
 * 带内渐隐：与渐进模糊**共用同一条路径**——起点同为 [ArtistReflectionPhotoOverlapFraction]
 * 处的 blurStart（压在照片下缘之上），终点同为带底；进度同样按 t² 增长。
 * 于是「模糊增长」与「溶入提取色」两条过渡的起止位置与节奏完全一致，不会出现
 * 模糊已经在照片下半部铺开、颜色却要等到照片底边才开始接管的错位。
 *
 * 用 DST_IN 直接打在位图 alpha 上（预乘语义下 SrcOver 到页面底色恰好是
 * 「照片色 → 提取色」的线性过渡）。位图不可变时先复制一份再打；复制不出来返回 null 让调用方回落。
 */
private fun applyReflectionFade(source: Bitmap, photoHeightPx: Int, bandHeightPx: Int): Bitmap? {
    val total = photoHeightPx + bandHeightPx
    if (bandHeightPx <= 0 || photoHeightPx <= 0) return source
    val out = if (source.isMutable) source else {
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
            ?: return null
        runCatching { source.recycle() }
        copy
    }
    val start = photoHeightPx - bandHeightPx * ArtistReflectionPhotoOverlapFraction
    // t² 曲线按 12 段折线近似：每段内的线性插值误差 < 1/255，肉眼不可见
    val steps = 12
    val colors = IntArray(steps + 1)
    val positions = FloatArray(steps + 1)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val a = ((1f - t * t) * 255f).roundToInt().coerceIn(0, 255)
        colors[i] = (a shl 24) or 0x00FFFFFF
        positions[i] = t
    }
    val canvas = Canvas(out)
    val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    fade.shader = android.graphics.LinearGradient(
        0f, start, 0f, total.toFloat(),
        colors, positions,
        android.graphics.Shader.TileMode.CLAMP
    )
    fade.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    canvas.drawRect(0f, start, out.width.toFloat(), total.toFloat(), fade)
    fade.xfermode = null
    return out
}

/**
 * AGSL 渐进模糊着色器实例（每次烘焙新建，uniform 按几何赋值）。
 * [edgeReflection] 为 true 时带内不做镜像、改为照片底边纵向延展（见 [ArtistHeroReflectionShader]）。
 */
fun newArtistHeroReflectionShader(
    widthPx: Int,
    photoHeightPx: Int,
    bandHeightPx: Int,
    maxBlurRadiusPx: Float,
    edgeReflection: Boolean = false
): RuntimeShader? {
    if (Build.VERSION.SDK_INT < 33) return null
    return runCatching {
        val blurStart = photoHeightPx - bandHeightPx * ArtistReflectionPhotoOverlapFraction
        RuntimeShader(ArtistHeroReflectionShader).apply {
            setFloatUniform("layerSize", widthPx.toFloat(), (photoHeightPx + bandHeightPx).toFloat())
            setFloatUniform("photoBottom", photoHeightPx.toFloat())
            setFloatUniform("bandBottom", (photoHeightPx + bandHeightPx).toFloat())
            setFloatUniform("blurStart", blurStart)
            setFloatUniform("maxRadius", maxBlurRadiusPx)
            setFloatUniform("edgeMode", if (edgeReflection) 1f else 0f)
        }
    }.getOrNull()
}

/**
 * RenderNode 录制「照片+镜像」→ 挂 AGSL RenderEffect → HardwareRenderer 渲进
 * ImageReader 的硬件缓冲 → 回读成可变软件位图。整条链路与系统模糊材质同一实现，
 * RenderEffect 真实作用于像素内容（与 Paint.maskFilter 的掩码语义完全不同）。
 *
 * 返回软件位图而非硬件位图：着色器算出的 alpha 在合成进不透明缓冲时会被丢弃，
 * 所以渐隐必须由调用方在位图 alpha 上补（见 [applyReflectionFade]），而硬件位图不可写。
 */
private fun bakeWithHardwareRenderer(
    src: Bitmap,
    widthPx: Int,
    photoHeightPx: Int,
    bandHeightPx: Int,
    maxBlurRadiusPx: Float,
    shader: RuntimeShader,
    edgeReflection: Boolean
): Bitmap {
    val total = photoHeightPx + bandHeightPx
    val node = RenderNode("artistHeroBake").apply { setPosition(0, 0, widthPx, total) }
    val canvas = node.beginRecording(widthPx, total)
    // edgeReflection 时带内不画内容，由 shader 采样照片底边合成（无镜像、省显存）
    drawPhotoAndMirror(canvas, src, widthPx, photoHeightPx, mirror = !edgeReflection)
    node.endRecording()
    // 两道链式效果：32 采样渐进散焦 → 8 点去噪（大半径下抖动细噪必须压，
    // 与 TitleBarProgressiveBlur 的双 shader 管线同构）
    val blurEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    val denoise = RuntimeShader(ArtistHeroDenoiseShader).apply {
        setFloatUniform("layerSize", widthPx.toFloat(), total.toFloat())
        setFloatUniform("photoBottom", photoHeightPx.toFloat())
        setFloatUniform("blurStart", photoHeightPx - bandHeightPx * ArtistReflectionPhotoOverlapFraction)
        setFloatUniform("bandBottom", total.toFloat())
        setFloatUniform("maxRadius", maxBlurRadiusPx)
        setFloatUniform("edgeMode", if (edgeReflection) 1f else 0f)
    }
    val denoiseEffect = RenderEffect.createRuntimeShaderEffect(denoise, "content")
    node.setRenderEffect(RenderEffect.createChainEffect(blurEffect, denoiseEffect))
    val renderer = HardwareRenderer()
    val reader = android.media.ImageReader.newInstance(
        widthPx, total, PixelFormat.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
    )
    try {
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        var image: android.media.Image? = null
        // 死线放宽到 3s：GPU 繁忙（转场/滚动）时 acquireLatestImage 偶尔要等，1s 过紧会让
        // 烘焙概率性失败（调用方 getOrNull → 无倒影硬衔接）。正常情况 syncAndDraw 后即取到。
        val deadline = android.os.SystemClock.uptimeMillis() + 3000
        while (image == null && android.os.SystemClock.uptimeMillis() < deadline) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(4)
        }
        val hardwareBuffer = image?.hardwareBuffer
            ?: throw IllegalStateException("hero bake: no image from HardwareRenderer")
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
            ?: throw IllegalStateException("hero bake: wrapHardwareBuffer failed")
        // 必须在 image.close() 之前回读成可变软件位图：后续 DST_IN 渐隐要写入 alpha，
        // 且硬件缓冲释放后再复制会失败。复制只做一次（这里），applyReflectionFade 原地打。
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("hero bake: copy to ARGB_8888 failed")
        bitmap.recycle()
        image.close()
        return software
    } finally {
        renderer.setSurface(null)
        renderer.destroy()
        reader.close()
    }
}

/**
 * API < 33 回退：金字塔降采样-回放分条模糊（链式 2x 缩小 + 双线性放大回原尺寸，
 * 级 ≈ log2(r)）。注意 Paint.maskFilter(BlurMaskFilter) 只模糊图元边缘掩码、
 * 不模糊位图内容，不可用作内容模糊。
 */
private fun bakeWithPyramidBlur(
    src: Bitmap,
    widthPx: Int,
    photoHeightPx: Int,
    bandHeightPx: Int,
    maxBlurRadiusPx: Float,
    edgeReflection: Boolean
): Bitmap {
    val total = photoHeightPx + bandHeightPx
    val out = Bitmap.createBitmap(widthPx, total, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    if (edgeReflection) {
        // 平板：不画镜像，用照片底部一细条纵向拉伸填满倒影带（延展底色，非第二张图）
        drawPhotoAndMirror(canvas, src, widthPx, photoHeightPx, mirror = false)
        drawEdgeExtendedBand(canvas, src, widthPx, photoHeightPx, bandHeightPx)
    } else {
        drawPhotoAndMirror(canvas, src, widthPx, photoHeightPx, mirror = true)
    }

    val blurStart = photoHeightPx - bandHeightPx * ArtistReflectionPhotoOverlapFraction
    val span = total - blurStart
    val base = out.copy(Bitmap.Config.ARGB_8888, false)
    val maxLevel = 6
    val levelBitmaps = arrayOfNulls<Bitmap>(maxLevel + 1)
    fun levelBitmap(level: Int): Bitmap {
        levelBitmaps[level]?.let { return it }
        var bmp = base
        var w = widthPx
        var h = total
        repeat(level) {
            w = (w / 2f).roundToInt().coerceAtLeast(1)
            h = (h / 2f).roundToInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        return Bitmap.createScaledBitmap(bmp, widthPx, total, true).also { levelBitmaps[level] = it }
    }
    val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    for (i in 0 until FallbackStrips) {
        val y0 = blurStart + span * i / FallbackStrips
        val y1 = blurStart + span * (i + 1) / FallbackStrips
        val t = (((y0 + y1) / 2f - blurStart) / span).coerceIn(0f, 1f)
        val r = maxBlurRadiusPx * t * t
        val level = if (r < 1f) 0 else ceil(ln(r.toDouble()) / ln(2.0))
            .roundToInt().coerceIn(0, maxLevel)
        val pad = (1 shl level) * 2f
        val sy0 = (y0 - pad).coerceAtLeast(0f)
        val sy1 = (y1 + pad).coerceAtMost(total.toFloat())
        canvas.save()
        canvas.clipRect(0f, y0, widthPx.toFloat(), y1 + 1f)
        canvas.drawBitmap(
            levelBitmap(level),
            Rect(0, sy0.roundToInt(), widthPx, sy1.roundToInt()),
            android.graphics.RectF(0f, sy0, widthPx.toFloat(), sy1),
            copyPaint
        )
        canvas.restore()
    }
    levelBitmaps.forEach { it?.recycle() }
    base.recycle()
    // 带内渐隐由 bakeHeroReflection 末尾统一施加（见 applyReflectionFade）
    return out
}
