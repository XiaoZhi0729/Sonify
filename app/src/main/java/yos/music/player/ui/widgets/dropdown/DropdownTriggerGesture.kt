package yos.music.player.ui.widgets.dropdown

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================
// 触发区「长按展开 + 不停手直接滑选」
//
// 需求：点过按钮打开过的用户，下一次更可能还是点。所以触发区要能长按 0.5s 直接长出
// 面板，手指不抬起继续滑，松手即选中。
//
// 为什么必须由**触发区**的手势节点全程持有指针：面板是展开后新组合出来的节点，永远
// 收不到这次已经发生的 down。Compose 不能把进行中的手势转交，只能"谁先按下谁负责到底"。
//
// 为什么不能只挂一个"长按检测"再让它和 clickable 共存（本文件第一版的写法）：
// 1.0.x 起 `Modifier.clickable` 走 ClickableNode 的直接指针节点，它在 Main pass 里
// 无条件消费 down、在 up 时**先 consume 再无条件 onClick**，不看别人有没有消费过。
// 也就是说"长按后 consume 掉 up 来取消那次点击"根本不成立 —— 长按抬手会再触发一次
// onClick（设置页那种取反语义会当场把菜单关掉）。所以本修饰符**替代** clickable，
// 点按与长按由同一个手势做仲裁，与工程内既有结论一致：
// 「菜单项不能再挂 clickable —— 两个手势状态互相抢事件正是跳状态的根因」。
//
// 命中沿用面板那套状态（[DropdownPanelDragSelectState]）：项的登记、高亮绘制
// （[dropdownPanelEntry]）全部复用，本文件只负责"用哪根手指的坐标去问"。
// =====================================================================

/**
 * 触发区手势状态。
 *
 * [dragSelect] 必须与弹层**共享同一个实例**：触发区靠它命中，面板靠它登记区间并画高亮。
 * 面板侧不需要任何"给谁下发"的通道 —— 由调用方把同一个对象分别交给触发区与弹层即可。
 */
@Stable
class DropdownTriggerGestureState(
    val dragSelect: DropdownPanelDragSelectState,
) {
    /**
     * 是否正被按住。给调用方画按压反馈用 —— 本修饰符替代了 clickable，
     * 也就一并接管了它的水波/按压态（多个调用点原本就 indication = null，
     * 需要反馈的（设置页行）靠这个状态自己画）。
     */
    var pressed by mutableStateOf(false)
        internal set
}

/** @param dragSelect 与弹层共享的滑选状态（弹层的 `dragSelectState` 参数传同一个实例） */
@Composable
fun rememberDropdownTriggerGestureState(
    dragSelect: DropdownPanelDragSelectState,
): DropdownTriggerGestureState = remember(dragSelect) { DropdownTriggerGestureState(dragSelect) }

/**
 * 触发区节点的 LayoutCoordinates。命中要把手指位置换成 root 坐标，而这一层节点自己
 * 也在动（面板展开时按钮会位移/缩放/淡出），所以**每次事件都现算**，不能冻结按下时的原点。
 */
private class TriggerCoords {
    var value: LayoutCoordinates? = null
}

/**
 * 触发区手势：**替代** `clickable` 使用，不要两者叠在同一个节点上。
 *
 * 行为：
 * - 按下后未达长按时限、未越过 touchSlop 就抬起 → [onTap]（就是原来的点击）；
 * - 按住达到平台长按时限（Android 默认 500ms，且尊重无障碍设置）→ [onExpand] 展开，
 *   之后本次手势接管指针，逐帧命中面板项；松手执行命中的项；
 * - 长按成立后**必须真实位移超过 touchSlop** 才允许提交：面板是从触发区原位长出来的，
 *   手指天然落在某一行上，不设这道守卫的话"长按→原地抬手"会选中一个用户没想选的行；
 * - 一屏装不下的长菜单直接放弃滑选（返回后用户照常点行）：纵向手势该归面板滚动，
 *   而面板的滚动由它自己的手势层负责，本次按下并不属于它。
 *
 * @param expanded 现读是否已展开。**不能捕获** —— `pointerInput(Unit)` 的 lambda
 *   只在首次组合跑一次，捕获会得到陈旧值（[dropdownPanelDragSelect] 同一条教训）。
 *   N.B. 只有 `expanded()` 不够：收起动画期间 show 已是 false、面板仍在渲染，
 *   所以内部另外用面板进度兜底（见 [DropdownPanelDragSelectState.currentFraction]）。
 * @param onTap 点按（未长按）。传 null = 本修饰符只负责长按，点按交给原 clickable
 *   （仅在无法替换 call site 的场合使用；此时长按抬手仍可能被 clickable 二次触发）。
 * @param onExpand 长按成立时展开弹层
 */
