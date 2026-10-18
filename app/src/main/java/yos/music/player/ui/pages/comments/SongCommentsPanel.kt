package yos.music.player.ui.pages.comments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouCommentRepository
import yos.music.player.data.repositories.KugouCommentRepository.CommentReplyNode
import yos.music.player.data.repositories.KugouCommentRepository.KugouComment
import yos.music.player.ui.pages.PlayerPageHeaderRow
import yos.music.player.ui.pages.PlayerPageHeaderText
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.effects.overlayEffect

/**
 * 真实软键盘高度（px）：小于 80dp 一律当 0。
 *
 * 本机 HyperOS 上系统 inset 派发本来就不可靠（见 RawWindowInsets.kt），ime inset 也会
 * 带出几 dp~几十 dp 的噪声；若把噪声当成键盘高度，输入框会被推上去一截。
 * 真机软键盘高度都在 200dp 以上，80dp 阈值足够区分。
 *
 * 全程用 px 比较：dp 换算会把亚 dp 的抖动舍成同值，动画目标反而更容易反复触发。
 */
@Composable
private fun imeBottomPx(): Int {
    val density = LocalDensity.current
    val px = WindowInsets.ime.getBottom(density)
    return if (px > with(density) { 80.dp.roundToPx() }) px else 0
}

/** 从任意 Compose Context 找回宿主 Activity（评论面板可能被 ContextWrapper 包裹）。 */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 单条评论上限：客户端先拦，避免上游 400 被统一压成「发送失败」。 */
private const val CommentMaxLength = 200

/** 输入框抬起后与键盘上沿之间的留白。严格贴住键盘时，药丸下边缘会和键盘工具条粘成一条。 */
private val KeyboardGap = 10.dp

/** 相对时间文案：结构由数据层给，语言由 string resource 决定。 */
@Composable
private fun commentTimeText(timeSec: Long): String =
    when (val time = KugouCommentRepository.commentTime(timeSec)) {
        KugouCommentRepository.CommentTime.JustNow ->
            stringResource(id = R.string.nowplaying_comments_time_just_now)
        is KugouCommentRepository.CommentTime.Minutes ->
            stringResource(id = R.string.nowplaying_comments_time_minutes, time.value)
        is KugouCommentRepository.CommentTime.Hours ->
            stringResource(id = R.string.nowplaying_comments_time_hours, time.value)
        is KugouCommentRepository.CommentTime.Days ->
            stringResource(id = R.string.nowplaying_comments_time_days, time.value)
        is KugouCommentRepository.CommentTime.Date -> time.text
    }

@Composable
private fun likesText(likes: Int): String =
    stringResource(
        id = R.string.nowplaying_comments_likes,
        KugouCommentRepository.formatLikes(
            likes,
            androidx.compose.ui.text.intl.Locale.current.language
        )
    )

