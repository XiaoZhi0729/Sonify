package yos.music.player.ui.launch

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser

/**
 * 手写体「你好」—— 原字形保真 + 按笔顺揭示。
 *
 * ── 为什么是这个方案（前面几版全错，结论写在这里）──────────────────
 * 曾试图「从字形反推笔画中心线，再用中心线重建字形」：
 *   · 骨架串折线 + RDP 简化 + 折返清理，重建 IoU 只有 0.31（字形残缺）
 *   · 用等宽 Stroke 描中心线 → 一坨粗管，不是手写
 *   · 用锥形轮廓描中心线 → 笔画断裂、缺横少点
 * 实测：骨架点 + 各自距离半径直接画圆，IoU 是 0.95 / precision 0.99。
 * 说明**数据一直是好的，错的是"串接/简化/重建"这一整套加工**。
 *
 * 正确做法：
 *   最终形态 = 字体轮廓 Path（矢量，100% 保真，任意尺寸都锐利）
 *   动画     = 沿人工排好笔顺的粗画笔，把轮廓逐步"揭示"出来
 *   收尾     = 画笔走完后整块字形淡入补齐（画笔只覆盖约 68%，
 *              不补齐会缺笔画；补齐后终点 100% 是字体原样）
 * 画笔不精确没关系 —— 它只决定露出顺序，不决定字形。
 *
 * ── 资源（由 D:/adxm/FlamingoSank/_sk/final_outline.py 生成）──────
 *   res/raw/hello_outline.txt  两字合并后的轮廓 PathData（归一化坐标）
 *   res/raw/hello_brush.txt    15 笔折线 + 时间窗（每行 t0 t1 x,y x,y ...）
 *
 * ── 授权 ────────────────────────────────────────────────────────
 * 字形来自 Google Fonts「Zhi Mang Xing / 志芒行书」，SIL Open Font License 1.1，
 * 允许商用、再分发、改制。轮廓数据由该字体离线烘焙而来，不含字体文件本身。
 * 这与之前的 Macintosh Skeletal Filled（Apple 版权、仅非商业）完全不同，
 * 现方案可以进公开 release。
 */
@Composable
fun HandwriteHelloText(
    modifier: Modifier = Modifier,
    width: Dp = 250.dp,
    colorLight: Color = Color(0xFF1D1D1F),
    colorDark: Color = Color(0xFFF5F5F7),
    durationMillis: Int = 1800,
    animate: Boolean = true,
    onFinished: (() -> Unit)? = null,
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val data = remember(context) { HelloWriting.load(context) } ?: return
    val ink = if (isDark) colorDark else colorLight

    /* 单条进度：画笔与末段补全都由它派生，不用多个 Animatable 各自跑。 */
    val progress = remember { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(animate) {
        if (!animate) {
            progress.snapTo(1f)
            onFinished?.invoke()
        } else {
            progress.animateTo(
                1f,
                tween(durationMillis = durationMillis, easing = LinearEasing)
            )
            onFinished?.invoke()
        }
    }

    val h = width * data.aspect
    Box(
        modifier
            .width(width)
            .height(h)
            .drawWithContent {
                drawWriting(data, progress.value, ink)
            }
    )
}

/**
 * 一帧：
 *   ① 离屏画"画笔"（按笔顺推进的粗线）→ 作为遮罩
 *   ② 用 SrcIn 把字形画进遮罩 → 只露出写到过的部分
 *   ③ 末 12% 把整块字形淡入补齐（覆盖画笔没走到的区域）
 *
 * Compose 的离屏用 [drawIntoCanvas] + saveLayer 实现，不依赖任何 View。
 */
private fun DrawScope.drawWriting(
    data: HelloWriting,
    progress: Float,
    ink: Color,
) {
    if (progress <= 0f) return
    val sx = size.width
    val sy = size.height

    /* ① 把"已写过的画笔路径"做成带面积的裁剪路径，在里面画完整字形。
          不用 saveLayer + DstIn —— 实测它没裁掉任何东西（第一帧就是整字）。
          clipPath 是 Canvas 原生裁剪，行为确定。 */
    val outline = Path().apply {
        addPath(data.outline)
        transform(Matrix().apply { scale(sx, sy) })
    }
    val brushArea = buildBrushArea(data.brushes, progress, sx, sy)

    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(brushArea)
        canvas.drawPath(outline, androidx.compose.ui.graphics.Paint().apply {
            color = ink
            style = androidx.compose.ui.graphics.PaintingStyle.Fill
        })
        canvas.restore()
    }

    /* ② 末段补全：整块字形淡入，覆盖画笔未走到的区域。
          这不是"淡入淡出"效果 —— 前 88% 都是逐笔揭示，
          这里只是补上骨架路径漏掉的 32% 笔画，终点 100% 是字体原样。 */
    if (progress > 0.88f) {
        val a = ((progress - 0.88f) / 0.12f).coerceIn(0f, 1f)
        val outline = Path().apply {
            addPath(data.outline)
            transform(Matrix().apply { scale(sx, sy) })
        }
        drawPath(outline, color = ink.copy(alpha = ink.alpha * a))
    }
}

/** 画笔粗细（相对字宽）。字形笔画约 0.041，给 0.058 留余量。 */
private const val BRUSH_W = 0.058f

