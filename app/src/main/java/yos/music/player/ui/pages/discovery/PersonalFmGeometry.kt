package yos.music.player.ui.pages.discovery

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主页「私人电台」板块的几何（纯函数，可 JVM 单测）。
 *
 * **唯一硬约束**：正在播放的那张圆封面必须**正好一半在玻璃里、一半在玻璃外**。
 * 圆是关于圆心左右对称的，所以「面积各半」等价于「圆心 X == 玻璃右缘」。
 *
 * ★圆心不是 `start + 直径/2`：Pager 的一页宽 = [FmPageSize] = 直径 + 间隙，
 * 圆在页面里**居中**（左右各留 gap/2）。所以圆心 = `start + FmPageSize/2`。
 * 用直径算会差 `gap/2`，玻璃一窄就露馅。
 */

/**
 * 整体等比放大倍率。
 *
 * 用户要求「等比例放大玻璃遮罩和封面的大小，适当即可」。
 * 所有几何量都由这一条推出来——想整体再大/再小只改这里，
 * 各个常量绝不会互相漂移（这正是上一版"扣两遍内缩"的教训来源）。
 */
internal const val FmScale: Float = 1.2f

/** 板块高度（放大前 150dp）。 */
internal val FmStageHeight: Dp = 150.dp * FmScale

/**
 * 圆封面相对整体倍率的**额外**放大。
 *
 * 用户要求「封面图大一点点就够」——所以不动 [FmScale]（那会连带放大玻璃、
 * 板块高度、圆角、间隙，等于整体重排），只给封面单独一个 1.08。
 * 94dp × 1.2 × 1.08 ≈ 122dp，比之前大 8%，视觉上"一点点"。
 *
 * ★放大封面不影响半遮不变量：[fmCircleRowStart] 是用 `pageSize/2` 反推的，
 * 而 `pageSize` 跟着直径走，所以圆心恒等于玻璃右缘这条铁律自动成立。
 */
internal const val FmCoverScale: Float = 1.08f

/** 圆封面直径（放大前 94dp）。 */
internal val FmCircleDiameter: Dp = 94.dp * FmScale * FmCoverScale

/** 圆之间的间隙，同时也是页面内圆的左右留白总量（放大前 8dp）。 */
internal val FmCircleGap: Dp = 8.dp * FmScale

/** 玻璃上下内缩（放大前 10dp）。 */
internal val FmGlassInsetY: Dp = 10.dp * FmScale

/** 玻璃圆角（放大前 14dp）。 */
internal val FmGlassCorner: Dp = 14.dp * FmScale

/**
 * 板块左内缩：玻璃左缘距**屏幕左缘**的距离。
 *
 * 用户要求「左边保持不变」，所以这个值恒等于其它板块标题的左内边距（20dp）。
 * ★板块本身不再有 horizontal padding（否则封面进不了屏幕外），
 * 这个缩进只作用在玻璃定位与 Pager 的 contentPadding 上。
 */
internal val FmRailStart: Dp = 20.dp

/**
 * 玻璃宽度（放大前 134dp）。
 *
 * 「削掉 1cm」的口径：1dp = 1/160 英寸，1cm = 160/2.54 ≈ 63dp。
 * 原板宽 ≈ 197dp → 197 − 63 = 134dp，再乘 [FmScale]。
 * 用绝对 dp 而非屏宽比例：「削 1cm」是绝对量，比例会随屏宽漂移。
 */
internal val FmGlassWidth: Dp = 134.dp * FmScale

/**
 * 玻璃高度 = 板块高度 − 上下内缩。
 *
 * ★**唯一的尺寸来源**。历史上调用点先 `.height(FmStageHeight - FmGlassInsetY*2)`
 * 又传了一个 `padding(vertical = FmGlassInsetY)`，Modifier 链里扣了两遍，
 * 玻璃实际短了 16dp 且上偏 8dp。现在只在
 * [yos.music.player.ui.pages.discovery.PersonalFmGlassStage] 里用这个常量定尺寸。
 */
internal val FmGlassHeight: Dp = FmStageHeight - FmGlassInsetY * 2

/** 一页的宽度 = 一张圆 + 一个间隙。 */
internal val FmPageSize: Dp = FmCircleDiameter + FmCircleGap

