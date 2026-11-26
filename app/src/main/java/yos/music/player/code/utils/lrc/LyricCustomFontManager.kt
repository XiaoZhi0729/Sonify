package yos.music.player.code.utils.lrc

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Xml
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import yos.music.player.data.libraries.SettingsLibrary

/**
 * 歌词自定义字体管理器：
 * - 负责从系统文件选择器导入 TTF / OTF / TTC 字体到应用私有目录并持久化；
 * - 负责按 (自定义字体路径, 100..900 字重) 解析与缓存 Compose [FontFamily]，
 *   同时兼容静态字体与可变字体（Variable Font `'wght'` 轴）；
 * - 未设置自定义字体时，优先探测系统可变字体（`'wght'` 轴）以实现 100..900 真无极字重，
 *   避免 `Typeface.create(DEFAULT, weight)` 被 `fonts.xml` 离散字重档位截断。
 */
object LyricCustomFontManager {

    private const val FONT_DIR_NAME = "lyric_fonts"
    private const val SYSTEM_FONT_CACHE_KEY = "__system__"
    private const val MAX_CACHE_ENTRIES = 32
    private val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc")
    private val SYSTEM_FONT_DIRS = listOf("/system/fonts", "/product/fonts", "/system_ext/fonts")
    private val SYSTEM_FONTS_XML_PATHS = listOf(
        "/system/etc/fonts.xml",
        "/product/etc/fonts.xml",
        "/system_ext/etc/fonts.xml",
    )

