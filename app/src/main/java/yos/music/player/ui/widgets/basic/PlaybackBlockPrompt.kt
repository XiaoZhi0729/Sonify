package yos.music.player.ui.widgets.basic

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.objects.PlaybackBlockState
import yos.music.player.data.repositories.CaptchaOutcome
import yos.music.player.data.repositories.KugouVipRepository

/**
 * 播放被风控（20028）时的说明弹窗。
 *
 * 只做三件事：解释发生了什么、给一个能点的动作、把结果落回 [PlaybackBlockState]。
 * 真正的滑块由 `KugouVipState.pendingCaptcha` 驱动（`KugouVerifyCaptchaDialog` 在根组合单点挂载），
 * 这里点「去验证」只是去**发起**它。
 *
 * 按钮由 [PlaybackBlockState.actionsOf] 决定：
 *  - 有 eventid / edt → 「去验证」+「重试」
 *  - 都没有           → 「重新登录」+「重试」
 */
@Composable
fun PlaybackBlockPrompt(
    onOpenLogin: () -> Unit,
    onRetry: () -> Unit,
    onVerified: () -> Unit
) {
    val block = PlaybackBlockState.block ?: return
    if (!PlaybackBlockState.promptRequested) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasChallenge = block.challenge != PlaybackBlockState.Challenge.None

    SonifyDialog(
        title = stringResource(id = R.string.playback_block_title),
        onDismissRequest = {
            PlaybackBlockState.dismissPrompt()
            YosDiagnostics.log("VERIFY_ACTION", "action" to "dismiss", "challenge" to block.challenge.name)
        },
        message = stringResource(
            id = if (hasChallenge) R.string.playback_block_message_verify
            else R.string.playback_block_message_none
        ),
        positiveContent = stringResource(
            id = if (hasChallenge) R.string.playback_block_action_verify
            else R.string.playback_block_action_login
        ),
        onPositive = {
            PlaybackBlockState.dismissPrompt()
            if (hasChallenge) {
                val token = block.challengeToken
                if (token.isNullOrEmpty()) {
                    PlaybackBlockState.downgradeToNoChallenge()
                } else scope.launch {
                    val outcome = runCatching { KugouVipRepository.verifyWithCaptcha(token) }
                        .getOrDefault(CaptchaOutcome.NoChallenge)
                    YosDiagnostics.log("VERIFY_ACTION", "action" to "verify", "result" to outcome.name)
                    when (outcome) {
                        CaptchaOutcome.Verified -> onVerified()
                        // 用户主动关掉滑块：保持胶囊，不打扰
                        CaptchaOutcome.Cancelled -> Unit
                        CaptchaOutcome.Rejected -> Toast.makeText(
                            context,
                            R.string.playback_block_rejected,
                            Toast.LENGTH_SHORT
                        ).show()
                        CaptchaOutcome.Unsupported,
                        CaptchaOutcome.NoChallenge -> {
                            // edt 也换不出验证码格式：降级成"没有入口"，下次就只给重登/重试
                            PlaybackBlockState.downgradeToNoChallenge()
                            Toast.makeText(
                                context,
                                R.string.playback_block_no_entry,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } else {
                YosDiagnostics.log("VERIFY_ACTION", "action" to "login", "challenge" to "none")
                onOpenLogin()
            }
        },
        negativeContent = stringResource(id = R.string.playback_block_action_retry),
        onNegative = {
            PlaybackBlockState.dismissPrompt()
            YosDiagnostics.log("VERIFY_ACTION", "action" to "retry", "challenge" to block.challenge.name)
            onRetry()
        }
    )
}
