package yos.music.player.code

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.AudioPropertiesReadStyle
import com.kyant.taglib.TagLib
import java.io.File
import java.nio.charset.Charset

object AudioMetadataUtils {
    fun loadLrcFile(context: Context, filePath: String): String? {
        return try {
            val file = File(filePath)
            val uri = Uri.fromFile(file)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes().toString(Charset.defaultCharset())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 读取音频文件的内嵌歌词。TagLib 会把 M4A 的 ©lyr atom、MP3 的 USLT 帧、
     * FLAC 的 VORBIS_COMMENT 归一为 LYRICS 属性键，个别文件键名带 description 后缀，做前缀匹配。
     */
    fun loadEmbeddedLyric(songPath: String): String? {
        return try {
            ParcelFileDescriptor.open(File(songPath), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val metadata = TagLib.getMetadata(fd.dup().detachFd(), readPictures = false)
                metadata?.propertyMap
                    ?.filterKeys { it.startsWith("LYRICS", ignoreCase = true) }
                    ?.values
                    ?.flatMap { values -> values.toList() }
                    ?.joinToString("\n")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 歌曲总时长（毫秒），供无时间戳歌词合成时间轴用；读不到返回 0。 */
    fun getAudioLengthMs(filePath: String): Long {
        return try {
            ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val properties = TagLib.getAudioProperties(fd.dup().detachFd(), AudioPropertiesReadStyle.Fast)
                (properties?.length ?: 0).toLong() * 1000L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun getQualityInfos(filePath: String): Pair<Int, Int> {
        val songFile = File(filePath)
        var bitrate: Int
        var sampleRate: Int

        println("质量分析 Taglib 实现获取")

        ParcelFileDescriptor.open(songFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val audioProperties = TagLib.getAudioProperties(fd.dup().detachFd(), AudioPropertiesReadStyle.Fast)
            bitrate = audioProperties?.bitrate ?: -1
            sampleRate = audioProperties?.sampleRate ?: -1
        }

        if (bitrate == -1 || sampleRate == -1) {
            val extractor = MediaExtractor()
            try {
                println("质量分析 MediaExtractor 实现获取")
                extractor.setDataSource(filePath)
                val format = extractor.getTrackFormat(0)
                if (bitrate == -1) {
                    bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
                if (sampleRate == -1) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                extractor.release()
            }
        }

        return Pair(bitrate, sampleRate)
    }

}