package yos.music.player.ui.pages.library.artists

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import yos.music.player.ui.widgets.basic.rawNavigationBarsBottomDp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight

/**
 * 艺人简介悬浮玻璃面板（ArtistDetail 内联组合在页面根 Box 顶层：
 * 页面本身在 NavHost 内，底栏与迷你播放器渲染在其后 → 层级天然「高于页面内容、
 * 低于底栏/迷你条」，无需弹层宿主）。
 *
 * 视觉：悬浮卡片——屏幕四边各留 14dp 空隙、四角平滑圆角（kyant shapes
 * [RoundedRectangle] + Continuous）；「收起」胶囊加宽居中。
 *
 * 动效：scrim 淡入 + 面板从屏幕下方弹入，主曲线与播放页壳层一致
 * （spring(stiffness=400, dampingRatio=1)，MainActivity 壳层同款曲线）；
 * 位移使用 0..1 屏高比例，收尾阈值为 0.001f，不能沿用像素位移的 1f。
 * 退出反向收尾后才从组合卸载（internalVisible 模式，同 LiquidDropdownLayout）。
 * 玻璃：API≥33 时 vibrancy + blur 采样 [backdrop]（种子 = 页面动画底色），
 * 液态玻璃开关再决定 lens/高光；API<33 实色面板兜底。
 * 关闭途径：收起胶囊 / 点压暗区 / 返回键；i 按钮高亮态与 visible 同源。
 */
@Composable
fun ArtistIntroSheet(
    visible: Boolean,
    title: String,
    content: String,
    backdrop: Backdrop?,
    onDismiss: () -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val liquidGlass = SettingsLibrary.BarBlurEffect
    // 四角平滑圆角（iOS 连续曲率），与全项目 RoundedRectangle 用法同源
    val sheetShape = RoundedRectangle(24.dp, RoundedCornerStyle.Continuous)
    val containerColor = Color(0xFFF2F2F7) withNight Color(0xFF232326)
    val contentColor = Color(0xFF1C1C1E) withNight Color(0xFFF2F2F7)
    val pillColor = Color.Black.copy(alpha = 0.06f) withNight Color.White.copy(alpha = 0.12f)
    val glassSurfaceAlpha = if (isDark) 0.8f else 0.72f

    val latestDismiss by rememberUpdatedState(onDismiss)
    var internalVisible by remember { mutableStateOf(false) }
    // 0 = 贴合展示位，1 = 整体位移一个屏高（完全离屏）
    val offsetYFraction = remember { Animatable(1f) }
    val scrimAlpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            internalVisible = true
            launch {
                offsetYFraction.animateTo(0f, spring(stiffness = 400f, dampingRatio = 1f, visibilityThreshold = 0.001f))
            }
            launch { scrimAlpha.animateTo(1f, tween(260)) }
        } else if (internalVisible) {
            launch { scrimAlpha.animateTo(0f, tween(200)) }
            offsetYFraction.animateTo(1f, spring(stiffness = 400f, dampingRatio = 1f, visibilityThreshold = 0.001f))
            internalVisible = false
        }
    }

    if (!visible && !internalVisible) return

    BackHandler(enabled = visible) { latestDismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(2f)
    ) {
        // 压暗层：点外关闭
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { latestDismiss() })
                }
        )

        BoxWithConstraints(
            Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            val density = LocalDensity.current
            val rootHeightPx = with(density) { maxHeight.toPx() }
            val isWideMiniBar = LocalConfiguration.current.screenWidthDp >= 600
            val miniPlayerHeight = if (isWideMiniBar) 62.dp else 43.dp
            val navigationBarHeight = rawNavigationBarsBottomDp()
            val bottomBarHeight = 58.dp
            // MainActivity 横屏时底栏与迷你条并排；竖屏（包括平板）上下堆叠。
            val isSplitMode = maxWidth > maxHeight
            val bottomAvoidance = navigationBarHeight + if (isSplitMode) {
                maxOf(bottomBarHeight, miniPlayerHeight)
            } else {
                bottomBarHeight + 5.dp + miniPlayerHeight
            }
            val panelMaxHeight = minOf(
                maxHeight * if (maxHeight < 500.dp) 0.88f else 0.66f,
                (maxHeight - bottomAvoidance - 28.dp).coerceAtLeast(0.dp)
            )
            val isGlassPath = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val glassModifier = if (isGlassPath) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { sheetShape },
                    effects = {
                        vibrancy()
                        blur(24f.dp.toPx())
                        if (liquidGlass) lens(48f.dp.toPx(), 48f.dp.toPx())
                    },
                    highlight = {
                        if (liquidGlass) Highlight.Default.copy(style = HighlightStyle.Default(angle = 90f))
                        else null
                    },
                    shadow = { null },
                    onDrawSurface = { drawRect(containerColor.copy(alpha = glassSurfaceAlpha)) }
                )
            } else {
                Modifier
                    .clip(sheetShape)
                    .background(containerColor.copy(alpha = 0.97f))
            }

            // 避让放在材质外侧，面板与底栏/迷你条仍相隔 14dp。
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = bottomAvoidance + 14.dp)
                    .heightIn(max = panelMaxHeight)
                    .graphicsLayer { translationY = offsetYFraction.value * rootHeightPx }
                    .then(glassModifier)
                    .semantics {
                        paneTitle = title
                        dismiss {
                            latestDismiss()
                            true
                        }
                    }
                    // 面板自身吃掉点击，避免穿透到压暗层造成双关闭
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(horizontal = 22.dp)
                    .padding(top = 20.dp, bottom = 12.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = content,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = contentColor.copy(alpha = 0.78f),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(Modifier.height(16.dp))
                // 「收起」胶囊：加宽（min 160dp）居中，面板内最底层，点击关闭
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(min = 160.dp)
                        .clip(sheetShape)
                        .background(pillColor)
                        .clickable { latestDismiss() }
                        .padding(horizontal = 30.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.artist_detail_intro_collapse),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
