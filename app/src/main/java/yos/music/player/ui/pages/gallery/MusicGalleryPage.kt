package yos.music.player.ui.pages.gallery

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import yos.music.player.ui.widgets.mochi.MochiActivityIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.PersonalFmObject
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.FavoriteRepository
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.basic.navigationBarsPadding
import yos.music.player.ui.widgets.basic.rawNavigationBarsBottomDp
import yos.music.player.ui.widgets.basic.statusBarsPadding
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.effects.overlayEffect
import yos.music.player.ui.widgets.effects.pearmesh.isPearMeshSupported
import yos.music.player.ui.widgets.effects.warmUpMeshArtwork
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import yos.music.player.ui.widgets.liquid.claimDragGestures
import yos.music.player.ui.widgets.liquid.classyabaWhiteEdge
import yos.music.player.ui.widgets.liquid.glassBlurScale
import yos.music.player.ui.widgets.mochi.rememberMochiSheetState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 音乐画廊（入口：资料库›在线音乐）。
 *
 * **运动模型**：界面上**恒定只有 4 个实体卡片容器**，整个流程里没有任何卡片被
 * 创建或销毁、也没有卡片从屏幕外进入或飞出。4 个固定物理槽位构成顺时针闭环
 * `Slot0(右上主焦点) → Slot1(右侧) → Slot2(正下方) → Slot3(左中) → Slot0`，
 * 向右滑动时四张卡沿环顺畅位移。
 *
 * **换图时机（延后一拍）**：卡片刚离开主焦点（Slot0→Slot1）时封面**纹丝不动**，
 * 作为"上一首被切走的视觉记忆"；直到它从 Slot1 滑向 Slot2（右侧→正下方）那一段，
 * 才在槽位内部用双层 `AsyncImage` 做交叉渐变，悄悄换成队列里的新歌。
 * 这一点由 [GallerySlotGeometry.crossfadeAlpha] 保证：它只在该段从 0 涨到 1。
 *
 * 手势：**手指向右滑 = 下一首**（scrollIndex 增大）；向左滑回退。
 * 松手用低刚度柔顺弹簧吸附归位。
 *
 * **滚动无上界**：画廊是电台式的循环轨道，内容用环形取模映射到队列，
 * 因此永远不会"滑到某一首就滑不动"；队列补货由 ensureAhead 在后台持续进行。
 *
 * 背景 = 播放页同款 [YosFloatingLight] 双实例（偶数锚点恒在下层 alpha=1，
 * 奇数锚点在上层按 frac 交叉淡化，弹簧微震荡不会触发底层 Uri 重载）。
 *
 * 全屏沉浸：MainActivity 按路由（UI.MusicGallery）隐藏全局底栏/迷你条、
 * 底部遮罩归零并强制状态栏白字。
 *
 * 性能红线：scrollIndex 只在 graphicsLayer / draw lambda 里读取；
 * 容器数量恒为 4，拖拽期间零新增节点。
 *
 * **按钮玻璃的采样边界（新增，务必遵守）**：按钮的液态玻璃要能透出背后的封面糊图，
 * 必须有一个页内 `LayerBackdrop` 作为采样源。录制范围**只**包含「背景 + 顶部 scrim +
 * 4 张封面卡」这一层（[galleryBackdropTarget]），按钮与文字区是它的**兄弟节点**。
 *  - 按钮**不能**进录制 Box：`drawBackdrop` 消费者采样到含自身的层 =
 *    RenderNode 自引用递归，RenderThread 原生栈溢出秒崩（ArtistDetail / SongCommentsPanel
 *    反复记录的同一条红线）。
 *  - 文字区**不进**录制 Box：歌名/歌手名的 `overlayEffect()` 是 `BlendMode.Plus`，
 *    它的颜色正确性依赖「Plus 的底是整页画布」（见歌手名处的长注释）；把它卷进任何
 *    新图层都可能让加色的底变成离屏缓冲的透明黑，静止与拖动中出现色差。
 *  - 也**不能**退回去采样 MainActivity 的 `navBackdrop`：本页整页就在
 *    `NavPageVisual` 的 `layerBackdrop(navBackdrop)` 子树内，同样自引用。
 */

/**
 * 松手归位弹簧：低刚度、高阻尼的**柔顺慢弹簧**。
 *
 * 旧值（stiffness 260~350）刚度太高，松手后卡片像磁铁一样"啪"地吸死，生硬急促。
 * stiffness 95 让归位舒展、慢速、有空气阻尼感；dampingRatio 0.96 接近临界阻尼，
 * 柔和收尾不来回晃。visibilityThreshold 压到 0.0005 是因为吸附行程可能不足一个
 * 整槽（0.22 阈值触发），粗阈值会让动画在还差几像素时提前判定结束。
 */
private val GallerySettleSpec = spring<Float>(
    dampingRatio = 0.96f,
    stiffness = 95f,
    visibilityThreshold = 0.0005f
)

/** 底部「上一首 / 下一首」按钮步进专用弹簧：响应跟手、连点顺滑且不过冲。 */
private val GalleryButtonStepSpec = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 180f,
    visibilityThreshold = 0.0005f
)

/**
 * 手势满行程距离占舞台宽度比例。
 *
 * 0.25 * 1280px = 320px ≈ 98dp 就是 100% 进度，轻轻一拨即可走完一次轮转。
 */
private const val TRIGGER_FRACTION = 0.25f

/** 松手翻页的位移阈值（占一个整槽的比例）：拖过 22% 就翻页。 */
private const val COMMIT_FRACTION = 0.22f

/** 松手翻页的速度阈值（索引单位/秒）。≈ 300dp/s ÷ (0.25 × 屏宽)。 */
private const val SETTLE_VELOCITY = 0.75f

/**
 * 传入 animateTo 的初速度限幅（索引单位/秒）。
 *
 * 不限幅时，用力甩一下会把手势速度原样灌进弹簧，归位瞬间过猛、冲过目标再回弹，
 * 正是"生硬"的来源之一。限到 ±2.2 后，无论轻拨还是快甩，卡片都以从容的节奏
 * 沿圆环滑入目标槽位。
 */
private const val MAX_SETTLE_VELOCITY = 2.2f

/** 封面卡片圆角：接近直角的微圆角实体唱片封面（不要大圆角 App 图标观感）。 */
private val GalleryCardCornerRadius = 4.dp

/** 文字区高度：容纳最多 2 行 30sp/36sp 标题 + 6dp + 16sp 歌手名。 */
private val GalleryTitleBlockHeight = 104.dp

/** 底部药丸：距**屏幕底边**（含系统导航条 inset 在内）的设计距离（用户裁决整体抬高 16dp）。 */
private val GalleryPillBottomFromScreen = 44.dp

/**
 * 播放药丸尺寸。宽度由 86dp 加长到 108dp（用户反馈"播放按钮要更长一些"）。
 *
 * 加长而不是加高：药丸是底部唯一的实体操作件，横向拉长让它更接近
 * 「播放条」的语义、也更像 iOS/Material 的底部主操作胶囊；高度保持 46dp，
 * 与顶栏 36dp 圆钮仍构成明确的尺寸层级，且不改动 `GalleryPillBottomFromScreen`
 * 与计数器（96dp）之间的既有间距关系。
 */
private val GalleryPillWidth = 108.dp
private val GalleryPillHeight = 46.dp

/** 页码计数器：距**屏幕底边**的设计距离（药丸右上方，与药丸同步抬高 16dp）。 */
private val GalleryCounterBottomFromScreen = 112.dp

