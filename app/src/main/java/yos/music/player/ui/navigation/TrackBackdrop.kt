package yos.music.player.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.roundToInt

/**
 * 当前歌曲封面派生的整页渐变，及其交叉淡化驱动。
 *
 * 三条约束决定了这里的结构：
 *
 *  1. **结果要跨页面存活。** 四个 Tab 常驻组合，切 Tab 会重建组合；每次重解码封面
 *     算色会让底色闪一下。[TrackGradientCache] 按「封面 + 深浅模式」缓存结果。
 *     不做跨进程持久化：重算只是解一张 128px 图 + 5 条带均值，且封面本来就在
 *     Coil 的磁盘缓存里，冷启动成本可忽略，换来的好处是不往 MMKV 里灌无限多的键。
 *
 *  2. **渐变不能进 `remember` 的键。** 顶栏 backdrop 的 `onDraw` 是 remember 在种子色
 *     上的（见 Title.kt 的 rememberTitleBackdrop）。若把逐帧变化的颜色当键，每一帧
 *     都会新建一个 LayerBackdrop，顶栏模糊采样被反复重置——这正是
 *     OnlinePlaylistDetail 记过的坑。所以这里只暴露**状态**，由绘制期读取。
 *
 *  3. **交叉淡化进度必须量化。** 顶栏 backdrop 录制的是整页 display list，进度每变
 *     一次就要重录一次。逐帧推进 = 每秒 60 次全屏重录，代价不可接受。量化到
 *     [FADE_STEPS] 档后，一次切歌只触发 ≤8 次重录；700ms 内推进 8 档，每档约 88ms、
 *     相邻档色差 <1%，肉眼完全看不出阶梯。
 */
object TrackBackdrop {
    /** 交叉淡化时长：长到不突兀，短到不让人觉得页面在"慢慢变色"。 */
    private const val FADE_MS = 700

    /** 交叉淡化量化档数。见类注释第 3 条。 */
    private const val FADE_STEPS = 8

    /** 采样边长。128px 足够稳定地反映封面配色，再大只是浪费解码时间。 */
    private const val SAMPLE_SIZE = 128

    private fun cacheKey(uri: Uri, isDark: Boolean): String =
        "track-backdrop:${uri}#${if (isDark) "dark" else "light"}#v1"