/**
 * 玻璃基础模糊半径（再乘 `glassBlurScale`）。
 *
 * ★**标称值不是实际视觉半径**：`com.kyant.backdrop.effects.blur` 内部会再乘一次
 * `DOWNSAMPLE_SCALE = 0.42`，所以全 App 的标称值都比肉眼看到的大一档
 * （下拉 24dp≈10dp、顶栏 14dp≈6dp）。这里 12dp ≈ 实际 5dp，与顶栏/底栏同一档。
 * 不要调到 20dp 以上：半径一大结构就被抹平，看起来是"一块白雾"而不是玻璃。
 */
internal val FmGlassBaseBlur: Dp = 12.dp

/**
 * 关闭「液态玻璃效果」后的磨砂模糊半径（不经 `glassBlurScale`）。
 *
 * 口径与底栏「磨砂胶囊」分支一致：**关玻璃不等于关模糊**。
 * 玻璃态 = 模糊 + vibrancy + 折射 + 边缘高光；磨砂态 = 只剩模糊 + 更实的表面色。
 * 库内仍会再乘 `DOWNSAMPLE_SCALE`，所以标称 16dp ≈ 实际 6.7dp。
 */
internal val FmFrostedBlur: Dp = 16.dp

/** 两个封面层共用的 `contentPadding(start)`（相对**玻璃**左缘）。 */
internal fun fmCircleRowStart(glassWidth: Dp = FmGlassWidth, pageSize: Dp = FmPageSize): Dp =
    glassWidth - pageSize / 2

/** 圆心 X（相对玻璃左缘）。半遮不变量：必须恒等于 [FmGlassWidth]。 */
internal fun fmCircleCenterX(glassWidth: Dp = FmGlassWidth, pageSize: Dp = FmPageSize): Dp =
    fmCircleRowStart(glassWidth, pageSize) + pageSize / 2

/**
 * Pager 的 `contentPadding.start`（相对**屏幕**左缘）。
 *
 * 板块已铺满屏宽（封面要能滑到屏幕外），所以必须把玻璃左缘的 20dp 一起加进来，
 * 才能让圆心的绝对位置仍与玻璃右缘对齐。
 */
internal fun fmPagerStartPadding(
    railStart: Dp = FmRailStart,
    glassWidth: Dp = FmGlassWidth,
    pageSize: Dp = FmPageSize
): Dp = railStart + fmCircleRowStart(glassWidth, pageSize)

/**
 * Pager 的 `contentPadding.end`。
 *
 * 让最后一页也能滚到「玻璃右缘」那一列，否则队尾几台永远滑不到焦点位。
 */
internal fun fmPagerEndPadding(
    stageWidth: Dp,
    railStart: Dp = FmRailStart,
    pageSize: Dp = FmPageSize
): Dp = (stageWidth - railStart - pageSize).coerceAtLeast(0.dp)

/** 圆落在玻璃内的宽度 = 直径的一半（面积恰好一半）。 */
internal fun fmHalfOverlap(circleDiameter: Dp = FmCircleDiameter): Dp = circleDiameter / 2

/**
 * 某页封面的不透明度 —— **按它在屏幕上的横向位置算**。
 *
 * 用户口径（2026-11-08）：
 * 「只要有封面在左边这一块，它往左走就逐渐变透明，往右走就又从透明逐渐变清楚；
 *   淡出从"正在播放的这个位置"再往左就开始。」
 * 追加（同日晚）：「最终消失的地方要**在玻璃的范围里头**，不能超出玻璃。」
 *
 * 所以淡出带整段**落在玻璃内部**，两端分别锚在玻璃的两条边上：
 *  - 右端 = 玻璃右缘（= 焦点列，"正在播放那个位置"）→ alpha = 1.0
 *  - 左端 = **玻璃左缘** → alpha = 0.0（在这里就彻底消失，不再往屏幕左缘拖）
 *
 * 上一版把 0 点放在屏幕左缘，于是封面滑出玻璃之后还残留半透明的一截，
 * 看起来"消失得太晚"——这正是这次要修的点。
 *
 * 判据是**位置**，不是"这一页是不是正在播"：任何一颗封面滑进这块都按同一套规则。
 *
 * @param fadeStartXPx 玻璃右缘（alpha=1 的那端）
 * @param fadeEndXPx 玻璃左缘（alpha=0 的那端）
 */
internal fun fmCoverAlpha(
    page: Int,
    pagerPosition: Float,
    pageSizePx: Float,
    fadeStartXPx: Float,
    fadeEndXPx: Float
): Float {
    if (pageSizePx <= 0f || fadeStartXPx <= 0f) return 1f
    val span = fadeStartXPx - fadeEndXPx
    // 玻璃宽度取到 0 或负数说明尺寸还没测量好：不要算成全透明（会闪一下全没）
    if (span <= 0f) return 1f
    // 该页当前圆心的屏幕 X：焦点列 + (page − pagerPosition) × 一页宽
    val centerX = fadeStartXPx + (page - pagerPosition) * pageSizePx
    return ((centerX - fadeEndXPx) / span).coerceIn(0f, 1f)
}