/**
 * 播放页评论区面板（歌曲评论）。
 *
 * 数据链：KugouCommentRepository → 本地 Rust server `/comment/...`。
 * 视觉：白字体系（播放页 contentColor = White），不使用 MaterialTheme 颜色；
 * 不使用任何 drawBackdrop/玻璃采样（本页在 layerBackdrop 录制子树内）。
 *
 * 版面：没有键盘时的让位完全交给宿主（竖屏让开控件带、横屏让开底栏），面板自己
 * 只负责「键盘弹出后还需要额外让出多少」——按自己底边到窗口底的真实距离算，
 * 与宿主用的是哪套让位无关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongCommentsPanel(
    hash: String?,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(hash) {
        if (hash.isNullOrBlank()) CommentUiSession()
        else commentUiSessions.getOrPut(hash) { CommentUiSession() }
    }
    val state = remember(hash) { SongCommentsState(scope, session) }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    // ── 关掉系统对本页的整窗平移 ──────────────────────────────────────────
    // 本面板的 lift 已经把输入框抬到键盘上沿；若系统再按 adjustPan 平移整个窗口，
    // 两笔补偿会叠加——输入框被顶到屏幕中部，同一窗口里的封面/标题/控件也一起上移。
    // 而且 pan 只在收到新的 bringIntoView 请求时重算，用户一打字光标移动触发重算、
    // pan 归零，于是出现「点输入框整体抬高、打一个字又落回来」。
    // 只在评论面板组合期间生效，onDispose 还原：搜索页/登录页等没有任何 IME 处理的
    // 页面仍然依赖系统自动让位，全局改会把它们的输入框埋到键盘下面。
    val hostWindow = remember(context) { context.findActivity()?.window }
    DisposableEffect(hostWindow) {
        val previousMode = hostWindow?.attributes?.softInputMode
        hostWindow?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        )
        onDispose {
            if (previousMode != null) hostWindow.setSoftInputMode(previousMode)
        }
    }

    LaunchedEffect(hash) {
        state.bind(hash)
        // bind() 的同步重置会清掉展开态与回复目标，恢复必须排在它之后
        state.restoreSession()
    }

    var text by remember(hash) { mutableStateOf(session.draft) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Pair<KugouComment, String?>?>(null) }
    var pendingMore by remember { mutableStateOf<Pair<KugouComment, String?>?>(null) }
    val focusRequester = remember { FocusRequester() }
    val listState = remember(hash) {
        LazyListState(session.firstVisibleIndex, session.firstVisibleOffset)
    }

    // 离开评论页前把 UI 会话写回：评论页在 AnimatedContent 内，切页即销毁
    DisposableEffect(hash) {
        onDispose {
            session.draft = text
            session.sortMode = state.sortMode
            session.firstVisibleIndex = listState.firstVisibleItemIndex
            session.firstVisibleOffset = listState.firstVisibleItemScrollOffset
            session.expanded = state.expandedContents.toSet()
            session.replyTarget = state.replyTarget
        }
    }

    // ── 键盘让位：只抬输入框，面板本身一动不动 ─────────────────────────────
    //
    // lift = max(0, 键盘高度 + 键盘上方留白 − 面板底边到窗口底的距离)
    //
    // 1) 「面板底边到窗口底的距离」量的是面板【外框】：外框尺寸由宿主的 fillMaxSize
    //    决定，内部的 offset / padding 改不动它，所以不会出现
    //    「补得多 → 量得更高 → 补得更多」的反馈环。
    //    竖屏这条距离 ≈ 进度条位置（300dp+，本来就比键盘高）→ lift = 0，一动不动；
    //    平板横屏这条距离 ≈ 分栏下沿那 84dp → lift ≈ 键盘高 − 84dp，正好贴键盘。
    // 2) 面板不再加 bottom padding：列表视口高度恒定，只把 lift 加进列表的
    //    contentPadding。以前给整个 Column 加 padding，键盘一弹整块面板被压缩，
    //    标题留在原地、列表被挤成一条缝——就是「内容被顶出去」的观感。
    // 3) lift 走 180ms 缓动：输入法在「空态 / 输入态」之间改高度时（候选栏出现、
    //    工具栏收起），输入框是滑过去贴住键盘，而不是跳一下。
    // 4) 输入框不贴着键盘：留 KeyboardGap，否则抬起后药丸下边缘和键盘上沿粘在一起，
    //    视觉上分不清「输入框」和「键盘工具条」。
    val rootView = LocalView.current
    val density = LocalDensity.current
    // 面板底边到窗口底的距离（px），-1 = 还没量到
    var panelBottomPx by remember { mutableIntStateOf(-1) }
    // 量基线时的窗口高度：窗口尺寸变了（旋转/分屏/多窗口）必须重量基线
    var baselineWindowPx by remember { mutableIntStateOf(-1) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val imePx = imeBottomPx()
    val keyboardGapPx = with(density) { KeyboardGap.roundToPx() }
    // 只在键盘收起时更新基线。原因：播放页收起到迷你条时，外壳会把整页往下挪
    // （MainActivity 的 offset = (parentHeight − shellHeight)/2），面板底边在窗口里的
    // 位置逐帧变化；若键盘此时还开着，拿它算 lift 就会让输入框和发送键在收起过程中
    // 自己上下来回漂。键盘不在时 lift 恒为 0，基线随便更新都没有副作用。
    // 还没量到基线时（面板刚进组合）无论键盘在不在都量一次，否则会一直没基线。
    val trackBaseline = imePx == 0 || panelBottomPx < 0
    val desiredLiftPx = if (imePx > 0 && panelBottomPx >= 0) {
        // + 键盘上方留白：输入框停在这条留白的上沿，不贴着键盘工具条
        (imePx + keyboardGapPx - panelBottomPx).coerceAtLeast(0)
    } else {
        0
    }
    // 4dp 死区：输入法派发的键盘高度带几 px 抖动，逐帧重定目标会让输入框持续微抖，
    // 观感就是"一上下移动"。小于死区不动；归零永远立刻执行。
    val deadbandPx = with(density) { 4.dp.roundToPx() }
    var liftTargetPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(desiredLiftPx, deadbandPx) {
        if (desiredLiftPx == 0 ||
            kotlin.math.abs(desiredLiftPx - liftTargetPx) > deadbandPx
        ) {
            liftTargetPx = desiredLiftPx
        }
    }
    val liftPx by animateIntAsState(
        targetValue = liftTargetPx,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "CommentsImeLift"
    )
    val lift = with(density) { liftPx.toDp() }
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val status = state.status
    // 评论池 id（childrenid）拿到之前不渲染输入框：只有 mixsongid 时 Rust 还要先反查
    // special_id，反查请求失败就会把「无法解析歌曲评论 special_id」这种内部错误抛给用户。
    val canComment = state.childrenId.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                // 尺寸为 0 的那一帧位置没有意义，丢掉，等下一次布局。
                if (rootView.height <= 0 || coords.size.height <= 0) {
                    return@onGloballyPositioned
                }
                // 负值意味着面板底边已经越过窗口底（播放页收起时整页被往下挪过），
                // 按 0 处理：此时「面板已经在窗口底以下」，不需要额外让位。
                val next = (rootView.height - coords.boundsInWindow().bottom)
                    .toInt().coerceAtLeast(0)
                // 键盘开着时冻结基线（见上面 trackBaseline 的注释），
                // 但窗口尺寸真的变了（旋转/分屏）就必须重量，否则基线会一直是错的。
                val windowResized = rootView.height != baselineWindowPx
                if (trackBaseline || windowResized) {
                    if (next != panelBottomPx) panelBottomPx = next
                    if (rootView.height != baselineWindowPx) {
                        baselineWindowPx = rootView.height
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CommentsHeader(
                total = state.total,
                showTotal = status == "ok",
                sortMode = state.sortMode,
                // 空态/加载态没有可切换的内容；错误态保留胶囊，用户才能切回另一种排序自救
                showSort = status != "empty" && status != "loading",
                sortLoading = state.sortLoading,
                onSortChanged = { state.switchSort(it) }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    status == "loading" -> CommentsStatus(loading = true)
                    status.startsWith("error:") -> CommentsStatus(
                        message = status.removePrefix("error:"),
                        onRetry = { state.retry() }
                    )
                    status == "empty" -> CommentsStatus(
                        emptyText = when {
                            state.localSong -> stringResource(id = R.string.nowplaying_comments_local_song)
                            state.unavailable -> stringResource(id = R.string.nowplaying_comments_unavailable)
                            else -> null
                        }
                    )
                    else -> PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = { state.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CommentsList(
                            state = state,
                            listState = listState,
                            // 输入框抬起来后压住的那一段要能在列表里滚出来
                            bottomExtra = lift,
                            // 渐隐跟着输入框一起抬。列表视口底边本来就在输入框常驻占位
                            // （Spacer(composerHeight)）之上，抬升 lift 后输入框顶边
                            // 正好落在「视口底边 − lift」，所以这里只减 lift，长度仍是 24dp。
                            bottomFadeInsetPx = { liftPx.toFloat() },
                            onReply = { top, reply ->
                                state.beginReply(top, reply)
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                            onDelete = { comment, topId -> pendingDelete = comment to topId },
                            onMore = { comment, topId -> pendingMore = comment to topId }
                        )
                    }
                }
            }

            // 输入框常态占位。输入框是浮层（见下），不参与这个 Column 的布局，
            // 这里只留出它的高度，列表底边仍然落在输入框上沿。
            Spacer(modifier = Modifier.height(composerHeight))
        }

        if (canComment) {
            CommentsComposer(
                text = text,
                onTextChange = {
                    if (it.length <= CommentMaxLength) {
                        text = it
                        session.draft = it
                    }
                },
                replyToName = state.replyTarget?.displayName,
                sending = state.sending,
                maxLength = CommentMaxLength,
                focusRequester = focusRequester,
                onTapInput = {
                    focusRequester.requestFocus()
                    keyboard?.show()
                },
                onCancelReply = { state.cancelReply() },
                onSend = {
                    val content = text.trim()
                    if (content.isEmpty() || state.sending) return@CommentsComposer
                    if (!KugouAccountState.isLoggedIn) {
                        showLoginDialog = true
                        return@CommentsComposer
                    }
                    scope.launch {
                        when (val outcome = state.submit(content)) {
                            SubmitOutcome.Success -> {
                                text = ""
                                session.draft = ""
                                keyboard?.hide()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.nowplaying_comments_sent),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            SubmitOutcome.NeedVerify -> Toast.makeText(
                                context,
                                context.getString(R.string.nowplaying_comments_verify_required),
                                Toast.LENGTH_SHORT
                            ).show()
                            SubmitOutcome.InvalidContent -> Toast.makeText(
                                context,
                                context.getString(R.string.nowplaying_comments_invalid_content),
                                Toast.LENGTH_SHORT
                            ).show()
                            is SubmitOutcome.Failure -> Toast.makeText(
                                context,
                                outcome.message ?: context.getString(R.string.nowplaying_comments_send_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { composerHeightPx = it.height }
                    .offset { IntOffset(0, -liftPx) }
            )
        }
    }

    if (showLoginDialog) {
        SonifyDialog(
            title = stringResource(id = R.string.nowplaying_comments_login_required),
            message = null,
            onDismissRequest = { showLoginDialog = false },
            positiveContent = stringResource(id = R.string.nowplaying_comments_login),
            onPositive = {
                showLoginDialog = false
                keyboard?.hide()
                onOpenLogin()
            },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { showLoginDialog = false },
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    }

    // 本人评论的「…」入口：长按是隐藏手势，这里给一个看得见的操作入口
    pendingMore?.let { (comment, topId) ->
        SonifyDialog(
            title = stringResource(id = R.string.nowplaying_comments_actions),
            message = if (comment.content.length > 80) comment.content.take(80) + "…" else comment.content,
            onDismissRequest = { pendingMore = null },
            positiveContent = stringResource(id = R.string.nowplaying_comments_delete_action),
            onPositive = {
                pendingMore = null
                pendingDelete = comment to topId
            },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { pendingMore = null },
            tertiaryContent = stringResource(id = R.string.nowplaying_comments_copy),
            onTertiary = {
                clipboard.setText(AnnotatedString(comment.content))
                pendingMore = null
                Toast.makeText(
                    context,
                    context.getString(R.string.nowplaying_comments_copied),
                    Toast.LENGTH_SHORT
                ).show()
            },
            destructive = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    }

    pendingDelete?.let { (comment, topId) ->
        SonifyDialog(
            title = stringResource(id = R.string.nowplaying_comments_delete),
            message = stringResource(id = R.string.nowplaying_comments_delete_confirm),
            onDismissRequest = { pendingDelete = null },
            positiveContent = stringResource(id = R.string.nowplaying_comments_delete_action),
            onPositive = {
                pendingDelete = null
                scope.launch {
                    val ok = state.deleteOwn(comment, topId)
                    if (!ok) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.nowplaying_comments_delete_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { pendingDelete = null },
            destructive = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    }
}

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

private enum class CommentSortMode { Hottest, Latest }

private class ReplyTarget(val top: KugouComment, val reply: KugouComment?) {
    val displayName: String get() = (reply ?: top).username
}

/**
 * 评论页 UI 会话：评论页在 AnimatedContent 里，切到歌词/播放列表后整棵子树销毁。
 * 这里按歌曲 hash 保留草稿、排序、滚动位置、展开态与回复目标，回来时恢复。
 * 只存内存，不落盘（与 commentCache 同策略，规避 Gson 混淆键名漂移）。
 */