    /**
     * 读取（必要时计算）当前歌曲封面在本深浅模式下的渐变。
     *
     * 未开启功能 / 无当前歌 / 取色失败都返回 null，调用方走主题白黑，逐像素不变。
     */
    @Composable
    fun rememberGradient(): TrackBackdropGradient? {
        if (!SettingsLibrary.TrackBackdropGradient) return null
        val uri = MediaViewModelObject.bitmap.value ?: return null
        val isDark = isFlamingoInDarkMode()
        val context = LocalContext.current
        val key = remember(uri, isDark) { cacheKey(uri, isDark) }
        // 命中缓存时首帧就是正确颜色（切 Tab 回来不闪）
        val state = remember(key) { mutableStateOf(TrackGradientCache.get(key)) }

        LaunchedEffect(key, uri) {
            if (state.value != null) return@LaunchedEffect
            val sampled = runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(uri)
                            // 取色要读像素 → 必须软件位图
                            .allowHardware(false)
                            .size(SAMPLE_SIZE)
                            .build()
                    ).drawable?.toBitmap() ?: return@withContext null
                    val w = bitmap.width
                    val h = bitmap.height
                    if (w <= 0 || h <= 0) return@withContext null
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    TrackGradientPalette.sample(pixels, w, h, isDark)
                }
            }.getOrNull() ?: return@LaunchedEffect
            TrackGradientCache.put(key, sampled)
            state.value = sampled
        }

        return state.value
    }

    /**
     * 交叉淡化后的当前渐变。
     *
     * 切歌过程中有三个必须处理的空窗/时序问题，否则背景会闪：
     *
     *  1. **新歌取色尚未算完时 `rememberGradient()` 返回 null。** 这个 null 绝不能
     *     当成「淡化的目标」——那会让整条渐变消失一帧、回退成主题白/黑，就是实测
     *     看到的"切歌闪一下"。null 期间一律继续显示上一首的颜色。
     *  2. **新色到达的那一帧不能先闪新色。** 若用同一个 `Animatable` 跨目标复用，
     *     组合先于 LaunchedEffect 执行，那一帧读到的还是上一轮残留的 1f，于是先画
     *     满新色、下一帧才被 snapTo(0) 拉回旧色再开始淡入——一次可见的闪跳。
     *     所以进度必须**随目标一起重建**（`remember(target)`），新目标出现的那一帧
     *     进度就是 0，从旧色起淡，不存在中间帧。
     *  3. **进度要量化。** 顶栏 backdrop 录的是整页 display list，进度每变一次就重录
     *     一次；逐帧推进等于每秒 60 次全屏重录。量化到 [FADE_STEPS] 档后，一次切歌
     *     只重录 ≤8 次，700ms 内每档约 88ms、相邻档色差 <1%，肉眼无阶梯感。
     */
    @Composable
    fun rememberBlendedGradient(): TrackBackdropGradient? {
        val target = rememberGradient()
        // 屏幕上当前实际显示的渐变（淡化中就是中间色）。每次产出都写回，作为下一次
        // 淡化的起点——否则淡化中途再切歌，起点会退回更早的旧色，画面先跳回去再
        // 重新淡，看起来又是一次闪。
        //
        // 用普通持有者而非 MutableState：它只在「目标变化」的那次组合里被读取（见下面
        // 的 remember(target)），写它不需要触发重组；用 State 反而会在每次写回时
        // 多引发一次整页重组。
        val current = remember { arrayOfNulls<TrackBackdropGradient>(1) }
        // 起点按目标一次性捕获：新目标出现时立即冻结"从哪个颜色出发"。
        val from = remember(target) { current[0] }
        // 进度随目标重建：新目标那一帧就是 0（见类注释第 2 条）。
        val progress = remember(target) {
            Animatable(if (from == null || target == null) 1f else 0f)
        }

        LaunchedEffect(target) {
            if (target == null) return@LaunchedEffect
            if (from != null && from != target) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(FADE_MS, easing = FastOutSlowInEasing)
                )
            }
        }

        // 新歌取色未完成：保留上一首的颜色，绝不回退主题色（见类注释第 1 条）
        if (target == null) return current[0]
        // 首次就绪，或与当前显示色相同：直接落位，不做无意义淡化
        val result = if (from == null || from == target) {
            target
        } else {
            // 量化：档位不变时返回同一个对象，从而不触发重绘/重录
            val step = (progress.value * FADE_STEPS).roundToInt().coerceIn(0, FADE_STEPS)
            remember(from, target, step) {
                TrackBackdropGradient.lerp(from, target, step.toFloat() / FADE_STEPS)
            }
        }
        current[0] = result
        return result
    }
}

/**
 * 渐变结果的内存缓存。
 *
 * 只存内存不落盘：见 [TrackBackdrop] 类注释第 1 条。上限 64 条 ≈ 最近几十首歌，
 * 每条只是一个 5 色 List，内存开销可忽略。
 */
private object TrackGradientCache {
    private val cache = object : android.util.LruCache<String, TrackBackdropGradient>(64) {}
    fun get(key: String): TrackBackdropGradient? = cache.get(key)
    fun put(key: String, value: TrackBackdropGradient) = cache.put(key, value)
}

/**
 * 页面渐变的下发通道。
 *
 * 由外壳在四个 Tab 的 NavHost 之上 provide 一次，`Title` 与 `NavPageVisual` 在
 * 绘制期读取。null = 该页面不参与封面取色（功能关闭，或处在自带提取色的页面里）。
 */
val LocalPageGradient = compositionLocalOf<PageGradient?> { null }

