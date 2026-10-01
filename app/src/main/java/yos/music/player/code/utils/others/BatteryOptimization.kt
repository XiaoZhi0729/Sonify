package yos.music.player.code.utils.others

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import yos.music.player.R

/**
 * 电池优化豁免：OEM（尤其 HyperOS/MIUI 的 PowerKeeper）会把熄屏后的音乐应用
 * 降级为 cached 并冻结进程，表现为播放几分钟后无声、亮屏解冻后原地续播。
 * 加入 AOSP 电池优化白名单后，Doze 与多数省电策略会放过本应用；
 * HyperOS 的「省电策略」仍需用户在系统设置里手动设为「无限制」。
 */
object BatteryOptimization {

    fun isIgnoringBatteryOptimization(context: Context): Boolean {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 未豁免时弹系统申请框（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）。
     * @return 是否真正发起了申请（已豁免或所有入口都失败时为 false）
     */
    fun requestIgnore(context: Context): Boolean {
        if (isIgnoringBatteryOptimization(context)) {
            return false
        }
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        return try {
            context.startActivity(requestIntent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                true
            } catch (_: ActivityNotFoundException) {
                openAppDetails(context)
                false
            }
        } catch (_: Exception) {
            openAppDetails(context)
            false
        }
    }

    private fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        } catch (_: Exception) {
            Toast.makeText(context, R.string.tip_intent_resolve_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
