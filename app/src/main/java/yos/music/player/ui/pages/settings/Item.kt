package yos.music.player.ui.pages.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.liquid.YosSwitch
import yos.music.player.ui.widgets.dropdown.LiquidOverlayDropdownPopup
import yos.music.player.ui.widgets.dropdown.dropdownTriggerAlpha
import yos.music.player.ui.widgets.dropdown.dropdownTriggerMeasure
import yos.music.player.ui.widgets.dropdown.rememberDropdownTriggerState


@Composable
fun SelectItem(
    enabled: Boolean = true,
    enabledProvider: (() -> Boolean)? = null,
    title: String,
    desc: String? = null,
    items: List<String>,
    onValueChange: (String) -> Unit,
    value: @Composable () -> String
) {
    // 设置页 LazyColumn 的 item 作用域里把 SettingsLibrary（DataSaver 状态）当参数求值读取，
    // 写入后该作用域不重组（见 settings-datasaver-state-read-scope-trap）。所以 enabled / value
    // 都改为在本组合项体内通过 provider 读取，写入才能即时让本项重组（置灰、数值立即更新）。
    val resolvedEnabled = enabledProvider?.invoke() ?: enabled
    val resolvedValue = value()
    val expanded = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val pageBackdrop = LocalTitlePageBackdrop.current
    val show = expanded.value && resolvedEnabled
    // 触发区（选中值 + 箭头）：弹窗从这块内容原位长成面板，展开后硬切隐藏（原版同款）
    val trigger = rememberDropdownTriggerState(show)

    Box(Modifier.fillMaxWidth()) {
        DefaultItem(enabled = resolvedEnabled, title = title, desc = desc, onClick = {
            expanded.value = !expanded.value
            if (expanded.value) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        }) {
            // 弹窗必须组合在锚点节点的**直接子布局内**：ListPopupLayout 的零尺寸 Spacer
            // 读 parentLayoutCoordinates 得锚点 bounds（原版 OverlayDropdownMenu 同构）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.dropdownTriggerMeasure(trigger),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 值文字 40% 透明是本行原有观感（原 contentAlpha 也只在 0.4 基线上淡出）
                    modifier = Modifier
                        .alpha(0.4f)
                        .dropdownTriggerAlpha(trigger),
                ) {
                    Text(text = resolvedValue, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_up_down),
                        contentDescription = "choose",
                        modifier = Modifier.height(16.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                LiquidOverlayDropdownPopup(
                    show = show,
                    onDismissRequest = { expanded.value = false },
                    onDismissFinished = trigger::onDismissFinished,
                    backdrop = pageBackdrop,
                    collapseSize = trigger.size,
                    collapseContent = {
                        DropdownValueCollapseContent(resolvedValue)
                    },
                ) {
                    items.forEachIndexed { index, item ->
                        key(item) {
                            LiquidDropdownRow(
                                text = item,
                                selected = item == resolvedValue,
                                onClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onValueChange(item)
                                    expanded.value = false
                                },
                                isFirst = index == 0,
                                isLast = index == items.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 收起态里的「当前值 + 箭头」副本（没有 0.4 alpha 的置灰：那是弹窗外那份的语义）。 */
@Composable
private fun DropdownValueCollapseContent(value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(0.4f),
    ) {
        Text(text = value, fontSize = 15.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            painter = painterResource(id = R.drawable.arrow_up_down),
            contentDescription = null,
            modifier = Modifier.height(16.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
fun LabelItem(
    enabled: Boolean = true,
    title: String,
    desc: String? = null,
    superLink: Boolean = false,
    showArrow: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)?
) =
    DefaultItem(
        enabled = enabled,
        title = title,
        titleHighLight = superLink,
        desc = desc,
        leading = leading,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        if (!superLink && showArrow) {
            Icon(
                painter = painterResource(id = R.drawable.ic_action_next), contentDescription = title,
                modifier = Modifier.height(11.dp),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
            )
        }
    }

@Composable
fun SwitchItem(
    enabled: Boolean = true,
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)?,
    checkedLambda: () -> Boolean
) {
    val context = LocalContext.current
    DefaultItem(enabled = enabled, title = title, desc = desc, onClick = null) {
        /*Switch(checkedLambda = checkedLambda, onValueChange = {
            if (onClick != null) {
                Vibrator.click(context)
            }
            onClick?.invoke()
        })*/

        YosSwitch(
            checked = checkedLambda(),
            onCheckedChange = { _ ->
                if (onClick != null) {
                    Vibrator.click(context)
                }
                onClick?.invoke()
            },
            modifier = Modifier.height(25.dp),
            switchHeight = 25.dp,
            checkedState = checkedLambda
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DefaultItem(
    enabled: Boolean = true,
    title: String,
    titleHighLight: Boolean = false,
    desc: String? = null,
    // 行首插图位（开发者头像等）。必须放在 onClick 之前：全仓调用点都用尾随 lambda 绑 backIcon，
    // 新参数若追加在末尾会把那个 lambda 变成别的类型，编译直接炸。
    leading: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)?,
    backIcon: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick == null && onLongClick == null -> Modifier
                    onClick == null -> Modifier.combinedClickable(enabled = enabled, onLongClick = onLongClick) { }
                    else -> Modifier.combinedClickable(enabled = enabled, onLongClick = onLongClick) {
                        onClick()
                    }
                }
            )
            .padding(horizontal = 15.dp, vertical = 11.dp)
            .graphicsLayer {
                if (!enabled) {
                    // compositingStrategy = CompositingStrategy.Offscreen
                    alpha = 0.6f
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(Modifier.padding(end = 12.dp)) {
                leading()
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .align(Alignment.CenterVertically)
                .alpha(0.94f)
        ) {
            if (titleHighLight) {
                Text(
                    text = title,
                    fontSize = 16.5.sp,
                    lineHeight = 20.5.sp,
                    // fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = title,
                    fontSize = 16.5.sp,
                    lineHeight = 20.5.sp,
                    // fontWeight = FontWeight.Medium,
                )
            }

            if (desc != null) {
                Text(
                    text = desc,
                    fontSize = 13.2.sp,
                    lineHeight = 16.2.sp,
                    color = LocalContentColor.current.copy(alpha = 0.5f),
                )
            }
        }
        Column(
            Modifier.padding(start = 15.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.End
        ) {
            YosWrapper {
                backIcon?.invoke()
            }
        }
    }
}

/*
@Composable
fun Switch(
    checkedLambda: () -> Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchHeight: Dp = 28.dp,
    switchWidth: Dp = 50.dp,
    trackColor: Color = Color(0x1A333333) withNight Color(0x1AF1F1F1),
    trackColorChecked: Color = MaterialTheme.colorScheme.primary,
    thumbColor: Color = Color(0xFFFFFFFF),
    thumbColorChecked: Color = Color(0xFFFFFFFF),
    animationDuration: Int = 150
) {
    val switchPadding = (switchHeight - 14.5.dp) / 2
    val thumbSize = switchHeight - switchPadding * 2
    val thumbPosition = animateFloatAsState(
        if (checkedLambda()) 1f else 0f, spring(0.65f)
    )
    val animatedTrackColor = animateColorAsState(
        if (checkedLambda()) trackColorChecked else trackColor,
        tween(durationMillis = animationDuration)
    )
    val animatedThumbColor = animateColorAsState(
        if (checkedLambda()) thumbColorChecked else thumbColor,
        tween(durationMillis = animationDuration)
    )
    Box(
        modifier
            .size(switchWidth, switchHeight)
            .clip(RoundedCornerShape(switchHeight / 2))
            .background(animatedTrackColor.value)
            .clickable(indication = null,
                interactionSource = remember {
                    MutableInteractionSource()
                }) { onValueChange(!checkedLambda()) }
            .padding(switchPadding)
    ) {
        val density = LocalDensity.current
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(x = with(density) {
                        ((switchWidth - switchHeight) * thumbPosition.value)
                            .toPx()
                            .toInt()
                    }, y = 0)
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(animatedThumbColor.value)
        )
    }
}*/
