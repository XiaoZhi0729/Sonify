package yos.music.player.ui.pages.settings.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState
import yos.music.player.data.repositories.KugouVipRepository
import yos.music.player.native.KugouApiService
import yos.music.player.native.KugouLoginAccountCandidate
import yos.music.player.native.KugouLoginException
import yos.music.player.native.PhoneLoginResult
import yos.music.player.ui.UI
import yos.music.player.ui.toUI
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.theme.headline
import yos.music.player.ui.theme.headlineDark
import yos.music.player.ui.theme.withNight

/** 未登录态的登录方式分段（与 UI 字符串 kugou_login_method_* 顺序一致）。 */
private const val LOGIN_METHOD_QR = 0
private const val LOGIN_METHOD_PHONE = 1

/** 二维码流程状态（状态码语义沿用 POC / md3Music login_page.dart 真实定义）。 */
private enum class QrState { IDLE, LOADING, WAITING, SCANNED, EXPIRED, TIMEOUT, FAILED }

/**
 * 酷狗账号正式登录页。
 *
 * 未登录时提供两种登录方式（顶部分段切换）：
 * - 扫码：loginQrKey → loginQrCreate(base64 PNG) → loginQrCheck 轮询（1.5s × 120，
 *   状态码 4=成功 / 2、803=已扫码待确认 / 800、0、402=过期，均沿用已验证定义）；
 * - 手机号：sendLoginCaptcha（发短信验证码，60s 倒计时）→ loginByCellphone，
 *   34175（一个手机号绑定多个账号）时弹窗选择账号后携带 userid 重新提交。
 *
 * 两种方式登录成功均由 KugouApiService 内部 applyLogin 写 MMKV 并同步
 * [KugouAccountState]，本页不保存任何 token/userid；随后重建 VIP 派生态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouLogin(navController: NavController) =
    SettingBackground {
        Title(
            title = stringResource(id = R.string.settings_online_kugou_account),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("kugou_login") {
                    Column(Modifier.fillMaxSize()) {
                        var showLogoutConfirm by remember { mutableStateOf(false) }

                        // ---------- 状态区（唯一真实源 KugouAccountState） ----------
                        ListHeader(content = stringResource(id = R.string.kugou_login_hint))
                        RoundColumn {
                            DefaultItem(
                                title = stringResource(
                                    id = if (KugouAccountState.isLoggedIn) R.string.kugou_login_status_logged_in
                                    else R.string.kugou_login_status_not_logged_in
                                ),
                                onClick = null
                            )
                        }

                        if (KugouAccountState.isLoggedIn) {
                            // ---------- 已登录 ----------
                            GroupSpacer()
                            val useridText = stringResource(
                                id = R.string.kugou_login_userid,
                                KugouAccountState.userid ?: ""
                            )
                            RoundColumn {
                                DefaultItem(
                                    title = useridText,
                                    onClick = null
                                )
                            }

                            GroupSpacer()
                            // VIP 签到入口 + 自动领取开关（酷狗 youth 活动）
                            RoundColumn {
                                LabelItem(
                                    title = stringResource(id = R.string.kugou_vip_sign_in_title),
                                    desc = stringResource(
                                        id = if (KugouVipState.isTodaySigned()) R.string.kugou_vip_signed_today
                                        else R.string.kugou_vip_not_signed_today
                                    )
                                ) {
                                    navController.toUI(UI.Settings.KugouVipSignIn)
                                }
                                Divider()
                                SwitchItem(
                                    title = stringResource(id = R.string.kugou_vip_auto_receive),
                                    desc = stringResource(id = R.string.kugou_vip_auto_receive_desc),
                                    onClick = {
                                        KugouVipRepository.AutoReceiveVip = !KugouVipRepository.AutoReceiveVip
                                    },
                                    checkedLambda = { KugouVipRepository.AutoReceiveVip }
                                )
                            }

                            GroupSpacer()
                            RoundColumn {
                                LabelItem(title = stringResource(id = R.string.kugou_logout)) {
                                    showLogoutConfirm = true
                                }
                            }

                            if (showLogoutConfirm) {
                                OptionDialog(
                                    icon = {},
                                    title = stringResource(id = R.string.kugou_logout),
                                    subTitle = stringResource(id = R.string.kugou_logout_confirm),
                                    content = null,
                                    positiveContent = stringResource(id = R.string.kugou_logout),
                                    negativeContent = stringResource(id = R.string.common_cancel),
                                    destructive = true,
                                    onPositive = {
                                        showLogoutConfirm = false
                                        // 只清账号凭证（token/userid/vip_token），dfid 与本地数据保留
                                        KugouApiService.clearLogin()
                                    },
                                    onNegative = { showLogoutConfirm = false },
                                    onDismissRequest = { showLogoutConfirm = false }
                                )
                            }
                        } else {
                            // ---------- 未登录：扫码 / 手机号两种方式分段切换 ----------
                            GroupSpacer()
                            var loginMethod by rememberSaveable { mutableIntStateOf(LOGIN_METHOD_QR) }
                            LoginMethodSegment(
                                selected = loginMethod,
                                onSelect = { loginMethod = it }
                            )
                            GroupSpacer()
                            if (loginMethod == LOGIN_METHOD_QR) {
                                QrLoginSection()
                            } else {
                                PhoneLoginSection()
                            }
                        }
                    }
                }
            })
    }

/**
 * 登录方式分段切换（扫码 | 手机号）。
 * 几何对齐 RoundColumn 卡片：16.5dp 页边距、9dp 圆角；容器底色同设置卡片
 * （onSecondary），选中胶囊用 primary。
 */