/**
 * 渐变**源**：外壳算出的当前值，只在 `AppTabsShell` 之上 provide 一次。
 *
 * 与 [LocalPageGradient] 分两个通道，是为了让「算」和「用」解耦：外壳必须拿这个值
 * 去算底衬色与底部遮罩色，而只有四个 Tab 根页该把它变成页面底色。艺人页、排行页、
 * 在线专辑/歌单页都在这四个 NavHost 内部，若把 [LocalPageGradient] 直接 provide 到
 * 外壳层，它们也会被染色——所以根页必须自己显式 opt-in。
 */
val LocalTrackGradientSource = compositionLocalOf<TrackBackdropGradient?> { null }

/**
 * 供绘制期读取的渐变持有者。
 *
 * 为什么不直接 provide `TrackBackdropGradient?`：绘制期要读的是**最新**混合结果，
 * 而 CompositionLocal 的值只在重组时更新。包成持有者后，`Title` 的 `onDraw` 每次
 * 绘制都能重新取一次，渐变推进不需要重组整页——这正是"渐变不进 remember 键"
 * 那条约束的落地方式。
 */
class PageGradient internal constructor(
    private val provider: () -> TrackBackdropGradient?
) {
    fun current(): TrackBackdropGradient? = provider()

    /** 顶栏 tint 用的近似单色：渐变平均色，保证顶栏与页面不脱节。 */
    fun averageColor(fallback: Color): Color = current()?.average ?: fallback
}

/**
 * 由外壳 provide 渐变源（见 [LocalTrackGradientSource]）。功能未开启时传 null。
 */
@Composable
fun ProvideTrackGradientSource(
    gradient: TrackBackdropGradient?,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalTrackGradientSource provides gradient, content = content)
}

/**
 * 四个 Tab **根页**的 opt-in：把外壳算好的渐变变成页面底色，并把该页的底色
 * 注册进 [ArtistPageAppearance]，使底栏遮罩与页面进出场**同帧同曲线**地换色。
 *
 * 包在根页（主页 / 资料库 / 搜索）的最外层，子页面不包 —— 二级页
 * （艺人页、排行页、在线专辑/歌单页）因此不会被染色，继续用各自的提取色。
 *
 * ★为什么要注册而不是让外壳直接读渐变色：底栏遮罩的换色必须与页面淡入淡出
 * 走同一个进度。若外壳按「当前路由是不是染色页」直接翻转颜色，换色就是一次瞬跳，
 * 与页面的 spring 淡入淡出节奏完全对不上——实测观感是"页面在慢慢淡、底栏已经跳完了"。
 * 走注册表后，遮罩色由页面自己的 `scrimTransition()` 进度驱动，与艺人页同一套机制。
 *
 * 渐变源为空（功能关闭 / 无当前歌 / 取色失败）时不下发任何东西，也不注册，
 * 子树行为与今天逐像素一致。
 */
@Composable
fun PageGradientRoot(
    entryId: String? = null,
    scrimTransition: PageScrimTransition? = null,
    content: @Composable () -> Unit
) {
    val gradient = LocalTrackGradientSource.current
    if (gradient == null) {
        content()
        return
    }
    // 状态包一层：绘制期读 current() 拿最新值，避免每帧重组整页
    val holder = remember { mutableStateOf(gradient) }
    holder.value = gradient
    val page = remember(holder) { PageGradient { holder.value } }

    // 底栏遮罩色 = 本页底色（渐变底部色），按本页进出场进度参与混合。
    // 与艺人页完全同构：颜色源是 State，进度来自 scrimTransition()。
    if (entryId != null && scrimTransition != null) {
        val scrimColor = remember(holder) {
            derivedStateOf { holder.value.bottom }
        }
        DisposableEffect(entryId, scrimTransition) {
            val registration = ArtistPageAppearance.register(
                entryId,
                scrimColor,
                scrimTransition,
                // 只参与遮罩色，不给底栏玻璃加 tint（用户裁决：只跟随采样 + 修遮罩色）
                contributesTint = false
            )
            onDispose { ArtistPageAppearance.unregister(entryId, registration) }
        }
    }

    CompositionLocalProvider(LocalPageGradient provides page, content = content)
}