/**
 * 把"已写过的折线"生成一张带面积的闭合路径（供 clipPath 用）。
 *
 * 折线本身没有面积，直接 clipPath 会什么都裁不出来。
 * 这里沿每个采样点算单位法向，向两侧偏移 BRUSH_W/2 生成左右边界，
 * 左正序 + 右逆序闭合成一条带子，末端补圆头。
 * 单笔独立成一条子路径（各笔之间不相连，避免笔与笔之间拉出直线）。
 */
private fun buildBrushArea(
    brushes: List<HelloWriting.Brush>,
    progress: Float,
    sx: Float,
    sy: Float,
): Path {
    val area = Path()
    val r = BRUSH_W * sx * 0.5f
    brushes.forEach { b ->
        if (progress <= b.t0) return@forEach
        val local = ((progress - b.t0) / (b.t1 - b.t0).coerceAtLeast(1e-6f))
            .coerceIn(0f, 1f)
        val exact = local * (b.points.size - 1)
        val n = exact.toInt().coerceIn(0, b.points.size - 1)
        val frac = exact - n
        if (n < 1) return@forEach

        val m = n + 2
        val xs = FloatArray(m)
        val ys = FloatArray(m)
        for (k in 0..n) {
            xs[k] = b.points[k].x * sx
            ys[k] = b.points[k].y * sy
        }
        /* 笔尖插值 */
        if (n < b.points.size - 1 && frac > 0f) {
            val a = b.points[n]; val c = b.points[n + 1]
            xs[n + 1] = (a.x + (c.x - a.x) * frac) * sx
            ys[n + 1] = (a.y + (c.y - a.y) * frac) * sy
        } else {
            xs[n + 1] = xs[n]
            ys[n + 1] = ys[n]
        }

        val nx = FloatArray(m)
        val ny = FloatArray(m)
        for (k in 0 until m) {
            val i0 = (k - 1).coerceAtLeast(0)
            val i1 = (k + 1).coerceAtMost(m - 1)
            var tx = xs[i1] - xs[i0]
            var ty = ys[i1] - ys[i0]
            val len = kotlin.math.sqrt(tx * tx + ty * ty)
            if (len < 1e-4f) {
                tx = 1f; ty = 0f
            } else {
                tx /= len; ty /= len
            }
            nx[k] = -ty
            ny[k] = tx
        }

        for (k in 0 until m) {
            val lx = xs[k] + nx[k] * r
            val ly = ys[k] + ny[k] * r
            if (k == 0) area.moveTo(lx, ly) else area.lineTo(lx, ly)
        }
        for (k in m - 1 downTo 0) {
            area.lineTo(xs[k] - nx[k] * r, ys[k] - ny[k] * r)
        }
        area.close()
        /* 起笔圆头 */
        area.addOval(
            androidx.compose.ui.geometry.Rect(
                xs[0] - r, ys[0] - r, xs[0] + r, ys[0] + r
            )
        )
        /* 笔尖圆头 */
        area.addOval(
            androidx.compose.ui.geometry.Rect(
                xs[m - 1] - r, ys[m - 1] - r, xs[m - 1] + r, ys[m - 1] + r
            )
        )
    }
    return area
}

/**
 * 数据：字形轮廓 + 笔顺画笔。
 * 只读两个 raw 资源，均为离线烘焙的纯坐标数据。
 */
internal class HelloWriting private constructor(
    val outline: Path,
    val brushes: List<Brush>,
    val aspect: Float,
) {
    class P(val x: Float, val y: Float)
    class Brush(val t0: Float, val t1: Float, val points: List<P>)

    companion object {
        private const val OUTLINE_RES = "hello_outline"
        private const val BRUSH_RES = "hello_brush"

        /** 由 final_outline.py 实测：画布 1800x844。 */
        private const val ASPECT = 0.46889f

        @Volatile
        private var cached: HelloWriting? = null

        fun load(context: Context): HelloWriting? {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: runCatching {
                    val od = context.resources.getIdentifier(
                        OUTLINE_RES, "raw", context.packageName)
                    val bd = context.resources.getIdentifier(
                        BRUSH_RES, "raw", context.packageName)
                    if (od == 0 || bd == 0) return null

                    val odText = context.resources.openRawResource(od)
                        .bufferedReader().use { it.readText() }.trim()
                    val outline = PathParser.createPathFromPathData(odText).asComposePath()

                    val brushes = context.resources.openRawResource(bd)
                        .bufferedReader().use { it.readLines() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            val t = line.trim().split(' ')
                            if (t.size < 4) return@mapNotNull null
                            val t0 = t[0].toFloatOrNull() ?: return@mapNotNull null
                            val t1 = t[1].toFloatOrNull() ?: return@mapNotNull null
                            val pts = t.drop(2).mapNotNull { tk ->
                                val f = tk.split(',')
                                if (f.size < 2) null else runCatching {
                                    P(f[0].toFloat(), f[1].toFloat())
                                }.getOrNull()
                            }
                            if (pts.size < 2) null else Brush(t0, t1, pts)
                        }
                    if (brushes.isEmpty()) return null

                    HelloWriting(outline, brushes, ASPECT).also {
                        cached = it
                        android.util.Log.d(
                            "HandwriteHelloText",
                            "outline + brushes=${brushes.size} " +
                                "points=${brushes.sumOf { b -> b.points.size }}"
                        )
                    }
                }.getOrNull()
            }
        }
    }
}