private class CommentUiSession {
    var draft: String = ""
    var sortMode: CommentSortMode = CommentSortMode.Hottest
    var firstVisibleIndex: Int = 0
    var firstVisibleOffset: Int = 0
    var expanded: Set<String> = emptySet()
    var replyTarget: ReplyTarget? = null
}

private val commentUiSessions = object : LinkedHashMap<String, CommentUiSession>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommentUiSession>?): Boolean =
        size > 8
}

internal sealed class SubmitOutcome {
    data object Success : SubmitOutcome()
    data object NeedVerify : SubmitOutcome()
    /** 上游 400：内容超长/敏感词等，需要用户改文案。 */
    data object InvalidContent : SubmitOutcome()
    data class Failure(val message: String?) : SubmitOutcome()
}

@Stable
private class FloorState {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var initialized by mutableStateOf(false)
    var replies by mutableStateOf<List<KugouComment>>(emptyList())
    var page by mutableIntStateOf(1)
    var hasMore by mutableStateOf(false)
    var message by mutableStateOf("")
    var total by mutableIntStateOf(0)
}

/** 同曲重进时的内存快照（不落盘：规避 Gson 混淆键名漂移）。 */
private data class Snapshot(
    val mixSongId: Long,
    val childrenId: String,
    val total: Int,
    val latest: List<KugouComment>,
    val hottest: List<KugouComment>,
    val singer: List<KugouComment>,
    val latestPage: Int,
    val hottestPage: Int,
    val latestHasMore: Boolean,
    val hottestHasMore: Boolean
)

private val commentCache = object : LinkedHashMap<String, Snapshot>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean =
        size > 8
}

