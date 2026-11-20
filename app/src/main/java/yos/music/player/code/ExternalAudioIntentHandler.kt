package yos.music.player.code

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem

/**
 * 解析外部通过 `ACTION_VIEW` 传入的音频播放请求，并构建可直接交给 [MediaController.prepare] 播放的 [YosMediaItem]。
 */
object ExternalAudioIntentHandler {

    data class AudioViewRequest(
        val uri: Uri,
        val mimeType: String?
    )

    private val SUPPORTED_SCHEMES = setOf("content", "file")

    fun isSupportedAudioScheme(scheme: String?): Boolean =
        scheme != null && scheme.lowercase() in SUPPORTED_SCHEMES

    /**
     * 从 [Intent] 中提取可播放的音频打开请求；非 `ACTION_VIEW` 或不支持的 scheme 返回 `null`。
     */
    fun extractAudioViewRequest(intent: Intent?): AudioViewRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (!isSupportedAudioScheme(uri.scheme)) return null
        return AudioViewRequest(uri = uri, mimeType = intent.type)
    }

    /**
     * 去掉音频文件名末尾的扩展名（仅当扩展名非空且不以点开头时），用作无内嵌标题时的展示名。
     */
    fun stripAudioExtension(fileName: String?): String? {
        val trimmed = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val dotIndex = trimmed.lastIndexOf('.')
        return if (dotIndex > 0) trimmed.substring(0, dotIndex) else trimmed
    }

    /**
     * 在 IO 线程解析外部音频 URI 的元数据：
     * 1. 若本地曲库已有相同 URI 的条目，直接复用（保留完整标签与封面）。
     * 2. 否则结合 `ContentResolver` 文件名与 [MediaMetadataRetriever] 读取标题、歌手、专辑、时长。
     */
    suspend fun resolveExternalAudioItem(
        context: Context,
        uri: Uri,
        intentMimeType: String?
    ): YosMediaItem = withContext(Dispatchers.IO) {
        MusicLibrary.songs.firstOrNull { it.uri == uri }?.let { return@withContext it }

        val displayName = queryDisplayName(context, uri) ?: uri.lastPathSegment
        val resolvedMime = intentMimeType
            ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: "audio/*"

        var metaTitle: String? = null
        var metaArtist: String? = null
        var metaAlbum: String? = null
        var metaDuration = 0L

        val retriever = MediaMetadataRetriever()
        try {
            runCatching {
                if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrEmpty()) {
                    retriever.setDataSource(uri.path)
                } else {
                    retriever.setDataSource(context, uri)
                }
                metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                metaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }
        } finally {
            runCatching { retriever.release() }
        }

        val finalTitle = metaTitle ?: stripAudioExtension(displayName) ?: "未知音频"

        YosMediaItem(
            uri = uri,
            mediaId = "external:$uri",
            mimeType = resolvedMime,
            title = finalTitle,
            writer = null,
            compilation = null,
            composer = null,
            artists = metaArtist,
            album = metaAlbum,
            albumArtists = null,
            thumb = null,
            trackNumber = null,
            discNumber = null,
            genre = null,
            recordingDay = null,
            recordingMonth = null,
            recordingYear = null,
            releaseYear = null,
            artistId = null,
            albumId = null,
            genreId = null,
            author = null,
            addDate = null,
            duration = metaDuration,
            modifiedDate = null,
            cdTrackNumber = null
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } else {
            uri.lastPathSegment
        }
    }.getOrNull()
}
