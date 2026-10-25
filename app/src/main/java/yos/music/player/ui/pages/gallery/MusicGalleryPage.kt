package yos.music.player.ui.pages.gallery

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import yos.music.player.ui.widgets.basic.capsuleEdgeLight
import yos.music.player.ui.widgets.basic.circleEdgeLight
import yos.music.player.ui.widgets.basic.navigationBarsPadding
import yos.music.player.ui.widgets.basic.rawNavigationBarsBottomDp
import yos.music.player.ui.widgets.basic.statusBarsPadding
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.effects.overlayEffect
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import yos.music.player.ui.widgets.liquid.claimDragGestures
import yos.music.player.ui.widgets.liquid.glassBlurScale
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

/** 玻璃内缘光：细亮描边，参数对齐 circleEdgeLight 的既有调用点（0.15dp / 0.5dp 模糊）。 */
private val GalleryGlassEdge = Color.White.copy(alpha = 0.45f)

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
 * 圆钮的玻璃底。
 *
 * `highlight` / `shadow` 传 null：库内这两个装饰各自每帧录一个离屏层，是已知的大头
 * 开销（见 GlassProbe 注释的实测结论），而本页已有内缘光 + 表面纱负责轮廓分离。
 * `effects` / `onDrawSurface` 必须由调用方用 remember 产出**稳定实例** ——
 * `DrawBackdropElement.equals` 逐项按引用比较，内联 lambda 每次重组都判「参数变了」，
 * 会触发 invalidateDrawCache 风暴（LiquidBottomTabs 记过同款教训）。
 */
