package yos.music.player.ui.widgets.audio

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.VocalSeparationState
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.effects.overlayEffect
import yos.music.player.ui.widgets.liquid.claimDragGestures

/** 药丸宽度。收起/展开不变宽——放大靠 graphicsLayer 的 scale，不挤压相邻控件。 */
private val PillWidth = 36.dp

/** 收起态高度：与旁边的翻译按钮同高，视觉上就是同一排图标。 */
private val PillHeightOff = 36.dp

/** 展开态高度 = 拖满全程的行程。手指上下移动多少，水位线就跟着走多少。 */
private val PillHeightOn = 88.dp

/**
 * 展开态总高度（含收起态那 36dp）。
 *
 * 导出给调用点用：药丸向上溢出时，**祖先节点也必须这么高**，否则命中测试会在
 * 祖先边界处把指针裁掉、穿透到下面的滚动歌词（用户实测）。
 * 调用点的写法见 NowPlaying.kt 里 `page == Lyric` 那个覆盖层。
 */
val VocalSeparationPillHeight = PillHeightOn

/**
 * 图标盒子尺寸。30dp——比翻译按钮的 [yos.music.player.ui.pages.TranslationButtonSize]
 * （32dp）小 2dp，字形因此是 19.93dp vs 翻译的 24.32dp。
 *
 * 故意不等大：麦克风的包围盒被四角散开的星芒撑开、中间是细笔画，而翻译图标是两块
 * 实心方块。按包围盒对齐时麦克风会明显偏大（实测过 32dp/24.32dp 那版），
 * 让它小一圈视觉重量才和翻译图标相当。
 */
private val PillIconSize = 30.dp

/**
 * 图标底部内边距。
 *
 * 2dp：32dp 图标 + 2dp = 34dp，在 36dp 的收起态按钮里上下各留 1dp，基本居中。
 * 原来是 6dp（配 24dp 图标），图标放大后若还留 6dp 会溢出按钮顶边 2dp。
 */
private val PillIconBottomPad = 2.dp

/** 展开态白色计量区。0% 时就是没有，不留最小线——留一条会让"拖到底"永远拖不干净。 */

/**
 * 展开态圆角：比胶囊明显更方，读起来是一块"竖向面板"而不是药丸。
 *
 * 这是需求里「展开后圆角减小」的落点——旧版展开时反而收到胶囊（18dp），
 * 与设计图相反。
 */
private val PillRadiusExpanded = 10.dp

/**
 * 收起态圆角，与播放页其它圆钮同一语言。
 *
 * **按下时不要改圆角**（踩过）：36dp 宽配 18dp 半径就是一个正圆，
 * 手指一按按钮会先"变成圆形"再长成圆角矩形，像换了个控件。
 * 按压反馈只靠 [pressScale] 的缩放，形状不动。
 */
private val PillRadiusIdle = 9.dp

/**
 * 底衬透明度（白色圆角矩形）。
 *
 * 收起态取 24%：照抄旁边翻译按钮的配方——`ic_nowplaying_translate.xml` 里那块
 * 圆角底就是 `#ffffff` + `fillAlpha="0.24"`，并且整块套了 `overlayEffect()`
 * （内部 `saveLayer` + `BlendMode.Plus`）与 `.alpha(0.4f)`。三者缺一观感就不同，
 * 所以这里也用同一套（见 [PillContentAlphaCollapsed]）。
 *
 * 展开态压到 12%：白色计量区是纯白，底衬太亮会把"水位线"淹掉、读不出拖到哪了。
 */
private const val ScrimAlphaCollapsed = 0.24f
private const val ScrimAlphaExpanded = 0.12f

/**
 * 整体透明度。
 *
 * 收起态 0.4：与翻译按钮的 `.alpha(0.4f)` 同值——那一行里所有控件都是"半透明次要控件"，
 * 药丸不跟着压暗就会比邻居显眼。
 *
 * 展开态 1.0：白色计量区是主读数，压到 40% 会灰得看不清拖到哪。
 */
private const val PillContentAlphaCollapsed = 0.4f
private const val PillContentAlphaExpanded = 1f

/** 果冻形变的最大拉伸量（沿拖动方向变长）。 */
private const val JellyStretch = 0.06f

/** 果冻形变的最大收窄量（垂直方向变长时，横向按比例变细）。 */
private const val JellySqueeze = 0.03f

/** 把「每帧位移」归一化成 -1..1 的参考行程。越小越敏感。 */
private val JellySpan = 6.dp

