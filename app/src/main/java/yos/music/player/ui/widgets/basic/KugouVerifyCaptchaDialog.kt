package yos.music.player.ui.widgets.basic

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import yos.music.player.R
import yos.music.player.data.objects.KugouVipState

/**
 * 酷狗 20028 二次验证（腾讯滑块）弹窗。
 *
 * 原先只挂在签到页内部，于是"播放链路遇到 20028"时根本没有验证入口，只能退避重试。
 * 现在由应用根组合挂载一次（MainActivity），任何页面触发的验证请求都能弹出——
 * 是否显示仍只由 [KugouVipState.pendingCaptcha] 决定。
 */
@Composable
fun KugouVerifyCaptchaDialog(pending: KugouVipState.PendingCaptcha) {
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
            shape = com.kyant.shapes.RoundedRectangle(24.dp),
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
                            .clickable { KugouVipState.cancelCaptcha() }
                            .padding(8.dp)
                    )
                }
                CaptchaWebView(txappid = pending.txappid)
            }
        }
    }
}

/** JS 桥（与 md3Music verify_captcha.html 约定同名 CaptchaChannel，回调统一切主线程）。 */
private class CaptchaBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(value: String?) {
        value ?: return
        Handler(Looper.getMainLooper()).post { onMessage(value) }
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
