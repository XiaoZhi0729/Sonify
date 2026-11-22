package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.PersonalFmObject
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.widgets.liquid.glassBlurScale

/**
 * 玻璃层里的内容：品牌行 → （弹性留白）→ 曲名 + 歌手 → 波形 + 玻璃播放钮。
 *
 * 玻璃宽 134dp、上下内缩后高 134dp，内边距 12/10 → 可用宽约 112dp。
 * 曲名 14sp 单行（约 8 个汉字），不能只把字号等比缩小就完事。
 *
 * 上一首/下一首按钮已按用户要求移除；换台靠 Pager 滑动与无障碍自定义动作。
 */
@Composable
internal fun GlassContent(
    song: KugouNewSong?,
    accent: Color?,
    isDark: Boolean
) {
    val scope = rememberCoroutineScope()
    val onGlass = onGlassColorOf(accent, isDark)
    val playingId = MediaController.musicPlaying.value?.mediaId
    val isPlayingNow = MediaViewModelObject.isPlaying.value
    val occludedProvider = yos.music.player.ui.widgets.basic.LocalOccludedByFullscreenPlayer.current
    val occludedByPlayer by remember(occludedProvider) { derivedStateOf { occludedProvider() } }

    val item = song?.let { KugouRepository.toQueueMediaItem(it) }
    val isThisSongPlaying = item != null && playingId == item.mediaId
    val showPause = isThisSongPlaying && isPlayingNow

    fun togglePlay() {
        val target = item ?: return
        val nowId = MediaController.musicPlaying.value?.mediaId
        if (nowId == target.mediaId) {
            if (MediaViewModelObject.isPlaying.value) MediaController.mediaControl?.fadePause()
            else MediaController.mediaControl?.fadePlay()
            return
        }
        scope.launch(Dispatchers.IO) {
            val queue = PersonalFmObject.songs.value.map { KugouRepository.toQueueMediaItem(it) }
            if (queue.none { it.mediaId == target.mediaId }) return@launch
            MediaController.prepare(target, queue, fmQueue = true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)
    ) {
        // ---- 品牌行 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_home_personal_fm_heart),
                contentDescription = null,
                tint = accent ?: onGlass,
                modifier = Modifier.size(10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(id = R.string.home_personal_fm_title),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = onGlass.copy(alpha = 0.66f),
                maxLines = 1
            )
        }

        Spacer(Modifier.weight(1f))

        // ---- 曲名 + 歌手 ----
        // 曲名放大到 17sp；太长就**横向滚动**（basicMarquee），
        // 两端各挖一条渐隐，让文字进出边缘时是淡入淡出而不是被硬切。
        MarqueeText(
            text = song?.name.orEmpty(),
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            color = onGlass,
            fadeWidth = 10.dp,
            enabled = !occludedByPlayer,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = song?.author.orEmpty(),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            // 歌手名不用裸 accent 作前景：按合成后的玻璃面重算对比度
            color = artistTextColorOf(accent, onGlass),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        // ---- 波形 + 播放钮 ----
        // ★两者**底边对齐**：Row 用 Bottom 对齐，波形自己再贴底，
        //   这样无论播放钮视觉多大，柱子底端和图标底端都在同一条线上。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Waveform(
                color = onGlass,
                playing = showPause && !occludedByPlayer,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .height(FmWaveformHeight)
                    .align(Alignment.Bottom)
            )
            // ★纯图标播放钮：尺寸与动效照迷你播放器，但**视觉盒比触摸盒小**
            //   （迷你播放器那边盒子本身就是 34dp，所以直接 fillMaxSize 没问题；
            //    这里触摸区是 48dp，若照抄 fillMaxSize 图标会撑到 48dp，明显过大）。
            PlainPlayButton(
                playing = showPause,
                tint = onGlass,
                onClick = ::togglePlay
            )
        }
    }
}