@Stable
private class SongCommentsState(
    private val scope: CoroutineScope,
    private val session: CommentUiSession
) {

    var songKey by mutableStateOf("")
        private set
    var mixSongId by mutableLongStateOf(0L)
        private set
    var childrenId by mutableStateOf("")
        private set
    var total by mutableIntStateOf(0)
        private set
    var sortMode by mutableStateOf(session.sortMode)
        private set
    var localSong by mutableStateOf(false)
        private set

    var latest by mutableStateOf<List<KugouComment>>(emptyList())
        private set
    var hottest by mutableStateOf<List<KugouComment>>(emptyList())
        private set
    var singerComments by mutableStateOf<List<KugouComment>>(emptyList())
        private set

    private var latestPage by mutableIntStateOf(0)
    private var hottestPage by mutableIntStateOf(0)
    private var latestHasMore by mutableStateOf(false)
    private var hottestHasMore by mutableStateOf(false)
    private var latestError by mutableStateOf<String?>(null)
    private var hottestError by mutableStateOf<String?>(null)
    private var firstLoading by mutableStateOf(false)

    var loadingMore by mutableStateOf(false)
        private set
    /** 翻页失败：只在页脚提示可重试，不清空已加载内容。 */
    var loadMoreFailed by mutableStateOf(false)
        private set
    /** 切排序加载中：列表保持旧内容，只在头部转圈。 */
    var sortLoading by mutableStateOf(false)
        private set
    /** 下拉刷新中：只驱动指示器，不清空列表。 */
    var refreshing by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var replyTarget by mutableStateOf(session.replyTarget)
        private set

    val floors = mutableStateMapOf<String, FloorState>()
    val expandedContents = mutableStateListOf<String>().apply { addAll(session.expanded) }

    private var loadJob: Job? = null

    val activeList: List<KugouComment>
        get() = if (sortMode == CommentSortMode.Hottest) hottest else latest

    val hasMore: Boolean
        get() = if (sortMode == CommentSortMode.Hottest) hottestHasMore else latestHasMore

    /** mixsongid 解析不出来（身份表与 /audio 都查不到）时按空态处理，不报错。 */
    var unavailable by mutableStateOf(false)
        private set

    val status: String
        get() {
            if (localSong) return "empty"
            if (songKey.isEmpty()) return "loading"
            if (unavailable) return "empty"
            if (firstLoading) return "loading"
            val active = activeList
            val error = if (sortMode == CommentSortMode.Hottest) hottestError else latestError
            // 已有内容时不清屏报错（翻页/刷新失败保留旧列表）
            if (error != null && active.isEmpty() && singerComments.isEmpty()) return "error:$error"
            if (active.isEmpty() && singerComments.isEmpty()) return "empty"
            return "ok"
        }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    fun bind(hash: String?) {
        if (hash.isNullOrBlank()) {
            localSong = true
            songKey = ""
            return
        }
        if (hash == songKey) return
        songKey = hash
        localSong = false
        resetForSong()
        // 同曲重进：先用内存快照秒显，再静默刷新（评论会变，不能只信缓存）
        val cached = commentCache[hash]
        if (cached != null) {
            restore(cached)
            refreshSilently(delayMs = 0)
        } else {
            loadFirst()
        }
    }

    /** bind() 的同步重置之后再恢复跨页面的 UI 会话（展开态 / 回复目标）。 */
    fun restoreSession() {
        if (expandedContents.isEmpty() && session.expanded.isNotEmpty()) {
            expandedContents.addAll(session.expanded)
        }
        if (replyTarget == null) replyTarget = session.replyTarget
    }

    private fun restore(snapshot: Snapshot) {
        mixSongId = snapshot.mixSongId
        childrenId = snapshot.childrenId
        total = snapshot.total
        latest = snapshot.latest
        hottest = snapshot.hottest
        singerComments = snapshot.singer
        latestPage = snapshot.latestPage
        hottestPage = snapshot.hottestPage
        latestHasMore = snapshot.latestHasMore
        hottestHasMore = snapshot.hottestHasMore
    }

    private fun saveSnapshot() {
        if (songKey.isEmpty() || mixSongId <= 0) return
        commentCache[songKey] = Snapshot(
            mixSongId = mixSongId,
            childrenId = childrenId,
            total = total,
            latest = latest,
            hottest = hottest,
            singer = singerComments,
            latestPage = latestPage,
            hottestPage = hottestPage,
            latestHasMore = latestHasMore,
            hottestHasMore = hottestHasMore
        )
    }

    private fun resetForSong() {
        loadJob?.cancel()
        mixSongId = 0
        childrenId = ""
        total = 0
        latest = emptyList()
        hottest = emptyList()
        singerComments = emptyList()
        latestPage = 0
        hottestPage = 0
        latestHasMore = false
        hottestHasMore = false
        latestError = null
        hottestError = null
        unavailable = false
        loadingMore = false
        loadMoreFailed = false
        floors.clear()
        expandedContents.clear()
        replyTarget = null
    }

    fun loadFirst() {
        loadJob?.cancel()
        loadJob = scope.launch {
            firstLoading = true
            latestError = null
            hottestError = null
            try {
                val mx = KugouCommentRepository.resolveMixSongId(songKey)
                mixSongId = mx
                if (mx <= 0) {
                    unavailable = true
                    return@launch
                }
                KugouCommentRepository.loadComments(mx, 1).fold(
                    onSuccess = { page ->
                        latest = page.comments
                        latestPage = 1
                        latestHasMore = page.rawCount >= KugouCommentRepository.PAGE_SIZE
                        total = page.total
                        childrenId = page.childrenId
                        singerComments = page.hotComments
                    },
                    onFailure = { error ->
                        // 首屏（cmtlist）失败是共享失败：两种排序都还没有内容，错误要同时记到
                        // 两边，否则默认「最热」读 hottestError=null 会把失败显示成「暂无评论」。
                        val message = error.message ?: ""
                        latestError = message
                        hottestError = message
                        return@launch
                    }
                )
                // 默认排序是最热：紧跟一次 topliked（切到最新时零请求）
                if (sortMode == CommentSortMode.Hottest && childrenId.isNotEmpty()) {
                    val ok = loadHottestPage(1)
                    // topliked 走 gateway 的另一个 host 路径，偶发 DNS/连接失败（真机实测）。
                    // 失败或「成功但空列表」都用最新页按点赞本地排序顶上：否则头部
                    // 「共 N 条」会和正文「暂无评论」同时出现。
                    if ((!ok || hottest.isEmpty()) && latest.isNotEmpty()) {
                        hottest = KugouCommentRepository.sortByLikes(latest)
                        hottestError = null
                    }
                }
                saveSnapshot()
            } finally {
                firstLoading = false
            }
        }
    }

    fun retry() {
        when {
            sortMode == CommentSortMode.Hottest && childrenId.isNotEmpty() ->
                scope.launch { firstLoading = true; try { loadHottestPage(1) } finally { firstLoading = false } }
            else -> loadFirst()
        }
    }

    fun switchSort(mode: CommentSortMode) {
        if (mode == sortMode || sortLoading) return
        // 目标排序还没数据时先加载再切：sortMode 一改 activeList 立刻变空，
        // 列表会整块闪成 loading / 暂无评论。
        if (mode == CommentSortMode.Hottest && hottestPage == 0 && childrenId.isNotEmpty()) {
            sortLoading = true
            scope.launch {
                try {
                    loadHottestPage(1)
                } finally {
                    sortLoading = false
                    sortMode = mode
                }
            }
            return
        }
        sortMode = mode
    }

    /** 下拉刷新：重拉当前排序第一页，列表保持可见，失败保留旧内容。 */
    fun refresh() {
        if (refreshing || firstLoading) return
        refreshing = true
        scope.launch {
            try {
                when {
                    childrenId.isEmpty() -> loadFirst()
                    sortMode == CommentSortMode.Hottest -> loadHottestPage(1)
                    else -> loadLatestFirstPage(replace = true)
                }
                saveSnapshot()
            } finally {
                refreshing = false
            }
        }
    }

    /** 拉最新第一页；[replace] = false 时按 id 合并，保留已加载的后续页。 */
    private suspend fun loadLatestFirstPage(replace: Boolean) {
        KugouCommentRepository.loadComments(mixSongId, 1).onSuccess { result ->
            latest = if (replace) {
                result.comments
            } else {
                val fresh = result.comments.mapTo(HashSet()) { it.id }
                (result.comments + latest.filterNot { it.id in fresh }).distinctBy { it.id }
            }
            if (replace || latestPage == 0) latestPage = 1
            if (replace) latestHasMore = result.rawCount >= KugouCommentRepository.PAGE_SIZE
            if (result.total > 0) total = result.total
            if (result.hotComments.isNotEmpty()) singerComments = result.hotComments
        }
    }

    /** 合并最热第一页：新页在前，已加载的后续页保留，分页计数不动。 */
    private suspend fun mergeHottestFirstPage() {
        if (childrenId.isEmpty()) return
        KugouCommentRepository.loadTopLiked(childrenId, 1).onSuccess { result ->
            val fresh = result.comments.mapTo(HashSet()) { it.id }
            hottest = KugouCommentRepository.sortByLikes(
                (result.comments + hottest.filterNot { it.id in fresh }).distinctBy { it.id }
            )
            if (hottestPage == 0) hottestPage = 1
            if (result.total > 0) total = result.total
        }
    }

    /** 拉一页最热；只有第一页失败才把整页切成错误态，翻页失败留在页脚提示。 */
    private suspend fun loadHottestPage(page: Int): Boolean {
        if (page == 1) hottestError = null
        return KugouCommentRepository.loadTopLiked(childrenId, page).fold(
            onSuccess = { result ->
                val merged = if (page == 1) result.comments else hottest + result.comments
                hottest = KugouCommentRepository.sortByLikes(merged).distinctBy { it.id }
                hottestPage = page
                hottestHasMore = result.rawCount >= KugouCommentRepository.PAGE_SIZE
                if (result.total > 0) total = result.total
                loadMoreFailed = false
                true
            },
            onFailure = { error ->
                if (page == 1) hottestError = error.message ?: "" else loadMoreFailed = true
                false
            }
        )
    }

    fun loadMore() {
        if (loadingMore || !hasMore || firstLoading) return
        val page = if (sortMode == CommentSortMode.Hottest) hottestPage else latestPage
        loadingMore = true
        loadMoreFailed = false
        scope.launch {
            try {
                if (sortMode == CommentSortMode.Hottest) {
                    loadHottestPage(page + 1)
                } else {
                    KugouCommentRepository.loadComments(mixSongId, page + 1).fold(
                        onSuccess = { result ->
                            latest = (latest + result.comments).distinctBy { it.id }
                            latestPage = page + 1
                            latestHasMore = result.rawCount >= KugouCommentRepository.PAGE_SIZE
                        },
                        onFailure = { loadMoreFailed = true }
                    )
                }
                saveSnapshot()
            } finally {
                loadingMore = false
            }
        }
    }

    fun retryLoadMore() {
        loadMoreFailed = false
        loadMore()
    }

    // ------------------------------------------------------------------
    // 楼中楼
    // ------------------------------------------------------------------

    fun floorState(commentId: String): FloorState? = floors[commentId]

    fun toggleFloor(comment: KugouComment) {
        val floor = floors.getOrPut(comment.id) { FloorState() }
        floor.expanded = !floor.expanded
        if (floor.expanded && !floor.initialized) {
            fetchFloor(comment, reset = true)
        }
    }

    fun loadMoreFloor(comment: KugouComment) {
        fetchFloor(comment, reset = false)
    }

    /** 首屏楼层加载失败后的重试（回到第 1 页）。 */
    fun retryFloor(comment: KugouComment) {
        fetchFloor(comment, reset = true)
    }

    private fun fetchFloor(comment: KugouComment, reset: Boolean) {
        val floor = floors.getOrPut(comment.id) { FloorState() }
        if (floor.loading) return
        floor.loading = true
        floor.message = ""
        scope.launch {
            try {
                val page = if (reset) 1 else floor.page + 1
                val specialId = comment.specialId?.takeIf { it.isNotBlank() } ?: childrenId
                KugouCommentRepository.loadFloor(
                    specialId = specialId,
                    tid = comment.tid ?: comment.id,
                    mixsongid = mixSongId.takeIf { it > 0 },
                    page = page
                ).fold(
                    onSuccess = { result ->
                        floor.replies = if (reset) result.comments else (floor.replies + result.comments)
                            .distinctBy { it.id }
                        floor.page = page
                        floor.initialized = true
                        floor.total = if (result.total > 0) result.total else comment.replyCount
                        // 与 md3Music 一致：满页 + 未达 total 才算还有；只按 total 判断会让
                        // 「加载更多回复」在被风控过滤的楼层上永远停不下来。
                        floor.hasMore = result.rawCount >= KugouCommentRepository.FLOOR_PAGE_SIZE &&
                                (floor.total <= 0 || floor.replies.size < floor.total)
                        floor.message = ""
                    },
                    onFailure = { error ->
                        floor.initialized = true
                        floor.message = error.message ?: ""
                    }
                )
            } finally {
                floor.loading = false
            }
        }
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    fun beginReply(top: KugouComment, reply: KugouComment?) {
        replyTarget = ReplyTarget(top, reply)
    }

    fun cancelReply() {
        replyTarget = null
    }

    suspend fun submit(content: String): SubmitOutcome {
        if (sending) return SubmitOutcome.Failure(null)
        // 没有评论资源（本地歌 / mixsongid 解析失败 / 列表没加载出来）时不要发请求：
        // Rust 会回「无法解析歌曲评论 special_id」，对用户是噪音。
        if (childrenId.isBlank()) return SubmitOutcome.Failure(null)
        sending = true
        try {
            val target = replyTarget
            val result = if (target == null) {
                KugouCommentRepository.sendComment(
                    content = content,
                    mixsongid = mixSongId.takeIf { it > 0 },
                    specialId = childrenId.takeIf { it.isNotBlank() }
                )
            } else {
                val quoted = target.reply ?: target.top
                KugouCommentRepository.sendReply(
                    content = content,
                    specialId = target.top.specialId?.takeIf { it.isNotBlank() } ?: childrenId,
                    tid = target.top.tid ?: target.top.id,
                    pid = target.reply?.id,
                    mixsongid = mixSongId.takeIf { it > 0 },
                    replyUserName = quoted.username,
                    replyContent = KugouCommentRepository.stripReplyQuote(quoted.content)
                )
            }
            return result.fold(
                onSuccess = { sendResult ->
                    when {
                        sendResult.ok -> {
                            replyTarget = null
                            if (target == null) {
                                refreshSilently(merge = true)
                            } else {
                                val floor = floors.getOrPut(target.top.id) { FloorState() }
                                floor.expanded = true
                                fetchFloor(target.top, reset = true)
                            }
                            SubmitOutcome.Success
                        }
                        sendResult.needVerify -> SubmitOutcome.NeedVerify
                        sendResult.errorCode == 400 -> SubmitOutcome.InvalidContent
                        else -> SubmitOutcome.Failure(
                            // 参数级/网络级错误（含 Rust 的「无法解析 special_id」、Dns Failed
                            // 之类带 URL 的文案）不给用户看，统一落到「发送失败」。
                            sendResult.message.takeIf { it.isNotBlank() && !it.startsWith("http") }
                        )
                    }
                },
                onFailure = { SubmitOutcome.Failure(it.message) }
            )
        } finally {
            sending = false
        }
    }

    suspend fun deleteOwn(comment: KugouComment, topId: String?): Boolean {
        val result = KugouCommentRepository.deleteComment(
            cid = comment.id,
            specialId = comment.specialId?.takeIf { it.isNotBlank() } ?: childrenId,
            mixsongid = mixSongId.takeIf { it > 0 },
            tid = topId
        )
        val ok = result.getOrNull()?.ok == true
        if (!ok) return false
        total = (total - 1).coerceAtLeast(0)
        if (topId != null) {
            // 楼中楼：本地移除该条，顶层回复数同步减一
            floors[topId]?.let { floor ->
                floor.replies = floor.replies.filterNot { it.id == comment.id }
                floor.total = (floor.total - 1).coerceAtLeast(0)
            }
            val top = latest.firstOrNull { it.id == topId } ?: hottest.firstOrNull { it.id == topId }
            if (top != null) {
                val newCount = (top.replyCount - 1).coerceAtLeast(0)
                latest = latest.map { if (it.id == topId) it.copy(replyCount = newCount) else it }
                hottest = hottest.map { if (it.id == topId) it.copy(replyCount = newCount) else it }
            } else {
                refreshSilently(merge = true)
            }
        } else {
            // 顶层评论：本地移除后再合并刷新第一页，不重置已加载页数
            latest = latest.filterNot { it.id == comment.id }
            hottest = hottest.filterNot { it.id == comment.id }
            singerComments = singerComments.filterNot { it.id == comment.id }
            floors.remove(comment.id)
            expandedContents.remove(comment.id)
            refreshSilently(merge = true)
        }
        return true
    }

    /**
     * 静默刷新当前排序的第一页（失败保留旧内容）。
     * [merge] = true 时按 id 合并、不重置分页：发送/删除后如果整页覆盖，
     * 用户已翻到第 5 页的列表会瞬间只剩 30 条，滚动位置还停在原处。
     */
    private fun refreshSilently(delayMs: Long = 400, merge: Boolean = false) {
        scope.launch {
            // 审核中的评论不会立刻出现，发布后稍等一下再拉，避免拿到旧快照
            if (delayMs > 0) delay(delayMs)
            if (merge) {
                if (sortMode == CommentSortMode.Hottest) mergeHottestFirstPage()
                else loadLatestFirstPage(replace = false)
            } else {
                if (sortMode == CommentSortMode.Hottest) {
                    if (childrenId.isNotEmpty()) loadHottestPage(1)
                } else {
                    loadLatestFirstPage(replace = true)
                }
            }
            saveSnapshot()
        }
    }
}

// ---------------------------------------------------------------------------
// 头部 / 列表 / 条目
// ---------------------------------------------------------------------------

@Composable
private fun CommentsHeader(
    total: Int,
    showTotal: Boolean,
    sortMode: CommentSortMode,
    showSort: Boolean,
    sortLoading: Boolean,
    onSortChanged: (CommentSortMode) -> Unit
) {
    PlayerPageHeaderRow {
        PlayerPageHeaderText(
            title = stringResource(id = R.string.nowplaying_comments_title),
            // 副标题只放总数；歌名在顶部 PlayingBar 已有，重复显示会信息过载。
            // 空态/错误态不报总数（否则会和「暂无评论」矛盾），但保留空行让标题不跳。
            subtitle = if (showTotal && total > 0) {
                stringResource(id = R.string.nowplaying_comments_total, total)
            } else {
                ""
            }
        )
        if (sortLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 1.5.dp
            )
            Spacer(Modifier.width(10.dp))
        }
        if (showSort) {
            SortSegments(sortMode = sortMode, onSortChanged = onSortChanged)
        }
    }
}

