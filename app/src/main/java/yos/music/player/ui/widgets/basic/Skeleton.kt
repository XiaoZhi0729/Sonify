package yos.music.player.ui.widgets.basic

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.ui.theme.isFlamingoInDarkMode

/**
 * 骨架屏扫光。
 *
 * 关键设计：**光是全页唯一的一道**，不是每个骨架块各画一条。
 *
 * 每个块只做两件事：铺一层底色，再把「全页那道光的局部切片」裁进自己的圆角形状里。
 * 因此相邻卡片上的高光是同一条光的连续片段——光穿过卡片间隙时不会断开、也不会在
 * 每个块内重复出现一条独立的移动条纹（那正是"每个方块里各有一道光在横向位移"的来源）。
 *
 * 动画值只在绘制期读取（见 [Modifier.skeleton]），每帧只失效绘制、不触发重组。
 */
@Stable
class SkeletonShimmer internal constructor(
    internal val progress: State<Float>,
    /** 扫光所在坐标系（根）的宽度，像素。整页共用，保证所有块的光带角度与相位一致。 */
    internal val rootWidthPx: Float,
    /** 系统关掉动画（开发者选项 / 省电）时为 false，骨架退化为静态灰块。 */
    val animated: Boolean
)

/** 扫光一个来回的时长。慢到不刺眼，快到不会让人觉得页面卡住。 */
private const val ShimmerDurationMillis = 1400

/**
 * 光带宽度占屏宽的比例。
 *
 * 必须远大于单个骨架块（卡片、文字条），否则高光的两个透明端会落在块内部，
 * 形成"块里有一条边界清晰的亮带"——这就是扫光看起来廉价的原因。
 */
private const val BandWidthFraction = 0.55f

@Composable
fun rememberSkeletonShimmer(enabled: Boolean = true): SkeletonShimmer {
    val context = LocalContext.current
    // Settings.Global 是跨进程查询，只读一次：放进每帧重组的作用域里它自己就会变成掉帧原因。
    val systemAnimated = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) > 0f
    }
    val windowInfo = LocalWindowInfo.current
    val rootWidthPx = remember(windowInfo) {
        windowInfo.containerSize.width.toFloat().takeIf { it > 0f } ?: 1080f
    }
    return if (enabled && systemAnimated) {
        animatedShimmer(rootWidthPx)
    } else {
        staticShimmer(rootWidthPx)
    }
}

@Composable
private fun animatedShimmer(rootWidthPx: Float): SkeletonShimmer {
    val transition = rememberInfiniteTransition(label = "skeletonShimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ShimmerDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmerProgress"
    )
    return remember(progress, rootWidthPx) {
        SkeletonShimmer(progress, rootWidthPx, animated = true)
    }
}

@Composable
private fun staticShimmer(rootWidthPx: Float): SkeletonShimmer {
    val static = remember { mutableFloatStateOf(0f) }
    return remember(static, rootWidthPx) {
        SkeletonShimmer(static, rootWidthPx, animated = false)
    }
}

/**
 * 骨架块：圆角底色 + 全页扫光在自己形状内的切片。
 *
 * 高光的渐变直接在**块自己的局部坐标**里构造，但端点由"根坐标下的全页光带"减去本块在根
 * 里的位置得到——线性渐变的场在两端点同时平移时保持不变，所以这与"在根坐标里画、再裁剪"
 * 等价，却不需要 translate/clip 的坐标换算。
 *
 * 位置用 `onGloballyPositioned` 记录、只在绘制期读取，滚动时不会触发重组。
 */
@Composable
fun Modifier.skeleton(
    shimmer: SkeletonShimmer,
    cornerRadius: Dp = 6.dp
): Modifier {
    val dark = isFlamingoInDarkMode()
    // 夜间底色要更亮一档：纯黑底上 0.10 的白色几乎看不见，骨架会像"没画"。
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.16f else 0.07f)
    val highlightColor =
        if (dark) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.75f)

    // 块在根坐标系里的位置：滚动时跟着变，光带因此始终锚定在屏幕上。
    val rootOffset = remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { rootOffset.value = it.positionInRoot() }
        .drawWithCache {
            val radius = cornerRadius.toPx()
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = CornerRadius(radius, radius)
                    )
                )
            }
            val rootWidth = shimmer.rootWidthPx
            val bandWidth = rootWidth * BandWidthFraction
            // 起点在屏幕左侧之外、终点在屏幕右侧之外各留一个带宽：
            // 保证任何 x 位置的块都会被完整扫过一次，不会"扫到一半就没了"。
            val travel = rootWidth + bandWidth * 2f
            // 两端透明、中段柔和收窄的高光：肩部越宽，边界越不可见。
            // 中段留出 0.34→0.66 的爬升/衰减，避免出现一条边锋利的亮带。
            val bandStops = arrayOf(
                0f to Color.Transparent,
                0.34f to Color.Transparent,
                0.5f to highlightColor,
                0.66f to Color.Transparent,
                1f to Color.Transparent
            )

            onDrawBehind {
                drawPath(path, baseColor)
                if (!shimmer.animated) return@onDrawBehind

                val origin = rootOffset.value
                // 光带中心（根坐标）。p=0 时中心在 -bandWidth，整条带完全在屏幕左侧外；
                // p=1 时中心在 rootWidth + bandWidth，完全在右侧外。整程无跳变。
                val centerX = -bandWidth + shimmer.progress.value * travel
                // **水平光带**：渐变轴只沿 x 延伸，y 恒为 0。
                //
                // 这里绝不能把 y 混进渐变轴。曾经用斜向渐变（end 点带 y 偏移），
                // 而 y 用的是"块在根坐标里的位置"，于是块内的渐变参数 t 被块在页面上的
                // 纵向位置主导——下方的块几乎全程透明，只在某一瞬间闪一下，
                // 表现为"光扫到一半突然消失、又重新扫一遍"。
                // 斜向感由各块自身的圆角与排布承担，不需要渐变轴倾斜。
                val startX = centerX - bandWidth / 2f - origin.x
                val endX = centerX + bandWidth / 2f - origin.x
                val brush = Brush.horizontalGradient(
                    colorStops = bandStops,
                    startX = startX,
                    endX = endX
                )
                clipPath(path) {
                    drawRect(brush = brush, topLeft = Offset.Zero, size = size)
                }
            }
        }
}
