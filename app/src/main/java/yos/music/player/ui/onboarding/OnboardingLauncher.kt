package yos.music.player.ui.onboarding

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import yos.music.player.R

/**
 * 引导页入口。
 *
 * 引导已拆成独立页面 [OnboardingActivity]：
 * - 首次启动（`forceLogin = false`）：已登录则跳过登录与验证码两屏；
 * - 设置 › 关于 › 重新查看引导（`forceLogin = true`）：强制走完整流程。
 */
object OnboardingLauncher {

    fun open(context: Context, forceLogin: Boolean = true, fadeEnter: Boolean = false) {
        val intent = Intent(context, OnboardingActivity::class.java).apply {
            putExtra(OnboardingActivity.EXTRA_FORCE_LOGIN, forceLogin)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (fadeEnter && context is Activity) {
            val options = ActivityOptions.makeCustomAnimation(
                context,
                R.anim.onboarding_fade_in,
                0
            ).toBundle()
            context.startActivity(intent, options)
        } else {
            context.startActivity(intent)
        }
    }
}
