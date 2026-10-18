package yos.music.player.code.utils.player

/**
 * 车载蓝牙歌词的元数据改写规则（纯函数，便于单测）。
 *
 * 车机 AVRCP 只读标准 MediaMetadata：把当前歌词行写进 title，artist 让位给
 * 「歌手 - 歌名」，让车机同时看得到正在唱什么、以及这是哪首歌。
 */
object BluetoothSessionTitles {

    /**
     * 歌词行生效时的标题。空白行返回 null——空标题会让部分车机显示空白行，
     * 不如保持歌名。
     */
    fun titleFor(line: String?): String? = line?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 歌词行生效时的 artist。歌手与歌名都缺时返回 null（不写这个字段）。
     * 歌名为空时不能拼出 "歌手 - " 这种尾巴。
     */
    fun artistFor(originalTitle: String?, originalArtist: String?, line: String?): String? {
        if (titleFor(line) == null) return null
        val title = originalTitle?.trim()?.takeIf { it.isNotEmpty() }
        val artist = originalArtist?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            artist != null && title != null -> "$artist - $title"
            artist != null -> artist
            title != null -> title
            else -> null
        }
    }
}