/**
 * 超长就横向滚动的单行文字，两端各有一条渐隐。
 *
 * 为什么不用 `TextOverflow.Ellipsis`：玻璃只有 134dp 宽，中文歌名动不动就 10 个字，
 * 省略号会把一半信息吃掉；滚动能让用户看全，而这是"正在播放什么"最需要看清的信息。
 *
 * ## ★高度必须显式给（否则整行塌成 0，后面的歌手名和播放钮一起消失）
 * `basicMarquee` 的跑马灯布局**不报告内容的固有高度**，把 Text 放进没有约束的
 * `Box` 里，Box 的高度会算成 0 —— 上一版就是这样，歌名下面的所有内容都被挤没了。
 * 所以这里用 `heightIn(min = lineHeight)` 把行高钉住。
 *
 * ## ★两端渐隐层必须用 `matchParentSize()`，绝不能用 `fillMaxHeight()`
 * `fillMaxHeight()` 取的是**父级给的最大高度**（Column 里剩下一百多 dp），
 * 不是文字的实际高度。它会把这个 Box 从 21dp 撑到一百多 dp →
 * Column 内容溢出 → 底下「波形 + 播放钮」那一行被推出玻璃、被裁掉。
 * 真机表现就是：**某些歌（名字长到触发渐隐时）播放钮消失、波形只剩顶部一小截**。
 * `matchParentSize()` 在父级测量完成后再定尺寸、**不参与测量**，所以不会撑高父级。
 *
 * ## ★两端渐隐只在"真的溢出"时才画
 * 上一版用固定 10dp 的渐隐带，短歌名（比如 4 个字 ≈ 68dp）时
 * 左右各 10dp 会直接咬掉首尾字符 —— 用户报的"名字不长也被渐变遮住"。
 * 现在先测量文字实际宽度：**没溢出就一个像素都不画**；
 * 溢出了才画，且宽度取 `min(配置值, 可用宽度的 15%)`。
 *
 * ## ★为什么用 DstOut 而不是叠一条底色渐变
 * 玻璃是**半透明材质**，背后是"底色 + 封面 + 提取色色渍"的采样结果，
 * 每处像素都不同。叠一条实色渐变会变成一块和玻璃对不上的**可见色块**。
 * 正解是只挖掉文字自己的 alpha：`BlendMode.DstOut` 画在同一图层上，
 * 文字边缘真的变半透明，透出的是玻璃本身。
 *
 * @param fadeWidth 单侧渐隐的**上限**宽度（实际还受 15% 上限与溢出判断约束）
 */
@Composable
private fun MarqueeText(
    text: String,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    letterSpacing: TextUnit,
    color: Color,
    fadeWidth: Dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    // 文字实际宽度 vs 可用宽度：只有溢出了才需要渐隐
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = remember(fontSize, lineHeight, fontWeight, letterSpacing) {
        TextStyle(
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing
        )
    }
    var availableWidth by remember { mutableIntStateOf(0) }
    val textWidthPx = remember(text, style, textMeasurer) {
        runCatching {
            textMeasurer.measure(AnnotatedString(text), style, maxLines = 1).size.width
        }.getOrDefault(0)
    }
    val overflow = availableWidth > 0 && textWidthPx > availableWidth
    // lineHeight 是 TextUnit，heightIn 要 Dp：用当前密度换算
    val minHeight = remember(lineHeight, density) { with(density) { lineHeight.toDp() } }

    Box(
        modifier
            // 钉住行高：跑马灯布局不报固有高度，不给就会塌成 0（见函数注释）
            .heightIn(min = minHeight)
            .onSizeChanged { availableWidth = it.width }
            // 必须离屏：DstOut 只能作用于同一图层内的已有像素。
            // 只在真的需要渐隐时才建层，短歌名走零开销路径。
            .then(
                if (overflow) {
                    Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                } else Modifier
            )
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            color = color,
            maxLines = 1,
            // ★overflow 必须是 Clip 而不是 Ellipsis：滚动期间 Ellipsis 会在
            // 中间某帧把文字截成"xxx…"，跑马灯看起来像在抽搐。
            overflow = TextOverflow.Clip,
            softWrap = false,
            // basicMarquee 只在文字确实超出容器且可见未被遮挡时才滚，短标题保持静止。
            modifier = Modifier.basicMarquee(
                iterations = if (enabled) Int.MAX_VALUE else 0,
                repeatDelayMillis = 1200,
                initialDelayMillis = 900
            )
        )
        if (overflow) {
            // 实际渐隐宽度：不超过配置值，也不超过可用宽度的 15%
            // 组合期算成 px（drawBehind 里没有 Dp 上下文）
            val fadePx = with(density) { fadeWidth.toPx() }
                .coerceAtMost(availableWidth * 0.15f)
            // ★整层用 matchParentSize() 铺满（不参与测量、不撑高父级），
            // 再用 drawRect 只画左/右那一条 fadePx 宽 —— 见函数注释里
            // fillMaxHeight 把整行撑到一百多 dp 的事故。
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        // ★注意 stop 的分数是相对 `startX..endX` 这一段，不是整个宽度
                        // 左端：alpha 从 0 渐变到 1（DstOut 用源的 alpha 挖掉文字）
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                                startX = 0f,
                                endX = fadePx
                            ),
                            topLeft = Offset.Zero,
                            size = Size(fadePx, size.height),
                            blendMode = BlendMode.DstOut
                        )
                        // 右端：反向
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0f to Color.Transparent,
                                1f to Color.Black,
                                startX = size.width - fadePx,
                                endX = size.width
                            ),
                            topLeft = Offset(size.width - fadePx, 0f),
                            size = Size(fadePx, size.height),
                            blendMode = BlendMode.DstOut
                        )
                    }
            )
        }
    }
}

/** 跑马灯两轮之间的空白由库默认处理，这里不再单独配置。 */

/**
 * 播放钮的**视觉**尺寸（图标本身）。
 *
 * 演进：原先图标撑满 48dp 触摸区（明显过大）→ 22dp（偏小）→
 * 用户要求「取上一个大值和现在小值的中间值」：**(48 + 22) / 2 = 35dp**。
 *
 * 与波形高（[FmWaveformHeight]=16dp）底边对齐后，两者看起来是一组。
 */
private val FmPlayButtonSize = 35.dp

