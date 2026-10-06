package yos.music.player.ui.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.OnBackCompletedFallback

/**
 * 每个 Tab（house）私有的返回事件派发器（同时实现新旧两套 owner 接口）。
 *
 * 背景（侧滑串栈 bug）：运行时 activity-compose 解析为 1.13.0（1.9.1 被传递依赖拉高），
 * 其 BackHandler 优先经 LocalNavigationEventDispatcherOwner 解析，为空才回退
 * LocalOnBackPressedDispatcherOwner / ViewTree。而 activity 1.13 会在 decor view 上
 * 挂根 NavigationEventDispatcher，ViewTree 查找永远命中——三个 house 的 NavHost
 * 内置 BackHandler（enabled = 栈深>1）全部落在同一个根 dispatcher 上，隐藏 house
 * 栈更深时优先吃掉系统返回，表现为「滑好几次才生效、切回 Tab 发现页面被莫名 pop」。
 * 仅 provide 旧 local 无效（新 local 优先级更高）。
 *
 * 修复：house 子树 provide 本 owner（新 local 优先命中），house 内所有 BackHandler
 * （NavHost 内置 + 弹层）注册进私有 dispatcher，与 activity 根 dispatcher 无级联。
 * 系统返回只经 AppTabsShell 的唯一 bridge 转发进当前 house；隐藏 house 物理上吃不到
 * 返回事件。house 为空（已在根且无弹层）时走 onBackCompletedFallback 兜底。
 * dispatchBack 只发 backCompleted：未 started 时 NavigationEventInput 会自动合成
 * started→completed（与 activity 1.13 OnBackPressedDispatcher.onBackPressed 同构）。
 */
class HouseBackDispatcherOwner(
    private val onEmptyFallback: () -> Unit
) : NavigationEventDispatcherOwner, OnBackPressedDispatcherOwner {

    override val navigationEventDispatcher: NavigationEventDispatcher =
        NavigationEventDispatcher(
            object : OnBackCompletedFallback {
                override fun onBackCompletedFallback() {
                    onEmptyFallback()
                }
            }
        )

    private val directInput = DirectNavigationEventInput()

    init {
        navigationEventDispatcher.addInput(directInput)
    }

    /** 把一次系统返回派发给本 house 当前优先级最高且 enabled 的回调。 */
    fun dispatchBack() {
        directInput.backCompleted()
    }

    // 旧体系兜底：BackHandler 1.13 解析到新 local 后不再读它；保留接口使 house 子树
    // 内潜在的旧 API 消费者不至于向上穿透回 activity 级 dispatcher（那会复现串栈）。
    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle =
        LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
}
