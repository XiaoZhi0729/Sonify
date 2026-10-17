package yos.music.player.ui.pages.library.artists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.circleEdgeLight

/**
 * 动作区半透明圆钮（不消费 backdrop）：处于 pageBackdrop 录制层内部，
 * 任何 drawBackdrop 消费者都会构成自引用递归崩溃，故用实色半透明圆。
 * 填充默认半透明白，透明度与「最新专辑」卡片同值（浅 0.22 / 深 0.12）；
 * 图标统一白色；高亮（highlighted，如简介展开）分支保持不变。
 *
 * [containerColor] 非 null 时覆盖默认白色半透明底——供底色不是主题色的页面
 * （如在线专辑详情的封面色底）按自身文字色取底，浅色封面上也保持可见。
 *
 * 从 ArtistDetail 抽出共享，歌手页与在线专辑详情页同源。
 */
@Composable
internal fun ArtistRoundActionButton(
    icon: Int,
    contentDescription: String?,
    iconTint: Color,
    highlighted: Boolean,
    enabled: Boolean = true,
    iconSize: Dp = 23.dp,
    highlightContainerColor: Color? = null,
    motionEffects: Boolean = true,
    dimWhenDisabled: Boolean = true,
    containerColor: Color? = null,
    onClick: () -> Unit
) {
    ArtistRoundShell(
        highlighted = highlighted,
        enabled = enabled,
        motionEffects = motionEffects,
        dimWhenDisabled = dimWhenDisabled,
        containerColor = containerColor,
        highlightContainerColor = highlightContainerColor,
        onClick = onClick
    ) { dim ->
        Icon(
            painter = painterResource(id = icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = iconColorOf(iconTint, dim)
        )
    }
}

/**
 * 「加入资料库」圆钮：外壳与 [ArtistRoundActionButton] 完全同源，图标换成
 * [CollectIconMorph]——加号与勾号互相**形变**（不是换图），并自带两个状态的
 * 无障碍文案（album_collect_add / album_collect_remove）。
 *
 * 与其它圆钮不同，这里**没有禁用态变暗**：图标任何时刻都是纯白 100%
 * （禁用态的半透明白压在深色底上会被看成一层灰黑遮罩）。
 */
@Composable
internal fun ArtistCollectButton(
    collected: Boolean,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
    iconTint: Color = Color.Unspecified,
    containerColor: Color? = null,
    motionEffects: Boolean = true,
    onClick: () -> Unit
) {
    ArtistRoundShell(
        highlighted = false,
        enabled = enabled,
        motionEffects = motionEffects,
        // 恒不透明：用户定版「图标任何时刻都是纯白 100%」，禁用态也不许乘 0.45
        // （半透明白落在深色底上会被看成一层灰黑遮罩）
        dimWhenDisabled = false,
        containerColor = containerColor,
        highlightContainerColor = null,
        onClick = onClick
    ) { dim ->
        CollectIconMorph(
            collected = collected,
            contentDescription = stringResource(
                id = if (collected) {
                    R.string.album_collect_remove
                } else {
                    R.string.album_collect_add
                }
            ),
            tint = iconColorOf(iconTint, dim),
            iconSize = iconSize,
            animated = motionEffects
        )
    }
}

/**
 * 42dp 半透明圆钮外壳（底色 / 白描边 / 按压回缩 / 点击），
 * 图标由 [content] 决定，并把禁用态的颜色系数 [dim] 交回给图标自行上色。
 */
@Composable
private fun ArtistRoundShell(
    highlighted: Boolean,
    enabled: Boolean,
    motionEffects: Boolean,
    dimWhenDisabled: Boolean,
    containerColor: Color?,
    highlightContainerColor: Color?,
    onClick: () -> Unit,
    content: @Composable BoxScope.(dim: Float) -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "artistActionPress")
    // 禁用态用**颜色 alpha** 调暗，不用 Modifier.alpha：alpha 会额外建一层 graphicsLayer，
    // 本机（ColorOS）上按钮先以禁用态组合、随后 enabled 翻转时这层会把底色画丢，
    // 表现为「圆钮只剩图标、没有圆底」，退出重进（首帧即 enabled）才恢复。
    val dim = if (dimWhenDisabled && !enabled) 0.45f else 1f
    val container = when {
        highlighted -> highlightContainerColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> containerColor ?: Color.White.copy(alpha = if (isDark) 0.12f else 0.22f)
    }
    Box(
        modifier = Modifier
            .then(if (motionEffects) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .size(42.dp)
            .clip(CircleShape)
            .background(container.copy(alpha = container.alpha * dim))
            // 圆钮高光：上下同强的纯色白描边（用户定版 alpha 0.1）
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.10f * dim),
                shape = CircleShape
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(dim)
    }
}

/** 图标色：未指定时用白色（圆钮材质固定白图标），再乘禁用态系数。 */
private fun iconColorOf(iconTint: Color, dim: Float): Color =
    (if (iconTint != Color.Unspecified) iconTint else Color.White).copy(alpha = dim)

/**
 * 播放胶囊（白底 + 深色实心三角 + 文字），对齐 Apple Music 专辑页的「播放」按钮。
 *
 * 与 [ArtistPlayButton]（白色镂空圆，孔洞透出背景）是两个形态：圆形用于歌手页
 * 三圆钮行，胶囊用于在线专辑详情的操作行。底色恒为白色，故内容色固定深色，
 * 不随页面文字色翻转（深色封面下白字会看不见）。
 */
@Composable
internal fun PlayPillButton(
    label: String,
    enabled: Boolean = true,
    containerColor: Color = Color.White,
    contentColor: Color = Color(0xFF161616),
    onClick: () -> Unit
) {
    // 同上：禁用态用颜色 alpha 调暗，避免额外 graphicsLayer 在 enabled 翻转时丢底色
    val dim = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(containerColor.copy(alpha = containerColor.alpha * dim))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.button_icon_play),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = contentColor.copy(alpha = contentColor.alpha * dim)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = contentColor.copy(alpha = contentColor.alpha * dim),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 播放圆钮：EvenOdd 几何镂空，孔洞始终透出真实背景（不依赖离屏清除）。
 * 从原三圆钮行内联抽出，供竖向三圆钮行与平板横屏头部复用。
 */
@Composable
internal fun ArtistPlayButton(enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(64.dp)
            .drawBehind {
                // 三角形：左移 4% 圆角放大；右侧尖角圆角必须沿上下两边的切线方向收缩
                // （控制点落在边的方向上），否则会呈现「尖角外贴圆点」的伪影（用户截图实锤）
                val path = Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w * 0.37f, h * 0.33f)
                    cubicTo(w * 0.37f, h * 0.2925f, w * 0.389f, h * 0.28f, w * 0.42f, h * 0.299f)
                    lineTo(w * 0.678f, h * 0.475f)
                    cubicTo(w * 0.701f, h * 0.491f, w * 0.701f, h * 0.510f, w * 0.677f, h * 0.525f)
                    lineTo(w * 0.42f, h * 0.695f)
                    cubicTo(w * 0.389f, h * 0.714f, w * 0.37f, h * 0.701f, w * 0.37f, h * 0.664f)
                    close()
                }
                val button = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(Rect(Offset.Zero, size))
                    addPath(path)
                }
                drawPath(button, color = Color.White)
            }
            .circleEdgeLight(Color.White.copy(alpha = 0.8f), 0.15.dp, 0.5.dp)
            .semantics { contentDescription = context.getString(R.string.normal_button_play) }
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    )
}
