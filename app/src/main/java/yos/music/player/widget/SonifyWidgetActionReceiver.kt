package yos.music.player.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.code.MediaController
import yos.music.player.code.NotificationPlaybackMode
import yos.music.player.code.PlaybackRestoreGate
import yos.music.player.code.nextNotificationMode
import yos.music.player.code.utils.player.FadeExo
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.PlayStatus
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.MediaViewModelObject

class SonifyWidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SonifyWidgetUpdater.updateAllWidgets(appContext)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // 仅在恢复已完成时立即同步翻转乐观态；冷启动场景等恢复完成再动作与刷新
                if (PlaybackRestoreGate.isReady &&
                    (action == ACTION_PLAY_PAUSE || action == ACTION_RESUME_EMPTY)
                ) {
                    val wasPlaying = MediaViewModelObject.isPlaying.value
                    val nextPlaying = if (action == ACTION_RESUME_EMPTY) true else !wasPlaying
                    SonifyWidgetState.hasPlayedInSession = true
                    SonifyWidgetUpdater.pushOptimisticPlayState(appContext, isPlaying = nextPlaying)
                }

                PlaybackRestoreGate.awaitReady()

                when (action) {
                    ACTION_PLAY_PAUSE -> {
                        SonifyWidgetState.hasPlayedInSession = true
                        handlePlayPause()
                        scheduleFadeReconcile(appContext)
                    }
                    ACTION_RESUME_EMPTY -> {
                        SonifyWidgetState.hasPlayedInSession = true
                        handleResumeEmpty()
                        scheduleFadeReconcile(appContext)
                    }
                    ACTION_NEXT -> {
                        SonifyWidgetState.hasPlayedInSession = true
                        MediaController.userSkipNext()
                    }
                    ACTION_MODE -> {
                        handleCycleMode(appContext)
                    }
                    ACTION_PLAY_RECENT -> {
                        val mediaId = intent.getStringExtra(EXTRA_TRACK_MEDIA_ID)
                        val uri = intent.getStringExtra(EXTRA_TRACK_URI)
                        val index = intent.getIntExtra(EXTRA_TRACK_INDEX, -1)
                        handlePlayRecent(mediaId, uri, index)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun scheduleFadeReconcile(appContext: Context) {
        // FadeExo 淡出动画耗时 200ms，动画结束后才调用 player.pause()，
        // 延迟 260ms 兜底校准一次确保桌面状态与真实引擎完全一致。
        CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(260L)
            SonifyWidgetUpdater.updateAllWidgetsAsync(appContext)
        }
    }

    private suspend fun handlePlayPause() {
        val controller = MediaController.mediaControl
        if (controller != null && controller.mediaItemCount > 0) {
            // 必须以 MediaViewModelObject.isPlaying（即 FadeExo 目标态）为准，
            // 不能读 controller.isPlaying：200ms 淡出期间引擎仍在出声，读它会把连点判成连续两次 pause。
            val currentlyPlaying = MediaViewModelObject.isPlaying.value
            with(FadeExo) {
                if (currentlyPlaying) {
                    controller.fadePause()
                } else {
                    controller.fadePlay()
                }
            }
            SonifyWidgetUpdater.pushOptimisticPlayState(
                controller.let { yos.music.player.code.MediaController.appContext } ?: return,
                isPlaying = !currentlyPlaying,
            )
            return
        }
        handleResumeEmpty()
    }

    private suspend fun handleResumeEmpty() {
        val controller = MediaController.mediaControl
        if (controller != null && controller.mediaItemCount > 0) {
            with(FadeExo) { controller.fadePlay() }
            return
        }
        val (status, list) = withContext(Dispatchers.IO) {
            runCatching { MusicLibrary.loadPlayStatus() }.getOrNull() to
                runCatching { MusicLibrary.loadPlayList() }.getOrNull()
        }
        val queue = MediaController.playingMusicList.value ?: list?.playingMusicList
        val target = MediaController.musicPlaying.value ?: status?.music ?: queue?.firstOrNull()
        if (target != null && !queue.isNullOrEmpty()) {
            MediaController.prepare(
                music = target,
                thisMusicList = queue,
                position = status?.position ?: 0L,
                shuffleModeEnabled = status?.shuffleModeEnabled ?: false,
                repeatMode = status?.repeatMode ?: 2,
                play = true,
            )
        }
    }

    private suspend fun handlePlayRecent(mediaId: String?, uri: String?, index: Int) {
        val queue = MediaController.playingMusicList.value
            ?: withContext(Dispatchers.IO) {
                runCatching { MusicLibrary.loadPlayList().playingMusicList }.getOrNull()
            }
        if (queue.isNullOrEmpty()) {
            handleResumeEmpty()
            return
        }

        val target: YosMediaItem? = queue.firstOrNull { item ->
            (!mediaId.isNullOrBlank() && item.mediaId == mediaId) ||
                (!uri.isNullOrBlank() && item.uri?.toString() == uri)
        } ?: queue.getOrNull(index) ?: queue.firstOrNull()

        if (target != null) {
            SonifyWidgetState.hasPlayedInSession = true
            MediaController.prepare(
                music = target,
                thisMusicList = queue,
                position = 0L,
                shuffleModeEnabled = MediaViewModelObject.shuffleModeEnabled.value,
                repeatMode = MediaViewModelObject.repeatMode.intValue,
                play = true,
            )
        }
    }

    private suspend fun handleCycleMode(appContext: Context) {
        val controller = MediaController.mediaControl
        val currentShuffle = controller?.shuffleModeEnabled ?: MediaViewModelObject.shuffleModeEnabled.value
        val currentRepeat = controller?.repeatMode ?: MediaViewModelObject.repeatMode.intValue
        val nextMode = nextNotificationMode(currentShuffle, currentRepeat)
        val (nextShuffle, nextRepeat) = when (nextMode) {
            NotificationPlaybackMode.ORDER -> false to Player.REPEAT_MODE_OFF
            NotificationPlaybackMode.REPEAT_ALL -> false to Player.REPEAT_MODE_ALL
            NotificationPlaybackMode.REPEAT_ONE -> false to Player.REPEAT_MODE_ONE
            NotificationPlaybackMode.SHUFFLE -> true to Player.REPEAT_MODE_OFF
        }

        MediaViewModelObject.shuffleModeEnabled.value = nextShuffle
        MediaViewModelObject.repeatMode.intValue = nextRepeat
        SonifyWidgetUpdater.pushOptimisticModeState(appContext, nextMode)

        controller?.sendCustomCommand(
            SessionCommand(CMD_PLAYBACK_MODE, Bundle.EMPTY),
            Bundle.EMPTY,
        )

        withContext(Dispatchers.IO) {
            val cur = runCatching { MusicLibrary.loadPlayStatus() }.getOrNull()
            MusicLibrary.updatePlayStatus(
                PlayStatus(
                    music = cur?.music ?: MediaController.musicPlaying.value,
                    position = cur?.position ?: 0L,
                    shuffleModeEnabled = nextShuffle,
                    repeatMode = nextRepeat,
                )
            )
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "yos.music.player.widget.ACTION_PLAY_PAUSE"
        const val ACTION_RESUME_EMPTY = "yos.music.player.widget.ACTION_RESUME_EMPTY"
        const val ACTION_NEXT = "yos.music.player.widget.ACTION_NEXT"
        const val ACTION_MODE = "yos.music.player.widget.ACTION_MODE"
        const val ACTION_PLAY_RECENT = "yos.music.player.widget.ACTION_PLAY_RECENT"

        const val EXTRA_TRACK_MEDIA_ID = "extra_track_media_id"
        const val EXTRA_TRACK_URI = "extra_track_uri"
        const val EXTRA_TRACK_INDEX = "extra_track_index"

        private const val CMD_PLAYBACK_MODE = "playback_mode"
    }
}
