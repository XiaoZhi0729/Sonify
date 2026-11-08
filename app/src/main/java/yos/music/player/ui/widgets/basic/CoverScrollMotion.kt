package yos.music.player.ui.widgets.basic

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

import kotlin.math.pow

/*
 * 详情页大封面的滚动动效（Apple Music 式）。
 *
 * 页面结构同构：单个 LazyColumn，第 0 个 item 是「大封面 + 标题 + meta + 操作行」的 Column。
 * 封面原本只是被列表带着走，滚动时毫无反应。这里补上滚动驱动：随首个 item 滚出视口，
 * 封面逐渐模糊、相对下沉（视差）、淡出。
 *
 * ── 为什么读滚动量而不是用 Modifier.offset ────────────────────────────────
 *
 * 下沉用 graphicsLayer.translationY。Modifier.offset 是布局修饰符，每帧变化会让父级
 * 重新放置并失效布局（ArtistDetail.kt 里踩过，注释写明必须用 translationY）。
 *
 * 进度在绘制期从 LazyListState 直接读：LazyColumn 在**测量阶段**就写好了这些值，
 * 而绘制必然在测量之后，所以拿到的是当帧值，零延迟、不触发重组。
 * 对比 snapshotFlow 采集进普通 State 再读——那条链路是「布局→帧末通知→协程→写 State
 * →下帧失效」，最低晚一帧且抖动（见 ArtistDetail.kt 的 heroOffsetInViewport 注释）。
 *
 * ── 为什么封面高度要自己量 ────────────────────────────────────────────────
 *
 * 「进度 1.0」的语义是**封面滚过自身高度**（你定的口径），不是滚过整个头部 item。
 * 头部 item 还含标题/按钮/简介，高度与封面无关，所以必须量封面自己。
 * 量到的值存进 MutableFloatState，供封面（位移/缩放/模糊）和文字（淡出）共用同一进度。
 */

/**
 * 动效全部可调参数。数值来自原型里逐帧调定的结果，改动这里即可整体重调。
 *
 * 曲线都定义在「进度 p」上，幂律 p^n 的语义：n>1 = **前慢后快**，n<1 = 前快后慢。
 * - [BlurSpan]：模糊在第几成进度时就跑满（0.58 = 58% 处已达上限，之后不再变糊）
 * - [BlurPow] 2.00 > 1：前段几乎不糊，过半才明显糊起来
 * - [OpacityPow] 1.15：略偏后段，前段基本保持清晰，接近末尾才快速消失
 * - [SinkPow] 1.65：下沉集中在中后段（前 1/4 只走约 10%）
 */
internal object CoverScrollMotion {
    /** 最浓时的模糊半径。40dp 在 3x 屏上是 120px，是这套动效里最贵的一笔。 */
    val MaxBlur = 40.dp

    /** 最大下沉量：封面相对列表向下滑的距离（视差迟滞）。 */
    val Sink = 120.dp

    /** 终态缩放：轻微缩小强化「退到后面去」的纵深。 */
    const val EndScale = 0.94f

    /** 模糊跑满的位置（进度比例）。 */
    const val BlurSpan = 0.58f

    const val BlurPow = 2.00f
    const val OpacityPow = 1.15f
    const val SinkPow = 1.65f

    /**
     * 标题/歌手名的淡出**起点**：进度到这儿才开始退，之前完全不透明。
     *
     * 不能跟封面同曲线——封面糊起来时标题还该是清晰可读的，过早一起淡会让整块头部
     * 一起「塌掉」，观感是页面在消失而不是封面在退让。
     */
    const val TitleFadeStart = 0.55f
}

/**
 * 滚动进度 0..1。封面滚过自身高度即到 1。
 *
 * [offset] 是首个 item 已滚出的像素数（`firstVisibleItemScrollOffset`）；
 * 首个 item 已完全离屏时应传 [Int.MAX_VALUE]，得到 1。
 * 尺寸未测出时返回 0，避免除零把封面瞬间抹掉。
 */
internal fun coverScrollProgress(offset: Int, heightPx: Int): Float = when {
    heightPx <= 0 -> 0f
    offset <= 0 -> 0f
    else -> (offset.toFloat() / heightPx).coerceIn(0f, 1f)
}

/** 模糊量的归一化比例 0..1（乘 [CoverScrollMotion.MaxBlur] 得到实际半径）。 */
internal fun coverBlurFraction(p: Float): Float {
    val t = (p / CoverScrollMotion.BlurSpan).coerceIn(0f, 1f)
    return t.pow(CoverScrollMotion.BlurPow)
}

/** 不透明度 1..0。封面与文字共用同一条曲线（你选的「跟着淡」）。 */
internal fun coverAlpha(p: Float): Float =
    (1f - p.coerceIn(0f, 1f).pow(CoverScrollMotion.OpacityPow)).coerceIn(0f, 1f)

/** 下沉量的归一化比例 0..1（乘 [CoverScrollMotion.Sink] 得到实际位移）。 */
internal fun coverSinkFraction(p: Float): Float =
    p.coerceIn(0f, 1f).pow(CoverScrollMotion.SinkPow)

/** 缩放系数 1..[CoverScrollMotion.EndScale]，与下沉同曲线（一起退远）。 */
internal fun coverScale(p: Float): Float {
    val t = p.coerceIn(0f, 1f).pow(CoverScrollMotion.SinkPow)
    return 1f - (1f - CoverScrollMotion.EndScale) * t
}

/**
 * 标题 / 歌手名的淡出：进度过半后才开始，到终点归零。
 *
 * 比封面晚得多是刻意的——封面先糊、先退，文字留在原地保持可读，
 * 直到封面基本退净才开始跟着走。若与 [coverAlpha] 同曲线，
 * 封面上还清晰的时候标题就已经淡了一半，整块头部会一起塌。
 */