@Composable
private fun SortSegments(
    sortMode: CommentSortMode,
    onSortChanged: (CommentSortMode) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(2.dp)
    ) {
        SortSegment(
            text = stringResource(id = R.string.nowplaying_comments_hottest),
            selected = sortMode == CommentSortMode.Hottest,
            onClick = { onSortChanged(CommentSortMode.Hottest) }
        )
        SortSegment(
            text = stringResource(id = R.string.nowplaying_comments_latest),
            selected = sortMode == CommentSortMode.Latest,
            onClick = { onSortChanged(CommentSortMode.Latest) }
        )
    }
}

@Composable
private fun SortSegment(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = Color.White.copy(alpha = if (selected) 1f else 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (selected) 0.16f else 0f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentsList(
    state: SongCommentsState,
    listState: LazyListState,
    onReply: (KugouComment, KugouComment?) -> Unit,
    onDelete: (KugouComment, String?) -> Unit,
    onMore: (KugouComment, String?) -> Unit,
    // 键盘把输入框顶起来后，列表底部要多让出的高度（让最后一条能滚到输入框上方）
    bottomExtra: Dp = 0.dp,
    // 输入框被键盘抬起的高度（px）。底部渐隐必须跟着抬到输入框上沿：
    // 渐隐画在列表视口底部，抬起的输入框只是浮在列表上方，若不跟着抬，
    // 渐隐就被输入框盖在下面，输入框正上方是一截没有过渡的实体文字＝文字压着输入框。
    bottomFadeInsetPx: () -> Float = { 0f }
) {
    val list = state.activeList
    val singerFromActive = remember(list) { list.filter { it.isStar } }
    val singer = remember(state.singerComments, singerFromActive) {
        (state.singerComments + singerFromActive).distinctBy { it.id }
    }
    val singerIds = remember(singer) { singer.mapTo(HashSet()) { it.id } }
    val regular = remember(list, singerIds) { list.filter { it.id !in singerIds } }
    val orderedRegular = remember(regular, state.sortMode) {
        // 「默认」保持上游 cmtlist 的混排顺序（它不是时间序，客户端重排只会假最新）；
        // 「最热」对 topliked 结果做稳定重排，修正页内个别倒挂。
        if (state.sortMode == CommentSortMode.Latest) regular
        else KugouCommentRepository.sortByLikes(regular)
    }

    // 距底部 4 条时自动翻页
    LaunchedEffect(listState, state.sortMode) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }.collect { nearBottom -> if (nearBottom) state.loadMore() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp + bottomExtra),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // 固定 24dp 渐隐：此前按列表高度 1/15 渐隐，静止时第一个分组标题
                // 正好落在渐隐区里，文字上半截是半透明的。
                val fade = 24.dp.toPx()
                val top = Brush.verticalGradient(
                    0f to Color.Transparent, 1f to Color.Black,
                    startY = 0f, endY = fade
                )
                // 渐隐下沿 = 视口底部 − 输入框抬起量（在 draw 期读 State，
                // 键盘抬升动画的每一帧都会让本缓存失效重算，渐隐与输入框同步走）
                val bottomEdge = (size.height - bottomFadeInsetPx()).coerceAtLeast(fade)
                val bottom = Brush.verticalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startY = bottomEdge - fade, endY = bottomEdge
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = top, blendMode = BlendMode.DstIn)
                    drawRect(brush = bottom, blendMode = BlendMode.DstIn)
                }
            }
    ) {
        if (singer.isNotEmpty()) {
            item("singer_header") {
                SectionHeader(stringResource(id = R.string.nowplaying_comments_singer_section))
            }
            items(singer, key = { "singer_${it.id}" }) { comment ->
                CommentItem(
                    comment = comment,
                    state = state,
                    onReply = onReply,
                    onDelete = onDelete,
                    onMore = onMore
                )
            }
            // 有歌手区时才需要分隔标题；没有歌手区时头部已有排序胶囊，标题是重复信息
            item("main_header") {
                SectionHeader(stringResource(id = R.string.nowplaying_comments_all_section))
            }
        }
        items(orderedRegular, key = { it.id }) { comment ->
            CommentItem(
                comment = comment,
                state = state,
                onReply = onReply,
                onDelete = onDelete,
                onMore = onMore
            )
        }
        item("footer") {
            ListFooter(state = state)
        }
    }

    // 切排序时回到顶部；首帧不滚——那是跨页面恢复的滚动位置
    var firstSort by remember { mutableStateOf(true) }
    LaunchedEffect(state.sortMode) {
        if (firstSort) {
            firstSort = false
            return@LaunchedEffect
        }
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(horizontal = 30.dp)
            .padding(top = 10.dp, bottom = 2.dp)
            .overlayEffect()
            .alpha(0.55f)
    )
}

