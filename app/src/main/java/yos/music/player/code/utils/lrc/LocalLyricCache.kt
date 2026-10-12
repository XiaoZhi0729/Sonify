package yos.music.player.code.utils.lrc

/**
 * 本地歌词内存缓存：key = "文件路径@mtime"。
 *
 * 本地歌词每次切歌都要读同名 .lrc 或音频内嵌标签（TagLib），是磁盘 IO + 解析；
 * 缓存后同一文件重复播放零 IO。访问序 LRU，上限 [MAX_ENTRIES]。不落盘——
 * 源文件本身就在磁盘上，重读成本低于维护一份镜像。
 */
internal object LocalLyricCache {
    private const val MAX_ENTRIES = 64

    private val map = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<LyricEntry>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<LyricEntry>>
            ): Boolean = size > MAX_ENTRIES
        }
    )

    fun get(key: String): List<LyricEntry>? = map[key]

    fun put(key: String, entries: List<LyricEntry>) {
        map[key] = entries
    }
}
