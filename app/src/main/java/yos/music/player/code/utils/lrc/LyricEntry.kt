package yos.music.player.code.utils.lrc

/** A timed lyric line with its optional translated text kept separately. */
data class LyricEntry(
    val mainLyric: List<Pair<Float, String>>,
    val translation: String? = null,
    val romanization: String? = null
) {
    val startTime: Float
        get() = mainLyric.firstOrNull()?.first ?: 0f
}
