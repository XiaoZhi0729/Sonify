package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator

private val OptionGap = 72.dp

/**
 * 深浅色模式选择：两张迷你手机预览图 + 单选点（样式参照 Cresto 的 ColorModeSelector）。
 *
 * [currentModeProvider] 必须是"在本组合项体内调用"的读取 lambda（与 SwitchItem 的
 * checkedLambda 同款模式）：真机验证过，在设置页 LazyColumn 的 item 作用域里以
 * 参数形式求值的自定义持久化状态读取不会建立订阅（开关写入后选项不刷新），
 * 而子作用域内的 lambda 读取可以即时重组。
 * 取值与 SettingsLibrary.CustomTheme 一致："Auto" / "Dark" / "Light"。
 * Auto 时预览图压暗到 0.6 并禁点，选中态跟随系统当前生效的模式。
 */
@Composable
fun ColorModeOptions(
    currentModeProvider: () -> String,
    systemInDarkTheme: Boolean,
    onModeChange: (String) -> Unit
) {
    val currentMode = currentModeProvider()
    val isAuto = currentMode == "Auto"
    val effectiveMode =
        if (currentMode == "Dark" || currentMode == "Light") currentMode
        else if (systemInDarkTheme) "Dark" else "Light"

    val optionsAlpha by animateFloatAsState(
        targetValue = if (isAuto) 0.6f else 1f,
        animationSpec = tween(200),
        label = "colorModeOptionsAlpha"
    )
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .graphicsLayer { alpha = optionsAlpha },
        horizontalArrangement = Arrangement.Center
    ) {
        ColorModeOption(
            selected = effectiveMode == "Light",
            enabled = !isAuto,
            imageRes = R.drawable.appearance_light,
            label = stringResource(R.string.settings_performance_ui_theme_light),
            onSelect = {
                Vibrator.click(context)
                onModeChange("Light")
            }
        )
        Spacer(Modifier.width(OptionGap))
        ColorModeOption(
            selected = effectiveMode == "Dark",
            enabled = !isAuto,
            imageRes = R.drawable.appearance_dark,
            label = stringResource(R.string.settings_performance_ui_theme_dark),
            onSelect = {
                Vibrator.click(context)
                onModeChange("Dark")
            }
        )
    }
}

@Composable
private fun ColorModeOption(
    selected: Boolean,
    enabled: Boolean,
    imageRes: Int,
    label: String,
    onSelect: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect
        )
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = label,
            modifier = Modifier
                .width(64.dp)
                .height(128.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(10.dp))
        ModeRadioDot(selected = selected)
    }
}

/**
 * 单选点：选中/未选中外形尺寸一致（20dp 圆），未选中=灰描边空心圈，
 * 选中=主题色实心+白勾，只做透明度过渡，不发生大小跳动。
 */
@Composable
private fun ModeRadioDot(selected: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "colorModeRadio"
    )
    val ringColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val fillColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(24.dp)) {
        val radius = 10.dp.toPx()
        val stroke = 2.dp.toPx()

        drawCircle(
            color = androidx.compose.ui.graphics.lerp(ringColor, fillColor, progress),
            radius = radius,
            style = Stroke(width = stroke)
        )
        if (progress > 0f) {
            drawCircle(
                color = fillColor,
                radius = radius - stroke / 2,
                alpha = progress
            )
            val check = Path().apply {
                moveTo(7.6.dp.toPx(), 12.2.dp.toPx())
                lineTo(10.8.dp.toPx(), 15.4.dp.toPx())
                lineTo(16.4.dp.toPx(), 8.8.dp.toPx())
            }
            drawPath(
                path = check,
                color = Color.White.copy(alpha = progress),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
