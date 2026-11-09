package yos.music.player.ui.widgets.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 切歌时**封面与背景共用**的唯一过渡时间轴。
 *
 * ## 为什么需要它
 * 改造前播放页的"切歌"是两条互不相关的时间线：
 *  - 封面：`AsyncImage` 原地换 `ImageRequest`。Coil 命中内存缓存时**连 100ms crossfade 都不做**
 *    （`CrossfadeTransition` 明确"内存命中不动画"），`FullScreenStaticCover` / `CoverMorphLayer`
 *    更是显式 `crossfade(false)` —— 于是封面是一帧硬切；
 *  - 背景：`AnimatedContent` 在**糊图解码完成那一帧**才开始 450ms 淡入（不是切歌那一帧）；
 *    流体网格则是 GL 侧 0.5s 线性混合。
 * 两者起点与时长都不同，观感就是"封面闪一下直接跳、背景慢慢化开"。
 *
 * ## 模型
 * [layers] 是**当前屏幕上最多两张**封面的数据标识（本工程即 `YosMediaItem.thumb`），
 * 末位是淡入层、前位是淡出层；过渡结束后裁回一张，旧图层随之离开组合（画笔与请求自动释放）。
 * [progress] 是唯一的 0..1 进度，背景与所有封面渲染面都从这里取值。
 *
 * ### 为什么是"列表"而不是两个固定槽
 * 快速连切时 `[A,B]` → `[B,C]`：更老的那张（A）被直接丢掉，B 从"淡入中"改为满不透明、
 * C 从 0 淡入。任何时刻最多两张，不会残留；代价是 B 会从当时的 alpha 跳到 1——一次轻微跳变，
 * 但比"排队等前一段跑完"好得多（后者会让画面滞后当前歌曲最多 [TrackArtworkCrossfadeMs]）。
 *
 * ### 为什么 alpha / scale 一律在图层块里读
 * 进度每帧都变。在组合期读它会让整棵封面子树（含 Coil 请求构造）逐帧重组，
 * 这是本项目已经在封面缩放、标题字号上踩过多次的坑，见 `CoverArtworkEffect` 的类注释。
 *
 * ## 就绪闸门
 * 新歌的过渡**不**在状态翻新的那一帧起步：先 `prepare(target)`（高清封面解码 +
 * 背景源图烘焙，两者并行、都走 Coil 内存缓存，常规切歌是命中），最多等
 * [TrackArtworkReadyTimeoutMs]。这样新封面第一帧就有图，不会出现"先闪占位图、
 * 再被正式图顶掉"。超时则照常起步——宁可让背景先动，也不能让封面永远停在旧歌。
 */
@Stable
class TrackArtworkTransition internal constructor() {

    /**
     * 屏幕上的封面数据标识，最多两张：`[淡出中的, 淡入中的]`。
     * 单张时即静止态（alpha 恒 1、scale 恒 1）。
     */
    var layers: List<Any?> by mutableStateOf(emptyList())
        internal set

    /** 唯一过渡进度。0 = 完全是旧图，1 = 完全是新图。 */
    val progress = Animatable(1f)
}

/** 过渡时长。背景（糊图/网格）与所有封面渲染面共用这一个值。 */
internal const val TrackArtworkCrossfadeMs = 600

/** 就绪闸门的等待上限。超时照常起步，绝不把过渡卡住。 */
internal const val TrackArtworkReadyTimeoutMs = 500L

/** 新封面淡入时的起始缩放（1.025 → 1.0）。全屏静态封面模式下不启用。 */
internal const val TrackArtworkIncomingStartScale = 1.025f

/** 旧封面淡出时收缩到的比例（1.0 → 0.97）。 */
internal const val TrackArtworkOutgoingEndScale = 0.97f

/**
 * 过渡用的缓动：与项目里艺人页底色、`ArtistPageAppearance` 同一个 600ms 家族，
 * 起步慢、中段快、收尾慢——封面与背景都不会"啪"地开始或结束。
 */
internal val TrackArtworkCrossfadeSpec =
    tween<Float>(TrackArtworkCrossfadeMs, easing = FastOutSlowInEasing)

/**
 * 下一组图层：末位永远是目标。
 *
 * 纯函数，单测钉死三条语义：空表起步 → 单张；目标即当前末位 → 原样返回（同封面不播动画）；
 * 其余 → 丢弃更老的、只留 `[当前末位, 目标]`（快速连切时最多两张，无残留）。
 */
internal fun artworkLayersAfter(current: List<Any?>, target: Any?): List<Any?> = when {
    current.isEmpty() -> listOf(target)
    current.last() == target -> current
    else -> listOf(current.last(), target)
}

/**
 * 某一图层的 alpha。
 *
 * 单张 = 静止态（恒 1，含 `null` 占位封面的首帧，不做"从无到有"的淡入）；
 * 两张时末位按进度淡入、前位按进度淡出（两者互补，交叉过程不会有亮度凹陷）。
 */
internal fun artworkLayerAlpha(index: Int, count: Int, progress: Float): Float = when {
    count <= 1 -> 1f
    index >= count - 1 -> progress
    else -> 1f - progress
}

/**
 * 某一图层的缩放。`enabled = false`（全屏静态封面）时恒为 1：
 * 该模式下封面是满屏出血的静态图，任何缩放都会让贴屏的顶边与底边淡出带产生位移。
 */
internal fun artworkLayerScale(
    index: Int,
    count: Int,
    progress: Float,
    enabled: Boolean
): Float {
    if (!enabled || count <= 1) return 1f
    return if (index >= count - 1) {
        TrackArtworkIncomingStartScale + (1f - TrackArtworkIncomingStartScale) * progress
    } else {
        1f - (1f - TrackArtworkOutgoingEndScale) * progress
    }
}