/**
 * 封面自转的相位推进（纯函数）。
 *
 * ★这里踩过一个很典型的坑：**全板块共用同一个累积角度**，绘制时只让"当前页"读它。
 * 一旦换台，新页就从 0 直接跳到累积值（比如 137°）再接着转，
 * 画面上就是"先跳一下、再开始转"。
 *
 * 正确模型：**每个台各自计相位**，谁被切过去就从自己的起点连续转下去；
 * 没在转的台保留它停下的角度（不归 0，否则它回到视野里又是一次跳变）。
 *
 * @param prevDegrees 该台上一帧的角度；null 表示还没转过
 * @param deltaMs 这一帧推进的毫秒数
 * @param degreesPerMs 角速度
 * @return 新角度。**不做 % 360**：`rotationZ` 从 359 跳回 0 会被合成器
 *   理解成反向猛转一圈；直接累加（float 能装到很大）视觉等价且无跳变。
 */
internal fun fmAdvancePhase(
    prevDegrees: Float?,
    deltaMs: Float,
    degreesPerMs: Float = 0.02f
): Float = (prevDegrees ?: 0f) + deltaMs * degreesPerMs

/**
 * 旋转起停缓冲时长。
 *
 * ★**必须与音频淡入淡出一致**：`FadeExo.fadeAnimationDuration = 200L`。
 * 用户口径：封面开始转/停止转的缓冲，要和音频的淡入淡出同步，
 * 这样"声音起来了，盘也起来了"是同一个节奏。
 * 改音频那边时这里要一起改。
 */
internal const val FM_SPIN_FADE_MS: Int = 200

/**
 * 某台在某一帧应当显示的角度。
 *
 * - 正在播并且系统动画开着 → 用它的相位
 * - 其它情况 → 保留它已停下的角度（没有记录就是 0）
 *
 * ★注意**不是** `if (isPlaying) phase else 0f`：那样一带一停就会归零跳变。
 */
internal fun fmPhaseToDraw(
    phaseDegrees: Float?,
    isSpinning: Boolean
): Float = phaseDegrees ?: 0f

/**
 * 「落位之后要不要换歌」。
 *
 * 判据全部是纯量，便于单测钉死——这套判断在真机上错过两次：
 * 先是多余的回弹把用户滑走的台拽回来，再是拉回来之后根本没换歌。
 *
 * @param userBrowsing 这一次落位是否由**真实手势**发起。外部跟随（通知栏切歌）、
 *   进页锚定、无障碍动作都不算浏览意图，绝不能反过来触发一次 prepare。
 * @param scrolling Pager 是否仍在滚（含松手后的吸附弹簧）。
 * @param offsetFraction 当前页的相对偏移；非 0 表示还没吸附到位。
 * @param targetMediaId 落位那一台对应的 mediaId。
 * @param playingMediaId 播放器当前的 mediaId（**暂停时仍然有值**）。
 */
internal fun fmShouldFocusOnSettle(
    userBrowsing: Boolean,
    scrolling: Boolean,
    offsetFraction: Float,
    targetMediaId: String?,
    playingMediaId: String?
): Boolean {
    if (!userBrowsing) return false
    if (scrolling) return false
    // 吸附弹簧没走完就换歌会导致"滑一半就切"
    if (offsetFraction != 0f) return false
    if (targetMediaId == null) return false
    // ★同一个 ID 就直接返回：这里必须包含「暂停中」的情况——
    //   暂停时 playingMediaId 依然非空，用户滑回它不该从头重播。
    if (targetMediaId == playingMediaId) return false
    return true
}

/**
 * Pager 的 item key。
 *
 * ★必须越界安全：`HorizontalPager` 内部维护 key→index 映射，队列从 50 首缩短到
 * 5 首时，它仍会用**旧页数**回调这个 lambda（真机实测 `Index 5 out of bounds
 * for length 5`）。返回稳定占位 key 让旧页面平滑退出，绝不直接 `songs[page]`。
 */
internal fun fmPageKey(songs: List<yos.music.player.data.repositories.KugouNewSong>, page: Int): String =
    songs.getOrNull(page)?.hash ?: "fm-placeholder-$page"
