package yos.music.player.code.utils.lrc

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.util.Locale
import yos.music.player.data.libraries.SettingsLibrary

/**
 * 歌词自定义字体管理器：
 * - 负责从系统文件选择器导入 TTF / OTF / TTC 字体到应用私有目录并持久化；
 * - 负责按 (自定义字体路径, 100..900 字重) 解析与缓存 Compose [FontFamily]，
 *   同时兼容静态字体与可变字体（Variable Font `'wght'` 轴）。
 */
object LyricCustomFontManager {

    private const val FONT_DIR_NAME = "lyric_fonts"
    private const val MAX_CACHE_ENTRIES = 16
    private val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc")

    private val fontFamilyCache =
        object : LinkedHashMap<String, FontFamily>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, FontFamily>?,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }

    sealed class ImportResult {
        data class Success(val displayName: String, val localPath: String) : ImportResult()
        data class Failure(val reason: FailureReason) : ImportResult()
    }

    enum class FailureReason {
        UNSUPPORTED_FORMAT,
        INVALID_FONT_FILE,
        IO_ERROR,
    }

    /**
     * 解析当前生效的歌词 [FontFamily]。
     * 返回 `null` 时表示使用系统默认字体（由 `TextStyle.fontWeight` 驱动 100..900 无极字重）。
     */
    @Synchronized
    fun resolveFontFamily(
        customPath: String = SettingsLibrary.LyricCustomFontPath,
        weight: Int = parseLyricFontWeight(SettingsLibrary.LyricFontWeight),
    ): FontFamily? {
        if (customPath.isBlank()) return null
        val file = File(customPath)
        if (!file.isFile || !file.canRead()) return null
        val clampedWeight = weight.coerceIn(100, 900)
        val key = "$customPath|$clampedWeight"
        fontFamilyCache[key]?.let { return it }

        val typeface = buildTypeface(file, clampedWeight) ?: return null
        val family = FontFamily(typeface)
        fontFamilyCache[key] = family
        return family
    }

    /**
     * 从系统 SAF [Uri] 导入字体文件到 `filesDir/lyric_fonts/`，校验通过后更新设置并清理旧字体文件。
     */
    fun importFontFromUri(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val rawName = queryDisplayName(context, uri)
        val ext = rawName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        val fontDir = File(context.filesDir, FONT_DIR_NAME).apply { mkdirs() }
        val tempFile = File(fontDir, "tmp_${System.currentTimeMillis()}.${ext.ifBlank { "ttf" }}")

        return try {
            val input = resolver.openInputStream(uri)
                ?: return ImportResult.Failure(FailureReason.IO_ERROR)
            input.use { stream ->
                tempFile.outputStream().use { out ->
                    stream.copyTo(out)
                }
            }
            val header = readHeaderBytes(tempFile)
            val isSupportedHeader = hasValidFontHeader(header)
            if (ext !in ALLOWED_EXTENSIONS && !isSupportedHeader) {
                tempFile.delete()
                return ImportResult.Failure(FailureReason.UNSUPPORTED_FORMAT)
            }
            if (!isSupportedHeader) {
                tempFile.delete()
                return ImportResult.Failure(FailureReason.INVALID_FONT_FILE)
            }

            // 验证系统 Typeface 能够实际解析该文件
            val testTypeface = runCatching { Typeface.createFromFile(tempFile) }.getOrNull()
            if (testTypeface == null) {
                tempFile.delete()
                return ImportResult.Failure(FailureReason.INVALID_FONT_FILE)
            }

            val finalExt = if (ext in ALLOWED_EXTENSIONS) ext else inferExtensionFromHeader(header)
            val targetFile = File(fontDir, "custom_lyric_font_${System.currentTimeMillis()}.$finalExt")
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val oldPath = SettingsLibrary.LyricCustomFontPath
            val displayName = rawName?.takeIf { it.isNotBlank() } ?: targetFile.name
            SettingsLibrary.LyricCustomFontPath = targetFile.absolutePath
            SettingsLibrary.LyricCustomFontName = displayName
            clearCacheForPath(oldPath)
            if (oldPath.isNotBlank() && oldPath != targetFile.absolutePath) {
                runCatching { File(oldPath).delete() }
            }
            ImportResult.Success(displayName = displayName, localPath = targetFile.absolutePath)
        } catch (_: Exception) {
            tempFile.delete()
            ImportResult.Failure(FailureReason.IO_ERROR)
        }
    }

    /** 清除自定义字体并恢复系统默认字体。 */
    fun clearCustomFont(context: Context? = null) {
        val oldPath = SettingsLibrary.LyricCustomFontPath
        SettingsLibrary.LyricCustomFontPath = ""
        SettingsLibrary.LyricCustomFontName = ""
        clearCacheForPath(oldPath)
        if (oldPath.isNotBlank()) {
            runCatching { File(oldPath).delete() }
        }
        if (context != null) {
            runCatching {
                File(context.filesDir, FONT_DIR_NAME).listFiles()?.forEach { it.delete() }
            }
        }
    }

    @Synchronized
    private fun clearCacheForPath(path: String) {
        if (path.isBlank()) return
        val prefix = "$path|"
        fontFamilyCache.keys.removeAll { it.startsWith(prefix) }
    }

    private fun buildTypeface(file: File, weight: Int): Typeface? {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val base = Typeface.Builder(file)
                        .setFontVariationSettings("'wght' $weight")
                        .setWeight(weight)
                        .build()
                    Typeface.create(base, weight, false)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    Typeface.Builder(file)
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                }
                else -> Typeface.createFromFile(file)
            }
        }.getOrNull() ?: runCatching { Typeface.createFromFile(file) }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun readHeaderBytes(file: File): ByteArray {
        val buf = ByteArray(4)
        return runCatching {
            file.inputStream().use { stream ->
                val read = stream.read(buf)
                if (read == 4) buf else ByteArray(0)
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun hasValidFontHeader(header: ByteArray): Boolean {
        if (header.size < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        val isTtf = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
        val isTrue = b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65 // "true"
        val isOtf = b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F // "OTTO"
        val isTtc = b0 == 0x74 && b1 == 0x74 && b2 == 0x63 && b3 == 0x66 // "ttcf"
        return isTtf || isTrue || isOtf || isTtc
    }

    private fun inferExtensionFromHeader(header: ByteArray): String {
        if (header.size < 4) return "ttf"
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        return when {
            b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F -> "otf"
            b0 == 0x74 && b1 == 0x74 && b2 == 0x63 && b3 == 0x66 -> "ttc"
            else -> "ttf"
        }
    }
}