@Composable
private fun ListFooter(state: SongCommentsState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.loadingMore -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 1.6.dp
            )
            state.loadMoreFailed -> Text(
                text = stringResource(id = R.string.nowplaying_comments_load_more_failed),
                fontSize = 12.5.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.retryLoadMore() }
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            state.hasMore -> Text(
                text = stringResource(id = R.string.nowplaying_comments_load_more),
                fontSize = 12.5.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.loadMore() }
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            state.activeList.isNotEmpty() -> Text(
                text = stringResource(id = R.string.nowplaying_comments_no_more),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.3f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentItem(
    comment: KugouComment,
    state: SongCommentsState,
    onReply: (KugouComment, KugouComment?) -> Unit,
    onDelete: (KugouComment, String?) -> Unit,
    onMore: (KugouComment, String?) -> Unit
) {
    val context = LocalContext.current
    val own = KugouCommentRepository.isOwnComment(comment, KugouAccountState.userid)
    val floor = state.floors[comment.id]
    val expanded = state.expandedContents.contains(comment.id)
    val floorExpanded = floor?.expanded == true
    // 歌手/热门评论常带「//@某人:原文」的引用尾巴，顶层也要剥掉
    val body = remember(comment.content) {
        KugouCommentRepository.stripReplyQuote(comment.content)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // 整卡只负责「展开」：滚动时点一下停住不该把已展开的回复收起来。
                // 收起走显式的「收起回复」。
                onClick = {
                    if (!floorExpanded && comment.replyCount > 0) state.toggleFloor(comment)
                },
                onLongClick = if (own) ({
                    Vibrator.click(context)
                    onDelete(comment, null)
                }) else null
            )
            .padding(horizontal = 30.dp)
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            CommentAvatar(url = comment.avatar, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comment.username,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (comment.isStar) {
                        Spacer(Modifier.width(5.dp))
                        CommentBadge(
                            text = stringResource(id = R.string.nowplaying_comments_badge_singer),
                            singer = true
                        )
                    }
                    if (comment.isHot) {
                        Spacer(Modifier.width(5.dp))
                        CommentBadge(stringResource(id = R.string.nowplaying_comments_badge_hot))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = commentTimeText(comment.timeSec),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        maxLines = 1
                    )
                }

                var overflow by remember(comment.id) { mutableStateOf(false) }
                Text(
                    text = body,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result -> if (!expanded) overflow = result.hasVisualOverflow },
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (overflow || expanded) {
                    Text(
                        text = stringResource(
                            id = if (expanded) R.string.nowplaying_comments_collapse
                            else R.string.nowplaying_comments_expand
                        ),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (expanded) state.expandedContents.remove(comment.id)
                                    else state.expandedContents.add(comment.id)
                                }
                            )
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (comment.likes > 0) {
                        // 没有点赞接口：只展示数字，不画拇指图标（避免看起来可点）
                        Text(
                            text = likesText(comment.likes),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    if (comment.replyCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { state.toggleFloor(comment) }
                                )
                                .padding(horizontal = 2.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    id = if (floorExpanded)
                                        R.string.nowplaying_comments_hide_replies
                                    else R.string.nowplaying_comments_view_replies,
                                    comment.replyCount
                                ),
                                fontSize = 11.5.sp,
                                color = Color.White.copy(alpha = 0.55f)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onReply(comment, null) }
                            )
                            .padding(horizontal = 2.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_comment_reply),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(id = R.string.nowplaying_comments_reply),
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                    // 本人评论：给一个看得见的入口（长按是隐藏手势）
                    if (own) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = "…",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onMore(comment, null) }
                                )
                                .padding(horizontal = 8.dp)
                        )
                    }
                }
            }
        }

        if (floor?.expanded == true) {
            FloorReplies(
                top = comment,
                floor = floor,
                state = state,
                onReply = onReply,
                onDelete = onDelete,
                onMore = onMore
            )
        }
    }
}