@Composable
private fun LoginMethodSegment(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(id = R.string.kugou_login_method_qr),
        stringResource(id = R.string.kugou_login_method_phone)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.5.dp)
            .background(
                MaterialTheme.colorScheme.onSecondary,
                RoundedCornerShape(9.dp)
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "loginMethodBg$index"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(background)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) Color.White
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
            }
        }
    }
}

/** 未登录时的二维码登录区：自动生成 → 扫码 → 确认；过期/失败可手动重新生成。 */
@Composable
private fun QrLoginSection() {
    val scope = rememberCoroutineScope()
    val api = remember { KugouApiService.getInstance() }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var qrState by remember { mutableStateOf(QrState.IDLE) }
    var pollJob by remember { mutableStateOf<Job?>(null) }

    /** 轮询（POC 同款节奏：1.5s × 120；成功/过期/超时即停）。 */
    fun pollQr(key: String) {
        // 重新生成时取消旧轮询，避免两个 key 交错请求、旧 key 过期回写覆盖新状态
        pollJob?.cancel()
        pollJob = scope.launch {
            var attempts = 0
            while (attempts < 120 && !KugouAccountState.isLoggedIn) {
                delay(1500)
                attempts++
                val st = api.loginQrCheck(key).getOrNull() ?: continue
                when (st) {
                    4 -> {
                        // 登录成功：applyLogin 已写凭证并按账号重载本地已签标记；
                        // 再后台重建服务端派生态（VIP 详情/当月打卡/自动补签）
                        scope.launch { KugouVipRepository.onLoggedIn() }
                        return@launch
                    }
                    2, 803 -> qrState = QrState.SCANNED
                    800, 0, 402 -> {
                        qrState = QrState.EXPIRED
                        return@launch
                    }
                }
            }
            if (!KugouAccountState.isLoggedIn) qrState = QrState.TIMEOUT
        }
    }

    /** 生成二维码（key → base64 PNG → Bitmap），成功后开始轮询。 */
    fun refreshQr() {
        if (qrState == QrState.LOADING) return
        qrState = QrState.LOADING
        qrBitmap = null
        scope.launch {
            val key = api.loginQrKey().getOrNull()
            if (key == null) {
                qrState = QrState.FAILED
                return@launch
            }
            api.loginQrCreate(key).fold(
                onSuccess = { dataUrl ->
                    val b64 = dataUrl.substringAfter(",", "")
                    val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp == null) {
                        qrState = QrState.FAILED
                        return@fold
                    }
                    qrBitmap = bmp
                    qrState = QrState.WAITING
                    pollQr(key)
                },
                onFailure = { qrState = QrState.FAILED }
            )
        }
    }

    // 进入页面（未登录且尚未生成）自动生成一次；离开页面 scope 取消轮询
    LaunchedEffect(Unit) {
        if (qrBitmap == null && qrState == QrState.IDLE) refreshQr()
    }

    val stateText = when (qrState) {
        QrState.IDLE -> ""
        QrState.LOADING -> stringResource(id = R.string.kugou_qr_loading)
        QrState.WAITING -> stringResource(id = R.string.kugou_qr_waiting)
        QrState.SCANNED -> stringResource(id = R.string.kugou_qr_scanned)
        QrState.EXPIRED -> stringResource(id = R.string.kugou_qr_expired)
        QrState.TIMEOUT -> stringResource(id = R.string.kugou_qr_timeout)
        QrState.FAILED -> stringResource(id = R.string.kugou_qr_failed)
    }

    RoundColumn {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            qrBitmap?.let { bmp ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(id = R.string.kugou_login_qr_desc),
                        modifier = Modifier
                            .size(210.dp)
                            .background(Color.White)
                            .padding(10.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.kugou_login_qr_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stateText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            } ?: Text(
                text = stateText,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 40.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
    }

    GroupSpacer()
    RoundColumn {
        LabelItem(
            title = stringResource(
                id = if (qrBitmap == null && qrState == QrState.IDLE) R.string.kugou_qr_generate
                else R.string.kugou_qr_regenerate
            )
        ) {
            refreshQr()
        }
    }
}

/**
 * 手机号（短信验证码）登录区：手机号 + 验证码 → 登录。
 *
 * 链路与参考项目 md3Music 一致：发码（/captcha/sent，成功后 60s 倒计时）→
 * 登录（/login/cellphone）；error_code=34175（一个手机号绑定多个账号）时弹窗
 * 选择账号，选中后携带 userid 重新提交。成功由 loginByCellphone 内部
 * applyLogin 写凭证，KugouAccountState 变化后本页整体切到已登录态。
 *
 * 视觉对齐设置页设计语言：输入行 = DefaultItem 同款行排版（15/11dp 内边距、
 * 16.5sp），不套灰盒；登录按钮 = OptionDialog 主按钮同款 50dp 胶囊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneLoginSection() {
    val scope = rememberCoroutineScope()
    val api = remember { KugouApiService.getInstance() }
    val context = LocalContext.current

    var mobile by rememberSaveable { mutableStateOf("") }
    var smsCode by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var loggingIn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<KugouLoginAccountCandidate>?>(null) }

    val mobileValid = mobile.length == 11
    val codeValid = smsCode.length == 6

    // 发码倒计时：每秒递减、归零即止；离开页面随组合销毁，无需手动取消
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    fun showMessage(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    /** 307xx 等业务失败取上游 error_msg 透传；网络/其它异常回退本地通用文案。 */
    fun failureText(e: Throwable): String =
        (e as? KugouLoginException)?.errorMsg?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.kugou_qr_failed)

    fun sendCode() {
        if (sending || countdown > 0 || !mobileValid) return
        Vibrator.click(context)
        sending = true
        showMessage(null, false)
        scope.launch {
            api.sendLoginCaptcha(mobile).fold(
                onSuccess = {
                    // 发码成功只进入倒计时；不额外提示“已发送”（倒计时文案已表达该状态）
                    countdown = 60
                    showMessage(null, false)
                },
                onFailure = { e -> showMessage(failureText(e), true) }
            )
            sending = false
        }
    }

    fun login(selectedUserid: String? = null) {
        if (loggingIn || !mobileValid || !codeValid) return
        Vibrator.click(context)
        loggingIn = true
        showMessage(null, false)
        scope.launch {
            api.loginByCellphone(mobile, smsCode, selectedUserid).fold(
                onSuccess = { result ->
                    when (result) {
                        is PhoneLoginResult.Success ->
                            // applyLogin 已写凭证、KugouAccountState 已同步；
                            // 后台重建服务端派生态（VIP 详情/当月打卡/自动补签），与扫码路径一致
                            scope.launch { KugouVipRepository.onLoggedIn() }
                        is PhoneLoginResult.NeedChooseAccount -> candidates = result.candidates
                        is PhoneLoginResult.Failed ->
                            showMessage(
                                result.errorMsg?.takeIf { it.isNotBlank() }
                                    ?: context.getString(R.string.kugou_phone_login_failed),
                                true
                            )
                    }
                },
                onFailure = { e -> showMessage(failureText(e), true) }
            )
            loggingIn = false
        }
    }

    RoundColumn {
        LoginTextFieldRow(
            value = mobile,
            placeholder = stringResource(id = R.string.kugou_phone_mobile_hint),
            imeAction = ImeAction.Next,
            onValueChange = { mobile = it.filter(Char::isDigit).take(11) }
        )
        Divider()
        LoginTextFieldRow(
            value = smsCode,
            placeholder = stringResource(id = R.string.kugou_phone_code_hint),
            imeAction = ImeAction.Done,
            onValueChange = { smsCode = it.filter(Char::isDigit).take(6) }
        ) {
            SendCodeTextButton(
                enabled = mobileValid && !sending && countdown == 0,
                sending = sending,
                countdown = countdown,
                onClick = ::sendCode
            )
        }
    }

    GroupSpacer()
    // 主按钮独立于卡片之外（与 OptionDialog 主按钮/参考登录页一致），页面级 16.5dp 边距
    LoginCapsuleButton(
        enabled = mobileValid && codeValid && !loggingIn,
        loggingIn = loggingIn,
        onClick = { login() }
    )

    message?.let { text ->
        Text(
            text = text,
            fontSize = 13.2.sp,
            lineHeight = 16.2.sp,
            color = if (messageIsError) MaterialTheme.colorScheme.error
            else headline withNight headlineDark,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 5.dp)
        )
    }

    // 多账号选择（error_code=34175）：选中后携带 userid 重新提交登录
    candidates?.let { list ->
        OptionDialog(
            icon = {},
            title = stringResource(id = R.string.kugou_phone_choose_account_title),
            subTitle = stringResource(id = R.string.kugou_phone_choose_account_subtitle),
            content = {
                Column {
                    list.forEachIndexed { index, account ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background((Color.LightGray withNight Color.DarkGray).copy(alpha = 0.25f))
                                .clickable {
                                    candidates = null
                                    login(account.userid)
                                }
                                .padding(horizontal = 15.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = account.nickname?.takeIf { it.isNotBlank() }
                                        ?: stringResource(id = R.string.kugou_login_userid, account.userid),
                                    fontSize = 16.5.sp,
                                    lineHeight = 20.5.sp
                                )
                                if (!account.nickname.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(id = R.string.kugou_login_userid, account.userid),
                                        fontSize = 13.2.sp,
                                        lineHeight = 16.2.sp,
                                        modifier = Modifier.alpha(0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            positiveContent = stringResource(id = R.string.common_cancel),
            onPositive = { candidates = null },
            onDismissRequest = { candidates = null }
        )
    }
}

/**
 * 登录页输入行：DefaultItem 同款行排版（水平 15dp、垂直 12dp、16.5sp），
 * 透明底直接嵌在 RoundColumn 卡片内（与设置行一致，不再套灰盒）。
 * [trailing] 为行尾插槽（验证码行的「获取验证码」），由调用方负责加 start 留白。
 */
@Composable
private fun LoginTextFieldRow(
    value: String,
    placeholder: String,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 16.5.sp,
                    modifier = Modifier.alpha(0.45f),
                    maxLines = 1
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 16.5.sp,
                    color = MaterialTheme.colorScheme.onBackground
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = imeAction
                ),
                modifier = Modifier.fillMaxWidth(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
        if (trailing != null) {
            Column(Modifier.padding(start = 15.dp)) {
                trailing()
            }
        }
    }
}

/**
 * 「获取验证码」文字按钮：SelectItem 行尾同款 15sp；可用时 primary 色，
 * 发送中/倒计时内置灰（文案随之切换，宽度变化由行尾自然容纳）。
 */
@Composable
private fun SendCodeTextButton(
    enabled: Boolean,
    sending: Boolean,
    countdown: Int,
    onClick: () -> Unit
) {
    Text(
        text = when {
            sending -> stringResource(id = R.string.kugou_phone_sending)
            countdown > 0 -> stringResource(id = R.string.kugou_phone_resend_countdown, countdown)
            else -> stringResource(id = R.string.kugou_phone_send_code)
        },
        fontSize = 15.sp,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
    )
}

/** 登录按钮：OptionDialog 主按钮同款 50dp 胶囊（圆角 = 高度一半、primary 底、16.5sp 白字）。
 *  禁用态保留主色仅降低不透明度（仍读作“登录按钮、只是未就绪”，避免灰底糊在黑背景里）。 */
@Composable
private fun LoginCapsuleButton(enabled: Boolean, loggingIn: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        label = "loginButtonBg"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.5.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (loggingIn) stringResource(id = R.string.kugou_phone_logging_in)
            else stringResource(id = R.string.kugou_phone_login),
            fontSize = 16.5.sp,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.7f)
        )
    }
}
