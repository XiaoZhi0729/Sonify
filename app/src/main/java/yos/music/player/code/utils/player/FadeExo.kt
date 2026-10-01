package yos.music.player.code.utils.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import androidx.compose.runtime.Stable
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import yos.music.player.data.objects.MediaViewModelObject.isPlaying

@Stable
object FadeExo {
    private var fadeVolumeAnimator: ValueAnimator? = null
    private var fadeAnimationDuration: Long = 200L
    var targetStatus: Int = 0

    private fun fadeVolume(
        player: ExoPlayer,
        from: Float,
        to: Float,
        duration: Long,
        targetStatus: Int,
        onEnd: (() -> Unit)? = null
    ) {
        FadeExo.targetStatus = targetStatus
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

}