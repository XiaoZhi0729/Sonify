package yos.music.player.ui.pages.library

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.tatarka.google.material.palettes.TonalPalette
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.objects.KugouFollowedArtist
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.ui.pages.discovery.rememberFmHue
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.DiscoveryMinImageSize
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout
import yos.music.player.ui.widgets.liquid.claimHorizontalDragGestures
import yos.music.player.ui.widgets.mochi.MochiButton
import yos.music.player.ui.widgets.mochi.MochiButtonDefaults
import yos.music.player.ui.widgets.mochi.MochiButtonStyle
import yos.music.player.ui.widgets.mochi.MochiPageControl
import yos.music.player.ui.widgets.mochi.MochiPeerTransition
import yos.music.player.ui.widgets.mochi.MochiPeerTransitionSpec
import yos.music.player.ui.widgets.mochi.MochiSprings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 纯白色矢量播放/暂停按钮（无圆形背景遮罩），图标、视觉尺寸（35.dp）、触控区（48.dp）
 * 与动效（scaleIn(0.3f)+fadeIn ↔ scaleOut(0.3f)+fadeOut）均与主页私人电台完全一致，
 * 底层配柔和阴影保证浅色封面上的可读性。
 *
 * 优化：使用轻量 [animateFloatAsState] + Draw 阶段 `graphicsLayer` 替代每张卡片一个
 * [AnimatedContent]（静止时零过渡状态机、仅渲染单个激活图标）。
 */
@Composable
fun BarePlayButton(
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 35.dp,
) {
    BarePlayButton(
        isPlayingProvider = { isPlaying },
        onClick = onClick,
        contentDescription = contentDescription,
        modifier = modifier,
        glyphSize = glyphSize
    )
}

@Composable
fun BarePlayButton(
    isPlayingProvider: () -> Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 35.dp,
) {
    val playing = isPlayingProvider()
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(180),
        label = "bare_play_pause_progress"
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (progress < 1f) {
            val playAlpha = (1f - progress).coerceIn(0f, 1f)
            val playScale = 0.3f + 0.7f * playAlpha
            BarePlayGlyph(
                iconRes = R.drawable.ic_nowplaying_mp_play,
                contentDescription = if (!playing) contentDescription else null,
                glyphSize = glyphSize,
                modifier = if (progress > 0f) {
                    Modifier.graphicsLayer {
                        alpha = playAlpha
                        scaleX = playScale
                        scaleY = playScale
                    }
                } else Modifier
            )
        }
        if (progress > 0f) {
            val pauseAlpha = progress.coerceIn(0f, 1f)
            val pauseScale = 0.3f + 0.7f * pauseAlpha
            BarePlayGlyph(
                iconRes = R.drawable.ic_nowplaying_mp_pause,
                contentDescription = if (playing) contentDescription else null,
                glyphSize = glyphSize,
                modifier = if (progress < 1f) {
                    Modifier.graphicsLayer {
                        alpha = pauseAlpha
                        scaleX = pauseScale
                        scaleY = pauseScale
                    }
                } else Modifier
            )
        }
    }
}

@Composable
private fun BarePlayGlyph(
    iconRes: Int,
    contentDescription: String?,
    glyphSize: Dp,
    modifier: Modifier = Modifier,
) {
    val iconPainter = painterResource(id = iconRes)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = iconPainter,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.36f),
            modifier = Modifier
                .size(glyphSize)
                .offset(y = 1.dp)
        )
        Icon(
            painter = iconPainter,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(glyphSize)
        )
    }
}

/**
 * 封面右下角轻微径向暗角，提升纯白播放/暂停图标在亮色封面上的对比度。
 * 使用 [drawWithCache] 缓存径向渐变并跳过独立 Canvas 节点。
 */
private fun Modifier.artworkCornerScrim(
    cornerRadius: Dp = 0.dp,
    alphaProvider: () -> Float = { 1f },
): Modifier = drawWithCache {
    val brush = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = 0.24f), Color.Transparent),
        center = Offset(size.width, size.height),
        radius = size.minDimension * 0.56f
    )
    val radiusPx = if (cornerRadius > 0.dp) cornerRadius.toPx() else 0f
    onDrawWithContent {
        drawContent()
        val scrimAlpha = alphaProvider().coerceIn(0f, 1f)
        if (scrimAlpha > 0.001f) {
            if (radiusPx > 0f) {
                drawRoundRect(
                    brush = brush,
                    alpha = scrimAlpha,
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
            } else {
                drawRect(brush = brush, alpha = scrimAlpha)
            }
        }
    }
}

private object DailyDateCache {
    private var cachedKey: String? = null
    private var cachedMonthWeekday: String = ""
    private var cachedDayOfMonth: String = ""

    @Synchronized
    fun today(): Pair<String, String> {
        val calendar = Calendar.getInstance()
        val locale = Locale.getDefault()
        val key = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}-${locale.toLanguageTag()}"
        if (key != cachedKey) {
            val monthStr = SimpleDateFormat("M月", locale).format(calendar.time)
            val weekStr = SimpleDateFormat("E", locale).format(calendar.time)
            cachedMonthWeekday = "$monthStr · $weekStr"
            cachedDayOfMonth = calendar.get(Calendar.DAY_OF_MONTH).toString()
            cachedKey = key
        }
        return cachedMonthWeekday to cachedDayOfMonth
    }
}

/**
 * 酷狗账号头像按钮（放置于资料库顶栏右上角，点击直接跳转酷狗账号页）。
 *
 * 有 [avatarUrl] 时铺真实头像照片；否则（未登录 / 上游无头像 / 加载失败）画昵称首字母块。
 * 字母块恒作为底层先画，照片只在解码成功后盖上去——加载中因此天然"无灰块闪烁"，
 * 失败时也自动退回字母，不需要额外占位图。
 */
