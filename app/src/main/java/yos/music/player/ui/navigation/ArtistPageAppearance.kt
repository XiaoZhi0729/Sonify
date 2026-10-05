package yos.music.player.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 一个注册页对底栏遮罩的贡献：页面背景色 + 其进出场过渡进度。 */
class ScrimRegistration internal constructor(
    val color: State<Color>,
    val transition: PageScrimTransition
)

/** 页面进出场进度：与页面 fadeIn/fadeOut 同帧同曲线，转场结束/被保留时 transitioning=false。 */
class PageScrimTransition(
    val progress: State<Float>,
    val transitioning: State<Boolean>
)

/** Entry-scoped colors keep retained tab pages from changing the active page's scrim. */
object ArtistPageAppearance {
    val activeEntryId = mutableStateOf<String?>(null)
    private val registrations: SnapshotStateMap<String, ScrimRegistration> = mutableStateMapOf()

    fun register(id: String, color: State<Color>, transition: PageScrimTransition): ScrimRegistration {
        val registration = ScrimRegistration(color, transition)
        registrations[id] = registration
        return registration
    }

    fun unregister(id: String, registration: ScrimRegistration) {
        // A departing composition must not remove a newer registration for the same entry.
        if (registrations[id] === registration) {
            registrations.remove(id)
        }
    }

    fun colorFor(id: String): Color? = registrations[id]?.color?.value

    /**
     * 底栏遮罩颜色 = 默认主题色之上，把每个「对当前画面生效」的注册页按其进出场
     * 过渡进度 lerp 到该页背景色。计入条件：progress>0 且（是当前活跃 entry，或
     * 转场仍在跑）。后者覆盖退场：pop 开始时 activeEntry 已翻到上一页，但艺人页
     * 还在组合里淡出；前者覆盖转场已结束的常驻页。被保留在离屏房子里的艺人页两者
     * 皆不满足，不参与混合。进度与页面淡入淡出出自同一个 AnimatedContent
     * Transition（同帧启动、同 spec），因此遮罩换色与页面进退场逐帧同步，
     * 不会出现"底栏先变、页面后变"。
     */
    fun blendedScrimColor(default: Color, activeId: String?): Color {
        var result = default
        for ((id, registration) in registrations) {
            val progress = registration.transition.progress.value
            if (progress <= 0f) continue
            if (id != activeId && !registration.transition.transitioning.value) continue
            result = lerp(result, registration.color.value, progress.coerceIn(0f, 1f))
        }
        return result
    }

}

/**
 * 取当前 destination 的进出场进度。NavHost 的 fadeIn/fadeOut 默认 spec 是
 * spring(1f, 400f, 0.01f)（反编译 animation 1.10.2 确认），这里的
 * Transition.animateFloat 挂在同一个 AnimatedContent Transition 上、用同 spec，
 * 故与页面淡入淡出逐帧一致；无转场（初始 destination/进程恢复）时 progress 恒为
 * 1f，transitioning 恒为 false。须在 NavHost 的 composable 内容 lambda
 * （AnimatedContentScope 接收者）内调用。
 */
@Composable
fun AnimatedContentScope.scrimTransition(): PageScrimTransition {
    val transition = this.transition
    val progress = with(transition) {
        animateFloat(
            transitionSpec = {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.01f
                )
            },
            label = "artistPageScrimProgress"
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    }
    val transitioning = remember(transition) {
        derivedStateOf { transition.currentState != transition.targetState }
    }
    return remember(transition, progress, transitioning) {
        PageScrimTransition(progress, transitioning)
    }
}
