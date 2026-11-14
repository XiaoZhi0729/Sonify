package yos.music.player.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.mochi.MochiBlurText
import yos.music.player.ui.widgets.mochi.MochiButton
import yos.music.player.ui.widgets.mochi.MochiButtonColors
import yos.music.player.ui.widgets.mochi.MochiSegmentedControl
import yos.music.player.ui.widgets.mochi.MochiSegmentedDefaults

/**
 * 主键：直接采用 `mochi-compose` 的 [MochiButton] 与 [MochiBlurText]：
 * - 按下弹跳放大、跟手偏移形变与跟手径向高光；
 * - 切屏改文案时使用 snappy 模糊缩放交叉替换效果。
 */
@Composable
internal fun PrimaryButton(
    text: String,
    colors: OnboardingColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current

    MochiButton(
        onClick = {
            Vibrator.click(context)
            onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Ob.Gutter),
        enabled = enabled,
        colors = MochiButtonColors(
            container = colors.brand,
            content = colors.onBrand,
            outline = Color.Transparent,
            pressedContainer = Color.Transparent,
            glow = Color.White.copy(alpha = if (colors.dark) 0.22f else 0.35f),
            disabledContainer = colors.brand.copy(alpha = 0.34f),
            disabledContent = colors.onBrand.copy(alpha = 0.72f),
        ),
        minHeight = Ob.PrimaryHeight,
    ) {
        MochiBlurText(
            text = text,
            style = TextStyle(
                fontSize = Ob.ButtonSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = Ob.ButtonTracking,
            ),
            maxLines = 1,
            contentAlignment = Alignment.Center,
        )
    }
}

/**
 * 次键：一行文字，无底无边。
 *
 * ★命中区只包文字 + 内边距，**不吃 `fillMaxWidth`**。
 *   上一版调用点传 `fillMaxWidth()` 想让文字居中，结果整行宽都可点：
 *   用户点屏幕左右边缘都会「跳过登录」。居中交给外层 Box 的 contentAlignment。
 */
@Composable
internal fun TextLink(
    text: String,
    colors: OnboardingColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    Vibrator.click(context)
                    onClick()
                }
                .padding(vertical = Ob.S10, horizontal = Ob.S20),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = colors.ink3,
                fontSize = Ob.DescSize,
            )
        }
    }
}

/**
 * 纯文字分段控件（歌词字号 小/中/大 / 玻璃渲染方式）。
 * 底层直接采用 `mochi-compose` 的 [MochiSegmentedControl]（连续曲率弹簧滑块 + 拖尾拉伸 + 按压抬升发光）。
 */
@Composable
internal fun OnboardingTextSegmented(
    options: List<String>,
    selected: Int,
    colors: OnboardingColors,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = MochiSegmentedControl(
    selectedIndex = selected,
    onSelect = onSelect,
    items = options,
    modifier = modifier.fillMaxWidth(),
    colors = MochiSegmentedDefaults.colors(
        track = colors.track,
        thumb = colors.indicator,
        selectedContent = colors.ink,
        content = colors.ink2,
    ),
)

/**
 * 发丝线。不是又一个灰色：它永远是"当前 ink 的 10%"，深浅切换时跟着墨色一起过渡。
 */
@Composable
internal fun Hairline(colors: OnboardingColors, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Ob.Hairline)
            .background(colors.hairline)
    )
}

/**
 * 卡片底板：圆角 + 卡片色 + 1.5dp 描边。
 *
 * 引导页的"控件行"装进 22dp 的 [ControlCard]（见 OnboardingPreview.kt），
 * 但这个函数仍然需要：云端互通说明卡、本机扫描卡、扫码弹层都是**独立的物件**，
 * 它们不是"设置行"，必须有边界告诉用户"这一块是一件事"。
 *
 * 判据：卡片是为了**分组**或**标示一个独立物件**；一行一个开关那种不需要。
 */
@Composable
internal fun OnboardingCard(
    colors: OnboardingColors,
    modifier: Modifier = Modifier,
    corner: Dp = Ob.CardCorner,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(YosRoundedCornerShape(corner))
            .background(colors.card)
            .border(1.5.dp, colors.cardStroke, YosRoundedCornerShape(corner)),
        content = content,
    )
}

/**
 * 胶囊次键（有底无边）：用在一张卡里当"另一个入口"，例如「改用酷狗 App 扫码登录」。
 *
 * 与 [TextLink]（无底）的分工：
 *   · [TextLink] 是**页面级**的次键，住在底栏的 40dp 槽位里。
 *   · 本函数是**卡片内**的次键，它是那张卡的一部分，所以要有底、要跟卡同色系。
 */
@Composable
internal fun CapsuleLink(
    text: String,
    colors: OnboardingColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                Vibrator.click(context)
                onClick()
            }
            .padding(horizontal = Ob.S16, vertical = Ob.S10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ob.S6),
    ) {
        leading?.invoke()
        Text(
            text = text,
            color = colors.ink,
            fontSize = Ob.DescSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