/**
 * 卡片舞台整体下移量（占舞台宽度 W 的比例）。
 *
 * 槽位坐标是**相对舞台中心**的，主卡顶边在 y = -0.15W - 0.26W = -0.41W，
 * 而标题区在歌名折成两行时底边约 268dp —— 两者几乎重合，实机里主卡会压住歌手名。
 * 这里只把整簇下移，不改任何槽位之间的相对关系。
 */
private const val STAGE_Y_BIAS = 0.065f

// =====================================================================================
// 按钮的液态玻璃材质
//
// 配方与 App 内既有玻璃控件**逐项同源**，改动时三处要一起改：
//   · LiquidTopBarButton（顶栏圆钮 / 返回键 / 艺人页圆钮）
//   · GlassEffectSetting 的 DemoGlassButton / DemoGlassCapsule（设置页实时预览）
//   · 本文件（音乐画廊的 3 个圆钮 + 播放药丸）
// 之所以复制配方而不是抽公共组件：三个场景的形状、尺寸、按压行为、内容色策略都不同，
// 强行归一会把 LiquidTopBarButton 变成一堆开关（它服务 3 个在用的界面，不宜冒险重构）。
// =====================================================================================

/**
 * 玻璃表面纱：叠在采样层之上的那层半透明白。
 *
 * 画廊是**恒为深色**的全屏页（背景是 `bakeDarkOverlay = true` 的封面糊图，且强制
 * 状态栏/导航栏白图标），因此无条件走深色玻璃分支 —— 取值对齐 GlassPreviewCard 的
 * dark 分支（0.10）与 LiquidTopBarButton 的自适应亮度分支（0.10）。
 *
 * **不能**套 LiquidTopBarButton 的浅色主题分支（面 0.76 + 黑图标）：深色照片上
 * 会出现「白芯片 + 白图标」的错配。
 */
private val GalleryGlassSurface = Color.White.copy(alpha = 0.10f)

/** 玻璃基准模糊半径：与顶栏圆钮同值，再乘全局「玻璃模糊强度」倍率。 */
private val GalleryGlassBaseBlur = 4.dp

/**
 * 画廊按钮的按压反馈：**弥散高光跟手 + 朝手指方向微倾 + 按压放大**。
 *
 * 三层接线（每层只用被实证可靠的机制）：
 *  - 点击：标准 `clickable`，`onClick` 直接调业务逻辑——每次重组捕获新闭包，
 *    无 remember 定格问题；焦点卡/播放状态读到的永远是当前值。
 *  - 按压视觉 + 拖动认领：[galleryPressGesture]（`claimDragGestures` +
 *    `InteractiveHighlight` 的 external 驱动接口）。**不使用
 *    `pressOnlyModifier`**：它的 `awaitFirstDown()` 要求"未消费"的 DOWN，
 *    在本页实机两种链序下都拿不到事件（clickable 正常触发而按压层饿死，
 *    诊断日志 GALLERY_PLAY 实证），而 `claimDragGestures` 的
 *    `awaitFirstDown(requireUnconsumed = false)` 消费免疫——同一节点上
 *    LiquidTopBarButton draggable 分支正是用 external 接口驱动视觉的。
 *  - 拖动认领：越过 touchSlop 后消费指针，从按钮起手的拖动**不再穿透**到
 *    页面根节点的翻页手势（恢复最初设计：按住按钮拖只让按钮倾斜跟手）。
 */
@Composable
private fun rememberGalleryHighlight(scope: kotlinx.coroutines.CoroutineScope): InteractiveHighlight =
    remember(scope) {
        InteractiveHighlight(
            animationScope = scope,
            // 画廊恒为深色底：光晕峰值走 InteractiveHighlight 的深色档（0.15）
            isDarkMode = true
        )
    }

/**
 * 按压手势层：DOWN 即驱动高光（点击也有按压反馈），拖动跟手微倾；
 * 越过 touchSlop 后消费指针＝认领拖动，页面翻页手势不再接管。
 */
private fun Modifier.galleryPressGesture(highlight: InteractiveHighlight): Modifier =
    pointerInput(highlight) {
        var pos = Offset.Zero
        claimDragGestures(
            onStart = { down ->
                pos = down.position
                highlight.externalDragStart(down.position)
            },
            onEnd = { highlight.externalDragEnd() },
            onCancel = { highlight.externalDragEnd() },
            onDrag = { delta ->
                pos += delta
                highlight.externalDragMove(pos)
            }
        )
    }

/** 按压时的变换（放大 + 朝手指方向倾斜）。绘制期直读，不触发重组。 */
private fun Modifier.galleryPressTransform(
    highlight: InteractiveHighlight,
    pressLift: Dp
): Modifier = graphicsLayer {
    val progress = highlight.pressProgress
    val scale = 1f + pressLift.toPx() / size.height * progress
    scaleX = scale
    scaleY = scale
    // 朝手指方向微倾（≤5%），与 LiquidTopBarButton 同一公式与上限
    val pressOffset = highlight.offset
    translationX = size.minDimension * 0.05f * pressOffset.x / size.maxDimension
    translationY = size.minDimension * 0.05f * pressOffset.y / size.maxDimension
}

/** 按压时的轻微压暗（高光是加色，需要一点减色来体现"按下去"）。 */
private fun Modifier.galleryPressDim(highlight: InteractiveHighlight): Modifier =
    drawWithContent {
        drawContent()
        val progress = highlight.pressProgress
        if (progress > 0f) {
            drawRect(Color.Black.copy(alpha = 0.03f * progress))
        }
    }

/**
 * 按钮的玻璃底 + ClassYaba 同款双层边框（两边黑、上下白高光）。
 *
 * `highlight = classyabaBlackRim` 走库内自带缓存的 `HighlightNode`（形状未变不重录），
 * `.classyabaWhiteEdge(shape)` 挂在 `emptyBackdrop` 上叠加纯描边不额外采样玻璃；
 * `shadow` 传 null 避免多余离屏投影开销。
 * `effects` / `onDrawSurface` 必须由调用方用 remember 产出**稳定实例** ——
 * `DrawBackdropElement.equals` 逐项按引用比较，内联 lambda 每次重组都判「参数变了」，
 * 会触发 invalidateDrawCache 风暴（LiquidBottomTabs 记过同款教训）。
 */
private fun Modifier.galleryGlass(
    backdrop: Backdrop,
    shapeProvider: () -> Shape,
    effects: BackdropEffectScope.() -> Unit,
    surface: DrawScope.() -> Unit
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = shapeProvider,
    effects = effects,
    highlight = null,
    shadow = null,
    onDrawSurface = surface
).classyabaWhiteEdge(shape = shapeProvider)

/** 玻璃效果链：vibrancy + blur(4dp × 倍率) + 折射（API 33+）。 */
private fun glassEffectsOf(glassScale: Float): BackdropEffectScope.() -> Unit = {
    vibrancy()
    blur(GalleryGlassBaseBlur.toPx() * glassScale)
    // lens 走 RuntimeShader，API 33 以下没有；与项目其余玻璃控件同一道门禁
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        lens(8.dp.toPx(), 24.dp.toPx())
    }
}