@Composable
private fun FloorReplies(
    top: KugouComment,
    floor: FloorState,
    state: SongCommentsState,
    onReply: (KugouComment, KugouComment?) -> Unit,
    onDelete: (KugouComment, String?) -> Unit,
    onMore: (KugouComment, String?) -> Unit
) {
    val nodes = remember(floor.replies) { KugouCommentRepository.buildReplyTree(floor.replies) }
    // pid → 昵称：剥掉「//@某人:原文」后仍要能看出在回谁
    val namesById = remember(floor.replies) {
        floor.replies.associate { it.id to it.username }
    }
    Column(
        modifier = Modifier
            .padding(start = 46.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        nodes.forEach { node ->
            FloorReplyItem(
                top = top,
                node = node,
                replyTo = node.reply.parentId?.let { namesById[it] },
                onReply = onReply,
                onDelete = onDelete,
                onMore = onMore
            )
        }
        when {
            floor.loading -> Box(
                Modifier.fillMaxWidth().height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 1.5.dp
                )
            }
            floor.initialized && floor.replies.isEmpty() && floor.message.isNotEmpty() -> Text(
                text = stringResource(id = R.string.nowplaying_comments_load_more_failed),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.retryFloor(top) }
                    )
            )
            floor.initialized && floor.replies.isEmpty() -> Text(
                text = stringResource(id = R.string.nowplaying_comments_no_replies),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 6.dp)
            )
            floor.hasMore -> Text(
                text = if (floor.message.isNotEmpty())
                    stringResource(id = R.string.nowplaying_comments_load_more_failed)
                else stringResource(id = R.string.nowplaying_comments_load_more_replies),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.loadMoreFloor(top) }
                    )
                    .padding(vertical = 6.dp)
            )
            floor.replies.isNotEmpty() -> Text(
                text = stringResource(id = R.string.nowplaying_comments_all_replies_loaded),
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FloorReplyItem(
    top: KugouComment,
    node: CommentReplyNode,
    replyTo: String?,
    onReply: (KugouComment, KugouComment?) -> Unit,
    onDelete: (KugouComment, String?) -> Unit,
    onMore: (KugouComment, String?) -> Unit
) {
    val context = LocalContext.current
    val reply = node.reply
    val own = KugouCommentRepository.isOwnComment(reply, KugouAccountState.userid)
    // 缩进封顶 2 层：再深就靠「回复 @xxx」表达关系，否则正文被挤成窄柱
    val indent = (node.depth.coerceAtMost(2) * 14).dp
    val body = if (node.isOrphan) reply.content else KugouCommentRepository.stripReplyQuote(reply.content)
    val prefix = replyTo?.let { stringResource(R.string.nowplaying_comments_reply_prefix, it) }
    val content = remember(prefix, body) {
        if (prefix == null) {
            AnnotatedString(body)
        } else {
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.55f))) { append(prefix) }
                append(body)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // 点一条回复 = 回复它（此前是空 lambda，吞掉点击却什么都不做）
                onClick = { onReply(top, reply) },
                onLongClick = if (own) ({
                    Vibrator.click(context)
                    onDelete(reply, top.id)
                }) else null
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        CommentAvatar(url = reply.avatar, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = reply.username,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = commentTimeText(reply.timeSec),
                    fontSize = 10.5.sp,
                    color = Color.White.copy(alpha = 0.35f),
                    maxLines = 1
                )
            }
            Text(
                text = content,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onReply(top, reply) }
                        )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_comment_reply),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(id = R.string.nowplaying_comments_reply),
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                if (own) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "…",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onMore(reply, top.id) }
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentBadge(text: String, singer: Boolean = false) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White.copy(alpha = if (singer) 0.95f else 0.8f),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            // 歌手徽章给一点暖色底，和「热门」区分开
            .background(
                if (singer) Color(0xFFFFC46B).copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.12f)
            )
            .padding(horizontal = 6.dp, vertical = 1.5.dp)
    )
}

