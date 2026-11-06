package yos.music.player.code.utils.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.runtime.Stable
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import yos.music.player.data.objects.MediaViewModelObject.isPlaying

@Stable
object FadeExo {
    private var fadeVolumeAnimator: ValueAnimator? = null
    private var fadeAnimationDuration: Long = 200L
    var targetStatus: Int = 0

    /**
     * 淡入淡出的代际号。每次起新的淡变都递增，回调里比对代际，过期回调直接丢弃。
     *
     * 为什么必须有它：`ValueAnimator.cancel()` 会回调**旧**动画的 `onAnimationEnd`。
     * 快速「暂停→播放」的真实顺序因此是 `play()` → `cancel(旧)` → 旧动画的 `pause()`，
     * 结果引擎被暂停、`targetStatus` 却是 1——界面显示在播、实际无声，而且不会自愈。
     */
    private var fadeGeneration = 0L

    private fun fadeVolume(
        player: ExoPlayer,
        from: Float,
        to: Float,
        duration: Long,
        targetStatus: Int,
        onEnd: (() -> Unit)? = null
    ) {
        FadeExo.targetStatus = targetStatus
        // 先递增代际再 cancel：旧动画的 onAnimationEnd 在 cancel() 内同步触发，
        // 它拿到的代际已经过期，于是不会把刚发出的 play() 按回去。
        val generation = ++fadeGeneration
        fadeVolumeAnimator?.cancel()
        fadeVolumeAnimator = ValueAnimator.ofFloat(from, to)
        fadeVolumeAnimator?.duration = duration
        fadeVolumeAnimator?.addUpdateListener { animation ->
            val volume = animation.animatedValue as Float
            try {
                player.volume = volume
            } catch (e: Exception) {
                animation.cancel()
            }
        }
        fadeVolumeAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (generation != fadeGeneration) return
                onEnd?.invoke()
            }
        })
        fadeVolumeAnimator?.start()
    }

    private fun fadeVolume(
        player: MediaController,
        from: Float,
        to: Float,
        duration: Long,
        targetStatus: Int,
        onEnd: (() -> Unit)? = null
    ) {
        FadeExo.targetStatus = targetStatus
        val generation = ++fadeGeneration
        fadeVolumeAnimator?.cancel()
        fadeVolumeAnimator = ValueAnimator.ofFloat(from, to)
        fadeVolumeAnimator?.duration = duration
        fadeVolumeAnimator?.addUpdateListener { animation ->
            val volume = animation.animatedValue as Float
            try {
                player.volume = volume
            } catch (e: Exception) {
                animation.cancel()
            }
        }
        fadeVolumeAnimator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (generation != fadeGeneration) return
                onEnd?.invoke()
            }
        })
        fadeVolumeAnimator?.start()
    }

    fun MediaController.fadePause() {
        setPlaying(0)
        val currentVolume = this.volume
        fadeVolume(this, currentVolume, 0f, fadeAnimationDuration, 0) {
            this.pause()
        }
    }

    fun MediaController.fadePlay() {
        setPlaying(1)
        val currentVolume = this.volume
        this.play()
        fadeVolume(this, currentVolume, 1f, fadeAnimationDuration, 1)
    }

    fun ExoPlayer.fadePause() {
        setPlaying(0)
        val currentVolume = this.volume
        fadeVolume(this, currentVolume, 0f, fadeAnimationDuration, 0) {
            this.pause()
        }
    }

    fun ExoPlayer.fadePlay() {
        setPlaying(1)
        val currentVolume = this.volume
        this.play()
        fadeVolume(this, currentVolume, 1f, fadeAnimationDuration, 1)
    }

    private fun setPlaying(targetStatus: Int) {
        isPlaying.value = targetStatus == 1
    }

    /**
     * 引擎真实状态回写：`targetStatus` 只在淡变时被写，一旦引擎自己变了（自然播完、
     * 出错进 IDLE、被外部暂停/停止），它会停在旧值，`ForwardingPlayer.isPlaying()`
     * 就长期谎报——这正是 STATE_LIE 记录的那个形态。
     *
     * 由服务在 `onIsPlayingChanged` 里调用，把目标态收敛回事实。淡变进行中不覆盖：
     * 那 200ms 内引擎报 false 是预期行为（音量还没降到 0），覆盖会让图标抖动。
     */
    fun syncFromEngine(isPlayingNow: Boolean, playbackState: Int) {
        if (fadeVolumeAnimator?.isRunning == true) return
        // 自然播完/出错必须如实报停：否则界面一直显示"在播"而实际无声，用户看不出来
        val shouldReport = isPlayingNow && playbackState != Player.STATE_IDLE
        targetStatus = if (shouldReport) 1 else 0
        isPlaying.value = shouldReport
    }

}