internal fun coverTitleAlpha(p: Float): Float {
    val start = CoverScrollMotion.TitleFadeStart
    val t = ((p.coerceIn(0f, 1f) - start) / (1f - start)).coerceIn(0f, 1f)
    return (1f - t * t).coerceIn(0f, 1f)
}

/**
 * 一次动效的共享状态：同一个进度喂给封面（位移/缩放/模糊）与文字（淡出），
 * 保证两者严格同帧、不会一个先动一个后动。
 *
 * 由 [rememberCoverScrollMotion] 创建，页面持有一份即可。
 */
@Stable
internal class CoverScrollMotionState internal constructor(
    private val listState: LazyListState,
    private val coverHeightPx: MutableFloatState
) {
    /**
     * 滚动进度。首个 item 已离屏时恒为 1（封面继续按终态画，直到随 item 一起被回收）。
     */
    val progress: State<Float> = derivedStateOf {
        if (listState.firstVisibleItemIndex > 0) {
            1f
        } else {
            coverScrollProgress(
                offset = listState.firstVisibleItemScrollOffset,
                heightPx = coverHeightPx.floatValue.toInt()
            )
        }
    }

    val blurRadius: State<Float> = derivedStateOf {
        CoverScrollMotion.MaxBlur.value * coverBlurFraction(progress.value)
    }

    /** 封面尺寸回调：必须挂在封面节点上，用真实测量值做进度分母。 */
    internal fun onCoverSizeChanged(height: Int) {
        coverHeightPx.floatValue = height.toFloat()
    }
}

@Composable
internal fun rememberCoverScrollMotion(listState: LazyListState): CoverScrollMotionState {
    val height = remember { mutableFloatStateOf(0f) }
    return remember(listState) { CoverScrollMotionState(listState, height) }
}

/**
 * 封面的位移 + 缩放 + 模糊。挂在封面外层 Box 上（ShadowImage 的父级）。
 *
 * 位移、缩放、透明度和模糊全部挂在**同一个扩大后的图层**上。
 *
 * 关键：`graphicsLayer` 一旦 alpha < 1 就必须建离屏层，层的大小严格等于该节点的
 * 测量尺寸。若它等于封面尺寸，那么画在封面之外的阴影（BlurMaskFilter）和模糊的
 * 高斯尾部会一起被层的矩形边界裁断——这就是「一开始滚动就出现一个和封面一样大的
 * 直角框把阴影和封面圈住」的真正来源（静止 alpha == 1 不建层，所以只在动画期间出现）。
 *
 * 因此这里先让节点比封面四周各大 MaxBlur，再在**外层** layout 扣回占位：
 * 父级看到的仍是原封面尺寸，圆角/sharedElement/列表高度都不变，但阴影与模糊都落在
 * 层内部，边缘不再被切。bleed 固定，不随进度变化，避免滚动起手时结构切换。
 *
 * @param enabled 是否启用封面模糊；false 时保留下沉/缩放/淡出。
 */
internal fun Modifier.coverScrollMotion(
    state: CoverScrollMotionState,
    enabled: Boolean = true
): Modifier = this
    .onSizeChanged { state.onCoverSizeChanged(it.height) }
    .layout { measurable, constraints ->
        val bleedPx = CoverScrollMotion.MaxBlur.roundToPx()
        val expandedWidth = if (constraints.maxWidth == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            constraints.maxWidth + bleedPx * 2
        }
        val expandedHeight = if (constraints.maxHeight == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            constraints.maxHeight + bleedPx * 2
        }
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = expandedWidth,
                maxHeight = expandedHeight
            )
        )
        layout(
            (placeable.width - bleedPx * 2).coerceAtLeast(0),
            (placeable.height - bleedPx * 2).coerceAtLeast(0)
        ) {
            placeable.place(-bleedPx, -bleedPx)
        }
    }
    .graphicsLayer {
        val p = state.progress.value
        translationY = CoverScrollMotion.Sink.toPx() * coverSinkFraction(p)
        val s = coverScale(p)
        scaleX = s
        scaleY = s
        alpha = coverAlpha(p)
        // 模糊作用在扩大后的层上，边缘有足够像素让高斯尾部衰减。
        // API < 31 无 RenderEffect，退化后只保留位移/缩放/淡出。
        renderEffect = if (enabled && Build.VERSION.SDK_INT >= 31) {
            val radiusPx = state.blurRadius.value.dp.toPx()
            if (radiusPx > 0.5f) {
                RenderEffect.createBlurEffect(
                    radiusPx,
                    radiusPx,
                    Shader.TileMode.DECAL
                ).asComposeRenderEffect()
            } else {
                null
            }
        } else {
            null
        }
        clip = false
    }
    .padding(CoverScrollMotion.MaxBlur)

/**
 * 头部「会退场」的那部分文字（标题 + 歌手名）的淡出。
 *
 * 与封面**不**同曲线：用 [coverTitleAlpha]，进度过半后才开始退。
 * 封面糊起来时标题仍清晰可读，等封面基本退净了文字才跟着走——
 * 反过来（同步淡）会让整块头部一起塌，观感是页面在消失而不是封面在让位。
 *
 * **不要**把播放控件 / 简介包进来：它们是常驻操作与内容，不该随封面淡出。
 */
@Composable
internal fun Modifier.coverTitleFade(state: CoverScrollMotionState): Modifier =
    this.graphicsLayer {
        alpha = coverTitleAlpha(state.progress.value)
    }
