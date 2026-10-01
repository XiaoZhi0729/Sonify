package yos.music.player.ui.pages.settings.account

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState
import yos.music.player.data.objects.currentMonthKey
import yos.music.player.data.repositories.KugouVipRepository
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * VIP 签到页（对齐 md3Music SignInCalendarPage，酷狗 youth 活动"签到送 VIP"）。
 *
 * 结构：会员状态卡（畅听/概念 + 相对到期文案）→ 签到/听歌领取/广告领取三操作
 * → 打卡月历（服务端 svip 记录 ∪ 本地已签兜底）。
 *
 * 20028 二次验证：监听 [KugouVipState.pendingCaptcha]，非 null 时弹出 WebView
 * 腾讯滑块（assets/web/verify_captcha.html，桥名 CaptchaChannel，与 md3Music 同约定）。
 * 自动签到路径不处理 20028（与 md3Music 一致），只有手动签到会触发本弹窗。
 *
 * 广告领取最长 8 次 × 30s 间隔，协程挂本页 scope，离开页面自动取消。
 */
@Composable
fun KugouVipSignIn(navController: NavController) =
    SettingBackground {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // 进入页面拉取 VIP 详情与当月打卡记录（接口必须显式传月份）
        LaunchedEffect(Unit) {
            KugouVipRepository.refreshVipDetail()
            KugouVipRepository.loadMonthRecord(currentMonthKey())
        }

        Title(
            title = stringResource(id = R.string.kugou_vip_sign_in_title),
            onBack = { navController.popBackStack() },
            titleHorizontalPadding = 32.dp,
            content = {
                if (!KugouAccountState.isLoggedIn) {
                    item("not_logged_in") {
                        ListHeader(stringResource(id = R.string.kugou_vip_not_logged_in))
                    }
                } else {
                    item("vip_status") { VipStatusSection() }
                    item("vip_actions") {
                        VipActionsSection(
                            onResult = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item("vip_calendar") { VipCalendarSection() }
                }
            }
        )

        // 20028 滑块验证弹窗：pendingCaptcha 非 null 即展示，完成/取消后自动关闭
        KugouVipState.pendingCaptcha?.let { pending ->
            VerifyCaptchaDialog(pending = pending)
        }
    }

// ==================== 会员状态卡 ====================

@Composable
private fun VipStatusSection() {
    val detail = KugouVipState.vipDetail
    ListHeader(stringResource(id = R.string.kugou_vip_status_header))
    RoundColumn {
        VipRow(
            title = stringResource(id = R.string.kugou_vip_listen_member),
            endTime = detail?.listenExpireTime
        )
        Divider()
        VipRow(
            title = stringResource(id = R.string.kugou_vip_concept_member),
            endTime = detail?.conceptExpireTime
        )
    }
}

@Composable
private fun VipRow(title: String, endTime: String?) {
    val context = LocalContext.current
    val expireText = remember(endTime) { formatVipExpireText(context, endTime) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = expireText ?: stringResource(id = R.string.kugou_vip_not_active),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
        )
    }
}

// ==================== 签到 / 领取操作 ====================

@Composable
private fun VipActionsSection(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    GroupSpacer()
    ListHeader(stringResource(id = R.string.kugou_vip_actions_header))
    RoundColumn {
        LabelItem(
            enabled = !KugouVipState.signInRunning,
            title = if (KugouVipState.signInRunning) stringResource(id = R.string.kugou_vip_action_running)
            else stringResource(id = R.string.kugou_vip_action_sign_in),
            desc = if (KugouVipState.isTodaySigned()) stringResource(id = R.string.kugou_vip_signed_today)
            else stringResource(id = R.string.kugou_vip_not_signed_today)
        ) {
            Vibrator.click(context)
            scope.launch {
                val (_, msg) = KugouVipRepository.manualSignIn()
                onResult(msg)
            }
        }
        Divider()
        LabelItem(
            enabled = !KugouVipState.listenRunning,
            title = if (KugouVipState.listenRunning) stringResource(id = R.string.kugou_vip_action_running)
            else stringResource(id = R.string.kugou_vip_action_listen_claim)
        ) {
            Vibrator.click(context)
            scope.launch {
                val (ok, msg) = KugouVipRepository.listenSongClaim()
                if (ok) runCatching { KugouVipRepository.refreshVipDetail() }
                onResult(msg)
            }
        }
        Divider()
        LabelItem(
            enabled = !KugouVipState.adRunning,
            title = if (KugouVipState.adRunning) stringResource(id = R.string.kugou_vip_action_running)
            else stringResource(id = R.string.kugou_vip_action_ad_claim)
        ) {
            Vibrator.click(context)
            scope.launch {
                val (ok, msg) = KugouVipRepository.claimAdVip()
                if (ok) runCatching { KugouVipRepository.refreshVipDetail() }
                onResult(msg)
            }
        }
    }
}

// ==================== 打卡月历 ====================

@Composable
private fun VipCalendarSection() {
    val now = Calendar.getInstance()
    var displayedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var displayedMonth by remember { mutableStateOf(now.get(Calendar.MONTH) + 1) } // 1-based
    val isCurrentMonth = displayedYear == now.get(Calendar.YEAR) &&
        displayedMonth == now.get(Calendar.MONTH) + 1

    fun shiftMonth(delta: Int) {
        var y = displayedYear
        var m = displayedMonth + delta
        if (m < 1) { m = 12; y-- }
        if (m > 12) { m = 1; y++ }
        displayedYear = y
        displayedMonth = m
    }

    // 切月懒加载（接口必须传 yyyy-MM；记录写入 KugouVipState，keyed by monthRecordsFor）
    LaunchedEffect(displayedYear, displayedMonth) {
        KugouVipRepository.loadMonthRecord(String.format(Locale.US, "%04d-%02d", displayedYear, displayedMonth))
    }

    // 已签日 = 服务端概念版(svip)记录 ∪ 本地已签日期（对齐 md3Music 口径，畅听 tvip 不计入）
    val receivedDays: Set<Int> = remember(
        KugouVipState.monthRecords, KugouVipState.monthRecordsFor,
        KugouVipState.localSignedDays, displayedYear, displayedMonth
    ) {
        buildSet {
            if (KugouVipState.monthRecordsFor ==
                String.format(Locale.US, "%04d-%02d", displayedYear, displayedMonth)
            ) {
                KugouVipState.monthRecords.forEach { rec ->
                    if (rec.vipType == "svip") {
                        parseDayOfMonth(rec.day, displayedYear, displayedMonth)?.let { add(it) }
                    }
                }
            }
            KugouVipState.localSignedDays.forEach { key ->
                parseDayOfMonth(key, displayedYear, displayedMonth)?.let { add(it) }
            }
        }
    }

    // 月历几何：周一开头（对齐 md3Music leading = weekday - 1）
    val firstDay = (Calendar.getInstance().apply { set(displayedYear, displayedMonth - 1, 1) }
        .get(Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = Calendar.getInstance()
        .apply { set(displayedYear, displayedMonth - 1, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
    val todayKey = String.format(Locale.US, "%04d-%02d-%02d",
        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))

    GroupSpacer()
    ListHeader(stringResource(id = R.string.kugou_vip_calendar_header))
    RoundColumn {
        // 月份切换头（prev 无限制，next 到当前月为止）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .alpha(0.6f)
                    .clickable { shiftMonth(-1) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_action_next),
                    contentDescription = stringResource(id = R.string.kugou_vip_prev_month),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .rotate(180f)
                        .height(24.dp)
                )
            }
            Text(
                text = stringResource(id = R.string.kugou_vip_month_label, displayedYear, displayedMonth),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (isCurrentMonth) 0.2f else 0.6f)
                    .clickable(enabled = !isCurrentMonth) { shiftMonth(1) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_action_next),
                    contentDescription = stringResource(id = R.string.kugou_vip_next_month),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.height(24.dp)
                )
            }
        }
        Divider()

        // 星期表头（周一 → 周日）
        val weekdayLabels = stringArrayResource(id = R.array.kugou_vip_weekdays)
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        // 日期网格
        val primary = MaterialTheme.colorScheme.primary
        val onBackground = MaterialTheme.colorScheme.onBackground
        val totalCells = firstDay + daysInMonth
        val rows = (totalCells + 6) / 7
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(rows) { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val index = row * 7 + col
                        val day = index - firstDay + 1
                        val valid = day in 1..daysInMonth
                        val signed = valid && day in receivedDays
                        val dayKey = if (valid) String.format(
                            Locale.US, "%04d-%02d-%02d", displayedYear, displayedMonth, day
                        ) else ""
                        DayCell(day = if (valid) day else null, signed = signed, isToday = valid && dayKey == todayKey, primary = primary, onBackground = onBackground)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Divider()
        Text(
            text = stringResource(id = R.string.kugou_vip_month_stat, receivedDays.size),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RowScope.DayCell(
    day: Int?,
    signed: Boolean,
    isToday: Boolean,
    primary: Color,
    onBackground: Color
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(3.dp)
            .then(if (signed) Modifier.background(primary.copy(alpha = 0.18f), CircleShape) else Modifier)
            .then(
                if (isToday && !signed) Modifier.border(1.dp, primary.copy(alpha = 0.45f), CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (day != null) {
            Text(
                text = day.toString(),
                fontSize = 13.sp,
                fontWeight = if (signed) FontWeight.Bold else FontWeight.Normal,
                color = if (signed) primary else onBackground.copy(alpha = if (isToday) 0.95f else 0.7f)
            )
        }
    }
}

// ==================== 20028 腾讯滑块验证弹窗 ====================

/** JS 桥（与 md3Music verify_captcha.html 约定同名 CaptchaChannel，回调统一切主线程）。 */
private class CaptchaBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(value: String?) {
        value ?: return
        Handler(Looper.getMainLooper()).post { onMessage(value) }
    }
}

@Composable
private fun VerifyCaptchaDialog(pending: KugouVipState.PendingCaptcha) {
    // 弹窗关闭（完成/取消/返回键）后 pendingCaptcha 已归零；若因其他途径关闭，
    // cancelCaptcha 幂等兜底，避免 continuation 悬挂到 3 分钟超时
    Dialog(
        onDismissRequest = { KugouVipState.cancelCaptcha() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(id = R.string.kugou_vip_captcha_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(id = R.string.kugou_vip_captcha_close),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier
                            .padding(8.dp)
                    )
                }
                CaptchaWebView(txappid = pending.txappid)
            }
        }
    }
}

@Composable
private fun CaptchaWebView(txappid: String) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val inject: () -> Unit = {
                    evaluateJavascript("window.initCaptcha && initCaptcha('$txappid')", null)
                }
                addJavascriptInterface(
                    CaptchaBridge { msg ->
                        when (msg) {
                            "__READY__" -> inject()
                            "__CANCEL__", "__ERROR__" -> KugouVipState.cancelCaptcha()
                            else -> KugouVipState.completeCaptcha(msg)
                        }
                    },
                    "CaptchaChannel"
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) = inject()
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        // 仅主框架失败才取消，避免子资源告警误关弹窗（md3Music 全部取消，这里更稳）
                        if (request.isForMainFrame) KugouVipState.cancelCaptcha()
                    }
                }
                webView = this
                loadUrl("file:///android_asset/web/verify_captcha.html")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    )
}

