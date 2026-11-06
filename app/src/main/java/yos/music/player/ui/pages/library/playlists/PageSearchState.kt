package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * 详情页页内搜索的全部状态与副作用（在线歌单 / 在线专辑共用）。
 *
 * 抽成一个 holder 是因为两个页面的搜索逻辑完全一致（防抖、拼音组合门禁、
 * 立即聚焦、收起清理、返回键分流），各抄一遍必然走样。
 *
 * 用法：
 * ```
 * val search = rememberPageSearch(resetKey = playlistId)
 * if (search.expanded) ... // 展开态
 * BackHandler(enabled = search.expanded) { search.dismiss() }
 * ```
 */
@Composable
internal fun rememberPageSearch(
    // 换页（换歌单/换专辑）时自动收起并清空，避免状态残留到下一个页面
    resetKey: Any?,
    // 是否允许搜索（无权限时不渲染入口，同时强制收起）
    enabled: Boolean = true
): PageSearchState {
    val state = remember { PageSearchState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 形变进度：0 = 折叠成圆钮，1 = 铺满整行
    val morph = animateFloatAsState(
        targetValue = if (state.expanded) 1f else 0f,
        animationSpec = SearchMorphSpec,
        label = "pageSearchMorph"
    )

    // 聚焦/键盘与收起清理。
    //
    // 展开：不等形变收敛 —— 点击当帧后等一个真实绘制帧就 requestFocus，
    // 让键盘立刻出现（形变要跑 300ms，等它结束用户会觉得没反应）。
    // 节点可能还没 attach，隔帧重试最多三次。
    //
    // 用 snapshotFlow + drop(1)：首次组合（expanded 初始为 false）不该跑收起分支，
    // 否则会把刚进页面的焦点抢到搜索钮上、还调一次 hide()。
    LaunchedEffect(Unit) {
        snapshotFlow { state.expanded }.drop(1).collect { expanded ->
            if (expanded) {
                for (attempt in 0 until 3) {
                    withFrameNanos { }
                    if (runCatching { state.fieldFocus.requestFocus() }.isSuccess) break
                }
                keyboard?.show()
            } else {
                snapshotFlow { morph.value }.first { it <= 0.005f }
                state.query = TextFieldValue("")
                state.keyword = ""
                runCatching { focusManager.clearFocus(force = true) }
                keyboard?.hide()
                runCatching { state.buttonFocus.requestFocus() }
            }
        }
    }

    // 防抖 250ms；拼音组合中（composition 未提交）不检索。
    //
    // 判据用 composition == null || collapsed，而不是 != null：英文输入法会把正在输入的
    // 整个单词标成 composing 区间（长单词期间一直非 null），部分国产输入法提交候选后也
    // 不清区间；只用 != null 会让这些情况下搜索结果一直不更新。长度为 0 的非 null 区间
    // （"空组合区"）同样应视为已上屏。
    LaunchedEffect(state.query.text, state.query.composition) {
        val composing = state.query.composition?.let { !it.collapsed } == true
        if (composing) return@LaunchedEffect
        val keyword = state.query.text.trim()
        if (keyword == state.keyword) return@LaunchedEffect
        if (keyword.isEmpty()) {
            state.keyword = ""
            return@LaunchedEffect
        }
        delay(250)
        state.keyword = keyword
    }

    LaunchedEffect(resetKey) {
        state.collapse()
    }
    LaunchedEffect(enabled) {
        if (!enabled) state.collapse()
    }

    // 把形变进度交给状态容器，页面与行组件都从这里取，避免两处各建一个动画
    state.morph = morph

    return state
}

/**
 * 页内搜索的状态容器。
 *
 * 字段都是 Compose 可观察状态，直接读即可触发重组；[expanded] 是「用户意图」，
 * 形变进度另由外部动画驱动（见 [rememberPageSearch] 的返回值 morph）。
 */
internal class PageSearchState {
    var expanded by mutableStateOf(false)
        private set

    /** 输入框内容。用 TextFieldValue 是为了拿到 composition（拼音未上屏判断）。 */
    var query by mutableStateOf(TextFieldValue(""))

    /** 防抖后的关键词：空串表示不过滤（展示完整列表）。 */
    var keyword by mutableStateOf("")

    /** 输入框焦点。 */
    val fieldFocus = FocusRequester()

    /** 折叠态圆钮焦点：收起后焦点回到这里。 */
    val buttonFocus = FocusRequester()

    /**
     * 形变进度（0 = 圆钮，1 = 铺满整行）。
     *
     * 由 [rememberPageSearch] 填充：动画必须只建一份，页面（算过滤结果、顶栏返回分流）
     * 与操作行（逐控件驱动）读同一个 State。
     */
    var morph: State<Float> = mutableStateOf(0f)
        internal set

    /** 当前是否处于过滤态（有关键词）。 */
    val filtering: Boolean get() = keyword.isNotEmpty()

    fun open() {
        // 防重复触发：形变进行中忽略（收敛后才可以再次展开）
        expanded = true
    }

    fun dismiss() {
        if (!expanded) return
        expanded = false
    }

    /** 清空输入但保持展开与聚焦（清空钮用）。 */
    fun clearQuery() {
        query = TextFieldValue("")
        keyword = ""
    }

    /** 立即收起并清空（换页/失去权限用，不走动画等待）。 */
    fun collapse() {
        expanded = false
        query = TextFieldValue("")
        keyword = ""
    }
}