/**
 * 波形高度。
 *
 * 用户要求「让波形图往下一点，底边和播放钮对齐」——
 * Row 用 `Alignment.Bottom` + 两者都贴底，所以这里只需给一个合理高度。
 * 16dp 让 14 根柱子在 134dp 宽的玻璃里仍有起伏感，又不至于顶到歌手名。
 */
private val FmWaveformHeight = 16.dp

/** 歌手名文字色：跟随主文字色但保留一点提取色，且保证不会淡到读不清。 */
private fun artistTextColorOf(accent: Color?, onGlass: Color): Color =
    if (accent == null) onGlass.copy(alpha = 0.78f)
    else androidx.compose.ui.graphics.lerp(onGlass, accent, 0.35f).copy(alpha = 0.92f)

/**
 * 波形：14 根细条，单个 Canvas 一次画完。
 *
 * 动画值以 `State<Float>` 传进 Canvas，**只在绘制期读 .value**。
 * 上版在组合期读 `.value`，播放时整个 Waveform 每帧重组一次
 * （项目红线：`Skeleton.kt:43`、`CoverArtworkEffect.kt:455-459`）。
 *
 * ## ★`rememberInfiniteTransition` 必须无条件调用
 * 上一版写成了 `if (playing) rememberInfiniteTransition(...) else remember { 0f }`
 * —— 这是**条件调用 composable**。`playing` 一翻转，组合结构就换了，
 * 动画实例被销毁重建、`phaseState` 的读取链断掉，表现就是"柱子不动了"。
 *
 * 现在的写法：transition **永远存在**（它自己不耗帧，只有被读的动画才推帧），
 * 播放与否只决定"要不要读它 / 用不用它算 wobble"。
 */
@Composable
private fun Waveform(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val barCount = 14
    val heights = remember {
        FloatArray(barCount) { i ->
            val a = kotlin.math.sin(i * 1.7f)
            val b = kotlin.math.cos(i * 0.61f)
            0.24f + 0.76f * kotlin.math.abs(a * b)
        }
    }
    // 无条件创建：见上面的注释，条件调用会让动画在状态翻转时被销毁
    val transition = rememberInfiniteTransition(label = "fmWave")
    val phaseState: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fmWavePhase"
    )

    Canvas(modifier) {
        // 不播放时不读 phaseState：不读就不推帧，静止态零开销
        val phase = if (playing) phaseState.value else 0f
        val gap = 1.5.dp.toPx()
        val barWidth = 1.5.dp.toPx()
        val step = (size.width + gap) / barCount
        for (i in 0 until barCount) {
            val base = heights[i]
            val wobble = if (playing) 0.72f + 0.28f * kotlin.math.sin(phase + i * 0.42f) else 1f
            val h = (size.height * base * wobble).coerceAtLeast(1.5.dp.toPx())
            drawRoundRect(
                color = color.copy(alpha = if (playing) 0.60f else 0.36f),
                topLeft = Offset(i * step, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/**
 * 纯图标播放钮 —— 动效对齐迷你播放器的 [PlayPauseButton]（MainActivity.kt:3902）。
 *
 * 用户口径（2026-11-08）：不要玻璃圆钮，只要图标；动效跟迷你播放器一样。
 * 追加：「这个播放暂停键太大了，小一点」。
 *
 * ## ★视觉盒与触摸盒必须分开
 * 迷你播放器那边 `Icon` 用 `fillMaxSize()` 是对的，因为它的盒子本身就是 34dp。
 * 这里照抄会把图标撑到**触摸区**的 48dp —— 明显过大。
 * 所以：触摸区 48dp（项目惯例：视觉小、触摸大），
 * 视觉盒 [FmPlayButtonSize]=22dp，图标 fillMaxSize 填满视觉盒。
 *
 * 照抄的两件事（改迷你播放器时这里要同步）：
 *  - 图标资源 `ic_nowplaying_mp_play` / `ic_nowplaying_mp_pause`
 *  - `AnimatedContent` 的 scaleIn(0.3f)+fadeIn ↔ scaleOut(0.3f)+fadeOut
 *
 * 点击只发命令、不预写播放态 —— 与迷你播放器同一套理由：
 * 先写会在命令失败时留下永久错误图标。
 */
@Composable
private fun PlainPlayButton(
    playing: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    val label = stringResource(
        id = if (playing) R.string.fm_button_pause else R.string.normal_button_play
    )
    Box(
        Modifier
            // 触摸区（视觉之外的可点范围）
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { onClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = playing,
            // ★与迷你播放器逐字相同：改那边必须同步这里
            transitionSpec = {
                (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                    scaleOut(targetScale = 0.3f) + fadeOut()
                )
            },
            label = "fmPlayPause"
        ) { isPlayingIcon ->
            Icon(
                painter = painterResource(
                    id = if (isPlayingIcon) R.drawable.ic_nowplaying_mp_pause
                    else R.drawable.ic_nowplaying_mp_play
                ),
                contentDescription = null,
                // ★视觉尺寸，不是触摸区
                modifier = Modifier.size(FmPlayButtonSize),
                tint = tint
            )
        }
    }
}
