package yos.music.player.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import yos.music.player.R
import yos.music.player.ui.widgets.basic.SonifyDialog
import java.util.Locale

/**
 * 开发者模式专用：假更新弹窗，逐项复刻 UpdateHost 的「发现新版本」布局，
 * 用于离线调试弹窗样式（含下载中/待安装两个阶段），不触发任何真实网络或安装行为。
 */
@Composable
fun FakeUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(UpdateStage.Available) }
    var progress by remember { mutableFloatStateOf(0f) }
    // 虚构但格式与真实 manifest 一致的完整信息
    val fakeVersion = "9.9.9 (9999)"
    val fakePublishedAt = "2026-10-06"
    val fakeApkSize = 52_428_800L // 50 MB
    val size = remember(fakeApkSize) { Formatter.formatFileSize(context, fakeApkSize) }
    val notes = remember {
        val locale = Locale.getDefault()
        if (locale.language == "zh") listOf(
            "重新设计了更新弹窗的视觉样式，信息层级更清晰",
            "优化了播放列表编辑模式下的拖拽排序动效",
            "修复了部分场景下歌词逐字时间戳渲染错位的问题",
            "提升了在线歌曲解析链路的稳定性，解析失败自动重试",
            "新增开发者模式，长按「检查更新」可预览此弹窗"
        ) else listOf(
            "Redesigned the update dialog visuals with clearer hierarchy",
            "Improved drag-and-drop reordering animation in queue editing",
            "Fixed word-timed lyrics rendering misalignment in some cases",
            "Improved stability of the online song resolve chain with auto retry",
            "Added developer mode: long-press \"Check for updates\" to preview this dialog"
        )
    }
    LaunchedEffect(stage) {
        if (stage == UpdateStage.Downloading) {
            progress = 0f
            while (progress < 1f) {
                delay(50)
                progress = (progress + 0.02f).coerceAtMost(1f)
            }
            stage = UpdateStage.Ready
        }
    }
    val busy = stage == UpdateStage.Downloading
    val title = when (stage) {
        UpdateStage.Downloading -> R.string.update_downloading
        UpdateStage.Ready -> R.string.update_available
        else -> R.string.update_available
    }
    val positive = when (stage) {
        UpdateStage.Available -> R.string.update_download
        UpdateStage.Ready -> R.string.update_install
        else -> R.string.common_ok
    }
    SonifyDialog(
        title = stringResource(title),
        positiveContent = stringResource(positive),
        positiveEnabled = !busy,
        closeOnPositive = false,
        negativeContent = stringResource(if (stage == UpdateStage.Downloading) R.string.update_cancel else R.string.update_later),
        negativeEnabled = true,
        tertiaryContent = if (stage == UpdateStage.Available) stringResource(R.string.update_ignore) else null,
        onTertiary = onDismiss,
        onDismissRequest = onDismiss,
        dismissEnabled = !busy,
        dismissOnBackPress = !busy,
        onPositive = {
            when (stage) {
                UpdateStage.Available -> stage = UpdateStage.Downloading
                UpdateStage.Ready -> onDismiss()
                else -> { }
            }
        },
        onNegative = {
            if (stage == UpdateStage.Downloading) stage = UpdateStage.Available else onDismiss()
        },
        content = {
            val foreground = LocalContentColor.current
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.update_version, fakeVersion),
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                val metadata = "$fakePublishedAt  ·  $size"
                Spacer(Modifier.height(6.dp))
                Text(
                    metadata,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = foreground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = foreground.copy(alpha = 0.1f))
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.update_release_notes),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = foreground.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    notes.forEach { note ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Text("\u2022", fontSize = 14.sp, lineHeight = 21.sp, color = foreground.copy(alpha = 0.4f))
                            Text(note, fontSize = 14.sp, lineHeight = 21.sp, color = foreground.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (stage == UpdateStage.Downloading || stage == UpdateStage.Verifying) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.update_progress, (progress * 100).toInt()),
                        fontSize = 12.sp,
                        color = foreground.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    )
}