@Composable
fun MusicGalleryPage(navController: NavController) {
    LaunchedEffect(Unit) { PersonalFmObject.ensureLoaded() }

    val songs = PersonalFmObject.songs.value
    val loading = PersonalFmObject.loading.value
    val loadError = PersonalFmObject.loadError.value
    val songCount = songs.size

    // 连续浮点索引：0.0 → 1.0 → 2.0 …（向右滑增=下一首，向左滑减=上一首）
    //
    // **刻意不设上界**：画廊是电台式循环轨道，内容用环形取模映射到队列，
    // 因此可以无限往前滑。曾经把它 coerceIn(0, songs.size-1) 会有两个后果：
    //   · 队列只有 10 首时"滑到第十首就滑不动"；
    //   · 补货/重载导致 songs 暂时变短时，索引被夹到 0 → "松手后突然跳回第一张"。
    // 只有下界 0（第一首之前不让继续往回拖）是有意义的。
    var scrollIndex by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // 进行中的落位目标（供上一首/下一首连点累进与上一首按钮即时启闭判定）
    var settleTarget by remember { mutableStateOf<Int?>(null) }
    // 落位动画的当前速度（供按钮连点时衔接速度，避免每次归零顿挫）
    var settleVelocity by remember { mutableFloatStateOf(0f) }
    // 按钮步进后的短防抖切歌任务（手势接管或点按播放/暂停时取消）
    var buttonFollowJob by remember { mutableStateOf<Job?>(null) }
    val tracker = remember { VelocityTracker() }
    val stageSize = remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val meshSupported = remember(context) { isPearMeshSupported(context) }
    val effectiveBgMode = resolveGalleryBackgroundMode(
        followPlayback = SettingsLibrary.GalleryFollowPlaybackBackground,
        customGalleryMode = SettingsLibrary.GalleryBackgroundMode,
        playbackEffectEnabled = SettingsLibrary.NowplayingBackgroundEffect,
        playbackMode = SettingsLibrary.NowplayingBackgroundMode,
        meshSupported = meshSupported,
    )
    val useMeshBg = effectiveBgMode == SettingsLibrary.GALLERY_BG_MESH
    val configuration = LocalConfiguration.current
    val sheetSpec = resolveGallerySettingsSheetSpec(
        contentHeightEstimateDp = GallerySettingsEstimatedHeightDp,
        windowHeightDp = configuration.screenHeightDp.toFloat(),
    )
    val settingsSheetState = key(sheetSpec) {
        rememberMochiSheetState(
            detents = sheetSpec.detents,
            initial = sheetSpec.initial,
            mediumFraction = sheetSpec.mediumFraction,
        )
    }
    // 本次手势净位移方向（>0 = 向前/下一首），供松手判据区分 floor/ceil 侧
    var gestureNetX by remember { mutableFloatStateOf(0f) }
    // 是否有手势正在拖拽（松手落位与看门狗都依赖它）
    var dragging by remember { mutableStateOf(false) }
    // 滑动翻页自动切歌的扳机：只在真实手势（onEnd/onCancel）置位。页面刚打开、
    // 看门狗兜底归位都不置位——进画廊只是浏览，不能劫持后台正在播的歌。
    var pendingFollow by remember { mutableStateOf(false) }
    // 进页锚定只做一次：补货会让列表增长（songCount 变化），不能反复重锚。
    var entryAnchored by remember { mutableStateOf(false) }

    fun settle(
        target: Float,
        initialVelocity: Float,
        spec: AnimationSpec<Float> = GallerySettleSpec
    ) {
        settleJob?.cancel()
        settleTarget = target.roundToInt().coerceAtLeast(0)
        // 速度限幅：见 MAX_SETTLE_VELOCITY 注释（防大力滑动时归位过猛）。
        val clampedVelocity = initialVelocity.coerceIn(-MAX_SETTLE_VELOCITY, MAX_SETTLE_VELOCITY)
        settleJob = scope.launch {
            try {
                // block 为无参接收者形式（Compose 1.10）：Animatable.() -> Unit，经 this.value 写回
                Animatable(scrollIndex).animateTo(target, spec, clampedVelocity) {
                    scrollIndex = value
                    settleVelocity = velocity
                }
            } finally {
                // 必须清空：看门狗用 settleJob != null 判断"动画在跑"，
                // 残留非 null 会让它永远认为还在落位，从此再不介入。
                if (settleJob === coroutineContext[Job]) {
                    settleJob = null
                    settleTarget = null
                    settleVelocity = 0f
                }
            }
        }
    }

    fun playSongAt(index: Int) {
        val currentSongs = PersonalFmObject.songs.value
        if (currentSongs.isEmpty()) return
        val song = currentSongs.getOrNull(GallerySlotGeometry.wrapSong(index, currentSongs.size))
            ?: return
        val item = KugouRepository.toQueueMediaItem(song)
        // 已在播（含暂停态）就是这首歌：不打断、不从头重播
        if (MediaController.musicPlaying.value?.mediaId == item.mediaId) return
        scope.launch(Dispatchers.IO) {
            val queue = PersonalFmObject.songs.value.map {
                KugouRepository.toQueueMediaItem(it)
            }
            MediaController.prepare(item, queue, fmQueue = true)
        }
    }

    fun stepSong(delta: Int) {
        if (songCount == 0) return
        val currentTarget = (settleTarget ?: scrollIndex.roundToInt()).coerceAtLeast(0)
        val next = GallerySlotGeometry.stepTarget(scrollIndex, settleTarget, delta)
        if (next == currentTarget) return
        Vibrator.click(context)
        dragging = false
        pendingFollow = false
        PersonalFmObject.ensureQueueAhead(next)
        val carryVelocity = if (settleJob != null) settleVelocity else 0f
        settle(next.toFloat(), carryVelocity, GalleryButtonStepSpec)
        buttonFollowJob?.cancel()
        buttonFollowJob = scope.launch {
            delay(150L)
            playSongAt(next)
        }
    }

    fun onCardClick(containerIndex: Int) {
        if (songCount == 0) return
        val currentTarget = (settleTarget ?: scrollIndex.roundToInt()).coerceAtLeast(0)
        val target = GallerySlotGeometry.cardClickTarget(
            containerIndex = containerIndex,
            scrollIndex = scrollIndex,
            activeTarget = settleTarget,
            size = songCount
        )
        val targetSong = songs.getOrNull(GallerySlotGeometry.wrapSong(target, songCount)) ?: return
        val targetMediaId = KugouRepository.toQueueMediaItem(targetSong).mediaId
        val playingMediaId = MediaController.musicPlaying.value?.mediaId
        val playingNow = MediaViewModelObject.isPlaying.value
        if (target == currentTarget) {
            if (playingMediaId == targetMediaId) {
                if (!playingNow) {
                    Vibrator.click(context)
                    MediaController.mediaControl?.fadePlay()
                }
            } else {
                Vibrator.click(context)
                playSongAt(target)
            }
            return
        }
        stepSong(target - currentTarget)
        if (playingMediaId == targetMediaId && !playingNow) {
            MediaController.mediaControl?.fadePlay()
        }
    }

    /**
     * 兜底归位 + 滑动翻页自动切歌（合并为单一观察者，且在拖拽/落位期间短路返回 null，
     * 避免 60~120 FPS 动画期间每帧分配 Triple 并唤醒协程空转）。
     *
     * 只要「没有手势在拖、也没有落位动画在跑」：
     * 1. 若索引停在半途（pointerInput 被取消导致悬空），吸回最近的整数位；
     * 2. 若已稳定在整数位且 pendingFollow 置位，则触发自动切歌。
     */
    LaunchedEffect(Unit) {
        snapshotFlow {
            if (dragging || settleJob != null) null else scrollIndex
        }.collect { value ->
            val current = value ?: return@collect
            val nearest = current.roundToInt().coerceAtLeast(0)
            if (abs(current - nearest) > 0.001f) {
                settle(nearest.toFloat(), 0f)
            } else if (pendingFollow) {
                pendingFollow = false
                if (PersonalFmObject.songs.value.isNotEmpty()) {
                    playSongAt(nearest)
                }
            }
        }
    }

    // ---------- 进页锚定：聚焦当前正在播的歌 ----------
    // scrollIndex 每次进页从 0 起步，若后台正在播 FM 队列里的第 N 首，重进画廊会
    // 聚焦第一首 → 播放按钮显示"播放"而非"暂停"（实机"重进页面按钮又变关闭"）。
    // 列表首次非空时把焦点锚到正在播的那首；不在列表内（本地歌/其他队列）则保持 0。
    // 只改 scrollIndex、不置 pendingFollow：锚定不是翻页意图，不触发自动切歌。
    LaunchedEffect(songCount) {
        if (entryAnchored || songCount == 0) return@LaunchedEffect
        entryAnchored = true
        val cur = MediaController.musicPlaying.value?.mediaId ?: return@LaunchedEffect
        val idx = songs.indexOfFirst { "kugou-online-${it.hash.lowercase()}" == cur }
        if (idx > 0) scrollIndex = idx.toFloat()
    }

    val density = LocalDensity.current
    // 系统导航条（手势栏）高度：底部药丸/计数器的规格是「距屏幕底边」，
    // 而 navigationBarsPadding() 已经先让出这一份，故内边距要把它扣掉。
    val navigationBarInset = rawNavigationBarsBottomDp()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { stageSize.value = it }
            // 手势：**不限制方向**地独占拖动（claimDragGestures）。
            //
            // 为什么不能用 claimHorizontalDragGestures：它只在"横向分量占优"时认领，
            // 而「转着圈滑」恰恰要求沿圆弧拖动——绕枢轴划弧时纵向分量天然很大
            // （例如 dx=100 / dy=300），会被判成"纵向滚动"直接放弃，卡片纹丝不动。
            // 画廊页是独立全屏页、没有外层滚动容器，不存在"纵向要留给父级"的问题，
            // 因此这里安全地认领任意方向的拖动。
            //
            // key 必须是 Unit，**不能**是任何会变的值：pointerInput 的 key 一变，
            // 手势节点会整个重建、正在跑的协程被取消——而被取消时既不走 onEnd
            // 也不走 onCancel，于是 scrollIndex 永久停在中间态（实机"松手不归位"）。
            .pointerInput(Unit) {
                var accumX = 0f
                // 手指当前位置（相对舞台中心，像素）——「转着圈滑」要靠它算切线方向。
                var fingerX = 0f
                var fingerY = 0f
                var didDrag = false
                claimDragGestures(
                    onStart = { down ->
                        // 仅记起点，不打断按钮点击或进行中的落位动画：
                        // 非拖动点按（例如点击底部上一首/下一首）在抬手时会由子 clickable 先触发 stepSong()，
                        // 若此处提前取消或抬手再走 onEnd() 归零，会把轮盘动画和切歌时机全打乱。
                        didDrag = false
                        tracker.resetTracking()
                        accumX = 0f
                        gestureNetX = 0f
                        // 舞台（BoxWithConstraints）铺满全屏，节点原点即舞台左上角，
                        // 减去半宽高即得"相对舞台中心"的坐标——枢轴用的是同一套基准。
                        val w = stageSize.value.width.toFloat()
                        val h = stageSize.value.height.toFloat()
                        fingerX = down.position.x - w / 2f
                        fingerY = down.position.y - h / 2f
                    },
                    onEnd = {
                        if (!didDrag) return@claimDragGestures
                        dragging = false
                        // 手势落位完成后自动跟随切歌（先置扳机再 settle：settleJob
                        // 同步赋值，collector 首帧看到的就是 settling=true，不会误消费）
                        pendingFollow = true
                        val widthPx = stageSize.value.width.toFloat()
                        val triggerPx = (widthPx * TRIGGER_FRACTION).coerceAtLeast(1f)
                        val velocity = runCatching { tracker.calculateVelocity() }.getOrNull()?.x ?: 0f
                        // 手势方向：向右滑(dx>0) = 下一首 = scrollIndex 增大，故速度直接同号。
                        val vIndex = velocity / triggerPx
                        val s = scrollIndex
                        val target = when {
                            // 只在下界（第一首之前）弹回；上界不设限，永不"滑不动"
                            s <= 0f -> 0f
                            else -> {
                                // 吸附目标基于 floor/ceil 与本次位移方向，不写 round±1
                                val base = floor(s)
                                val frac = s - base
                                if (gestureNetX > 0f) {
                                    // 向前（下一首）：越过 22% 行程或速度过阈 → ceil
                                    if (frac >= COMMIT_FRACTION || vIndex > SETTLE_VELOCITY) base + 1f else base
                                } else {
                                    // 向后（上一首）：退回 22% 以内或速度过阈 → floor
                                    if (frac <= 1f - COMMIT_FRACTION || vIndex < -SETTLE_VELOCITY) base else base + 1f
                                }
                            }
                        }
                        settle(target, vIndex)
                    },
                    onCancel = {
                        if (!didDrag) return@claimDragGestures
                        dragging = false
                        // 手势被抢占：无方向信息，就近吸附（同样视为一次翻页意图）
                        pendingFollow = true
                        settle(scrollIndex.roundToInt().coerceAtLeast(0).toFloat(), 0f)
                    },
                    onDrag = { delta ->
                        if (!didDrag) {
                            // 真正越过 touchSlop 开始拖动时才打断旧落位/按钮防抖并置 dragging
                            didDrag = true
                            settleJob?.cancel()
                            settleJob = null
                            settleTarget = null
                            settleVelocity = 0f
                            buttonFollowJob?.cancel()
                            buttonFollowJob = null
                            dragging = true
                        }
                        val widthPx = stageSize.value.width.toFloat()
                        if (widthPx <= 0f) return@claimDragGestures
                        val triggerPx = widthPx * TRIGGER_FRACTION
                        // 手指位置随位移推进（切线方向依赖当前位置）
                        fingerX += delta.x
                        fingerY += delta.y
                        accumX += delta.x
                        // 手势方向（决定松手吸附方向）：沿用"净横向位移"的语义，
                        // 转圈手势的纵向分量不该翻转翻页方向。
                        gestureNetX += delta.x
                        tracker.addPosition(SystemClock.uptimeMillis(), Offset(accumX, 0f))
                        // 「转着圈滑」：手指位移投影到圆弧切线方向换算成环位移；
                        // 靠近枢轴时平滑退回横向。见 GallerySlotGeometry.dragToRingDelta。
                        val ringDelta = GallerySlotGeometry.dragToRingDelta(
                            dx = delta.x,
                            dy = delta.y,
                            fingerX = fingerX,
                            fingerY = fingerY,
                            stageWidthPx = widthPx,
                            triggerPx = triggerPx
                        )
                        // 越界橡皮筋只作用在下界（第一首之前）。
                        scrollIndex = GallerySlotGeometry.rubberBand(
                            scrollIndex + ringDelta,
                            0f,
                            Float.MAX_VALUE
                        )
                    }
                )
            }
    ) {
        val stageWidthPx = with(density) { maxWidth.toPx() }
        // 卡片基准尺寸：scale=1.0 时边长 = W * 0.52（本机 ≈205dp）
        val baseSize: Dp = maxWidth * GallerySlotGeometry.BASE_SIZE_FRACTION
        val sharedShadowImage = rememberGalleryCardShadow(baseSize + GalleryShadowPad * 2)

        // 当前四舍五入聚焦位（背景暖热与锚点使用；由 derivedStateOf 门控，仅跨半程整数切换时变化）
        val focusedIdx by remember { derivedStateOf { scrollIndex.roundToInt().coerceAtLeast(0) } }

        // ---------- 背景层：静态 / 慢速漂移（奇偶双槽）/ 流体网格（单实例） ----------
        // 锚点取半程中点：evenIdx = floor((s+1)/2)*2、oddIdx = floor(s/2)*2+1，
        // 弹簧在整数位 ±2~3% 超弹震荡不会跨过锚点切换 → Uri 稳定不重载。
        val evenIdx by remember { derivedStateOf { (floor((scrollIndex + 1f) / 2f) * 2f).toInt() } }
        val oddIdx by remember { derivedStateOf { (floor(scrollIndex / 2f) * 2f + 1f).toInt() } }
        val isPlayingGlobally = MediaViewModelObject.isPlaying
        // 打开设置面板期间允许动态背景流动，方便用户暂停时也能实时预览三种模式差异；关闭后严格跟随播放态。
        val animateBg = isPlayingGlobally.value || settingsSheetState.isVisible

        LaunchedEffect(focusedIdx, songCount, useMeshBg) {
            if (!useMeshBg || songCount <= 0) return@LaunchedEffect
            val nextUri = songs.getOrNull(GallerySlotGeometry.wrapSong(focusedIdx + 1, songCount))
                ?.artworkUrl?.let(Uri::parse) ?: return@LaunchedEffect
            warmUpMeshArtwork(context, nextUri)
        }

        LaunchedEffect(settingsSheetState.isVisible, focusedIdx, songCount) {
            if (!settingsSheetState.isVisible || !meshSupported || songCount <= 0) return@LaunchedEffect
            val focusedUri = songs.getOrNull(GallerySlotGeometry.wrapSong(focusedIdx, songCount))
                ?.artworkUrl?.let(Uri::parse) ?: return@LaunchedEffect
            warmUpMeshArtwork(context, focusedUri)
        }

        // ---------- 按钮玻璃的采样源（页内 LayerBackdrop） ----------
        // 录制范围 = 背景 + 顶部 scrim + 4 张封面卡，见 [galleryBackdropTarget] 与文件头
        // 「按钮玻璃的采样边界」注释。开关关闭时不挂录制节点：没人采样就没有全屏重录。
        val glassOn = SettingsLibrary.BarBlurEffect
        val glassScale = glassBlurScale
        val pageBackdrop = rememberLayerBackdrop()
        val glassEffects = remember(glassScale) { glassEffectsOf(glassScale) }
        // 显式声明 receiver 类型：`remember { { drawRect(..) } }` 的嵌套 lambda
        // Kotlin 推不出接收者，会报 CANNOT_INFER_PARAMETER_TYPE。
        val glassSurface: DrawScope.() -> Unit = remember {
            { drawRect(GalleryGlassSurface) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .then(if (glassOn) Modifier.layerBackdrop(pageBackdrop) else Modifier)
        ) {
            if (useMeshBg && songCount > 0) {
                YosFloatingLight(
                    modifier = Modifier.fillMaxSize(),
                    album = {
                        songs.getOrNull(GallerySlotGeometry.wrapSong(focusedIdx, songCount))
                            ?.artworkUrl?.let(Uri::parse)
                    },
                    isPlaying = { animateBg },
                    nowPage = { "NowPlaying" },
                    showMiniPlayer = false,
                    enableMesh = true,
                    overrideBackgroundMode = SettingsLibrary.GALLERY_BG_MESH,
                )
            } else {
                YosFloatingLight(
                    modifier = Modifier.fillMaxSize(),
                    album = {
                        songs.getOrNull(GallerySlotGeometry.wrapSong(evenIdx, songCount))
                            ?.artworkUrl?.let(Uri::parse)
                    },
                    isPlaying = {
                        effectiveBgMode != SettingsLibrary.GALLERY_BG_STATIC &&
                            animateBg && evenIdx == focusedIdx
                    },
                    nowPage = { "NowPlaying" },
                    showMiniPlayer = false,
                    enableMesh = false,
                    overrideBackgroundMode = effectiveBgMode,
                )
                // 上层（奇数锚点）：base 偶 → alpha=frac；base 奇 → alpha=1-frac。
                // 不与下层同时改 alpha，避免上层 alpha=1 时完全挡住下层渐变。
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val s = scrollIndex
                            val base = floor(s).toInt()
                            val frac = s - floor(s)
                            alpha = if (base % 2 == 0) frac else 1f - frac
                        }
                ) {
                    YosFloatingLight(
                        modifier = Modifier.fillMaxSize(),
                        album = {
                            songs.getOrNull(GallerySlotGeometry.wrapSong(oddIdx, songCount))
                                ?.artworkUrl?.let(Uri::parse)
                        },
                        isPlaying = {
                            effectiveBgMode != SettingsLibrary.GALLERY_BG_STATIC &&
                                animateBg && oddIdx == focusedIdx
                        },
                        nowPage = { "NowPlaying" },
                        showMiniPlayer = false,
                        enableMesh = false,
                        overrideBackgroundMode = effectiveBgMode,
                    )
                }
            }

            // 顶部轻 scrim，保证标题区在浅色封面上可读（背景层已自带烘焙压暗）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(GalleryTopScrimBrush)
            )

            // ---------- 封面卡片层：恒定 4 个容器，无增删 ----------
            if (songCount > 0) {
                for (containerIndex in 0 until GallerySlotGeometry.SLOT_COUNT) {
                    key(containerIndex) {
                        GalleryCardContainer(
                            containerIndex = containerIndex,
                            songs = songs,
                            stageWidthPx = stageWidthPx,
                            baseSize = baseSize,
                            shadowImage = sharedShadowImage,
                            scroll = { scrollIndex },
                            onClick = { onCardClick(containerIndex) },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }

        // ---------- 顶栏（独立重组作用域，收藏状态变化不波及背景/卡片） ----------
        GalleryTopBar(
            songs = songs,
            songCount = songCount,
            scroll = { scrollIndex },
            pageBackdrop = pageBackdrop,
            glassOn = glassOn,
            glassEffects = glassEffects,
            glassSurface = glassSurface,
            onOpenSettings = {
                Vibrator.click(context)
                settingsSheetState.show()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp)
        )

        // ---------- 文字区：当前歌 ↔ 下一首 的交叉淡化（Draw 阶段淡出，零每帧重组） ----------
        GalleryTitleCrossfade(
            songs = songs,
            songCount = songCount,
            scroll = { scrollIndex },
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 88.dp)
        )

        // ---------- 计数器：当前聚焦位在队列中的位置 ----------
        if (songCount > 0) {
            GalleryCounter(
                songCount = songCount,
                scroll = { scrollIndex },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 药丸右上方：右侧 28dp、距**屏幕底边** 96dp。
                    // navigationBarsPadding() 已先让出手势栏，故内边距要扣掉这一份。
                    .navigationBarsPadding()
                    .padding(
                        end = 28.dp,
                        bottom = (GalleryCounterBottomFromScreen - navigationBarInset)
                            .coerceAtLeast(0.dp)
                    )
            )
        }

        // ---------- 底部播放控件：上一首 + 播放药丸 + 下一首 ----------
        if (songCount > 0) {
            GalleryBottomControls(
                songs = songs,
                songCount = songCount,
                scroll = { scrollIndex },
                settleTarget = { settleTarget },
                pageBackdrop = pageBackdrop,
                glassOn = glassOn,
                glassEffects = glassEffects,
                glassSurface = glassSurface,
                onStepSong = ::stepSong,
                onCancelButtonFollow = {
                    buttonFollowJob?.cancel()
                    buttonFollowJob = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 距**屏幕底边**（含手势栏）44dp：navigationBarsPadding 已让出 inset，
                    // 这里扣掉它，实机量出来才是设计值而不是 设计值 + inset。
                    .navigationBarsPadding()
                    .padding(
                        bottom = (GalleryPillBottomFromScreen - navigationBarInset)
                            .coerceAtLeast(0.dp)
                    )
            )
        }

        // ---------- 加载 / 空态 ----------
        if (loading && songCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MochiActivityIndicator(size = 32.dp, color = Color.White)
            }
        } else if (songCount == 0 && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = loadError ?: stringResource(R.string.personal_fm_empty),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.page_music_gallery_retry),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { PersonalFmObject.load() }
                            .padding(horizontal = 22.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }

    // 翻页预热：聚焦位变化时检查是否需要补货（ensureQueueAhead 内部自带节流守卫，
    // 且补到的新批会同步追加进活动播放队列——画廊浏览与播放队列同序增长）
    LaunchedEffect(Unit) {
        snapshotFlow { GallerySlotGeometry.segmentAt(scrollIndex) }
            .collect { PersonalFmObject.ensureQueueAhead(it) }
    }

    // 画廊设置面板（MOCHI 底部弹出面板：根据当前高度裁定半屏或全屏）
    MusicGallerySettingsSheet(
        state = settingsSheetState,
        meshSupported = meshSupported,
    )
}

@Composable
private fun GalleryTopBar(
    songs: List<KugouNewSong>,
    songCount: Int,
    scroll: () -> Float,
    pageBackdrop: Backdrop,
    glassOn: Boolean,
    glassEffects: BackdropEffectScope.() -> Unit,
    glassSurface: DrawScope.() -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusedSongIndex by remember(songCount) {
        derivedStateOf {
            GallerySlotGeometry.wrapSong(scroll().roundToInt().coerceAtLeast(0), songCount)
        }
    }
    val focusedSong = songs.getOrNull(focusedSongIndex)
    val focusedItem = focusedSong?.let { KugouRepository.toQueueMediaItem(it) }
    val focusedFavorite = focusedItem?.let { FavoriteRepository.isFavorite(it) } == true

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.page_music_gallery_title),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.page_music_gallery_subtitle),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                letterSpacing = 2.sp
            )
        }
        GalleryCircleButton(
            // 收藏当前聚焦歌：歌手页同款星形图标，已收藏白色实心（用户定版，非红色）
            iconRes = if (focusedFavorite) R.drawable.ic_artist_star_fill
            else R.drawable.ic_artist_star,
            backdrop = pageBackdrop,
            glassOn = glassOn,
            glassEffects = glassEffects,
            glassSurface = glassSurface,
            onClick = {
                val item = focusedItem ?: return@GalleryCircleButton
                Vibrator.click(context)
                scope.launch {
                    val nowFavorite = FavoriteRepository.isFavorite(item)
                    val result = if (nowFavorite) {
                        FavoriteRepository.removeFavorite(item)
                    } else {
                        FavoriteRepository.addFavorite(item)
                    }
                    result.onFailure { e ->
                        Toast.makeText(context, e.message ?: "收藏操作失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        Spacer(Modifier.width(10.dp))
        GalleryCircleButton(
            iconRes = R.drawable.ic_gallery_settings,
            backdrop = pageBackdrop,
            glassOn = glassOn,
            glassEffects = glassEffects,
            glassSurface = glassSurface,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun GalleryTitleCrossfade(
    songs: List<KugouNewSong>,
    songCount: Int,
    scroll: () -> Float,
    modifier: Modifier = Modifier
) {
    val segment by remember {
        derivedStateOf { GallerySlotGeometry.segmentAt(scroll()) }
    }
    Column(modifier = modifier) {
        // 固定小标签：私人电台（不参与 Crossfade，位置恒定）
        Text(
            text = stringResource(R.string.home_personal_fm_title),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        // Crossfade 容器：两层各自叠在同一个左上角，靠绘制期 graphicsLayer/overlayEffect
        // 完成淡入淡出与纵向位移，拖拽与动画期间零组合重组、零文本重排。
        Box(
            Modifier
                .fillMaxWidth()
                .height(GalleryTitleBlockHeight)
        ) {
            if (songCount > 0) {
                for (i in segment..(segment + 1)) {
                    val songIndex = GallerySlotGeometry.wrapSong(i, songCount)
                    key(songIndex) {
                        val song = songs[songIndex]
                        Box(
                            Modifier
                                .fillMaxSize()
                                // 只做位移，**不设 alpha**：translationY 不会触发父级离屏层，
                                // 从而保证歌手名 overlayEffect 的 Plus 混合底色始终为整页背景。
                                .graphicsLayer {
                                    translationY =
                                        GallerySlotGeometry.textTranslationYDp(i - scroll()).dp.toPx()
                                }
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    text = song.name,
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    lineHeight = 36.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = GalleryTitleTextStyle,
                                    modifier = Modifier.graphicsLayer {
                                        alpha = GallerySlotGeometry.textAlpha(i - scroll())
                                    }
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = song.author,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.overlayEffect {
                                        GallerySlotGeometry.textAlpha(i - scroll())
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryCounter(
    songCount: Int,
    scroll: () -> Float,
    modifier: Modifier = Modifier
) {
    val counterValue by remember(songCount) {
        derivedStateOf {
            GallerySlotGeometry.wrapSong(
                scroll().roundToInt().coerceAtLeast(0),
                songCount
            ) + 1
        }
    }
    AnimatedContent(
        targetState = counterValue,
        transitionSpec = {
            val offsetSpec = tween<IntOffset>(240)
            if (targetState > initialState) {
                (slideInVertically(offsetSpec) { it / 2 } + fadeIn(tween(240))) togetherWith
                    (slideOutVertically(offsetSpec) { -it / 2 } + fadeOut(tween(150)))
            } else {
                (slideInVertically(offsetSpec) { -it / 2 } + fadeIn(tween(240))) togetherWith
                    (slideOutVertically(offsetSpec) { it / 2 } + fadeOut(tween(150)))
            }
        },
        label = "galleryCounter",
        modifier = modifier
    ) { value ->
        Text(
            text = String.format(Locale.US, "%d / %d", value, songCount),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun GalleryBottomControls(
    songs: List<KugouNewSong>,
    songCount: Int,
    scroll: () -> Float,
    settleTarget: () -> Int?,
    pageBackdrop: Backdrop,
    glassOn: Boolean,
    glassEffects: BackdropEffectScope.() -> Unit,
    glassSurface: DrawScope.() -> Unit,
    onStepSong: (Int) -> Unit,
    onCancelButtonFollow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val canGoPrevious by remember {
        derivedStateOf { GallerySlotGeometry.canStepPrevious(scroll(), settleTarget()) }
    }
    val focusedSongIndex by remember(songCount) {
        derivedStateOf {
            GallerySlotGeometry.wrapSong(scroll().roundToInt().coerceAtLeast(0), songCount)
        }
    }
    val focusedSong = songs.getOrNull(focusedSongIndex)
    val focusedItem = focusedSong?.let { KugouRepository.toQueueMediaItem(it) }
    val playingMediaId = MediaController.musicPlaying.value?.mediaId
    val playingNow = MediaViewModelObject.isPlaying.value
    val isFocusedPlaying = playingNow && playingMediaId == focusedItem?.mediaId

    fun playFocused() {
        onCancelButtonFollow()
        val song = focusedSong ?: return
        val item = KugouRepository.toQueueMediaItem(song)
        val nowPlayingId = MediaController.musicPlaying.value?.mediaId
        YosDiagnostics.log(
            "GALLERY_PLAY",
            "focused" to item.mediaId,
            "cur" to nowPlayingId,
            "playing" to MediaViewModelObject.isPlaying.value
        )
        if (nowPlayingId == item.mediaId) {
            if (MediaViewModelObject.isPlaying.value) MediaController.mediaControl?.fadePause()
            else MediaController.mediaControl?.fadePlay()
        } else {
            scope.launch(Dispatchers.IO) {
                val queue = PersonalFmObject.songs.value.map {
                    KugouRepository.toQueueMediaItem(it)
                }
                MediaController.prepare(item, queue, fmQueue = true)
            }
        }
    }

    val pillHighlight = rememberGalleryHighlight(scope)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GalleryCircleButton(
            iconRes = R.drawable.ic_nowplaying_rewind,
            backdrop = pageBackdrop,
            glassOn = glassOn,
            glassEffects = glassEffects,
            glassSurface = glassSurface,
            contentDescription = stringResource(R.string.page_music_gallery_prev),
            iconTint = Color.White.copy(alpha = if (canGoPrevious) 1f else 0.35f),
            buttonSize = GalleryPillHeight,
            iconSize = 22.dp,
            fallbackSurfaceAlpha = 0.22f,
            enabled = canGoPrevious,
            onClick = { onStepSong(-1) }
        )
        // 开关翻转必须换 key 强制重建节点：库内 DrawBackdropElement 会被 Compose 原地
        // update（不重建录制管线），模糊层从此不出图、只剩半透明表面色。
        key(glassOn) {
            Box(
                Modifier
                    .galleryPressTransform(pillHighlight, GalleryPillPressLift)
                    .width(GalleryPillWidth)
                    .height(GalleryPillHeight)
                    .then(
                        if (glassOn) {
                            Modifier
                                .galleryGlass(
                                    backdrop = pageBackdrop,
                                    shapeProvider = GalleryPillShapeProvider,
                                    effects = glassEffects,
                                    surface = glassSurface
                                )
                                .galleryPressDim(pillHighlight)
                        } else {
                            Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f))
                                .galleryPressDim(pillHighlight)
                        }
                    )
                    .then(pillHighlight.modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { playFocused() }
                    .galleryPressGesture(pillHighlight)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = if (isFocusedPlaying) {
                        painterResource(R.drawable.ic_nowplaying_pause)
                    } else {
                        painterResource(R.drawable.button_icon_play)
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (isFocusedPlaying) 17.dp else 27.dp)
                )
            }
        }
        GalleryCircleButton(
            iconRes = R.drawable.ic_nowplaying_fforward,
            backdrop = pageBackdrop,
            glassOn = glassOn,
            glassEffects = glassEffects,
            glassSurface = glassSurface,
            contentDescription = stringResource(R.string.page_music_gallery_next),
            iconTint = Color.White,
            buttonSize = GalleryPillHeight,
            iconSize = 22.dp,
            fallbackSurfaceAlpha = 0.22f,
            enabled = true,
            onClick = { onStepSong(1) }
        )
    }
}

/**
 * 单个实体卡片容器：**恒定存在**，不随歌曲切换增删。
 *
 * 内部双层 `AsyncImage`（旧图 A / 新图 B）+ 交叉渐变：
 * 只有当容器处于 `Slot1 → Slot2`（右侧 → 正下方）这一段时，
 * [GallerySlotGeometry.crossfadeAlpha] 才从 0 涨到 1，上层旧图淡出、露出底层新图。
 * 其余三段进度恒为 0，封面纹丝不动——这就是"延后一拍换图"。
 *
 * 闪白防护：`ImageRequest` 按 URL `remember` 缓存、关闭 Coil 自身的 crossfade、
 * 不设 placeholder（未命中时保留上一帧），避免重组时从占位图重新淡入。
 */
@Composable
private fun GalleryCardContainer(
    containerIndex: Int,
    songs: List<KugouNewSong>,
    stageWidthPx: Float,
    baseSize: Dp,
    shadowImage: ImageBitmap?,
    scroll: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 层级按**实时尺寸序**取，derivedStateOf 保证只有排名真正变化（一次翻页 1 次）才重组。
    val zRank by remember(containerIndex) {
        derivedStateOf { GallerySlotGeometry.zRank(containerIndex, scroll()) }
    }
    Box(
        modifier
            .zIndex(GallerySlotGeometry.zIndexForRank(zRank))
            // 节点尺寸 = 卡片 + 一圈阴影留白。
            .size(baseSize + GalleryShadowPad * 2)
            .graphicsLayer {
                val p = GallerySlotGeometry.ringPosition(containerIndex, scroll())
                val g = GallerySlotGeometry.geometry(p)
                translationX = g.offsetX * stageWidthPx
                translationY = (g.offsetY + STAGE_Y_BIAS) * stageWidthPx
                scaleX = g.scale
                scaleY = g.scale
            }
            .drawBehind {
                if (shadowImage != null) {
                    drawImage(image = shadowImage, topLeft = Offset.Zero)
                }
            }
    ) {
        // 卡片本体：内缩一圈留白，落在节点中央，位置与「无留白」时完全一致
        Box(
            Modifier
                .fillMaxSize()
                .padding(GalleryShadowPad)
                .clip(GalleryCardShape)
                .clickable(onClick = onClick)
        ) {
            val songCount = songs.size
            val songPair by remember(containerIndex, songCount) {
                derivedStateOf { GallerySlotGeometry.songPair(containerIndex, scroll(), songCount) }
            }
            val isCrossfading by remember(containerIndex, songCount) {
                derivedStateOf {
                    GallerySlotGeometry.crossfadeAlpha(containerIndex, scroll(), songCount) > 0f
                }
            }

            // 图层：**恒定两个固定槽位 + 固定 key**，只换 model、绝不增删节点。
            // 仅在 isCrossfading 时显示并绘制淡出上层；非换图段将 card-fade 的 alpha 置 0
            // 跳过过绘（节点本身常驻不销毁，消除 100% 静止态双倍纹理采样）。
            val baseSong = songs.getOrNull(
                if (isCrossfading) songPair.second else songPair.first
            )
            val fadeSong = songs.getOrNull(songPair.first)

            if (baseSong != null) {
                key("card-base") {
                    GalleryCardImage(
                        song = baseSong,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            if (fadeSong != null) {
                key("card-fade") {
                    GalleryCardImage(
                        song = fadeSong,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val f = GallerySlotGeometry.crossfadeAlpha(
                                    containerIndex, scroll(), songCount
                                )
                                alpha = if (f > 0f) 1f - f else 0f
                            }
                    )
                }
            }
        }
    }
}

/** 单张封面：ImageRequest 按 URL 缓存，避免重组时重建请求导致闪白。 */
@Composable
private fun GalleryCardImage(song: KugouNewSong, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(song.artworkUrl) {
        ImageRequest.Builder(context)
            .data(song.artworkUrl)
            .crossfade(false)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = song.name,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/** 卡片圆角形状：微圆角实体唱片封面。 */
private val GalleryCardShape = YosRoundedCornerShape(GalleryCardCornerRadius)

/** 卡片四周的阴影留白：节点比卡片大一圈，阴影才不会被离屏层裁掉。 */
private val GalleryShadowPad = 28.dp

/**
 * 固定弥散投影：**预渲染到软件位图**再当普通图片绘制。
 *
 * 为什么必须这么做（"阴影时有时无"的根因之一）：
 *  `BlurMaskFilter` 在 **硬件加速画布上不受支持**。Android 的 HWUI 对
 *  `Paint.setMaskFilter` 是静默忽略的——不报错、不告警，只是那一次绘制没有模糊。
 *  而 Compose 的 `drawWithCache`/`onDrawBehind` 走的是 RenderNode 的硬件画布，
 *  于是表现会随 RenderNode 是否走了离屏层/软件回退而摇摆：有的帧糊了、有的帧
 *  变成硬边、有的帧整个看不见。修法是在 `drawWithCache` 里建一张
 *  `ARGB_8888` 软件位图，在**软件 Canvas** 上画带 maskFilter 的圆角矩形
 *  （软件光栅化支持 maskFilter），再转成 `ImageBitmap` 每帧 `drawImage`——
 *  硬件画布上完全确定性。
 *
 * 为什么还要留白（第二个根因）：graphicsLayer 在 alpha<1 / scale≠1 时会创建
 *  离屏层，离屏层**裁剪到节点边界**。阴影画在卡片边界之外，于是只有 alpha 不足 1
 *  的卡会丢阴影。调用方因此把节点放大 `GalleryShadowPad`，卡片本体内缩同样距离，
 *  阴影落在边界内，任何 alpha/缩放都不被裁。
 */
@Composable
private fun rememberGalleryCardShadow(nodeSize: Dp): ImageBitmap? {
    val density = LocalDensity.current
    val sizePx = with(density) { nodeSize.roundToPx() }
    val padPx = with(density) { GalleryShadowPad.toPx() }
    val cornerPx = with(density) { GalleryCardCornerRadius.toPx() }
    val blurPx = with(density) { 16.dp.toPx() }
    val offsetPx = with(density) { 8.dp.toPx() }

    return remember(sizePx, padPx, cornerPx, blurPx, offsetPx) {
        if (sizePx <= 0) {
            null
        } else {
            val bitmap = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb((0.38f * 255f).toInt(), 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(
                    blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }
            val rect = android.graphics.RectF(
                padPx,
                padPx + offsetPx,
                sizePx - padPx,
                sizePx - padPx + offsetPx
            )
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
            bitmap.asImageBitmap()
        }
    }
}

/**
 * 圆形玻璃按钮（顶栏收藏 / 设置，以及底部上一首 / 下一首）。
 *
 * 两种外观按「工具栏液态玻璃」开关切换：
 *  - 开：液态玻璃圆钮 —— 采样页内 [pageBackdrop]（背景糊图 + 4 张封面卡），
 *    vibrancy + blur + 折射 + ClassYaba 双层描边（左右黑、上下白高光）+ 表面纱，
 *    按压有弥散高光与轻微放大。
 *  - 关：退回半透明白圆（白 [fallbackSurfaceAlpha]）。
 *
 * [glassEffects] / [glassSurface] 由调用方 remember 后传入，理由见 [galleryGlass]。
 */
@Composable
private fun GalleryCircleButton(
    iconRes: Int,
    backdrop: Backdrop,
    glassOn: Boolean,
    glassEffects: BackdropEffectScope.() -> Unit,
    glassSurface: DrawScope.() -> Unit,
    contentDescription: String? = null,
    iconTint: Color = Color.White,
    buttonSize: Dp = 42.dp,
    iconSize: Dp = 23.dp,
    fallbackSurfaceAlpha: Float = 0.18f,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val highlight = rememberGalleryHighlight(scope)
    val painter = painterResource(iconRes)
    LaunchedEffect(enabled) {
        if (!enabled) highlight.externalDragEnd()
    }
    // 开关翻转必须换 key 强制重建节点，否则库内 DrawBackdropElement 会被原地 update、
    // 模糊层不出图（同 LiquidBottomTabs 记过的坑）。key 是组合级 API，包住整个 Box。
    key(glassOn) {
        Box(
            Modifier
                .size(buttonSize)
                // 放大 + 朝手指方向微倾作用在整个按钮（玻璃/边框/图标同一图层），
                // 而不是 drawBackdrop 的采样层：采样层内动会让磨砂内容在固定圆形窗口里
                // 滑动（看着像被圆罩圈住），图标也不会跟玻璃一起动。
                .galleryPressTransform(highlight, GalleryCirclePressLift)
                .then(
                    if (glassOn) {
                        Modifier
                            .galleryGlass(
                                backdrop = backdrop,
                                shapeProvider = GalleryCircleShapeProvider,
                                effects = glassEffects,
                                surface = glassSurface
                            )
                            // 玻璃之上的轻微压暗，让"按下去"有减色依据
                            .galleryPressDim(highlight)
                    } else {
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = fallbackSurfaceAlpha))
                            .galleryPressDim(highlight)
                    }
                )
                // 弥散高光：夹在材质与图标之间，盖在玻璃上、图标之下
                .then(highlight.modifier)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                // 按压视觉 + 拖动认领（消费免疫，见 galleryPressGesture 注释）
                .then(if (enabled) Modifier.galleryPressGesture(highlight) else Modifier)
                .semantics {
                    role = Role.Button
                    if (!enabled) disabled()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/** 圆钮按压放大当量：42dp 圆上 +6dp ≈ 14%（与主页「更多」圆钮同规格）。 */
private val GalleryCirclePressLift = 6.dp

/**
 * 播放药丸按压放大当量：46dp 高上 +6dp ≈ 13%。
 * 与圆钮取同一当量而不是同一比例——两个按钮相邻，放大"幅度"一致比"比例"一致更像一套控件。
 */
private val GalleryPillPressLift = 6.dp

/** 播放药丸的玻璃形状：真胶囊（非玻璃分支仍用 CircleShape，保持历史像素）。 */
private val GalleryPillGlassShape = Capsule()

/** 稳定形状 Provider：避免 DrawBackdropElement 每次重组因新 lambda 触发 invalidateDrawCache。 */
private val GalleryCircleShapeProvider: () -> Shape = { CircleShape }
private val GalleryPillShapeProvider: () -> Shape = { GalleryPillGlassShape }

/** 顶部轻 scrim 渐变画刷：避免每次组合重新分配 Brush 与 ColorStop 列表。 */
private val GalleryTopScrimBrush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.22f),
    1f to Color.Transparent
)

/** 标题文本投影样式（静态常量，淡出透明度通过 graphicsLayer.alpha 同步缩放）。 */
private val GalleryTitleTextStyle = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.18f),
        blurRadius = 8f
    )
)