/**
 * 人声分离入口：歌词页右上角的竖向药丸。
 *
 * ## 为什么向上长
 * 药丸住在歌词页控件带顶边那一行，**下方紧邻进度条**（实测间距约 30dp）。
 * 向下长会盖住进度条右端、连拖动进度都点不到。所以展开体一律向上生长，
 * 长进歌词文字区——那里是内容区，覆盖它没有交互代价。
 *
 * ## 布局节点只有 36dp
 * Compose 的命中测试受父节点边界约束：超出父边界的部分点不到。若把 88dp 的
 * 布局节点直接塞进那一行，要么撑高整行（挤动旁边的翻译按钮），要么上半截不可点。
 *
 * 因此这里让**布局节点恒为 36dp**（与翻译按钮同尺寸、同位置，行高完全不变），
 * 展开体用 `graphicsLayer.translationY` 纯绘制地向上溢出。命中区域就是那 36dp
 * 图标区，正好在白色计量区的正下方——按住图标往上拖就是加水声抑制，手势方向与
 * 视觉增长方向一致；一旦手势被认领，指针捕获会让后续移动继续送达，不受边界影响。
 *
 * ## 形态（水位 = 人声抑制量，往上拉人声越少）
 * - 收起：36dp，只有麦克风图标。
 * - 展开：白色计量区从**底边**向上生长，高度 = 人声抑制量，占满整个展开体
 *   （图标也在其中，会被白色淹没）。
 *   - 最下 0%：完全原声（没做任何处理），等同关闭——松手即自动收起。
 *   - 最上 100%：人声被压到最低，只剩伴奏。
 *
 * 注意状态里存的 `ratio` 是**原声含量**（0=原声、1=纯伴奏），与水位方向相反，
 * 所以绘制时取补数 `1f - ratio`。音频侧的混音律不用动，它本来就按「原声含量」算。
 *
 * ## 图标反色
 * 白色水位线会横穿图标，所以图标画两遍：底层恒为浅色（水位线以上看得见），
 * 上层是固定深色、只画水位线**以下**的部分。水位线穿过图标时上半浅下半深，
 * 无论拖到哪个高度都清晰。
 *
 * 不用 BlendMode.Difference：那需要离屏合成，部分 ColorOS 机型上不稳，
 * 而且会把 12% 的底衬也一起反掉。
 *
 * ## 交互（一个手势同时承载点按与拖动）
 * - **点按**（位移未越过 touchSlop）：切换开关。
 * - **拖动**（越过 slop 即独占指针）：立刻开启，并按相对拖动调水位；
 *   向上拖 = 水位升高 = 人声变少、向下拖 = 人声变多。收起态直接拖动可一步完成
 *   "开 + 调到想要的量"。
 *
 * 占比在拖动中**实时生效**（听感跟手），只有松手才写持久化偏好——
 * 拖动中逐帧写 MMKV 没必要，且会让设置页读数一直抖。
 *
 * ## 动效
 * - 按下放大（scale 1.0→1.08，原点在底边所以向上长），拖动期间让位给果冻形变。
 * - **果冻**：拖动时沿拖动方向拉长、垂直方向收窄，幅度 6% / 3%；
 *   松手用欠阻尼弹簧回弹（过冲一次再停），读起来是"果冻"而不是"橡皮筋"。
 * - 白色条拖动中直接跟手（snap），松手才用 spring 收敛。
 */