    private val fontFamilyCache =
        object : LinkedHashMap<String, FontFamily>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, FontFamily>?,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }

    @Volatile
    private var cachedSystemVariableFonts: List<File>? = null

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
     * - 设置了自定义字体时，基于该字体文件按 `weight` 构造可变/静态 [FontFamily]；
     * - 未设置自定义字体时，若系统存在支持 `'wght'` 轴的可变字体（如 MiSansVF、OPlusSans、RobotoFlex 等），
     *   直接通过 `FontVariationSettings` 构造无极字重 [FontFamily]；
     * - 若系统无可用可变字体，返回 `null` 回退系统默认字体。
     */
    @Synchronized
    fun resolveFontFamily(
        customPath: String = SettingsLibrary.LyricCustomFontPath,
        weight: Int = parseLyricFontWeight(SettingsLibrary.LyricFontWeight),
    ): FontFamily? {
        val clampedWeight = weight.coerceIn(100, 900)
        if (customPath.isBlank()) {
            return resolveSystemVariableFontFamily(clampedWeight)
        }
        val file = File(customPath)
        if (!file.isFile || !file.canRead()) {
            return resolveSystemVariableFontFamily(clampedWeight)
        }
        val key = "$customPath|$clampedWeight"
        fontFamilyCache[key]?.let { return it }

        val typeface = buildTypeface(file, clampedWeight) ?: return null
        val family = FontFamily(typeface)
        fontFamilyCache[key] = family
        return family
    }

    private fun resolveSystemVariableFontFamily(weight: Int): FontFamily? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val key = "$SYSTEM_FONT_CACHE_KEY|$weight"
        fontFamilyCache[key]?.let { return it }

        val systemFonts = discoverSystemVariableFonts()
        if (systemFonts.isEmpty()) return null

        val typeface = buildMultiFileVariableTypeface(systemFonts, weight) ?: return null
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
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasVariableWeightAxis(file) -> {
                    val primaryFont = Font.Builder(file)
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                    val builder = Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(primaryFont).build())
                    for (sysFile in discoverSystemVariableFonts()) {
                        runCatching {
                            val fallbackFont = Font.Builder(sysFile)
                                .setFontVariationSettings("'wght' $weight")
                                .build()
                            builder.addCustomFallback(PlatformFontFamily.Builder(fallbackFont).build())
                        }
                    }
                    builder.setSystemFallback("sans-serif").build()
                }
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

    private fun buildMultiFileVariableTypeface(files: List<File>, weight: Int): Typeface? {
        if (files.isEmpty()) return null
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val primaryFont = Font.Builder(files.first())
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                    val builder = Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(primaryFont).build())
                    for (i in 1 until files.size) {
                        runCatching {
                            val fallbackFont = Font.Builder(files[i])
                                .setFontVariationSettings("'wght' $weight")
                                .build()
                            builder.addCustomFallback(PlatformFontFamily.Builder(fallbackFont).build())
                        }
                    }
                    builder.setSystemFallback("sans-serif").build()
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    Typeface.Builder(files.first())
                        .setFontVariationSettings("'wght' $weight")
                        .build()
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun discoverSystemVariableFonts(): List<File> {
        cachedSystemVariableFonts?.let { return it }
        val candidates = LinkedHashSet<String>()
        for (xmlPath in SYSTEM_FONTS_XML_PATHS) {
            val xmlFile = File(xmlPath)
            if (xmlFile.isFile && xmlFile.canRead()) {
                candidates.addAll(parseSystemVariableFontCandidates(xmlFile))
            }
        }
        val resolved = ArrayList<File>()
        val seenPaths = HashSet<String>()
        for (name in candidates) {
            val file = resolveSystemFontFile(name) ?: continue
            val canonical = file.absolutePath
            if (seenPaths.add(canonical) && hasVariableWeightAxis(file)) {
                resolved.add(file)
            }
        }
        cachedSystemVariableFonts = resolved
        return resolved
    }

    private fun resolveSystemFontFile(fileName: String): File? {
        if (fileName.startsWith("/")) {
            return File(fileName).takeIf { it.isFile && it.canRead() }
        }
        for (dir in SYSTEM_FONT_DIRS) {
            val f = File(dir, fileName)
            if (f.isFile && f.canRead()) return f
        }
        return null
    }

    /**
     * 从系统 `fonts.xml` 中提取无衬线主字体（`sans-serif`）及简体/繁体中文回退字体文件名，
     * 保持声明顺序去重。
     */
    internal fun parseSystemVariableFontCandidates(fontsXml: File): List<String> {
        val primary = LinkedHashSet<String>()
        val cjk = LinkedHashSet<String>()
        runCatching {
            fontsXml.inputStream().use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                var currentFamilyName: String? = null
                var currentFamilyLang: String? = null
                var inFontTag = false
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "family" -> {
                                currentFamilyName = parser.getAttributeValue(null, "name")
                                currentFamilyLang = parser.getAttributeValue(null, "lang")
                            }
                            "font" -> {
                                val style = parser.getAttributeValue(null, "style")
                                inFontTag = style == null || style == "normal"
                            }
                        }
                        XmlPullParser.TEXT -> if (inFontTag) {
                            val fontName = parser.text?.trim().orEmpty()
                            if (fontName.isNotEmpty()) {
                                when {
                                    currentFamilyName == "sans-serif" -> primary.add(fontName)
                                    currentFamilyLang?.contains("zh", ignoreCase = true) == true -> cjk.add(fontName)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "font" -> inFontTag = false
                            "family" -> {
                                currentFamilyName = null
                                currentFamilyLang = null
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return (primary + cjk).toList()
    }

    /**
     * 检查 TTF / OTF / TTC 字体文件是否包含 OpenType `fvar` 表且声明了 `'wght'` 可变字重轴。
     */
    internal fun hasVariableWeightAxis(file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() < 12L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val tag = raf.readInt()
                when (tag) {
                    TAG_TTCF -> {
                        raf.seek(8L)
                        val numFonts = raf.readInt()
                        if (numFonts <= 0 || numFonts > 64) return false
                        val checkCount = numFonts.coerceAtMost(8)
                        for (i in 0 until checkCount) {
                            raf.seek(12L + i * 4L)
                            val fontOffset = raf.readInt().toLong() and 0xFFFFFFFFL
                            if (sfntHasVariableWeightAxis(raf, fontOffset)) return true
                        }
                        false
                    }
                    TAG_TTF, TAG_TRUE, TAG_OTTO -> sfntHasVariableWeightAxis(raf, 0L)
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    private fun sfntHasVariableWeightAxis(raf: RandomAccessFile, sfntOffset: Long): Boolean {
        val fileLen = raf.length()
        if (sfntOffset < 0L || sfntOffset + 12L > fileLen) return false
        raf.seek(sfntOffset + 4L)
        val numTables = raf.readUnsignedShort()
        if (numTables <= 0 || numTables > 256) return false
        if (sfntOffset + 12L + numTables * 16L > fileLen) return false

        var fvarOffset = -1L
        var fvarLength = 0L
        for (i in 0 until numTables) {
            raf.seek(sfntOffset + 12L + i * 16L)
            val tableTag = raf.readInt()
            raf.readInt() // checksum
            val offset = raf.readInt().toLong() and 0xFFFFFFFFL
            val length = raf.readInt().toLong() and 0xFFFFFFFFL
            if (tableTag == TAG_FVAR) {
                fvarOffset = offset
                fvarLength = length
                break
            }
        }
        if (fvarOffset < 0L || fvarLength < 16L || fvarOffset + fvarLength > fileLen) return false

        raf.seek(fvarOffset + 4L)
        val axesArrayOffset = raf.readUnsignedShort()
        raf.readUnsignedShort() // reserved
        val axisCount = raf.readUnsignedShort()
        val axisSize = raf.readUnsignedShort()
        if (axisCount <= 0 || axisCount > 64 || axisSize < 20) return false
        if (axesArrayOffset + axisCount.toLong() * axisSize > fvarLength) return false

        for (i in 0 until axisCount) {
            raf.seek(fvarOffset + axesArrayOffset + i.toLong() * axisSize)
            if (raf.readInt() == TAG_WGHT) return true
        }
        return false
    }

    private const val TAG_TTF = 0x00010000
    private const val TAG_TRUE = 0x74727565 // "true"
    private const val TAG_OTTO = 0x4F54544F // "OTTO"
    private const val TAG_TTCF = 0x74746366 // "ttcf"
    private const val TAG_FVAR = 0x66766172 // "fvar"
    private const val TAG_WGHT = 0x77676874 // "wght"

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
