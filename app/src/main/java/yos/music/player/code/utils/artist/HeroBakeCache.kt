package yos.music.player.code.utils.artist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest

/**
 * 歌手页 hero 倒影「烘焙」位图缓存。烘焙是 CPU/GPU 异步操作，冷启动或回访若每次都重烘，
 * 会呈现「照片先出、倒影随后才浮现」。这里分两级缓存：
 *  - 内存级：同进程回访 → 命中即可落位，近首帧；
 *  - 磁盘级：跨进程/冷启动 → 异步解码 PNG 后换上（不阻塞首帧，先出锐利照片）。
 *
 * 形状对齐 md3Music depth_cover_cache：目录 `<filesDir>/hero_bake_cache/<md5(key)>.png` + LRU。
 * 键含头像 URL / 朝向比例 / 延展模式 / 烘焙几何——换设备或转屏自然失效重烘，不会错位。
 */
internal object HeroBakeCache {
    private const val DIR_NAME = "hero_bake_cache"
    private const val MAX_DISK = 24
    private const val MAX_MEM = 8

    private val mem = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap>
        ): Boolean = size > MAX_MEM
    }

    @Volatile
    private var dir: File? = null

    private fun dir(context: Context): File =
        dir ?: synchronized(this) {
            dir ?: File(context.filesDir, DIR_NAME).also { it.mkdirs() }.also { dir = it }
        }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), md5(key) + ".png")

    /** 仅内存级命中（组合期/主线程可安全调用）。 */
    fun memoryGet(key: String): ImageBitmap? = synchronized(this) { mem[key] }

    /** 内存未命中则解码磁盘 PNG（**必须在 IO 线程调用**：文件 IO + 解码）。 */
    fun load(context: Context, key: String): ImageBitmap? {
        synchronized(this) { mem[key]?.let { return it } }
        val f = fileFor(context, key)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
            .getOrNull()
            ?.also { synchronized(this) { mem[key] = it } }
    }

    /** 写内存 + 落盘 PNG（落盘为文件 IO，建议在 IO 线程调用）。 */
    fun put(context: Context, key: String, bitmap: Bitmap) {
        synchronized(this) { mem[key] = bitmap.asImageBitmap() }
        runCatching {
            fileFor(context, key).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            trim(context)
        }
    }

    fun clear(context: Context) {
        synchronized(this) { mem.clear() }
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".png") } ?: return
        if (files.size <= MAX_DISK) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK).forEach { it.delete() }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
