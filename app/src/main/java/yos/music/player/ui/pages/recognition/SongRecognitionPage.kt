package yos.music.player.ui.pages.recognition

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import yos.music.player.R
import yos.music.player.data.recognition.RecognitionConfig
import yos.music.player.data.recognition.RecognitionParser
import yos.music.player.data.recognition.RecognizedSong
import yos.music.player.data.recognition.SongRecognizer
import yos.music.player.data.recognition.playRecognizedSong
import yos.music.player.data.recognition.prepareSegmentPcm
import yos.music.player.native.KugouApiService
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.Title

/**
 * 听歌识曲页（入口：搜索页搜索框右侧的麦克风标志）。
 *
 * 业务链完全对照 md3Music 的 song_recognition_page.dart：
 * 麦克风录 8s（44.1kHz 原始信号）→ 降采样 8kHz + 静音判定 + 增益归一化 →
 * POST /audio/match → 解析命中歌曲 → 播放。
 * 最多听 7 段（56s），静音段直接跳过续录；任意一段命中即结束。
 *
 * 权限：首次点麦克风才申请 RECORD_AUDIO（不在启动流程里打扰用户）。
 */
@Composable
fun SongRecognitionPage(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { RecognitionController() }

    val permissionDeniedMsg = stringResource(R.string.song_recognition_permission_denied)
    val recordFailedMsg = stringResource(R.string.song_recognition_record_failed)
    val notFoundMsg = stringResource(R.string.song_recognition_not_found)
    val silentMsg = stringResource(R.string.song_recognition_no_sound)
    val playFailedMsg = stringResource(R.string.song_recognition_play_failed)

    // 调试包把最后一段录音落到 cache，便于 adb pull 回来判断"没录到声音"还是"指纹不认"
    val dumpDir = remember(context) {
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) context.cacheDir else null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            controller.start(scope, recordFailedMsg, notFoundMsg, silentMsg, dumpDir)
        } else {
            controller.fail(permissionDeniedMsg)
        }
    }

    val onMicClick: () -> Unit = {
        when {
            controller.isRunning -> controller.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED ->
                controller.start(scope, recordFailedMsg, notFoundMsg, silentMsg, dumpDir)

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 离开页面（返回 / 切 Tab）必须停录，否则麦克风会一直被占用
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    val song = controller.result

    Title(
        title = stringResource(R.string.song_recognition_title),
        onBack = {
            controller.stop()
            navController.popBackStack()
        }
    ) {
        item("Mic") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillParentMaxHeight(0.52f),
                contentAlignment = Alignment.Center
            ) {
                MicButton(phase = controller.phase, onClick = onMicClick)
            }
        }

        item("Status") {
            Text(
                text = statusText(controller.phase, controller.attempt),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
        }

        if (song != null) {
            item("Result") {
                ResultCard(
                    song = song,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 24.dp),
                    onPlay = {
                        scope.launch {
                            playRecognizedSong(song).onFailure {
                                controller.fail(playFailedMsg)
                            }
                        }
                    }
                )
            }
        }

        val error = controller.errorMessage
        if (error != null) {
            item("Error") {
                ErrorCard(
                    message = error,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun statusText(phase: RecognitionPhase, attempt: Int): String = when (phase) {
    RecognitionPhase.Recording -> stringResource(
        R.string.song_recognition_listening,
        (attempt * RecognitionConfig.SEGMENT_SECONDS).coerceAtMost(RecognitionConfig.MAX_TOTAL_SECONDS),
        RecognitionConfig.MAX_TOTAL_SECONDS
    )

    RecognitionPhase.Recognizing -> stringResource(R.string.song_recognition_recognizing, attempt)
    else -> stringResource(R.string.song_recognition_hint)
}

/** 140dp 大圆钮：录音中脉冲放大，颜色随状态切换（与参照实现一致）。 */
@Composable
private fun MicButton(phase: RecognitionPhase, onClick: () -> Unit) {
    val isRecording = phase == RecognitionPhase.Recording
    val isBusy = isRecording || phase == RecognitionPhase.Recognizing

    // 只在录音时跑脉冲动画（静止态不保留无限动画，省掉无谓的逐帧重组）
    var pulse by remember { mutableStateOf(1f) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                animate(1f, 1.12f, animationSpec = tween(600, easing = FastOutSlowInEasing)) { v, _ -> pulse = v }
                animate(1.12f, 1f, animationSpec = tween(600, easing = FastOutSlowInEasing)) { v, _ -> pulse = v }
            }
        } else {
            pulse = 1f
        }
    }

    val containerColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isBusy -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val iconColor = when {
        isRecording -> MaterialTheme.colorScheme.onError
        isBusy -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = Modifier
            .scale(if (isRecording) pulse else 1f)
            .size(140.dp)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_song_recognition_mic),
            contentDescription = stringResource(R.string.song_recognition_title),
            modifier = Modifier.size(56.dp),
            tint = iconColor
        )
    }
}