@Composable
fun VocalSeparationPill(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val available = VocalSeparationState.available
    val isOn = VocalSeparationState.isOn
    val mediaId = MediaController.musicPlaying.value?.mediaId

    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 果冻形变目标 -1..1（正值 = 向上拉长）。用瞬时位移而不是累计位移：
    // 手指一停形变就回到 0，这才像"跟着手指走"而不是"被拉变形了"。
    var jellyTarget by remember { mutableFloatStateOf(0f) }

    val pillHeight by animateDpAsState(
        targetValue = if (isOn) PillHeightOn else PillHeightOff,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "vocalSepPillHeight"
    )
    // 圆角**不随按下变化**：36dp 宽配 18dp 半径就是正圆，按下去会先变圆形再长成矩形。
    // 按压反馈只靠 pressScale 的缩放。
    val radius by animateDpAsState(
        targetValue = if (isOn) PillRadiusExpanded else PillRadiusIdle,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 600f),
        label = "vocalSepPillRadius"
    )
    // 拖动期间不再叠加按压缩放：1.08 与果冻 6% 叠在一起会过冲成"被捏扁"
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !dragging) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 700f),
        label = "vocalSepPillScale"
    )
    // 拖动中快速跟随（临界阻尼，只带一点点滞后 = 平滑），
    // 松手用欠阻尼弹簧回 0 —— 那一下过冲就是果冻的"抖回来"
    val jelly by animateFloatAsState(
        targetValue = jellyTarget,
        animationSpec = if (dragging) {
            spring(dampingRatio = 1f, stiffness = 900f)
        } else {
            spring(dampingRatio = 0.35f, stiffness = 500f)
        },
        label = "vocalSepPillJelly"
    )
    // 拖动中直接跟手（snap），松手/外部改值时用 spring 收敛。
    //
    // **水位 = 人声抑制量，不是原声含量**：白色越高人声越少。
    // 状态里存的 [VocalSeparationState.ratio] 仍是「原声含量」（0=纯伴奏、1=原声），
    // 音频侧按它混音；UI 这边取补数，于是「水位拉满 = 人声全没」「水位归零 = 完全原声」。
    // 后者正好等价于关闭，所以归零即关闭（见 onEnd）。
    val fillFraction by animateFloatAsState(
        targetValue = 1f - VocalSeparationState.ratio,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "vocalSepPillFill"
    )

    // 平滑圆角（超椭圆连续圆角），与播放列表的随机/循环按钮同一个形状类。
    // 普通 RoundedCornerShape 的四角是正圆弧，和曲率突变的直边相接，
    // 在 36dp 这么小的尺寸上能看出"角是贴上去了"；YosRoundedCornerShape 用
    // 超椭圆曲线把曲率连续过渡到直边，观感才是本项目其它圆钮的样子。
    val shape = YosRoundedCornerShape(radius)
    // 收起态图标跟随页面内容色（与翻译按钮一致）；展开态底层用白色，
    // 因为图标大部分区域压在水位线以上那块深色底衬上。
    // **不能**用 Color.Unspecified：那会让图标回落到 drawable 里写死的黑色，
    // 在深色播放页上直接看不见（已实测）。
    val iconTint = if (isOn) Color.White else LocalContentColor.current
    // 底衬透明度跟着开关态过渡：收起 24%（与翻译按钮同值）、展开 12%（别淹掉水位线）
    val scrimAlpha by animateFloatAsState(
        targetValue = if (isOn) ScrimAlphaExpanded else ScrimAlphaCollapsed,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "vocalSepPillScrim"
    )
    // 整体透明度：收起态压到 0.4，与旁边翻译按钮的 .alpha(0.4f) 一致。
    // 用 ModulateAlpha 而不是普通 alpha：加性/镂空混合在普通 alpha 下会先被
    // 离屏压暗再合成，观感与翻译按钮对不上。
    val contentAlpha by animateFloatAsState(
        targetValue = if (isOn) PillContentAlphaExpanded else PillContentAlphaCollapsed,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "vocalSepPillAlpha"
    )

    // 展开体高度与图标几何：都是绘制期要用的 px 值。
    // 直接由 pillHeight 换算，不用 onSizeChanged —— 高度动画每帧都在变，
    // 回调写 State 会在布局期触发逐帧重组。
    val iconTopPadPx = with(density) { PillIconBottomPad.toPx() }
    val iconSizePx = with(density) { PillIconSize.toPx() }
    val jellySpanPx = with(density) { JellySpan.toPx() }
    val dragSpanPx = with(density) { PillHeightOn.toPx() }

    // 白色水位线在图标局部坐标里的位置（图标节点高 = iconSizePx）。
    // <= 0 表示水位线在图标上方（整块图标都在白里 → 全镂空）；
    // >= iconSizePx 表示水位线在图标下方（整块图标都在深底上 → 全白）。
    // **不要**给 fillDp 加 coerceAtLeast 之类的最小值：拖到底就该完全没有白色，
    // 留一条 2dp 的线会让"拉到 0%"永远拖不干净（用户实测反馈过）。
    val fillDp = pillHeight * fillFraction.coerceIn(0f, 1f)
    val fillPx = with(density) { fillDp.toPx() }
    val invertClipTop = (iconTopPadPx + iconSizePx) - fillPx

    Box(
        modifier = modifier
            .width(PillWidth)
            // ## 布局节点高度必须跟随展开态
            //
            // 这里是**命中区域的主因**。Compose 的 pointerInput 只覆盖它挂的那个节点的
            // 测量矩形，而节点之外的部分（哪怕子节点绘制出来了）收不到任何指针。
            //
            // 曾经写成 `.height(PillHeightOff)` 恒定 36dp、让展开体用
            // `wrapContentHeight(unbounded) + requiredHeight(88dp)` 纯绘制上溢 52dp：
            // 视觉上完全正常，但手指按在展开体上半部分时连本节点都进不去，
            // 指针直接穿透到下面的滚动歌词（用户实测：只有从底部图标那 36dp 起手才正常）。
            //
            // 祖先撑高是无效的：先把 Row 撑到 88dp、后来又把祖先 Box 撑到 88dp 都没用，
            // 因为最内层的这个 36dp 节点自己就把指针挡在外面了。
            .height(pillHeight)
            .pointerInput(available) {
                // key 只能是 available：isOn / 高度会在手势进行中变化
                // （拖动第一帧就会开启、展开动画会改 bodyHeightPx），把它们当 key 会在
                // 拖动中途重启 pointerInput、把当前手势整个取消掉。
                // 其余状态在回调里按需读实时值即可（MutableState 读取总是拿当前值）。
                var claimed = false
                var baseRatio = 0f
                var accumulated = 0f
                var toppedOut = false
                // 本次拖动开始前的占比：拉满关闭后要恢复到它，而不是「路上经过的 99%」
                var startRatio = 0f
                claimDragGestures(
                    onStart = {
                        pressed = true
                        claimed = false
                        accumulated = 0f
                        toppedOut = false
                        jellyTarget = 0f
                        // 收起态拖动会先开启，届时占比被重置为偏好值，故基准取偏好值。
                        // 偏好值读出来若已是最底（= 完全原声 = 等同没开），换成默认起点。
                        baseRatio = if (VocalSeparationState.isOn) {
                            VocalSeparationState.ratio
                        } else {
                            SettingsLibrary.VocalSeparationDefaultRatio
                                .coerceIn(0f, 1f)
                                .let {
                                    if (it <
                                        VocalSeparationState.RatioAtBottom
                                    ) {
                                        VocalSeparationState.FallbackOnRatio
                                    } else {
                                        it
                                    }
                                }
                        }
                        startRatio = baseRatio
                    },
                    onEnd = {
                        pressed = false
                        dragging = false
                        jellyTarget = 0f
                        if (claimed) {
                            // 水位拖到**最底** = 完全原声 = 关闭；拖到最顶（人声最低）**不关闭**。
                            // 等松手再收最顺（拖到底那一帧就收会让展开体在手底下瞬间塌掉）。
                            //
                            // 用「水位」而不是 ratio 判定，避免再次搞反方向：
                            // ratio 是原声含量（0=原声、1=纯伴奏），水位是 1-ratio，
                            // 两者方向相反，直接比 ratio 极易写错边界（已经踩过一次）。
                            val endFill = 1f - VocalSeparationState.ratio
                            if (endFill <= VocalSeparationState.FillAtBottom) {
                                VocalSeparationState.disableByFullRatio(mediaId, startRatio)
                            } else {
                                // 松手才持久化（拖动中写会逐帧刷偏好）
                                SettingsLibrary.VocalSeparationDefaultRatio =
                                    VocalSeparationState.ratio
                            }
                        } else if (available) {
                            Vibrator.click(context)
                            VocalSeparationState.toggle(mediaId)
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.vocal_separation_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onCancel = {
                        pressed = false
                        dragging = false
                        jellyTarget = 0f
                    },
                    onDrag = { delta ->
                        if (!available) return@claimDragGestures
                        if (!claimed) {
                            // 越过 slop 的第一帧：收起态直接开启（一步到位）
                            claimed = true
                            dragging = true
                            if (!VocalSeparationState.isOn) {
                                VocalSeparationState.applyEnabled(true, mediaId)
                                baseRatio = VocalSeparationState.ratio
                            }
                        }
                        // 向上拖（delta.y 为负）= 水位上升 = 人声减少 = 原声含量下降。
                        // 所以这里对 ratio 取**负**增量，与 fillFraction = 1 - ratio 一致。
                        accumulated += delta.y
                        val span = if (dragSpanPx > 1f) dragSpanPx else 1f
                        val next = (baseRatio + accumulated / span).coerceIn(0f, 1f)
                        val before = (VocalSeparationState.ratio * 20f).toInt()
                        VocalSeparationState.updateRatio(next)
                        // 越档才给一次触感，不能每帧都震
                        if ((next * 20f).toInt() != before) Vibrator.click(context)
                        // 到底（水位归零）= 关闭，给一次重一点的提示。
                        // 到顶不震：那是"人声最低"的正常效果位置，不是要关闭。
                        val atBottom = (1f - next) <= VocalSeparationState.FillAtBottom
                        if (atBottom && !toppedOut) {
                            toppedOut = true
                            Vibrator.longClick(context)
                        } else if (!atBottom) {
                            toppedOut = false
                        }
                        // 果冻：形变方向跟随手指拖动方向（上拉变高变细）
                        jellyTarget = (-delta.y / jellySpanPx).coerceIn(-1f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // 展开体：根节点高度已经是 pillHeight（36dp 或 88dp），所以这里直接填满，
        // 靠根节点的 `contentAlignment = BottomCenter` 保持底边对齐、向上生长。
        //
        // 历史上这里用过 `wrapContentHeight(Bottom, unbounded) + requiredHeight(pillHeight)`
        // 的纯绘制上溢组合（根节点恒 36dp），视觉正常但命中区域只有 36dp——
        // 那正是「从白色区域起手拖会滚歌词」的根源。现在根节点自己撑到 pillHeight，
        // 子节点只需 fillMaxHeight 即可，不再需要任何 unbounded 技巧。
        //
        // 仍然不能改成 `wrapContentSize`：它的 direction = Direction.Both，
        // unbounded 下会把宽度也置成 0..Infinity，内层 `fillMaxWidth()` 的
        // `hasBoundedWidth == false` 就不生效（白色计量条会塌成 0dp 宽）。
        Box(
            modifier = Modifier
                .width(PillWidth)
                .fillMaxHeight()
                // 与翻译按钮同一套配方：加性提亮（overlayEffect = saveLayer + BlendMode.Plus）
                // 再整体压暗到 0.4。两者缺一，同一行里的亮度就对不上——
                // 普通 alpha 混合最多只能到 24% 白，而加性叠加会明显更亮。
                //
                // 顺序**必须**是 overlayEffect 在外、alpha 在内：
                // 反过来（Plus 落在离屏层内部）时目标缓冲是空的，Plus 退化成 SrcOver，
                // 加性效果整个丢失。
                .overlayEffect()
                .alpha(contentAlpha)
                .graphicsLayer {
                    scaleX = pressScale * (1f - JellySqueeze * jelly)
                    scaleY = pressScale * (1f + JellyStretch * jelly)
                    // 以底边为缩放原点：放大/拉长都向上长，不挤动下方邻居
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // 图标"镂空"用 BlendMode.DstOut 擦像素，必须在离屏层里做：
                    // 否则擦的是窗口内容，挖出来是黑洞而不是页面背景。
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(shape)
                // 收起/展开都有这一层圆角矩形底（收起态与旁边的翻译按钮同配方）
                .background(Color.White.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.BottomCenter
        ) {
            // 计量区：从底边向上生长，占满整个展开体高度（图标也在其中）。
            if (isOn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fillDp)
                        .background(Color.White)
                )
            }

            // 图标：钉在展开体底部 = 节点底部，收起/展开都在同一位置
            Box(
                modifier = Modifier
                    .padding(bottom = PillIconBottomPad)
                    .size(PillIconSize)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_vocal_separation),
                    contentDescription = stringResource(id = R.string.vocal_separation),
                    modifier = Modifier.fillMaxSize(),
                    tint = iconTint
                )
                if (isOn) {
                    // 「反色」= 镂空：水位线以下的图标**挖成透明**，让页面背景直接透出来，
                    // 而不是把图标画成深色。
                    //
                    // 做法：把图标画进一个 saveLayer，再用 BlendMode.DstOut 合成回去。
                    // DstOut 是 `dst * (1 - srcAlpha)`——只在图标笔画覆盖到的地方把下层
                    // 擦掉，笔画之外（srcAlpha=0）原样保留。于是白色计量区和底衬上被挖出
                    // 一个图标形状的洞，透出的是页面背景（模糊封面），不是黑洞。
                    //
                    // 不能改用 BlendMode.Clear 直接 drawRect：Clear 无视 src 内容，
                    // 会把整块裁剪区（含图标周围的白色）一起清成方形缺口。
                    //
                    // 前提：必须处在离屏层里（外层 graphicsLayer 已设
                    // CompositingStrategy.Offscreen），否则擦的是窗口内容。
                    Icon(
                        painter = painterResource(id = R.drawable.ic_vocal_separation),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                val top = invertClipTop
                                // 水位线已在图标上方（整块图标都浸在白色里）→ 全挖
                                // 水位线在图标下方 → 图标完整保留，不挖
                                if (top < size.height) {
                                    val holeTop = top.coerceAtLeast(0f)
                                    clipRect(
                                        left = 0f,
                                        top = holeTop,
                                        right = size.width,
                                        bottom = size.height
                                    ) {
                                        drawContext.canvas.saveLayer(
                                            Rect(0f, holeTop, size.width, size.height),
                                            Paint().apply { blendMode = BlendMode.DstOut }
                                        )
                                        this@drawWithContent.drawContent()
                                        drawContext.canvas.restore()
                                    }
                                }
                            },
                        tint = Color.White
                    )
                }
            }
        }
    }
}