@Composable
fun KugouProfileAvatarButton(
    isLoggedIn: Boolean,
    nickname: String?,
    userid: String?,
    isVip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
) {
    val initial = remember(nickname, userid) {
        val raw = nickname?.trim()?.takeIf { it.isNotEmpty() }
            ?: userid?.trim()?.takeIf { it.isNotEmpty() }
            ?: "K"
        raw.first().uppercaseChar().toString()
    }
    // 解码失败后不再重试本 URL（换头像 URL 变了会自动复位重试）
    var avatarFailed by remember(avatarUrl) { mutableStateOf(false) }
    val showAvatar = isLoggedIn && !avatarUrl.isNullOrBlank() && !avatarFailed
    val context = LocalContext.current

    val ringBrush = if (isLoggedIn) {
        if (isVip) {
            Brush.linearGradient(listOf(Color(0xFFF59F00), MaterialTheme.colorScheme.primary))
        } else {
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                )
            )
        }
    } else null

    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .then(
                if (ringBrush != null) {
                    Modifier.background(ringBrush).padding(2.dp)
                } else {
                    Modifier.background(
                        Color(0xFF767680).copy(alpha = 0.12f) withNight Color(0xFF767680).copy(alpha = 0.24f)
                    )
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isLoggedIn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF2B2D42), Color(0xFF5C677D)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (showAvatar) {
                    AsyncImage(
                        model = remember(avatarUrl) {
                            ImageRequest.Builder(context)
                                .data(avatarUrl)
                                .crossfade(true)
                                .allowHardware(true)
                                .precision(Precision.INEXACT)
                                .size(192)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { avatarFailed = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun DialogActionRow(
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

enum class LibrarySourceTab {
    Online,
    Local
}

/**
 * 对齐项目 `DiscoverySectionHeader` 的统一分区标题行（20.sp Bold + 24.dp `ic_chevron_right`）。
 */
@Composable
fun LibrarySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    actionLabel: String? = null,
    onTitleClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 20.sp
            )
            if (showChevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_right),
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .alpha(0.35f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 顶部双功能小组件自适应容器：
 * - 平板 / 宽屏（>= 600dp）：双卡并排占满屏幕宽度，无横向滚动与多余留白；
 * - 手机（< 600dp）：HorizontalPager 单屏刚好完整展示一张卡片，不超出屏幕。
 */
@Composable
fun LibraryHeroWidgetRail(
    containerWidth: Dp,
    pagerState: PagerState,
    dailySongs: List<KugouNewSong>,
    fmSongs: List<KugouNewSong>,
    fmIndex: Int,
    nowPlayingMediaIdProvider: () -> String?,
    isGlobalPlayingProvider: () -> Boolean,
    onPlayDailyToggle: () -> Unit,
    onPlayDailySongAt: (Int) -> Unit,
    onOpenDailyDetail: () -> Unit,
    onSwitchFm: (Int) -> Unit,
    onPlayCurrentFmToggle: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenFmList: () -> Unit,
) {
    val isWide = containerWidth >= 600.dp
    val isCompactPhone = containerWidth < 370.dp

    if (isWide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DailyRecommendWidgetCard(
                modifier = Modifier.weight(1f),
                isCompactPhone = false,
                dailySongs = dailySongs,
                nowPlayingMediaIdProvider = nowPlayingMediaIdProvider,
                isGlobalPlayingProvider = isGlobalPlayingProvider,
                onPlayToggle = onPlayDailyToggle,
                onPlaySongAt = onPlayDailySongAt,
                onOpenDetail = onOpenDailyDetail
            )
            PersonalFmGalleryWidgetCard(
                modifier = Modifier.weight(1f),
                isCompactPhone = false,
                fmSongs = fmSongs,
                fmIndex = fmIndex,
                nowPlayingMediaIdProvider = nowPlayingMediaIdProvider,
                isGlobalPlayingProvider = isGlobalPlayingProvider,
                onSwitchFm = onSwitchFm,
                onPlayCurrentFmToggle = onPlayCurrentFmToggle,
                onOpenGallery = onOpenGallery,
                onOpenFmList = onOpenFmList
            )
        }
    } else {
        Layout(
            content = {
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fill,
                    pageSpacing = 12.dp,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> DailyRecommendWidgetCard(
                            modifier = Modifier.fillMaxWidth(),
                            isCompactPhone = isCompactPhone,
                            dailySongs = dailySongs,
                            nowPlayingMediaIdProvider = nowPlayingMediaIdProvider,
                            isGlobalPlayingProvider = isGlobalPlayingProvider,
                            onPlayToggle = onPlayDailyToggle,
                            onPlaySongAt = onPlayDailySongAt,
                            onOpenDetail = onOpenDailyDetail
                        )
                        else -> PersonalFmGalleryWidgetCard(
                            modifier = Modifier.fillMaxWidth(),
                            isCompactPhone = isCompactPhone,
                            fmSongs = fmSongs,
                            fmIndex = fmIndex,
                            nowPlayingMediaIdProvider = nowPlayingMediaIdProvider,
                            isGlobalPlayingProvider = isGlobalPlayingProvider,
                            onSwitchFm = onSwitchFm,
                            onPlayCurrentFmToggle = onPlayCurrentFmToggle,
                            onOpenGallery = onOpenGallery,
                            onOpenFmList = onOpenFmList
                        )
                    }
                }

                MochiPageControl(
                    state = pagerState,
                    dotSize = 7.dp,
                    activeWidth = 18.dp,
                    pitch = 16.dp,
                    height = 22.dp,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
        ) { measurables, constraints ->
            val pagerPlaceable = measurables[0].measure(constraints)
            val indicatorPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            // 布局高度严格等于卡片高度，指示器悬浮落在下方既有 22.dp 间距带内，不影响与「最近播放」的间距
            layout(pagerPlaceable.width, pagerPlaceable.height) {
                pagerPlaceable.placeRelative(0, 0)
                val indicatorX = (pagerPlaceable.width - indicatorPlaceable.width) / 2
                indicatorPlaceable.placeRelative(indicatorX, pagerPlaceable.height)
            }
        }
    }
}

internal data class FmWidgetPalette(
    val bgStart: Color,
    val bgEnd: Color,
    val glow: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val buttonContainer: Color,
    val buttonContent: Color,
    val buttonGlow: Color,
    val listActionText: Color,
    val emptyCoverBg: Color,
    val emptyCoverBorder: Color,
    val coverBorder: Color,
)

internal fun resolveFmWidgetPalette(hue: Float?, isDark: Boolean): FmWidgetPalette {
    if (hue == null) {
        return if (isDark) {
            FmWidgetPalette(
                bgStart = Color(0xFF1F212B),
                bgEnd = Color(0xFF13141B),
                glow = Color(0xFF5C7CFA).copy(alpha = 0.24f),
                border = Color.White.copy(alpha = 0.12f),
                primaryText = Color.White,
                secondaryText = Color.White.copy(alpha = 0.68f),
                buttonContainer = Color.White.copy(alpha = 0.16f),
                buttonContent = Color.White,
                buttonGlow = Color.White.copy(alpha = 0.28f),
                listActionText = Color.White.copy(alpha = 0.76f),
                emptyCoverBg = Color.White.copy(alpha = 0.10f),
                emptyCoverBorder = Color.White.copy(alpha = 0.22f),
                coverBorder = Color.White.copy(alpha = 0.20f),
            )
        } else {
            FmWidgetPalette(
                bgStart = Color(0xFFF3F4F8),
                bgEnd = Color(0xFFE8ECF4),
                glow = Color(0xFF7B93F5).copy(alpha = 0.22f),
                border = Color.Black.copy(alpha = 0.09f),
                primaryText = Color(0xFF16181F),
                secondaryText = Color(0xFF16181F).copy(alpha = 0.68f),
                buttonContainer = Color(0xFF16181F).copy(alpha = 0.10f),
                buttonContent = Color(0xFF16181F),
                buttonGlow = Color.White.copy(alpha = 0.50f),
                listActionText = Color(0xFF16181F).copy(alpha = 0.76f),
                emptyCoverBg = Color(0xFF16181F).copy(alpha = 0.07f),
                emptyCoverBorder = Color(0xFF16181F).copy(alpha = 0.14f),
                coverBorder = Color.Black.copy(alpha = 0.08f),
            )
        }
    }

    val basePalette = TonalPalette.fromHueAndChroma(hue.toDouble(), if (isDark) 22.0 else 18.0)
    val accentPalette = TonalPalette.fromHueAndChroma(hue.toDouble(), if (isDark) 48.0 else 44.0)
    return if (isDark) {
        FmWidgetPalette(
            bgStart = Color(basePalette.tone(20)),
            bgEnd = Color(basePalette.tone(11)),
            glow = Color(accentPalette.tone(56)).copy(alpha = 0.38f),
            border = Color(accentPalette.tone(80)).copy(alpha = 0.16f),
            primaryText = Color.White,
            secondaryText = Color.White.copy(alpha = 0.68f),
            buttonContainer = Color(accentPalette.tone(90)).copy(alpha = 0.16f),
            buttonContent = Color.White,
            buttonGlow = Color.White.copy(alpha = 0.28f),
            listActionText = Color.White.copy(alpha = 0.76f),
            emptyCoverBg = Color.White.copy(alpha = 0.10f),
            emptyCoverBorder = Color.White.copy(alpha = 0.22f),
            coverBorder = Color.White.copy(alpha = 0.20f),
        )
    } else {
        FmWidgetPalette(
            bgStart = Color(basePalette.tone(96)),
            bgEnd = Color(basePalette.tone(90)),
            glow = Color(accentPalette.tone(74)).copy(alpha = 0.42f),
            border = Color(accentPalette.tone(40)).copy(alpha = 0.14f),
            primaryText = Color(basePalette.tone(12)),
            secondaryText = Color(basePalette.tone(20)).copy(alpha = 0.76f),
            buttonContainer = Color(accentPalette.tone(40)).copy(alpha = 0.13f),
            buttonContent = Color(basePalette.tone(16)),
            buttonGlow = Color.White.copy(alpha = 0.50f),
            listActionText = Color(basePalette.tone(20)).copy(alpha = 0.78f),
            emptyCoverBg = Color(basePalette.tone(30)).copy(alpha = 0.08f),
            emptyCoverBorder = Color(basePalette.tone(30)).copy(alpha = 0.16f),
            coverBorder = Color.Black.copy(alpha = 0.08f),
        )
    }
}

internal object DailyRecommendPaging {
    const val PAGE_SIZE = 3

    fun pageCount(totalSongs: Int): Int =
        if (totalSongs <= 0) 0 else (totalSongs + PAGE_SIZE - 1) / PAGE_SIZE

    fun pageRange(totalSongs: Int, page: Int): IntRange {
        val count = pageCount(totalSongs)
        if (count == 0 || page !in 0 until count) return IntRange.EMPTY
        val start = page * PAGE_SIZE
        val end = (start + PAGE_SIZE).coerceAtMost(totalSongs)
        return start until end
    }

    fun resolveSwipeTarget(
        currentPage: Int,
        pageCount: Int,
        netDragYPx: Float,
        velocityYPxPerSec: Float,
        dragThresholdPx: Float,
        velocityThresholdPxPerSec: Float,
    ): Int {
        if (pageCount <= 1) return 0
        val clampedCurrent = currentPage.coerceIn(0, pageCount - 1)
        val advanceUp = netDragYPx <= -dragThresholdPx ||
            (velocityYPxPerSec <= -velocityThresholdPxPerSec && netDragYPx < 0f)
        val retreatDown = netDragYPx >= dragThresholdPx ||
            (velocityYPxPerSec >= velocityThresholdPxPerSec && netDragYPx > 0f)
        return when {
            advanceUp -> (clampedCurrent + 1).coerceAtMost(pageCount - 1)
            retreatDown -> (clampedCurrent - 1).coerceAtLeast(0)
            else -> clampedCurrent
        }
    }
}

@Composable
private fun DailyRecommendWidgetCard(
    modifier: Modifier = Modifier,
    isCompactPhone: Boolean,
    dailySongs: List<KugouNewSong>,
    nowPlayingMediaIdProvider: () -> String?,
    isGlobalPlayingProvider: () -> Boolean,
    onPlayToggle: () -> Unit,
    onPlaySongAt: (Int) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val surfaceColor = Color(0xFFF5F5F7) withNight Color(0xFF1C1C1E)
    val hairline = libraryHairlineColor()
    val cardShape = remember { ContinuousRoundedRectangle(18.dp) }
    val context = LocalContext.current
    val density = LocalDensity.current

    val (monthWeekday, dayOfMonth) = remember { DailyDateCache.today() }
    val nowPlayingMediaId = nowPlayingMediaIdProvider()
    val isGlobalPlaying = isGlobalPlayingProvider()

    val isDailyActive = !nowPlayingMediaId.isNullOrBlank() && dailySongs.any { song ->
        nowPlayingMediaId.equals(song.hash, ignoreCase = true) ||
            nowPlayingMediaId.endsWith(song.hash, ignoreCase = true)
    }
    val isDailyPlaying = isDailyActive && isGlobalPlaying

    val pageCount = DailyRecommendPaging.pageCount(dailySongs.size)
    var currentPage by remember { mutableIntStateOf(0) }

    LaunchedEffect(pageCount) {
        if (pageCount == 0 && currentPage != 0) {
            currentPage = 0
        } else if (pageCount > 0 && currentPage >= pageCount) {
            currentPage = pageCount - 1
        }
    }

    val safePage = if (pageCount == 0) 0 else currentPage.coerceIn(0, pageCount - 1)

    fun switchToPage(target: Int) {
        if (target == currentPage) return
        currentPage = target
        Vibrator.click(context)
    }

    Row(
        modifier = modifier
            .height(176.dp)
            .clip(cardShape)
            .background(surfaceColor)
            .border(0.5.dp, hairline, cardShape)
            .clickable(onClick = onOpenDetail)
            .padding(start = 14.dp, end = 10.dp)
    ) {
        // 左侧：日历印章 + 位置更紧凑的自适应 MOCHI 播放/暂停按钮
        Column(
            modifier = Modifier
                .width(if (isCompactPhone) 94.dp else 104.dp)
                .fillMaxHeight()
                .padding(top = 13.dp, bottom = 12.dp, end = 10.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Column {
                Text(
                    text = monthWeekday,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = dayOfMonth,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isCompactPhone) 32.sp else 36.sp,
                    lineHeight = if (isCompactPhone) 34.sp else 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp,
                )
                Text(
                    text = stringResource(id = R.string.home_everyday_recommend_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(id = R.string.library_daily_count_fmt, dailySongs.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(10.dp))

            MochiButton(
                onClick = onPlayToggle,
                enabled = dailySongs.isNotEmpty(),
                style = MochiButtonStyle.Filled,
                colors = MochiButtonDefaults.colors(MochiButtonStyle.Filled),
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = isDailyPlaying,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                            scaleOut(targetScale = 0.3f) + fadeOut()
                        )
                    },
                    label = "daily_play_pause_anim"
                ) { playing ->
                    Icon(
                        painter = painterResource(
                            id = if (playing) R.drawable.ic_nowplaying_mp_pause else R.drawable.ic_nowplaying_mp_play
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = stringResource(
                        id = if (isDailyPlaying) R.string.widget_pause else R.string.library_daily_play
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // 竖向细分割线
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(top = 13.dp, bottom = 12.dp)
                .width(0.5.dp)
                .background(hairline)
        )

        // 右侧：支持纵向滑动翻页 + MOCHI 同层级切换 + 上下渐变遮罩的 3 首微缩歌曲列表
        if (dailySongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 10.dp, top = 13.dp, bottom = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(id = R.string.library_daily_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        } else {
            val pages = remember(pageCount) { (0 until pageCount).toList() }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MochiPeerTransition(
                    targetState = safePage,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    pages = pages,
                    onPageChange = { target -> switchToPage(target) },
                    orientation = Orientation.Vertical,
                    background = Color.Transparent,
                    backdrop = Color.Transparent,
                    edgeFadeExtent = 20.dp,
                    spec = MochiPeerTransitionSpec(),
                    retainPages = false,
                ) { page ->
                    val range = DailyRecommendPaging.pageRange(dailySongs.size, page)
                    val rowCount = if (range.isEmpty()) 0 else (range.last - range.first + 1)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 13.dp, bottom = 12.dp)
                    ) {
                        for (slot in 0 until DailyRecommendPaging.PAGE_SIZE) {
                            if (slot < rowCount) {
                                val globalIdx = range.first + slot
                                val song = dailySongs[globalIdx]
                                val isCurrent = !nowPlayingMediaId.isNullOrBlank() &&
                                    (nowPlayingMediaId.equals(song.hash, ignoreCase = true) ||
                                        nowPlayingMediaId.endsWith(song.hash, ignoreCase = true))
                                DailyRecommendSongRow(
                                    song = song,
                                    isCurrent = isCurrent,
                                    onClick = { onPlaySongAt(globalIdx) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            } else {
                                Spacer(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }
                            if (slot < DailyRecommendPaging.PAGE_SIZE - 1) {
                                if (slot < rowCount - 1) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(0.5.dp)
                                            .background(hairline)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(0.5.dp))
                                }
                            }
                        }
                    }
                }

                if (pageCount > 1) {
                    val trackHeight = 44.dp
                    val thumbHeight = (trackHeight / pageCount).coerceIn(10.dp, 22.dp)
                    val thumbProgress by animateFloatAsState(
                        targetValue = safePage.toFloat() / (pageCount - 1),
                        animationSpec = MochiSprings.crisp(320),
                        label = "daily_page_thumb"
                    )
                    val travelPx = with(density) { (trackHeight - thumbHeight).toPx() }
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .fillMaxHeight()
                            .padding(top = 13.dp, bottom = 12.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    val nextPage = (safePage + 1) % pageCount
                                    switchToPage(nextPage)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(trackHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(0, (travelPx * thumbProgress).roundToInt()) }
                                    .width(3.dp)
                                    .height(thumbHeight)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyRecommendSongRow(
    song: KugouNewSong,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AsyncImage(
            model = remember(song.artworkUrl) {
                ImageRequest.Builder(context)
                    .data(song.artworkUrl)
                    .size(120)
                    .precision(Precision.INEXACT)
                    .placeholder(R.drawable.placeholder_music_default_artwork)
                    .error(R.drawable.placeholder_music_default_artwork)
                    .fallback(R.drawable.placeholder_music_default_artwork)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(0.5.dp, libraryCoverRingColor(), RoundedCornerShape(6.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.author,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PersonalFmGalleryWidgetCard(
    modifier: Modifier = Modifier,
    isCompactPhone: Boolean,
    fmSongs: List<KugouNewSong>,
    fmIndex: Int,
    nowPlayingMediaIdProvider: () -> String?,
    isGlobalPlayingProvider: () -> Boolean,
    onSwitchFm: (Int) -> Unit,
    onPlayCurrentFmToggle: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenFmList: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isFlamingoInDarkMode()
    val safeIndex = if (fmSongs.isEmpty()) 0 else fmIndex.coerceIn(0, fmSongs.lastIndex)

    // 连续浮点进度：整个 5 卡扇形窗口、左侧歌名、触觉刻度共用同一个物理源
    val scrollAnim = remember { Animatable(safeIndex.toFloat()) }
    val dragYAnim = remember { Animatable(0f) }
    val pressScaleAnim = remember { Animatable(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var activeTarget by remember { mutableStateOf<Int?>(null) }
    var pendingCommitTarget by remember { mutableStateOf<Int?>(null) }
    var lastCommittedWrapped by remember { mutableIntStateOf(safeIndex) }
    var lastHapticAbsIndex by remember { mutableIntStateOf(safeIndex) }

    val displayAbsIndex by remember {
        derivedStateOf { scrollAnim.value.roundToInt() }
    }
    val displayWrappedIndex = if (fmSongs.isEmpty()) 0 else FmCoverStackPhysics.wrapIndex(displayAbsIndex, fmSongs.size)
    val currentSong = fmSongs.getOrNull(displayWrappedIndex)

    // 动态封面取色（沿用上一次有效色相，避免切歌解码瞬间闪烁）
    val extractedHue = rememberFmHue(currentSong?.artworkUrl)
    var lastValidHue by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(extractedHue, fmSongs.isEmpty()) {
        if (fmSongs.isEmpty()) {
            lastValidHue = null
        } else if (extractedHue != null) {
            lastValidHue = extractedHue
        }
    }
    val effectiveHue = extractedHue ?: lastValidHue
    val targetPalette = remember(effectiveHue, isDark) {
        resolveFmWidgetPalette(effectiveHue, isDark)
    }
    val colorAnimSpec = remember { tween<Color>(durationMillis = 420, easing = FastOutSlowInEasing) }
    val bgStartState = animateColorAsState(targetPalette.bgStart, colorAnimSpec, label = "fm_bg_start")
    val bgEndState = animateColorAsState(targetPalette.bgEnd, colorAnimSpec, label = "fm_bg_end")
    val glowState = animateColorAsState(targetPalette.glow, colorAnimSpec, label = "fm_glow")
    val borderState = animateColorAsState(targetPalette.border, colorAnimSpec, label = "fm_border")
    val primaryTextState = animateColorAsState(targetPalette.primaryText, colorAnimSpec, label = "fm_primary_text")
    val secondaryTextState = animateColorAsState(targetPalette.secondaryText, colorAnimSpec, label = "fm_secondary_text")
    val buttonContainerState = animateColorAsState(targetPalette.buttonContainer, colorAnimSpec, label = "fm_btn_container")
    val buttonContentState = animateColorAsState(targetPalette.buttonContent, colorAnimSpec, label = "fm_btn_content")
    val buttonGlowState = animateColorAsState(targetPalette.buttonGlow, colorAnimSpec, label = "fm_btn_glow")
    val listActionTextState = animateColorAsState(targetPalette.listActionText, colorAnimSpec, label = "fm_list_text")

    // 仅手指主动拖过中点时触发极轻 tick 触觉刻度；松手回弹/惯性滑行/点击不震动
    LaunchedEffect(displayAbsIndex, fmSongs.size, isDragging) {
        if (fmSongs.size > 1 && displayAbsIndex != lastHapticAbsIndex) {
            lastHapticAbsIndex = displayAbsIndex
            if (isDragging) {
                Vibrator.tick(context)
            }
        }
    }

    // 外部 fmIndex 变更（如从画廊页或播放控制切歌回来）时临界阻尼平滑对齐至最近等效位置
    LaunchedEffect(safeIndex, fmSongs.size) {
        if (fmSongs.isEmpty() || isDragging || settleJob != null) return@LaunchedEffect
        val currentWrapped = FmCoverStackPhysics.wrapIndex(scrollAnim.value.roundToInt(), fmSongs.size)
        if (currentWrapped != safeIndex) {
            val eqTarget = FmCoverStackPhysics.nearestEquivalentScroll(scrollAnim.value, safeIndex, fmSongs.size)
            lastHapticAbsIndex = eqTarget.roundToInt()
            lastCommittedWrapped = safeIndex
            scrollAnim.animateTo(
                targetValue = eqTarget,
                animationSpec = spring(dampingRatio = 1.0f, stiffness = 240f, visibilityThreshold = 0.001f)
            )
        }
    }

    fun settleTo(
        targetAbs: Int,
        initialVelocityCardsPerSec: Float,
        hasMomentum: Boolean,
        commitPlayback: Boolean,
        isTap: Boolean = false,
    ) {
        settleJob?.cancel()
        activeTarget = targetAbs
        val (damping, stiffness) = when {
            isTap -> 1.0f to 225f
            hasMomentum -> 1.0f to 240f
            else -> 1.0f to 280f
        }
        val targetWrapped = if (fmSongs.isEmpty()) 0 else FmCoverStackPhysics.wrapIndex(targetAbs, fmSongs.size)
        pendingCommitTarget = if (commitPlayback) targetWrapped else null

        settleJob = scope.launch {
            try {
                scrollAnim.animateTo(
                    targetValue = targetAbs.toFloat(),
                    initialVelocity = initialVelocityCardsPerSec,
                    animationSpec = spring(
                        dampingRatio = damping,
                        stiffness = stiffness,
                        visibilityThreshold = 0.001f
                    )
                )
                if (commitPlayback && pendingCommitTarget == targetWrapped && targetWrapped != lastCommittedWrapped) {
                    lastCommittedWrapped = targetWrapped
                    onSwitchFm(targetWrapped)
                }
            } finally {
                if (activeTarget == targetAbs) {
                    activeTarget = null
                    settleJob = null
                }
            }
        }
    }

    val cardShape = remember { ContinuousRoundedRectangle(18.dp) }

    Box(
        modifier = modifier
            .height(176.dp)
            .clip(cardShape)
            .drawWithCache {
                val outline = cardShape.createOutline(size, layoutDirection, this)
                val borderStroke = Stroke(width = 0.5.dp.toPx())
                val glowCenter = Offset(size.width * 0.78f, size.height * 0.48f)
                val glowRadius = size.width * 0.54f
                onDrawWithContent {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(bgStartState.value, bgEndState.value),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height)
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glowState.value, Color.Transparent),
                            center = glowCenter,
                            radius = glowRadius
                        ),
                        center = glowCenter,
                        radius = glowRadius
                    )
                    drawContent()
                    drawOutline(
                        outline = outline,
                        color = borderState.value,
                        style = borderStroke
                    )
                }
            }
            .clickable(onClick = onOpenGallery)
            .padding(horizontal = 15.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useCompactStack = isCompactPhone || maxWidth < 310.dp
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FmCardInfoColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 15.dp),
                    currentSong = currentSong,
                    primaryTextState = primaryTextState,
                    secondaryTextState = secondaryTextState,
                    buttonContainerState = buttonContainerState,
                    buttonContentState = buttonContentState,
                    buttonGlowState = buttonGlowState,
                    listActionTextState = listActionTextState,
                    onOpenGallery = onOpenGallery,
                    onOpenFmList = onOpenFmList
                )

                FmFannedCoverStack(
                    isCompactPhone = useCompactStack,
                    isDark = isDark,
                    fmSongs = fmSongs,
                    scrollAnim = scrollAnim,
                    dragYAnim = dragYAnim,
                    pressScaleAnim = pressScaleAnim,
                    nowPlayingMediaIdProvider = nowPlayingMediaIdProvider,
                    isGlobalPlayingProvider = isGlobalPlayingProvider,
                    emptyCoverBg = targetPalette.emptyCoverBg,
                    emptyCoverBorder = targetPalette.emptyCoverBorder,
                    coverBorderProvider = { targetPalette.coverBorder },
                    onInterruptAnimation = {
                        settleJob?.cancel()
                        settleJob = null
                        activeTarget = null
                        pendingCommitTarget = null
                        isDragging = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            scrollAnim.stop()
                            dragYAnim.stop()
                        }
                    },
                    onDragFinish = { velocityCardsPerSec, netScroll, startScroll ->
                        isDragging = false
                        scope.launch {
                            pressScaleAnim.animateTo(1f, spring(dampingRatio = 1.0f, stiffness = 360f))
                        }
                        scope.launch {
                            dragYAnim.animateTo(0f, spring(dampingRatio = 0.92f, stiffness = 320f))
                        }
                        if (fmSongs.size <= 1) {
                            settleTo(
                                targetAbs = startScroll.roundToInt(),
                                initialVelocityCardsPerSec = 0f,
                                hasMomentum = false,
                                commitPlayback = false
                            )
                        } else {
                            val targetAbs = FmCoverStackPhysics.projectTarget(
                                currentScroll = scrollAnim.value,
                                velocityCardsPerSec = velocityCardsPerSec,
                                gestureNetScroll = netScroll,
                                startScroll = startScroll
                            )
                            val hasMomentum = abs(velocityCardsPerSec) > 0.6f || targetAbs != startScroll.roundToInt()
                            settleTo(
                                targetAbs = targetAbs,
                                initialVelocityCardsPerSec = velocityCardsPerSec,
                                hasMomentum = hasMomentum,
                                commitPlayback = true
                            )
                        }
                    },
                    onCardTap = {
                        if (fmSongs.size > 1) {
                            val base = activeTarget ?: scrollAnim.value.roundToInt()
                            val carryV = if (settleJob != null) scrollAnim.velocity else 0f
                            settleTo(
                                targetAbs = base + 1,
                                initialVelocityCardsPerSec = carryV,
                                hasMomentum = false,
                                commitPlayback = true,
                                isTap = true
                            )
                        } else {
                            onPlayCurrentFmToggle()
                        }
                    },
                    onPlayCurrentFmToggle = onPlayCurrentFmToggle
                )
            }
        }
    }
}

@Composable
private fun FmCardInfoColumn(
    modifier: Modifier,
    currentSong: KugouNewSong?,
    primaryTextState: State<Color>,
    secondaryTextState: State<Color>,
    buttonContainerState: State<Color>,
    buttonContentState: State<Color>,
    buttonGlowState: State<Color>,
    listActionTextState: State<Color>,
    onOpenGallery: () -> Unit,
    onOpenFmList: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = stringResource(id = R.string.library_fm_heading),
                color = primaryTextState.value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            if (currentSong != null) {
                AnimatedContent(
                    targetState = currentSong.hash,
                    transitionSpec = {
                        val enterSpec = tween<Float>(260, easing = FastOutSlowInEasing)
                        val exitSpec = tween<Float>(200, easing = FastOutSlowInEasing)
                        val enterSlideSpec = tween<IntOffset>(260, easing = FastOutSlowInEasing)
                        val exitSlideSpec = tween<IntOffset>(200, easing = FastOutSlowInEasing)
                        (fadeIn(enterSpec) + slideInVertically(enterSlideSpec) { it / 6 }) togetherWith
                            (fadeOut(exitSpec) + slideOutVertically(exitSlideSpec) { -it / 6 })
                    },
                    label = "fm_card_song_meta"
                ) { _ ->
                    Column {
                        Text(
                            text = currentSong.name,
                            color = primaryTextState.value,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = currentSong.author,
                            color = secondaryTextState.value,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(id = R.string.library_fm_empty),
                    color = secondaryTextState.value,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val baseTonal = MochiButtonDefaults.colors(MochiButtonStyle.Tonal)
            MochiButton(
                onClick = onOpenGallery,
                style = MochiButtonStyle.Tonal,
                colors = baseTonal.copy(
                    container = buttonContainerState.value,
                    content = buttonContentState.value,
                    glow = buttonGlowState.value
                ),
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.library_fm_enter_gallery),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Text(
                text = "${stringResource(id = R.string.library_fm_list)} ›",
                color = listActionTextState.value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenFmList)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FmFannedCoverStack(
    isCompactPhone: Boolean,
    isDark: Boolean,
    fmSongs: List<KugouNewSong>,
    scrollAnim: Animatable<Float, *>,
    dragYAnim: Animatable<Float, *>,
    pressScaleAnim: Animatable<Float, *>,
    nowPlayingMediaIdProvider: () -> String?,
    isGlobalPlayingProvider: () -> Boolean,
    emptyCoverBg: Color,
    emptyCoverBorder: Color,
    coverBorderProvider: () -> Color,
    onInterruptAnimation: () -> Unit,
    onDragFinish: (velocityCardsPerSec: Float, netScroll: Float, startScroll: Float) -> Unit,
    onCardTap: () -> Unit,
    onPlayCurrentFmToggle: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragStepPx = with(density) { FmCoverStackPhysics.dragStepDp(isCompactPhone).dp.toPx() }
    val verticalLimitPx = with(density) { FmCoverStackPhysics.VERTICAL_RUBBER_LIMIT_DP.dp.toPx() }

    val latestSongs = rememberUpdatedState(fmSongs)
    val latestInterrupt = rememberUpdatedState(onInterruptAnimation)
    val latestDragFinish = rememberUpdatedState(onDragFinish)

    val stageWidth = if (isCompactPhone) 130.dp else 150.dp
    val stageHeight = if (isCompactPhone) 138.dp else 150.dp
    val coverSize = if (isCompactPhone) 108.dp else 124.dp
    val cornerRadius = if (isCompactPhone) 13.dp else 15.dp
    val cornerShape = remember(cornerRadius) { ContinuousRoundedRectangle(cornerRadius) }
    val nodeSize = coverSize + FmCoverShadowPad * 2
    val shadowPadPx = with(density) { FmCoverShadowPad.toPx() }
    val sharedShadowImage = rememberFmCoverShadow(coverSize, cornerShape)
    val shadowBaseAlpha = if (isDark) 1.0f else 0.72f

    // 锚定最近整数槽位并维持 [-1..3] 5 卡缓冲窗口，避免回拉或轻微过冲时频繁销毁重建槽位
    val windowAnchor by remember {
        derivedStateOf { scrollAnim.value.roundToInt() }
    }

    Box(
        modifier = Modifier
            .width(stageWidth)
            .height(stageHeight)
            .pointerInput(dragStepPx) {
                val tracker = VelocityTracker()
                var pointerPos = Offset.Zero
                var startScroll = 0f
                var liveScroll = 0f
                var rawYAccum = 0f
                var netScroll = 0f
                var didDrag = false

                claimHorizontalDragGestures(
                    onStart = { down ->
                        didDrag = false
                        rawYAccum = 0f
                        netScroll = 0f
                        pointerPos = down.position
                        tracker.resetTracking()
                        tracker.addPosition(SystemClock.uptimeMillis(), pointerPos)
                    },
                    onDrag = { delta ->
                        if (!didDrag) {
                            didDrag = true
                            startScroll = scrollAnim.value
                            liveScroll = startScroll
                            rawYAccum = FmCoverStackPhysics.inverseRubberBand(dragYAnim.value, verticalLimitPx)
                            latestInterrupt.value()
                        }
                        pointerPos += delta
                        tracker.addPosition(SystemClock.uptimeMillis(), pointerPos)

                        val songs = latestSongs.value
                        val dScroll = -delta.x / dragStepPx
                        netScroll += dScroll
                        liveScroll += dScroll
                        rawYAccum += delta.y

                        val nextScroll = if (songs.size <= 1) {
                            startScroll + FmCoverStackPhysics.rubberBand(netScroll, 0.35f)
                        } else {
                            liveScroll
                        }
                        val dampedY = FmCoverStackPhysics.rubberBand(rawYAccum, verticalLimitPx)

                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            scrollAnim.snapTo(nextScroll)
                            dragYAnim.snapTo(dampedY)
                        }
                    },
                    onEnd = {
                        if (!didDrag) return@claimHorizontalDragGestures
                        val vxPxPerSec = tracker.calculateVelocity().x
                        val vCardsPerSec = (-vxPxPerSec / dragStepPx).coerceIn(-18f, 18f)
                        latestDragFinish.value(vCardsPerSec, netScroll, startScroll)
                    },
                    onCancel = {
                        if (!didDrag) return@claimHorizontalDragGestures
                        latestDragFinish.value(0f, netScroll, startScroll)
                    }
                )
            }
    ) {
        if (fmSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .offset(x = 2.dp, y = 10.dp)
                    .clip(cornerShape)
                    .background(emptyCoverBg)
                    .border(0.5.dp, emptyCoverBorder, cornerShape)
            )
        } else {
            val len = fmSongs.size
            // 从底到顶声明 5 个物理槽位：windowAnchor + 3 -> windowAnchor - 1
            for (slotOffset in 3 downTo -1) {
                val absIdx = windowAnchor + slotOffset
                val songIdx = FmCoverStackPhysics.wrapIndex(absIdx, len)
                val song = fmSongs[songIdx]

                key(absIdx) {
                    Box(
                        modifier = Modifier
                            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                            .size(nodeSize)
                            .graphicsLayer {
                                val relDepth = absIdx - scrollAnim.value
                                val t = FmCoverStackPhysics.cardTransform(relDepth, isCompactPhone)

                                // 主卡与第 2 张的 2D 垂直跟随在 relDepth == 0 处 C¹ 连续衔接，消除退场起手瞬跳
                                val extraY = dragYAnim.value * FmCoverStackPhysics.dragYWeight(relDepth)
                                val pressWeight = FmCoverStackPhysics.pressWeight(relDepth)
                                val pressFactor = 1f + (pressScaleAnim.value - 1f) * pressWeight

                                translationX = t.offsetXDp * density.density - shadowPadPx
                                translationY = t.offsetYDp * density.density + extraY - shadowPadPx
                                scaleX = t.scale * pressFactor
                                scaleY = t.scale * pressFactor
                                rotationZ = t.rotationZ
                                rotationY = t.rotationY
                                cameraDistance = 12f * density.density
                                alpha = t.alpha
                            }
                            .drawBehind {
                                val img = sharedShadowImage ?: return@drawBehind
                                val relDepth = absIdx - scrollAnim.value
                                val t = FmCoverStackPhysics.cardTransform(relDepth, isCompactPhone)
                                val shadowFactor = (t.shadowElevationDp / 10f).coerceIn(0f, 1f)
                                val drawAlpha = shadowBaseAlpha * shadowFactor
                                if (drawAlpha > 0.001f) {
                                    drawImage(
                                        image = img,
                                        topLeft = Offset.Zero,
                                        alpha = drawAlpha
                                    )
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(FmCoverShadowPad)
                                .clip(cornerShape)
                                .drawWithCache {
                                    val outline = cornerShape.createOutline(size, layoutDirection, this)
                                    val stroke = Stroke(width = 0.5.dp.toPx())
                                    onDrawWithContent {
                                        drawContent()
                                        drawOutline(
                                            outline = outline,
                                            color = coverBorderProvider(),
                                            style = stroke
                                        )
                                    }
                                }
                                .clickable(
                                    enabled = slotOffset == 0,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onCardTap
                                )
                        ) {
                            AsyncImage(
                                model = remember(song.artworkUrl) {
                                    ImageRequest.Builder(context)
                                        .data(song.artworkUrl)
                                        .size(320)
                                        .precision(Precision.INEXACT)
                                        .placeholder(R.drawable.placeholder_music_default_artwork)
                                        .error(R.drawable.placeholder_music_default_artwork)
                                        .fallback(R.drawable.placeholder_music_default_artwork)
                                        .build()
                                },
                                contentDescription = song.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(
                                        if (slotOffset in -1..1) {
                                            Modifier.artworkCornerScrim(0.dp) {
                                                FmCoverStackPhysics.focusOverlayProgress(absIdx - scrollAnim.value)
                                            }
                                        } else Modifier
                                    )
                            )
                            if (slotOffset in -1..1) {
                                BarePlayButton(
                                    isPlayingProvider = {
                                        val mediaId = nowPlayingMediaIdProvider()
                                        isGlobalPlayingProvider() && !mediaId.isNullOrBlank() &&
                                            (mediaId.equals(song.hash, ignoreCase = true) ||
                                                mediaId.endsWith(song.hash, ignoreCase = true))
                                    },
                                    onClick = {
                                        if (FmCoverStackPhysics.focusOverlayProgress(absIdx - scrollAnim.value) > 0.35f) {
                                            onPlayCurrentFmToggle()
                                        }
                                    },
                                    contentDescription = song.name,
                                    glyphSize = 30.dp,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .graphicsLayer {
                                            val p = FmCoverStackPhysics.focusOverlayProgress(absIdx - scrollAnim.value)
                                            alpha = p
                                            val s = 0.84f + 0.16f * p
                                            scaleX = s
                                            scaleY = s
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

private val FmCoverShadowPad = 22.dp

/**
 * 预渲染软件模糊的漫游电台封面阴影位图，避免 HWUI RenderNode 实时 3D shadowElevation
 * 在曲率圆角 Path + alpha/rotationY 动画下逐帧重算导致的闪烁。
 */
@Composable
private fun rememberFmCoverShadow(
    coverSize: Dp,
    cornerShape: ContinuousRoundedRectangle,
): ImageBitmap? {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val nodeSize = coverSize + FmCoverShadowPad * 2
    val nodeSizePx = with(density) { nodeSize.roundToPx() }
    val padPx = with(density) { FmCoverShadowPad.toPx() }
    val blurPx = with(density) { 9.dp.toPx() }
    val offsetYPx = with(density) { 4.dp.toPx() }
    val insetPx = with(density) { 1.dp.toPx() }

    return remember(nodeSizePx, padPx, cornerShape, layoutDirection, blurPx, offsetYPx, insetPx) {
        if (nodeSizePx <= 0) {
            null
        } else {
            val bitmap = android.graphics.Bitmap.createBitmap(
                nodeSizePx, nodeSizePx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb((0.36f * 255f).toInt(), 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(
                    blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL
                )
            }
            val shadowW = (nodeSizePx - padPx * 2f - insetPx * 2f).coerceAtLeast(1f)
            val outline = cornerShape.createOutline(Size(shadowW, shadowW), layoutDirection, density)
            canvas.save()
            canvas.translate(padPx + insetPx, padPx + insetPx + offsetYPx)
            when (outline) {
                is Outline.Generic -> canvas.drawPath(outline.path.asAndroidPath(), paint)
                is Outline.Rounded -> {
                    val path = Path().apply { addRoundRect(outline.roundRect) }
                    canvas.drawPath(path.asAndroidPath(), paint)
                }
                is Outline.Rectangle -> {
                    val r = outline.rect
                    canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
                }
            }
            canvas.restore()
            bitmap.asImageBitmap()
        }
    }
}

/**
 * 关注的艺人横向圆形头像列表：复用项目圆形头像描边与自适应布局。
 */
@Composable
fun LibraryFollowedArtistsRail(
    artists: List<KugouFollowedArtist>,
    containerWidth: Dp,
    onArtistClick: (KugouFollowedArtist) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layout = remember(containerWidth) {
        calculateAdaptiveImageLayout(
            containerWidth = containerWidth,
            startPadding = 20.dp,
            endPadding = 20.dp,
            minImageSize = 96.dp,
            spacing = 14.dp
        )
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(
            items = artists,
            key = { "followed_artist:${it.artistId}" },
            contentType = { "followed_artist_avatar" }
        ) { artist ->
            Column(
                modifier = Modifier
                    .width(layout.itemSize)
                    .clickable { onArtistClick(artist) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = remember(artist.avatarUrl) {
                        ImageRequest.Builder(context)
                            .data(artist.avatarUrl)
                            .crossfade(true)
                            .placeholder(R.drawable.songcredits_monogram_person)
                            .error(R.drawable.songcredits_monogram_person)
                            .fallback(R.drawable.songcredits_monogram_person)
                            .allowHardware(true)
                            .precision(Precision.INEXACT)
                            .size(320)
                            .build()
                    },
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            clip = true
                            shape = CircleShape
                        }
                        .drawWithCache {
                            val outline = CircleShape.createOutline(
                                Size(size.width, size.height),
                                LayoutDirection.Ltr,
                                density
                            )
                            onDrawWithContent {
                                drawContent()
                                drawOutline(
                                    outline = outline,
                                    color = Color.DarkGray.copy(alpha = 0.08f),
                                    style = Stroke(width = 6f)
                                )
                                drawOutline(
                                    outline = outline,
                                    color = Color.DarkGray.copy(alpha = 0.4f),
                                    style = Stroke(width = 6f),
                                    blendMode = BlendMode.Overlay
                                )
                            }
                        }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = artist.name,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.9f)
                )
            }
        }
    }
}

data class LibraryGridItem(
    val pinKey: String,
    val title: String,
    val subtitle: String,
    val coverData: Any?,
    val isPinned: Boolean,
    val canRemove: Boolean,
    val sharedCoverKey: String? = null,
)

/**
 * 自适应多列封面网格的单行条目，供外层 `LazyColumn` 虚拟化按行复用，
 * 避免切换 Tab 或初次展开时一次性组合全量歌单卡片。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryCollectionGridRow(
    items: List<LibraryGridItem>,
    columns: Int,
    coverSizePx: Int,
    isLastRow: Boolean,
    isItemPlaying: (String) -> Boolean,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onItemClick: (LibraryGridItem) -> Unit,
    onItemPlayClick: (LibraryGridItem) -> Unit,
    onItemLongClick: (LibraryGridItem) -> Unit,
    placeholderRes: Int = R.drawable.placeholder_music_default_artwork,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 20.dp,
                bottom = if (isLastRow) 0.dp else 20.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        items.forEach { item ->
            LibraryGridCard(
                item = item,
                coverSizePx = coverSizePx,
                isPlayingProvider = { isItemPlaying(item.pinKey) },
                placeholderRes = placeholderRes,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onClick = { onItemClick(item) },
                onPlayClick = { onItemPlayClick(item) },
                onLongClick = { onItemLongClick(item) },
                modifier = Modifier.weight(1f)
            )
        }
        repeat((columns - items.size).coerceAtLeast(0)) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun LibraryGridCard(
    item: LibraryGridItem,
    coverSizePx: Int,
    isPlayingProvider: () -> Boolean,
    placeholderRes: Int,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverBoxModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .then(
            if (sharedTransitionScope != null && animatedVisibilityScope != null && item.sharedCoverKey != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(key = item.sharedCoverKey),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            } else Modifier
        )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(modifier = coverBoxModifier) {
            ShadowImage(
                dataLambda = { item.coverData ?: placeholderRes },
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxSize()
                    .artworkCornerScrim(7.dp),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH,
                matchDisplaySize = true,
                targetSizePx = coverSizePx
            )
            BarePlayButton(
                isPlayingProvider = isPlayingProvider,
                onClick = onPlayClick,
                contentDescription = item.title,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (item.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = item.title,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.9f)
            )
        }
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.6f)
            )
        }
    }
}

/**
 * 本地音乐页导航行（歌曲 / 专辑 / 歌手），字号、图标与排版与经典旧版 `SmallLabelItem` 完全一致。
 */
@Composable
fun LibraryLocalNavRows(
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    onSongsClick: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LocalNavRow(
            icon = painterResource(id = R.drawable.ic_library_link_icon_songs),
            label = stringResource(id = R.string.page_library_songs),
            count = songCount,
            onClick = onSongsClick
        )
        LibraryDivider()
        LocalNavRow(
            icon = painterResource(id = R.drawable.ic_library_link_icon_album),
            label = stringResource(id = R.string.page_library_albums),
            count = albumCount,
            onClick = onAlbumsClick
        )
        LibraryDivider()
        LocalNavRow(
            icon = painterResource(id = R.drawable.ic_library_link_icon_artists),
            label = stringResource(id = R.string.page_library_artists),
            count = artistCount,
            onClick = onArtistsClick
        )
    }
}

@Composable
private fun LocalNavRow(
    icon: Painter,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 18.dp, end = 2.dp)
                .size(40.dp)
        )
        Text(
            text = label,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
            modifier = Modifier.padding(end = 8.dp)
        )
        Icon(
            painter = painterResource(id = R.drawable.ic_action_next),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 21.dp)
                .height(12.dp)
                .alpha(0.3f),
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * 歌单 / 收藏专辑长按操作菜单弹窗（播放、置顶、插播下一首、移出资料库）。
 */
@Composable
@NonSkippableComposable
fun LibraryCollectionContextDialog(
    item: LibraryGridItem,
    onPlayNow: () -> Unit,
    onTogglePin: () -> Unit,
    onPlayNext: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    SonifyDialog(
        title = item.title,
        onDismissRequest = onDismiss,
        positiveContent = stringResource(id = R.string.common_ok),
        onPositive = onDismiss,
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    AsyncImage(
                        model = remember(item.coverData) {
                            ImageRequest.Builder(context)
                                .data(item.coverData)
                                .size(140)
                                .precision(Precision.INEXACT)
                                .placeholder(R.drawable.placeholder_music_default_artwork)
                                .error(R.drawable.placeholder_music_default_artwork)
                                .fallback(R.drawable.placeholder_music_default_artwork)
                                .build()
                        },
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(0.5.dp, libraryCoverRingColor(), RoundedCornerShape(8.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.subtitle,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(libraryHairlineColor())
                )
                Spacer(Modifier.height(6.dp))

                DialogActionRow(
                    label = stringResource(id = R.string.library_ctx_play_now),
                    onClick = {
                        onDismiss()
                        onPlayNow()
                    }
                )
                DialogActionRow(
                    label = stringResource(
                        id = if (item.isPinned) R.string.library_ctx_unpin else R.string.library_ctx_pin
                    ),
                    onClick = {
                        onDismiss()
                        onTogglePin()
                    }
                )
                DialogActionRow(
                    label = stringResource(id = R.string.library_ctx_play_next),
                    onClick = {
                        onDismiss()
                        onPlayNext()
                    }
                )
                if (item.canRemove) {
                    DialogActionRow(
                        label = stringResource(id = R.string.library_ctx_remove),
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDismiss()
                            onRemove()
                        }
                    )
                }
            }
        }
    )
}

@Composable
internal fun libraryHairlineColor(): Color =
    Color(0xFF3C3C43).copy(alpha = 0.14f) withNight Color(0xFF545458).copy(alpha = 0.44f)

@Composable
internal fun libraryCoverRingColor(): Color =
    Color.Black.copy(alpha = 0.06f) withNight Color.White.copy(alpha = 0.11f)