fun Modifier.dropdownTriggerGesture(
    state: DropdownTriggerGestureState,
    expanded: () -> Boolean,
    onTap: (() -> Unit)? = null,
    onExpand: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val currentExpanded = rememberUpdatedState(expanded)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnExpand = rememberUpdatedState(onExpand)
    val coords = remember { TriggerCoords() }

    Modifier
        .onGloballyPositioned { coords.value = it }
        .pointerInput(state) {
            val longPressMs = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 已展开（或收起动画还没走完）：本次按下不归本修饰符，交给面板自己
                if (currentExpanded.value.invoke() || state.dragSelect.currentFraction() > 0.01f) {
                    return@awaitEachGesture
                }
                // **按下即消费**，与 `Modifier.clickable` 完全一致，这是防穿透的关键：
                // 本控件通常是某一行（歌曲行、设置行）的子节点，行自己挂着 clickable。
                // 父层 ClickableNode 用 `isChangedToDown(requireUnconsumed = true)` 认领按下，
                // 只有 down 被消费它才不会武装自己的点击 —— 旧实现漏了这一步，
                // 点三个点会同时点开菜单**并**让整行播放（实机复现）。
                // 注意只消费 down：后续 move 必须保持未消费，外层列表的滚动
                // （awaitTouchSlopOrCancellation 见到消费即放弃）才能从本控件上起手。
                down.consume()
                state.pressed = true
                var budget = longPressMs
                var lastUptime = down.uptimeMillis
                var longPressed = false
                var released = false
                var aborted = false
                var lastPos = down.position
                // 面板长到可命中那一刻的手指位置：提交前要求从这里走够一段距离
                var readyAnchor: Offset? = null
                var lastHit: DropdownPanelEntry? = null
                try {
                    while (true) {
                        // 长按前必须有超时：手指静止时根本没有指针事件，光靠轮询等不到
                        val event = if (longPressed) {
                            awaitPointerEvent()
                        } else {
                            withTimeoutOrNull(budget) { awaitPointerEvent() }
                        }
                        if (event == null) {
                            longPressed = true
                            // 清一次：面板里可能留着上一次会话的命中残留
                            state.dragSelect.clear()
                            currentOnExpand.value.invoke()
                            continue
                        }
                        val change = event.changes.fastFirstOrNull { it.id == down.id }
                        if (change == null) {
                            aborted = true
                            break
                        }
                        if (!longPressed) {
                            val delta = change.uptimeMillis - lastUptime
                            if (delta > 0) {
                                budget -= delta
                                lastUptime = change.uptimeMillis
                            }
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            // 被别的节点接管（外层滚动/翻页）：让出去，别跟它抢
                            if (event.changes.any { it.isConsumed }) {
                                aborted = true
                                break
                            }
                            if ((change.position - down.position).getDistance() > slop) {
                                aborted = true
                                break
                            }
                            continue
                        }
                        // ── 长按之后：本次手势归我们 ──
                        change.consume()
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        if (!state.dragSelect.fitsOnScreen) {
                            // 一屏装不下：纵向手势该归面板滚动。放弃本次滑选，
                            // 面板保持展开，用户松手后照常点行
                            aborted = true
                            break
                        }
                        lastPos = change.position
                        if (!state.dragSelect.isReadyForHitTest()) continue
                        if (readyAnchor == null) readyAnchor = change.position
                        val root = rootPosition(coords.value, change.position)
                        if (root != null) {
                            // root 坐标命中：换算（减面板原点）收在状态里，调用点不必各自记得
                            val hit = state.dragSelect.hitTest2DInRoot(root.x, root.y)
                            if (hit != null && hit !== lastHit) {
                                // 仅"换了一项"才震，与面板内滑选同一手感
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            lastHit = hit
                        }
                    }
                    val drag = state.dragSelect
                    if (longPressed) {
                        val anchor = readyAnchor
                        val movedEnough = anchor != null &&
                                (lastPos - anchor).getDistance() > slop
                        // 用缓存的本帧命中，不重新读 selected：
                        // 项离开组合时 unregister 会把 selected 置 null（DropdownPanelDragSelectState），
                        // 松手重读会让动作静默丢失
                        val commit = if (drag.fitsOnScreen && movedEnough) lastHit else null
                        drag.clear()
                        commit?.action?.invoke()
                    } else if (released && !aborted) {
                        currentOnTap.value?.invoke()
                    }
                } finally {
                    state.pressed = false
                    if (longPressed) state.dragSelect.clear()
                }
            }
        }
}

/** 节点局部坐标 → root 坐标。节点已被移出组合/尚未布局时返回 null（本帧不命中）。 */
private fun rootPosition(
    coords: LayoutCoordinates?,
    local: Offset,
): Offset? {
    if (coords == null || !coords.isAttached) return null
    return coords.localToRoot(local)
}