@Composable
private fun ResultCard(
    song: RecognizedSong,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background((Color.LightGray withNight Color.DarkGray).copy(alpha = 0.25f))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.song_recognition_success),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            val score = song.score
            if (!score.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.song_recognition_score, score),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!song.coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                val album = song.album
                if (!album.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = album,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        NormalButton(
            icon = painterResource(id = R.drawable.button_icon_play),
            label = stringResource(id = R.string.normal_button_play),
            modifier = Modifier.fillMaxWidth(),
            onClick = onPlay
        )
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/** 页面内识别流程的三种进行态 + 结束态。 */
private enum class RecognitionPhase { Idle, Recording, Recognizing, Done, Failed }

/**
 * 识别流程状态机（页面级，随页面销毁而废弃）。
 *
 * 状态全部是 Compose State，循环体跑在页面的 [CoroutineScope]（主线程）上，
 * 录音/网络各自在内部切 IO，因此这里只做串行编排。
 */
private class RecognitionController {

    var phase by mutableStateOf(RecognitionPhase.Idle)
        private set
    var attempt by mutableStateOf(0)
        private set
    var result by mutableStateOf<RecognizedSong?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val recognizer = SongRecognizer()
    private var job: Job? = null

    val isRunning: Boolean
        get() = phase == RecognitionPhase.Recording || phase == RecognitionPhase.Recognizing

    fun start(
        scope: CoroutineScope,
        recordFailedMessage: String,
        notFoundMessage: String,
        silentMessage: String,
        dumpDir: File?
    ) {
        if (job?.isActive == true) return
        result = null
        errorMessage = null
        attempt = 0
        job = scope.launch { loop(recordFailedMessage, notFoundMessage, silentMessage, dumpDir) }
    }

    fun stop() {
        recognizer.requestStop()
        job?.cancel()
        job = null
        if (isRunning) phase = RecognitionPhase.Idle
    }

    /** 页面级错误（权限被拒 / 播放失败等），不改变已有结果。 */
    fun fail(message: String) {
        errorMessage = message
        if (!isRunning) phase = RecognitionPhase.Failed
    }

    private suspend fun loop(
        recordFailedMessage: String,
        notFoundMessage: String,
        silentMessage: String,
        dumpDir: File?
    ) {
        var lastError: String? = null
        var silentSegments = 0
        try {
            while (attempt < RecognitionConfig.MAX_ATTEMPTS) {
                attempt += 1

                phase = RecognitionPhase.Recording
                val raw = recognizer.recordSegment()
                if (raw == null) {
                    phase = RecognitionPhase.Failed
                    errorMessage = recordFailedMessage
                    return
                }

                phase = RecognitionPhase.Recognizing
                val segment = prepareSegmentPcm(raw)
                Log.d(
                    TAG,
                    "segment #$attempt raw=${raw.size}B pcm8k=${segment.pcm.size}B " +
                            "maxAmp=${segment.maxAmplitude} silent=${segment.isSilent}"
                )
                if (dumpDir != null) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            File(dumpDir, "recog_seg_${attempt}_raw.pcm").writeBytes(raw)
                            File(dumpDir, "recog_seg_${attempt}_8k.pcm").writeBytes(segment.pcm)
                        }
                    }
                }
                // 静音段（环境太吵/太安静/没放歌）直接跳过，继续听下一段
                if (segment.isSilent) {
                    silentSegments++
                    continue
                }

                val response = KugouApiService.getInstance().audioMatch(segment.pcm)
                val json = response.getOrNull()
                if (json == null) {
                    lastError = response.exceptionOrNull()?.message
                    continue
                }

                val song = RecognitionParser.parse(json)
                if (song != null) {
                    result = song
                    phase = RecognitionPhase.Done
                    return
                }
            }

            phase = RecognitionPhase.Failed
            // 每一段都是静音：问题在采集侧（麦克风没拾到声音），不是"没识别出来"
            errorMessage = when {
                lastError != null -> lastError
                silentSegments == attempt -> silentMessage
                else -> notFoundMessage
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            phase = RecognitionPhase.Failed
            errorMessage = error.message ?: notFoundMessage
        }
    }

    companion object {
        private const val TAG = "SongRecognition"
    }
}
