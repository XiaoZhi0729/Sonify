package yos.music.player.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.lrc.LyricMainFontSizeSp
import yos.music.player.code.utils.lrc.lyricMainTextStyle
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.MainViewModelObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.effects.warmUpFloatingLightArtwork

/**
 * ══ 屏 3（外观与歌词）与屏 4（玻璃材质）的预览舞台 ════════════════════════
 *
 * 这两屏的共同点是"改一个开关，上面那块东西当场变"。舞台必须**足够像真的**，
 * 否则用户不知道自己在调什么。
 *
 * ★★ 判据：预览要么就是**真实现**，要么就别做。
 *   上一版第 3 屏的背景是我自绘的三团径向光斑 —— 它看起来像"动态背景"，
 *   但和播放页实际渲染的东西（糊封面 + 压暗 + Ken Burns）毫无关系。
 *   用户改开关看到的是"我这块光斑停没停"，而不是"我的背景会怎样"。
 *
 *   这一版直接调播放页同一条管线 [YosFloatingLight]，参数与
 *   `MainActivity.kt` 里播放壳的调用逐项对齐。于是预览与真实效果不可能分叉。
 *
 * ── 裸字号为什么是对的 ───────────────────────────────────────────
 * `Ob` 是**界面文字**的栅格。这里的歌词按"像不像播放页的歌词"定字号，
 * 引用 `Ob.TitleSize` 会让歌词跟着页面标题一起放大，就不再像歌词了。
 * 判据：**界面元素的尺寸进令牌表，内容（作品）的尺寸跟着作品。**
 */

// ── 预览用的那首歌 ──────────────────────────────────────────────────────────

/**
 * 预览舞台要展示的那首歌。
 *
 * 优先级（**单一来源**，避免"封面是 A、歌词是 B"）：
 *   ① 曲库里标题命中「甜蜜的死亡」(Komm, süsser Tod) 的那首；
 *   ② 当前正在播的那首；
 *   ③ 都没有 → null（舞台退化为纯深底 + 占位文案）。
 *
 * ★为什么不硬编码一首：引导页第一次启动时用户往往还没播过歌，
 *   不能指望 `musicPlaying` 有值；而把一首商业专辑封面打进包里既不必要
 *   也有版权问题。从用户自己的曲库里取，零素材、零网络。
 */
@Composable
internal fun rememberPreviewTrack(): YosMediaItem? {
    // 订阅两个来源，任一变都会重组
    val playing = MediaController.musicPlaying.value
    val songs = MusicLibrary.songs

    return remember(playing, songs) {
        val wanted = PreviewTrackKeywords
        songs.firstOrNull { song ->
            val title = song.title?.lowercase().orEmpty()
            wanted.any { title.contains(it) }
        } ?: playing
    }
}

/** 「甜蜜的死亡」在各种写法下的匹配词（小写）。 */
private val PreviewTrackKeywords = listOf(
    "甜蜜的死亡",
    "komm",
    "süsser",
    "susser",
    "sweet death",
)

// ── 屏 3 · 歌词舞台 ─────────────────────────────────────────────────────────

/**
 * 歌词舞台：真实播放页背景 + 三行左对齐歌词。
 *
 * ── 为什么底色恒为深色 ────────────────────────────────────────────
 * 它模拟的是播放页。播放页在任何主题下都是深色的（全屏糊封面 + 黑底歌词），
 * 浅色主题下给它换白底就"不像播放页"了，这项预览立刻失去意义。
 *
 * ── [YosFloatingLight] 的三个参数选择 ─────────────────────────────
 *   · `isPlaying = { true }`：引导页没有播放器，恒真才有动效。
 *     这不会影响真实播放（它只是个"要不要跑 Ken Burns"的门）。
 *   · `showMiniPlayer = true`：**只画静态糊图，不跑按全屏设计的 Ken Burns 位移**。
 *     那个位移动画（scale 1.20~1.32、5.5% 平移）是为全屏设计的，塞进 245dp
 *     的小卡里会显得在抖。
 *   · `enableMesh = false`：网格模式要 TextureView + 独立 EGL 线程，
 *     为一块 245dp 的预览开一条 GL 线程不值得。
 *
 * "背景动态效果"开关**不用传进来**：组件内部自己读
 * `SettingsLibrary.NowplayingBackgroundEffect` 决定动效，"打开/关闭"照旧实时生效。
 */
