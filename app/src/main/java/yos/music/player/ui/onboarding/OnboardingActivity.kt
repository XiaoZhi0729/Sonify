package yos.music.player.ui.onboarding

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import yos.music.player.R
import yos.music.player.ui.theme.YosMusicTheme
import yos.music.player.ui.widgets.basic.ProvideRawWindowInsets

/**
 * 首次启动 / 重看引导的独立页面（Activity）。
 *
 * 相比挂在 MainActivity 里的悬浮 Box 遮罩：
 * - 拥有独立窗口与 `adjustNothing`，不与主壳的底栏 / 迷你条 / 播放页共用层级；
 * - 从「设置 › 关于 › 重新查看引导」进入时走标准 Activity 栈，返回即回到设置页。
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.onboarding_fade_in,
                R.anim.onboarding_fade_out
            )
        }
        val forceLogin = intent.getBooleanExtra(EXTRA_FORCE_LOGIN, false)
        setContent {
            YosMusicTheme {
                ProvideRawWindowInsets {
                    OnboardingOverlay(
                        forceLogin = forceLogin,
                        onFinish = {
                            OnboardingPrefs.completed = true
                            if (!forceLogin) {
                                OnboardingPrefs.pendingHomeReveal = true
                            }
                            finishWithFade()
                        }
                    )
                }
            }
        }
    }

    private fun finishWithFade() {
        finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.onboarding_fade_in, R.anim.onboarding_fade_out)
        }
    }

    companion object {
        const val EXTRA_FORCE_LOGIN = "force_login"
    }
}