@Composable
private fun CommentAvatar(url: String?, size: Dp) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .error(R.drawable.songcredits_monogram_person)
                    .placeholder(R.drawable.songcredits_monogram_person)
                    .fallback(R.drawable.songcredits_monogram_person)
                    .allowHardware(true)
                    .precision(Precision.INEXACT)
                    .size(if (size >= 32.dp) 128 else 72)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.songcredits_monogram_person),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 状态行 / 输入框
// ---------------------------------------------------------------------------

@Composable
private fun CommentsStatus(
    loading: Boolean = false,
    message: String? = null,
    emptyText: String? = null,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    strokeWidth = 1.6.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(id = R.string.nowplaying_comments_loading),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
            message != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(id = R.string.nowplaying_comments_load_failed),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.nowplaying_comments_retry),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onRetry?.invoke() }
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            else -> Text(
                text = emptyText?.takeIf { it.isNotBlank() }
                    ?: stringResource(id = R.string.nowplaying_comments_empty),
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun CommentsComposer(
    text: String,
    onTextChange: (String) -> Unit,
    replyToName: String?,
    sending: Boolean,
    maxLength: Int,
    focusRequester: FocusRequester,
    onTapInput: () -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 与列表/分组标题同一条 30dp 左边线，避免输入框比正文宽 8dp
            .padding(horizontal = 30.dp)
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        if (replyToName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.nowplaying_comments_reply_to, replyToName),
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCancelReply
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 输入框高度只由行数决定。BasicTextField 的固有高度会随聚焦/光标/
            // 字体 padding 变化，占位符又是另一套测量，两者混用就会在
            // 「刚聚焦 / 输入一个字 / 删空 / 重开键盘」之间来回跳。
            var lineCount by remember { mutableStateOf(1) }
            val lineHeightDp = with(LocalDensity.current) { 18.sp.toDp() }
            val inputHeight = (lineHeightDp * lineCount + 18.dp).coerceAtLeast(36.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(inputHeight)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    // 点药丸任意位置都聚焦：BasicTextField 的空态命中区只有文字行，
                    // 上下 padding 里点不到时键盘拉不起来。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTapInput
                    )
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.nowplaying_comments_hint),
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = !sending,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    cursorBrush = SolidColor(Color.White),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { if (text.isNotBlank() && !sending) onSend() }
                    ),
                    onTextLayout = { lineCount = it.lineCount.coerceAtLeast(1) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            Spacer(Modifier.width(10.dp))
            // 接近上限时才显示计数，避免平时占位
            if (text.length >= maxLength - 40) {
                Text(
                    text = stringResource(
                        id = R.string.nowplaying_comments_length_counter,
                        text.length,
                        maxLength
                    ),
                    fontSize = 10.5.sp,
                    color = Color.White.copy(
                        alpha = if (text.length >= maxLength) 0.7f else 0.35f
                    ),
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            val enabled = text.isNotBlank() && !sending
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.07f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = text.isNotBlank() && !sending,
                        onClick = onSend
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White.copy(alpha = 0.8f),
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.nowplaying_comments_send),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (enabled) 0.95f else 0.35f)
                    )
                }
            }
        }
    }
}