@Composable
internal fun LyricStage(
    colors: OnboardingColors,
    fontScale: Float,
    /** 舞台可用高度（由调用点按剩余空间算，横屏不炸）。 */
    availableHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
) {
    val track = rememberPreviewTrack()

    // 封面：有歌用它的封面，没有就用包内的 banner 图（按主题选）。
    // banner 是资源、不是 Uri，所以转成 android.resource:// URI 交给同一条管线。
    val context = androidx.compose.ui.platform.LocalContext.current
    val lightBannerUri = remember(context) {
        android.net.Uri.parse("android.resource://${context.packageName}/${R.drawable.sonify_banner_light}")
    }
    val darkBannerUri = remember(context) {
        android.net.Uri.parse("android.resource://${context.packageName}/${R.drawable.sonify_banner_dark}")
    }
    LaunchedEffect(track == null) {
        if (track?.thumb == null) {
            launch { warmUpFloatingLightArtwork(context, lightBannerUri) }
            launch { warmUpFloatingLightArtwork(context, darkBannerUri) }
        }
    }
    val fallbackUri = if (isFlamingoInDarkMode()) darkBannerUri else lightBannerUri
    val artworkUri = track?.thumb ?: fallbackUri

    // 歌词扫色时钟：7 秒一轮。
    var sweep by remember { mutableFloatStateOf(0.18f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000_000f) % 7f
            sweep = (0.18f + 0.72f * (t / 7f)).coerceIn(0f, 1f)
        }
    }

    val stageHeight = if (availableHeight != null) {
        (availableHeight.value * 0.42f).coerceIn(180f, 245f).dp
    } else {
        245.dp
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(stageHeight)
            .clip(YosRoundedCornerShape(Ob.StageCorner))
            .background(colors.stageBase)
    ) {
        // ① 真背景：与播放壳同一条管线（流体网格 / 糊封面 + 压暗 + 版式）
        //
        // ★★两个参数是为**这一屏要能演示"背景动态效果"**而加的，缺任一个这一屏就是假的：
        //
        //   `animateAlways = true` ← 修的是"开关按了完全没反应"。
        //     这里必须传 `showMiniPlayer = true`（舞台里不画真实迷你条），
        //     而 `showMiniPlayer` 原本被并进了动效门 `active` —— 于是动效时间轴
        //     从来不启动，开/关画面逐像素相同。`animateAlways` 把两件事拆开。
        //
        //   `forceMesh = true` ← 预览恒展示流体网格（用户明确要的那套动态背景），
        //     不带条件地读用户的 `NowplayingBackgroundMode`。它是纯展示，
        //     不改用户设置；设备没有 GLES3 时组件内部自动退回漂移分支。
        YosFloatingLight(
            modifier = Modifier.fillMaxSize(),
            album = { artworkUri },
            isPlaying = { true },
            nowPage = { "" },
            showMiniPlayer = true,
            enableMesh = true,
            forceMesh = true,
            animateAlways = true,
        )

        // ② 三行左对齐歌词
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Ob.S24, vertical = Ob.S24),
        ) {
            Spacer(Modifier.weight(1f))

            // ★字号口径与**真实播放页**一致，不是自己另定一套：
            //   播放页的当前行是 `LyricMainFontSizeSp (37.5sp) × LyricFontScale(0.8..1.2)`。
            //   舞台只有 245dp 高（约整屏的 29%），所以再乘一个舞台缩放比 ——
            //   关键是要保住"小/中/大 = 0.8/1.0/1.2"这层**比例关系**，
            //   用户在这里看到的相对变化，就是播放页里会发生的相对变化。
            //   （判据：预览里的档位差必须是真实现的档位差，不能是另一套映射。）
            val animatedScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = fontScale.coerceIn(0.8f, 1.2f),
                animationSpec = Ob.SlideSpring,
                label = "stageLyricScale",
            )
            val lyricSize = (LyricMainFontSizeSp * animatedScale * StageLyricScale).sp
            val lines = previewLyricLines()

            // 上一行：已经唱过，最暗
            Text(
                text = lines.prev,
                color = Color.White.copy(alpha = 0.35f),
                fontSize = (lyricSize.value * 0.75f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(Ob.S12))

            // 当前行：扫色（亮/暗两层 + 硬边界）
            SweptLyric(
                text = lines.current,
                progress = sweep,
                fontSize = lyricSize,
                dim = Color.White.copy(alpha = 0.35f),
                bright = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((lyricSize.value * 1.45f).dp),
            )

            Spacer(Modifier.height(Ob.S12))

            // 下一行：还没唱，比上一行再淡
            Text(
                text = lines.next,
                color = Color.White.copy(alpha = 0.25f),
                fontSize = (lyricSize.value * 0.75f).sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 预览用的三行歌词。
 *
 * ★只在"预览那首歌**就是**当前在播的那首"时才取真歌词。
 *   `MediaViewModelObject.lrcEntries` 是全局单份、随当前播放曲目覆写的，
 *   如果预览选中的是曲库里另一首，拿它去配就会"歌是 A、词是 B"。
 *
 * 取真歌词的口径与工程既有实现一致：`mainLyric.joinToString("") { it.second }`
 * （逐字/逐段拼接），当前行由 `MainViewModelObject.syncLyricIndex` 给出。
 */
@Composable
private fun previewLyricLines(): LyricTriple {
    val playing = MediaController.musicPlaying.value
    val track = rememberPreviewTrack()
    val entries = MediaViewModelObject.lrcEntries.value
    val index = MainViewModelObject.syncLyricIndex.intValue

    val isPlayingTrack = track != null && playing != null && track.uri == playing.uri

    if (isPlayingTrack && entries.isNotEmpty() && index in entries.indices) {
        fun lineAt(i: Int): String? =
            entries.getOrNull(i)?.mainLyric?.joinToString("") { it.second }?.takeIf { it.isNotBlank() }

        return LyricTriple(
            prev = lineAt(index - 1).orEmpty(),
            current = lineAt(index).orEmpty(),
            next = lineAt(index + 1).orEmpty(),
        )
    }

    // 没有对应曲目的真歌词 → 用界面文案占位（不编造歌词）。
    return LyricTriple(
        prev = stringResource(id = R.string.onboarding_lyric_prev),
        current = stringResource(id = R.string.onboarding_lyric_current),
        next = stringResource(id = R.string.onboarding_lyric_next),
    )
}

private class LyricTriple(val prev: String, val current: String, val next: String)

/**
 * 舞台歌词相对真实播放页的缩放比。
 *
 * 37.5sp × 0.52 ≈ 19.5sp —— 与原型稿在这一屏用的 20px 几乎相同（巧合，但正好说明
 * 两边想要的是同一个视觉重量）。改舞台高度时要重新核这个值。
 */
private const val StageLyricScale = 0.52f

/**
 * 逐字扫色歌词。
 *
 * 这是这个 App 最有辨识度的一个细节：正在唱的那一句，左侧已经唱过的部分是亮的，
 * 右侧未唱的部分是暗的，扫色边界跟着播放进度走。
 *
 * ★**左对齐**（`dx = 0`）。上一版是居中，那是"封面上的歌词"的排法；
 *   播放页的歌词是左对齐的，而且左对齐才看得出扫色是从同一个基线往前推的。
 *
 * 实现用两层文字 + `clipRect`：底层暗、上层亮并只画到 `progress × width`。
 * 不用 `drawText(brush)` 的渐变 —— 逐字扫色要的是**硬的边界**（一个字一个字地亮），
 * 软渐变会看起来像"光晕"，不像"唱到这儿"。
 */
@Composable
private fun SweptLyric(
    text: String,
    progress: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    dim: Color,
    bright: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val realWeight = lyricMainTextStyle().fontWeight ?: FontWeight.ExtraBold
    val style = TextStyle(
        fontSize = fontSize,
        fontWeight = realWeight,
        letterSpacing = 0.05.sp
    )
    // 尺寸与字重随真实歌词设置变，按 text+fontSize+realWeight 重新测量（不是每帧）
    val layout: TextLayoutResult = remember(text, fontSize, realWeight) {
        measurer.measure(text = text, style = style, maxLines = 1)
    }

    Canvas(modifier) {
        val lw = layout.size.width.toFloat()
        val lh = layout.size.height.toFloat()
        // ★左对齐：dx 恒为 0
        val dy = (size.height - lh) / 2f

        drawText(layout, color = dim, topLeft = Offset(0f, dy))

        val sweepX = lw * progress.coerceIn(0f, 1f)
        clipRect(left = 0f, top = 0f, right = sweepX, bottom = size.height) {
            drawText(layout, color = bright, topLeft = Offset(0f, dy))
        }
    }
}

// ── 共用：控件卡 ────────────────────────────────────────────────────────────

/**
 * 一行偏好装进一张 22dp 圆角的控件卡。
 *
 * 这是原型稿的口径（iOS 分组列表）：一个开关/一个分段/一个滑块各占一张卡。
 * 与 [OnboardingCard] 的分工：
 *   · [OnboardingCard] = **独立物件**（云端互通说明卡、本机扫描卡、扫码弹层底板）
 *   · 本函数 = **装一行控件**的容器，卡里只有"标题 + 控件"
 */
@Composable
internal fun ControlCard(
    colors: OnboardingColors,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = YosRoundedCornerShape(Ob.ControlCorner)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card.copy(alpha = 0.72f))
            .border(1.dp, colors.cardStroke, shape)
            .padding(horizontal = Ob.S16, vertical = Ob.S12),
        content = content,
    )
}

/** 控件卡里的一行：左标题（+ 可选副标题）+ 右控件。 */
@Composable
internal fun ControlRow(
    colors: OnboardingColors,
    title: String,
    subtitle: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★weight(1f)：标题列优先拿空间。控件宽度固定，标题可换行 ——
        //   超大字体下开关不会被挤出屏幕。
        Column(Modifier.weight(1f, fill = true)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = Ob.RowSize,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Ob.S2))
                Text(
                    text = subtitle,
                    color = colors.ink3,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.width(Ob.S12))
        control()
    }
}

/** 舞台下面的脚注：一行小字，说明这个开关会不会影响别处。 */
@Composable
internal fun StageFootnote(text: String, colors: OnboardingColors) {
    Text(
        text = text,
        color = colors.ink3,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Ob.S2, vertical = Ob.S4),
    )
}