// ==================== 工具 ====================

/** 从 "yyyy-MM-dd..." 字符串解析日（仅当年月与展示月一致时返回 1-based 日）。 */
private fun parseDayOfMonth(value: String?, year: Int, month: Int): Int? {
    if (value.isNullOrEmpty()) return null
    val parts = value.split("-")
    if (parts.size < 3) return null
    if (parts[0].toIntOrNull() != year || parts[1].toIntOrNull() != month) return null
    return parts[2].toIntOrNull()?.takeIf { it in 1..31 }
}

/**
 * 相对到期文案（移植 md3Music vip_status.dart formatVipExpireText）：
 * "X年后/个月后/天后/小时后/分钟后到期"→"即将到期"→"已过期"。
 * endTime 形如 "2026-08-16 10:30:00"；无值或解析失败返回 null。
 */
private fun formatVipExpireText(context: Context, endTime: String?): String? {
    if (endTime.isNullOrEmpty()) return null
    val parsed = runCatching {
        (SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false })
            .parse(endTime)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(endTime)
    }.getOrNull() ?: return null
    val diffMs = parsed.time - System.currentTimeMillis()
    if (diffMs <= 0) return context.getString(R.string.kugou_vip_expired)
    val minutes = diffMs / 60_000
    val hours = diffMs / 3_600_000
    val days = diffMs / 86_400_000
    return when {
        days > 365 -> context.getString(R.string.kugou_vip_expire_in_years, (days / 365).toInt())
        days > 30 -> context.getString(R.string.kugou_vip_expire_in_months, (days / 30).toInt())
        days > 0 -> context.getString(R.string.kugou_vip_expire_in_days, days.toInt())
        hours > 0 -> context.getString(R.string.kugou_vip_expire_in_hours, hours.toInt())
        minutes > 0 -> context.getString(R.string.kugou_vip_expire_in_minutes, minutes.toInt())
        else -> context.getString(R.string.kugou_vip_expiring_soon)
    }
}