private fun Modifier.galleryGlass(
    backdrop: Backdrop,
    shape: Shape,
    effects: BackdropEffectScope.() -> Unit,
    surface: DrawScope.() -> Unit
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = effects,
    highlight = { null },
    shadow = { null },
    onDrawSurface = surface
)

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
    val tracker = remember { VelocityTracker() }
    val stageSize = remember { mutableStateOf(IntSize.Zero) }
    // 设置钮的「正在开发中」占位弹窗
    var showWipDialog by remember { mutableStateOf(false) }
    // 本次手势净位移方向（>0 = 向前/下一首），供松手判据区分 floor/ceil 侧
    var gestureNetX by remember { mutableFloatStateOf(0f) }
    // 是否有手势正在拖拽（松手落位与看门狗都依赖它）
    var dragging by remember { mutableStateOf(false) }
    // 滑动翻页自动切歌的扳机：只在真实手势（onEnd/onCancel）置位。页面刚打开、
    // 看门狗兜底归位都不置位——进画廊只是浏览，不能劫持后台正在播的歌。
    var pendingFollow by remember { mutableStateOf(false) }
    // 进页锚定只做一次：补货会让列表增长（songCount 变化），不能反复重锚。
    var entryAnchored by remember { mutableStateOf(false) }

    fun settle(target: Float, initialVelocity: Float) {
        settleJob?.cancel()
        // 速度限幅：见 MAX_SETTLE_VELOCITY 注释（防大力滑动时归位过猛）。
        val clampedVelocity = initialVelocity.coerceIn(-MAX_SETTLE_VELOCITY, MAX_SETTLE_VELOCITY)
        settleJob = scope.launch {
            try {
                // block 为无参接收者形式（Compose 1.10）：Animatable.() -> Unit，经 this.value 写回
                Animatable(scrollIndex).animateTo(target, GallerySettleSpec, clampedVelocity) {
                    scrollIndex = value
                }
            } finally {
                // 必须清空：看门狗用 settleJob != null 判断"动画在跑"，
                // 残留非 null 会让它永远认为还在落位，从此再不介入。
                if (settleJob === coroutineContext[Job]) settleJob = null
            }
        }
    }

    /**
     * 兜底归位：只要「没有手势在拖、也没有落位动画在跑」而索引停在半途，
     * 就把它吸回最近的整数位。
     *
     * 为什么需要：`claimHorizontalDragGestures` 只在正常抬手/被消费时回调
     * onEnd/onCancel；一旦**协程本身被取消**（pointerInput 的 key 变化导致
     * 手势节点重建），两个回调都不会执行，索引就永久卡在 0.37 这种中间态——
     * 实机表现正是「松手后不归位，停在那里」。
     * 看门狗与手势实现解耦，任何原因导致的悬空状态都能自愈。
     */
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(dragging, settleJob != null, scrollIndex) }
            .collect { (isDragging, settling, value) ->
                if (isDragging || settling) return@collect
                val nearest = value.roundToInt().coerceAtLeast(0)
                if (abs(value - nearest) > 0.001f) {
                    settle(nearest.toFloat(), 0f)
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

    // ---------- 滑动翻页自动切歌 ----------
    // 手势结束后落位稳定在整数位，若聚焦歌不是正在播的歌则自动开播（翻页即换歌）。
    // 判据与看门狗同源：无拖拽 + 无落位动画 + 值恰在整数位——弹簧超弹震荡期间
    // settleJob 非 null 会被跳过，不会在半程/过冲瞬间误触发。
    // pendingFollow 只由真实手势置位：页面首进、看门狗归位都不会动当前播放。
    LaunchedEffect(songCount) {
        snapshotFlow { Triple(dragging, settleJob != null, scrollIndex) }
            .collect { (isDragging, settling, value) ->
                if (isDragging || settling || !pendingFollow) return@collect
                pendingFollow = false
                if (songCount == 0) return@collect
                val nearest = value.roundToInt().coerceAtLeast(0)
                if (abs(value - nearest) > 0.001f) return@collect
                val song = songs.getOrNull(GallerySlotGeometry.wrapSong(nearest, songCount))
                    ?: return@collect
                val item = KugouRepository.toQueueMediaItem(song)
                // 已在播（含暂停态）就是这首歌：不打断、不从头重播
                if (MediaController.musicPlaying.value?.mediaId == item.mediaId) return@collect
                launch(Dispatchers.IO) {
                    val queue = PersonalFmObject.songs.value.map {
                        KugouRepository.toQueueMediaItem(it)
                    }
                    MediaController.prepare(item, queue, fmQueue = true)
                }
            }
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
                claimDragGestures(
                    onStart = { down ->
                        // Settling 中再按下：先取消动画再从当前值续拖
                        settleJob?.cancel()
                        settleJob = null
                        dragging = true
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
                        dragging = false
                        // 手势被抢占：无方向信息，就近吸附（同样视为一次翻页意图）
                        pendingFollow = true
                        settle(scrollIndex.roundToInt().coerceAtLeast(0).toFloat(), 0f)
                    },
                    onDrag = { delta ->
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

        // 当前段号与段内进度：文字、计数器、背景都基于它
        val segment by remember { derivedStateOf { GallerySlotGeometry.segmentAt(scrollIndex) } }
        val focusedSongIndex by remember(songCount) {
            derivedStateOf {
                GallerySlotGeometry.wrapSong(
                    GallerySlotGeometry.segmentAt(scrollIndex),
                    songCount
                )
            }
        }
        // 当前聚焦歌的派生物：播放药丸图标、收藏按钮、自动跟随切歌三处共用。
        // isFavorite 内部读的是 State（在线 favoriteHashes / 本地 favPlayList），
        // 收藏状态变化会直接触发重组刷新图标。
        val focusedSong = songs.getOrNull(focusedSongIndex)
        val focusedItem = focusedSong?.let { KugouRepository.toQueueMediaItem(it) }
        val focusedFavorite = focusedItem?.let { FavoriteRepository.isFavorite(it) } == true

        // 收藏当前聚焦歌（顶栏收藏钮）。乐观更新：先本地翻转，云端失败回滚并提示。
        val context = LocalContext.current
        fun toggleFocusedFavorite() {
            val item = focusedItem ?: return
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

        // ---------- 背景层：YosFloatingLight 双实例奇偶槽位 ----------
        // 锚点取半程中点：evenIdx = floor((s+1)/2)*2、oddIdx = floor(s/2)*2+1，
        // 弹簧在整数位 ±2~3% 超弹震荡不会跨过锚点切换 → Uri 稳定不重载。
        val evenIdx by remember { derivedStateOf { (floor((scrollIndex + 1f) / 2f) * 2f).toInt() } }
        val oddIdx by remember { derivedStateOf { (floor(scrollIndex / 2f) * 2f + 1f).toInt() } }
        val isPlayingGlobally = MediaViewModelObject.isPlaying
        val focusedIdx by remember { derivedStateOf { scrollIndex.roundToInt() } }

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
            YosFloatingLight(
                modifier = Modifier.fillMaxSize(),
                album = {
                    songs.getOrNull(GallerySlotGeometry.wrapSong(evenIdx, songCount))
                        ?.artworkUrl?.let(Uri::parse)
                },
                isPlaying = { isPlayingGlobally.value && evenIdx == focusedIdx },
                nowPage = { "NowPlaying" },
                showMiniPlayer = false
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
                    isPlaying = { isPlayingGlobally.value && oddIdx == focusedIdx },
                    nowPage = { "NowPlaying" },
                    showMiniPlayer = false
                )
            }

            // 顶部轻 scrim，保证标题区在浅色封面上可读（背景层已自带烘焙压暗）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.22f),
                            1f to Color.Transparent
                        )
                    )
            )

            // ---------- 封面卡片层：恒定 4 个容器，无增删 ----------
            // 容器 i 的环位置 p = (i + scroll) mod 4；向右滑时 p 增大，
            // 卡片沿 Slot0→1→2→3→0 顺时针前进。
            // 空列表必须整段跳过：队列首拉期间 songs 为空，取歌会抛 IndexOutOfBounds。
            //
            // 卡片必须在录制层**内**：横屏/平板上 Slot2 卡片会一直延伸到底部，
            // 底部药丸正压在其上；只录背景会让药丸采到「背后其实有卡片」的错误画面。
            if (songCount > 0) {
                for (containerIndex in 0 until GallerySlotGeometry.SLOT_COUNT) {
                    key(containerIndex) {
                        GalleryCardContainer(
                            containerIndex = containerIndex,
                            songs = songs,
                            stageWidthPx = stageWidthPx,
                            baseSize = baseSize,
                            scroll = { scrollIndex },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }

        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp),
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
                onClick = { toggleFocusedFavorite() }
            )
            Spacer(Modifier.width(10.dp))
            GalleryCircleButton(
                iconRes = R.drawable.ic_gallery_settings,
                backdrop = pageBackdrop,
                glassOn = glassOn,
                glassEffects = glassEffects,
                glassSurface = glassSurface,
                onClick = { showWipDialog = true }
            )
        }

        // ---------- 文字区：当前歌 ↔ 下一首 的交叉淡化 ----------
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 88.dp)
        ) {
            // 固定小标签：私人电台（不参与 Crossfade，位置恒定）
            Text(
                text = stringResource(R.string.home_personal_fm_title),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            // Crossfade 容器：两层各自叠在同一个左上角，靠 graphicsLayer 的 alpha/translationY
            // 交叉淡化。**每层内部必须是 Column**——直接平铺两个 Text 会让歌名与歌手名
            // 重叠在同一行（曾实机暴露的 bug）。
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
                            // 淡入淡出的 alpha **必须写在文字颜色里，不能写成 graphicsLayer
                            // 的 alpha**。原因见下方歌手名处的长注释：overlayEffect 用的是
                            // BlendMode.Plus，它的结果取决于"加色时的底"是什么。父级一旦有
                            // alpha<1，framework 就会为这一层建离屏缓冲，Plus 的底变成透明
                            // 黑，加色效果消失 → 实机表现为「动画中颜色不对，停下才恢复」。
                            val fade = GallerySlotGeometry.textAlpha(i - scrollIndex)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    // 只做位移，**不设 alpha**：translationY 不会触发离屏层，
                                    // 从而保证 overlayEffect 的 saveLayer 始终以整页背景为底。
                                    .graphicsLayer {
                                        translationY =
                                            GallerySlotGeometry.textTranslationYDp(i - scrollIndex).dp.toPx()
                                    }
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = song.name,
                                        // 标题走普通混合：直接用颜色 alpha 淡出，投影同步按
                                        // fade 缩放，否则字淡走了黑影还留在原地。
                                        color = Color.White.copy(alpha = fade),
                                        fontSize = 30.sp,
                                        // 用 ExtraBold(800) 而不是 Bold(700)：本机 MIUI 的
                                        // /system/etc/fonts.xml 把 sans-serif 的 700 映射到
                                        // MiSans 可变字体的 wght=450（比 400 档的 330 只粗一
                                        // 点，肉眼近乎常规），700 实机根本不显粗。800 映射到
                                        // wght=520，是"明显加粗但不糊"的档位；再上一档 900
                                        // （wght=630）实机偏黑偏重，已否掉。AOSP 设备上 800 同样
                                        // 是介于 Bold 与 Black 之间的加粗，观感一致。
                                        fontWeight = FontWeight.ExtraBold,
                                        lineHeight = 36.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(
                                            // 投影削弱（用户裁决）：0.35/12f 偏重，降为
                                            // 0.18/8f——只保可读性衬托，不再有"糊一圈"感
                                            shadow = Shadow(
                                                color = Color.Black.copy(alpha = 0.18f * fade),
                                                blurRadius = 8f
                                            )
                                        )
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    // 仅歌手名，不拼专辑名。
                                    //
                                    // 颜色材质对齐艺人页的次级信息行（「N 首歌曲 · N 张专辑」，
                                    // 见 ArtistDetail 的 HeaderStatus）：白 45% + Plus 加色混合
                                    // 的微光质感。画布底色恒为深色封面糊图，故固定走暗底分支。
                                    //
                                    // **淡出为什么乘在颜色 alpha 上、而不是给父级加
                                    // graphicsLayer alpha**：overlayEffect 内部是
                                    // `canvas.saveLayer(rect, Paint(BlendMode.Plus))`，Plus 是
                                    // 「结果 = 底 + 源」，**底是什么决定最终颜色**。
                                    //   · 父级 alpha == 1：framework 不建离屏层，saveLayer 的底
                                    //     就是整页画布（背景糊图 + 卡片）→ 得到「背景 + 白45%」
                                    //     的发光质感，即静止态看到的颜色。
                                    //   · 父级 alpha < 1：framework 必须为这一层建离屏缓冲才能
                                    //     整体半透明合成，saveLayer 的底变成该缓冲的**透明黑**
                                    //     → Plus 退化成「0 + 白45%」= 一片发暗的普通白，发光
                                    //     质感消失。
                                    // 于是同一段文字在「拖动中」和「停稳后」是两种颜色 —— 这就是
                                    // 颜色跳变的根因。把 fade 乘进颜色 alpha 后，全程都不产生
                                    // 离屏层，Plus 的底恒为整页背景，任何进度下的颜色都与静止态
                                    // 同源，且亮度随 fade 线性衰减。
                                    Text(
                                        text = song.author,
                                        color = Color.White.copy(alpha = 0.45f * fade),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.overlayEffect()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------- 计数器：当前段号在队列中的位置 ----------
        if (songCount > 0) {
            val counterValue by remember(songCount) {
                derivedStateOf {
                    GallerySlotGeometry.wrapSong(
                        GallerySlotGeometry.segmentAt(scrollIndex),
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
            ) { value ->
                Text(
                    text = String.format(Locale.US, "%d / %d", value, songCount),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }

        // ---------- 底部播放药丸 ----------
        if (songCount > 0) {
            val playingMediaId = MediaController.musicPlaying.value?.mediaId
            val playingNow = MediaViewModelObject.isPlaying.value
            // 药丸的点击/播放逻辑抽成局部函数：clickable 与无障碍入口共用同一份实现。
            // 状态全部在调用时实时读取，不捕获组合期的旧值。
            fun playFocused() {
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
                    // 已是当前歌 → 淡入淡出播放/暂停
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
            // 开关翻转必须换 key 强制重建节点：库内 DrawBackdropElement 会被 Compose 原地
            // update（不重建录制管线），模糊层从此不出图、只剩半透明表面色。LiquidBottomTabs
            // 已实证该坑，双向切换都靠换 key 恢复。key 是组合级 API，包住整个 Box。
            key(glassOn) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        // 距**屏幕底边**（含手势栏）28dp：navigationBarsPadding 已让出 inset，
                        // 这里扣掉它，实机量出来才是 28dp 而不是 28 + inset。
                        .navigationBarsPadding()
                        .padding(
                            bottom = (GalleryPillBottomFromScreen - navigationBarInset)
                                .coerceAtLeast(0.dp)
                        )
                        // 放大 + 朝手指方向微倾作用在整个药丸（玻璃/边缘光/图标同一图层）。
                        .galleryPressTransform(pillHighlight, GalleryPillPressLift)
                        .width(GalleryPillWidth)
                        .height(GalleryPillHeight)
                        .then(
                            // 玻璃分支：真胶囊 + 页内采样 + 内缘光 + 轻微压暗。
                            // 非玻璃分支：逐像素保留改动前的白 22% 平底。
                            if (glassOn) {
                                Modifier
                                    .galleryGlass(
                                        backdrop = pageBackdrop,
                                        shape = GalleryPillGlassShape,
                                        effects = glassEffects,
                                        surface = glassSurface
                                    )
                                    .capsuleEdgeLight(GalleryGlassEdge, 0.15.dp, 0.5.dp)
                                    .galleryPressDim(pillHighlight)
                            } else {
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.22f))
                                    .galleryPressDim(pillHighlight)
                            }
                        )
                        // 弥散高光：夹在材质层与内容之间（玻璃之上、图标之下），否则会被材质盖住
                        .then(pillHighlight.modifier)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { playFocused() }
                        // 按压视觉 + 拖动认领（消费免疫，见 galleryPressGesture 注释）；
                        // 排在 clickable 之后：越过 touchSlop 才消费，纯点按不受影响
                        .galleryPressGesture(pillHighlight)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        // 播放/暂停用项目既有资源：播放中=播放页 ic_nowplaying_pause（双竖条，
                        // 点按即暂停），暂停/未播=专辑页 button_icon_play（三角，点按即播放）。
                        // 两 drawable glyph 规范不同：三角高占 viewport 61%、双杠占 100%，
                        // 按"视觉等高≈13.4dp"反推尺寸：三角 22dp / 双杠 14dp。
                        painter = if (playingNow && playingMediaId == focusedItem?.mediaId) {
                            painterResource(R.drawable.ic_nowplaying_pause)
                        } else {
                            painterResource(R.drawable.button_icon_play)
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(
                            if (playingNow && playingMediaId == focusedItem?.mediaId) 17.dp else 27.dp
                        )
                    )
                }
            }
        }

        // ---------- 加载 / 空态 ----------
        if (loading && songCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
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

    // 设置钮暂无落点功能：弹「正在开发中」占位提示（逻辑上线前先给明确反馈）
    if (showWipDialog) {
        AlertDialog(
            onDismissRequest = { showWipDialog = false },
            confirmButton = {
                TextButton(onClick = { showWipDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            text = { Text(stringResource(R.string.page_music_gallery_wip)) }
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
    scroll: () -> Float,
    modifier: Modifier = Modifier
) {
    // 层级按**实时尺寸序**取，而不是按整槽 floor。
    //
    // 曾经用 slotAt()（floor 后的整槽）：卡片从 Slot3 走到 Slot0 时 p 要一路走到
    // 4.0 才回绕成 0，于是整个拖拽过程 floor(p) 恒为 3，层级一直停在 Slot3 的 40，
    // 直到动画结束那一帧才跳到 100 —— 实机表现就是「正在聚焦的卡片要等动画结束
    // 才跑到最上面」。
    // 改成按尺寸排名（GallerySlotGeometry.zRank）：接任主卡的 scale 从 0.64 涨向 1.0、
    // 卸任主卡从 1.0 跌向 0.64，两者在**行程中点**尺寸相等，层级就在中点交换 ——
    // 动画进行到一半新主卡就已经在最上层，落位只是"升到顶"而不是"突然跳到顶"。
    // derivedStateOf 保证只有排名真正变化（一次翻页 1 次）才重组。
    val zRank by remember(containerIndex) {
        derivedStateOf { GallerySlotGeometry.zRank(containerIndex, scroll()) }
    }
    Box(
        modifier
            .zIndex(GallerySlotGeometry.zIndexForRank(zRank))
            // 节点尺寸 = 卡片 + 一圈阴影留白。
            //
            // 为什么必须留白：graphicsLayer 在 alpha<1 / scale≠1 时会创建离屏层，
            // 而离屏层**裁剪到节点边界**；阴影画在卡片边界之外，于是只有
            // "入场/退场那种 alpha 不足 1 的卡"会丢阴影。把节点放大到能容纳阴影，
            // 阴影就落在边界内，任何 alpha/缩放都不被裁。
            .size(baseSize + GalleryShadowPad * 2)
            .graphicsLayer {
                val p = GallerySlotGeometry.ringPosition(containerIndex, scroll())
                val g = GallerySlotGeometry.geometry(p)
                translationX = g.offsetX * stageWidthPx
                // 整簇下移 STAGE_Y_BIAS：见该常量的注释（纯构图修正，不动槽位相对关系）
                translationY = (g.offsetY + STAGE_Y_BIAS) * stageWidthPx
                scaleX = g.scale
                scaleY = g.scale
            }
            .cardDropShadow()
    ) {
        // 卡片本体：内缩一圈留白，落在节点中央，位置与「无留白」时完全一致
        Box(
            Modifier
                .fillMaxSize()
                .padding(GalleryShadowPad)
                .clip(GalleryCardShape)
        ) {
            val songPair = GallerySlotGeometry.songPair(containerIndex, scroll(), songs.size)
            // 必须用带 size 的重载：内容前后相同的那一段返回 0，不做渐变。
            // 否则上层旧图淡出、底层因"同图不画"而空白 → 卡片淡成空框，
            // 落位后 alpha 弹回 1 又"突然出现"（实机 bug：最后一张逐渐消失又冒出）。
            val crossfade = GallerySlotGeometry.crossfadeAlpha(
                containerIndex, scroll(), songs.size
            )

            // 图层：**恒定两个固定槽位 + 固定 key**，只换 model、绝不增删节点。
            //
            // 这是"落位后闪一下"的最终修法。之前试过两种都会闪：
            //  a) 「底层=新图 / 上层=旧图」两个角色，但"不换图"时只画一层 → 结构
            //     在 1 层/2 层之间来回切换；
            //  b) 用 `layers` 列表 + `key(songIndex)` → 列表长度变化时，同一首歌在
            //     `forEach` 里的**位置**变了，Compose 按位置分组，仍视作不同节点。
            // 两者都会让 AsyncImage 节点被销毁重建，图片重新加载 → 中间 1~2 帧空白，
            // 视觉上就是"动画结束、图片闪一下"。
            //
            // 现在：两个槽位的 `key` 是**固定的字面量**（"card-base" / "card-fade"），
            // 组合节点从页面创建到销毁全程不重建；换图只改 `model`（URL）。
            // 由于两张封面此前都已被加载过，Coil 内存缓存命中，`model` 切换是同步
            // 完成的，不会出现空白帧。
            //
            // 合成方式：底层（当前可见内容）恒 alpha=1 打底；上层（正在淡出的旧图）
            // alpha 从 1 降到 0。段内进度 0 时上层 alpha=1 且与底层同图，视觉等价；
            // 段末上层 alpha=0 完全隐藏，此时两层内容已一致，切换零观感差异。
            val fade = crossfade
            val baseSong = songs.getOrNull(
                if (fade > 0f) songPair.second else songPair.first
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
                            .graphicsLayer { alpha = 1f - fade }
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
private fun Modifier.cardDropShadow(): Modifier = this.drawWithCache {
    val w = size.width.toInt()
    val h = size.height.toInt()
    if (w <= 0 || h <= 0) {
        onDrawBehind { }
    } else {
        val pad = GalleryShadowPad.toPx()
        val cornerPx = GalleryCardCornerRadius.toPx()
        val blurPx = 16.dp.toPx()
        val offsetPx = 8.dp.toPx()
        // 卡片矩形（内缩留白后的位置）
        val cardLeft = pad
        val cardTop = pad
        val cardRight = size.width - pad
        val cardBottom = size.height - pad
        val bitmap = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb((0.38f * 255f).toInt(), 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(
                blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        // 阴影 = 卡片矩形向下偏移 8dp，模糊 16dp（全部落在节点留白内）
        val rect = android.graphics.RectF(
            cardLeft,
            cardTop + offsetPx,
            cardRight,
            cardBottom + offsetPx
        )
        canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
        val shadowImage = bitmap.asImageBitmap()

        onDrawBehind {
            drawImage(image = shadowImage, topLeft = Offset.Zero)
        }
    }
}

/**
 * 顶栏圆形按钮（随机 / 收藏 / 设置）。
 *
 * 图标用项目既有资源（[iconRes]，drawable id）：随机=专辑页 button_icon_shuffle、
 * 收藏=歌手页星形 ic_artist_star(_fill)（已收藏红色实心，同歌手页语义）、
 * 设置=设计稿 gear（ic_gallery_settings）。
 * 尺寸对齐主页「更多」圆钮（LiquidTopBarButton）：42dp 盒 + 23dp 图标。
 * 两种外观按「工具栏液态玻璃」开关切换：
 *  - 开：液态玻璃圆钮 —— 采样页内 [pageBackdrop]（背景糊图 + 4 张封面卡），
 *    vibrancy + blur + 折射 + 内缘光 + 表面纱，按压有弥散高光与轻微放大。
 *  - 关：退回改动前的半透明白圆（白 18%），逐像素一致。
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
    onClick: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val highlight = rememberGalleryHighlight(scope)
    val painter = painterResource(iconRes)
    // 开关翻转必须换 key 强制重建节点，否则库内 DrawBackdropElement 会被原地 update、
    // 模糊层不出图（同 LiquidBottomTabs 记过的坑）。key 是组合级 API，包住整个 Box。
    key(glassOn) {
        Box(
            Modifier
                .size(42.dp)
                // 放大 + 朝手指方向微倾作用在整个按钮（玻璃/边缘光/图标同一图层），
                // 而不是 drawBackdrop 的采样层：采样层内动会让磨砂内容在固定圆形窗口里
                // 滑动（看着像被圆罩圈住），图标也不会跟玻璃一起动。
                .galleryPressTransform(highlight, GalleryCirclePressLift)
                .then(
                    if (glassOn) {
                        Modifier
                            .galleryGlass(
                                backdrop = backdrop,
                                shape = CircleShape,
                                effects = glassEffects,
                                surface = glassSurface
                            )
                            .circleEdgeLight(GalleryGlassEdge, 0.15.dp, 0.5.dp)
                            // 玻璃之上的轻微压暗，让"按下去"有减色依据
                            .galleryPressDim(highlight)
                    } else {
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .galleryPressDim(highlight)
                    }
                )
                // 弥散高光：夹在材质与图标之间，盖在玻璃上、图标之下
                .then(highlight.modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                // 按压视觉 + 拖动认领（消费免疫，见 galleryPressGesture 注释）
                .galleryPressGesture(highlight)
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(23.dp)
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