/**
 * 建立并驱动唯一过渡。
 *
 * @param source 当前歌曲封面标识（本工程是 `MediaViewModelObject.bitmap`，即 `YosMediaItem.thumb`）。
 *   必须是**唯一真相源**：封面所有渲染面与背景都从 [TrackArtworkTransition.layers] 取图，
 *   因此两个渲染面天然同帧。
 * @param prepare 就绪闸门：把新封面与背景源图解进 Coil 内存缓存。可取消。
 */
@Composable
fun rememberTrackArtworkTransition(
    source: () -> Any?,
    prepare: suspend (Any?) -> Unit = {}
): TrackArtworkTransition {
    val transition = remember { TrackArtworkTransition() }
    // 是否已经装载过第一个值。不能只靠 layers 是否为空判断：一首**没有封面**的歌
    // 会得到 layers = [null]，若把它当成"还没起步"，下一首有封面的歌就会整段硬切。
    val started = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // snapshotFlow 而不是在组合期直接 `source()`：本函数在 PlayerShell 顶层调用，
    // 组合期读状态会让**整个外壳**（含迷你条、底栏、弹层宿主）在每次切歌时重组。
    // 走 flow 则只在外壳之外推进状态，图层变化只失效读取 layers / progress 的绘图节点。
    //
    // collectLatest 提供"中断"语义：源变了就取消上一段 animateTo，配合
    // artworkLayersAfter 的裁剪规则，任何时刻最多两张图层。
    LaunchedEffect(Unit) {
        snapshotFlow { source() }.collectLatest { target ->
            // 冷启动（含播放状态恢复）不播过渡：那一刻屏幕上本来就没有旧图可以淡出。
            if (!started.getAndSet(true)) {
                transition.layers = listOf(target)
                transition.progress.snapTo(1f)
                // 预热失败无所谓：各渲染面自己还会再请求一次。
                runCatching { prepare(target) }
                return@collectLatest
            }
            val existing = transition.layers
            // 占位态（尚无封面）→ 第一张真封面：也可能是"装上后第一首歌"，
            // 从占位图淡入没有意义，直接落位。
            if (existing.size == 1 && existing[0] == null && target != null) {
                transition.progress.snapTo(1f)
                transition.layers = listOf(target)
                runCatching { prepare(target) }
                return@collectLatest
            }
            // 同一张封面（同专辑相邻曲目常见）：跳过整段动画。
            if (existing.lastOrNull() == target) return@collectLatest

            // 新图没就绪就不要起步，否则会先看到一帧占位图/空白。
            withTimeoutOrNull(TrackArtworkReadyTimeoutMs) { runCatching { prepare(target) } }

            // 顺序是刻意的：**先把进度归零、再发布双图层**。反过来会有一帧
            // "新图层按旧进度(1.0)绘制"的硬切，正是本次要消灭的现象。
            transition.progress.snapTo(0f)
            transition.layers = artworkLayersAfter(existing, target)
            transition.progress.animateTo(1f, TrackArtworkCrossfadeSpec)
            // 过渡结束：旧图层离开组合（图层节点与它的画笔/请求一起释放）。
            // 单图层时 alpha/缩放恒为 1，与 progress 取值无关，所以先裁后归位不会闪。
            transition.layers = listOf(target)
            transition.progress.snapTo(1f)
        }
    }

    return transition
}

/**
 * 供播放页与外壳共用的过渡实例。
 *
 * 外壳（`MainActivity` 的 PlayerShell）建立唯一一份并 provide；封面渲染面与背景层消费。
 * `static` 语义成立是因为实例本身恒定（内部状态用 `mutableStateOf` / `Animatable` 承载）。
 */
val LocalTrackArtworkTransition = staticCompositionLocalOf<TrackArtworkTransition?> { null }

/**
 * 把 [TrackArtworkTransition] 的图层渲染成一组重叠节点，供各封面渲染面复用。
 *
 * 每个图层拿到一条 `Modifier`，里面只有 alpha 与缩放（都在 `graphicsLayer` 块里读进度，
 * 零重组）。**倾斜/涟漪/描边等交互动效必须由调用方套在外面那层**，而不是每张图各套一份：
 * 交叉淡化期间有两张图，各画一份描边高光会叠出双倍亮度。
 *
 * 用 `key(layer)` 而不是 `key(index)`：过渡结束裁剪图层时，淡入层从末位变成唯一项，
 * 用内容作键才能让它的节点**原地存活**——否则节点被重建，Coil 会先给一帧占位图。
 *
 * @param artwork 没有过渡实例时的直接数据源（不走切歌过渡的调用方）。
 * @param layerContent 第二个参数只是 alpha/缩放修饰符，尺寸与布局由调用方自己给。
 */
@Composable
internal fun ArtworkCrossfadeLayers(
    transition: TrackArtworkTransition?,
    artwork: () -> Any?,
    scaleOnTransition: Boolean,
    layerContent: @Composable (artwork: Any?, modifier: Modifier) -> Unit
) {
    if (transition == null) {
        layerContent(artwork(), Modifier)
        return
    }
    val layers = transition.layers
    layers.forEachIndexed { index, layer ->
        key(layer) {
            layerContent(
                layer,
                Modifier.graphicsLayer {
                    val p = transition.progress.value
                    alpha = artworkLayerAlpha(index, layers.size, p)
                    val s = artworkLayerScale(index, layers.size, p, scaleOnTransition)
                    if (s != 1f) {
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin.Center
                    }
                }
            )
        }
    }
}
