package yos.music.player.data.vocalsep

import android.content.Context
import org.tensorflow.lite.Interpreter
import yos.music.player.code.utils.others.YosDiagnostics
import java.io.File
import java.io.FileOutputStream

/**
 * 人声分离模型的加载与生命周期。
 *
 * 模型随 APK 打进 assets，但首次使用时要先落到 filesDir：TFLite 的 `Interpreter`
 * 需要可 seek 的 `File`（或整块 mmap 的 ByteBuffer），assets 里是压缩流，不能直接喂。
 * 拷贝按**长度**判重，不做哈希——4.9 MB 的文件每次启动算一遍 SHA 不值得，
 * 长度变了说明换了模型，重新拷即可。
 *
 * 模型是**可缺失**的：开源构建不带模型，功能整体置灰而不是崩溃。所有失败路径都返回
 * null / false，由上层决定如何提示。
 *
 * 线程约定：[load] 会读磁盘 + 建解释器（数百毫秒），必须在后台线程调用。
 * 返回的 [Interpreter] 不是线程安全的，调用方须保证每个播放器各持一份。
 */
object VocalSepModels {

    private const val ASSET_DIR = "vocalsep"
    private const val MODEL_NAME = "mss-model.tflite"

    /** 加载成功后缓存的模型文件（避免每次切歌都去问 assets）。 */
    @Volatile
    private var cachedFile: File? = null

    /**
     * 模型是否可用（只查 assets，不解码模型）。
     *
     * 用于 UI 决定按钮是否置灰：真正的加载在 [load] 里做，这里只回答"这个包带不带模型"。
     */
    fun isModelPresent(context: Context): Boolean = runCatching {
        context.assets.open("$ASSET_DIR/$MODEL_NAME").use { it.read() }
        true
    }.getOrDefault(false).also { present ->
        // 开源构建不带模型：这条能立刻把"包本身没有模型"与"模型有但加载失败"分开。
        YosDiagnostics.log(
            "VSEP_MODEL",
            "present" to present,
            "asset_bytes" to runCatching { assetLength(context) }.getOrNull(),
            "abi" to android.os.Build.SUPPORTED_ABIS.firstOrNull()
        )
    }

    /**
     * 建一个**新的**解释器（每个播放器一份）。
     *
     * 返回 null 表示模型缺失或加载失败——调用方应保持功能不可用，不要重试到卡顿。
     * 多线程：本方法自身线程安全（拷贝文件加锁），但返回的 Interpreter 由调用方独占。
     */
    fun load(context: Context): Interpreter? = runCatching {
        val file = ensureModelFile(context) ?: return@runCatching null
        val options = Interpreter.Options().apply {
            // CPU + XNNPACK。不做 GPU：旧 GpuDelegate 在部分 SoC 驱动上会在
            // new/首次 run 里 SIGSEGV，Java 层 try/catch 拦不住，进程直接消失。
            setUseXNNPACK(true)
            // 留一半核给解码/UI：推理单块预算 93 ms，实测 4–15 ms，够用且不抢主线程
            setNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6))
        }
        Interpreter(file, options)
    }.onFailure { t ->
        // 这一层失败（.so 加载 / 模型解析 / 内存不足）在 UI 上只表现为"按钮置灰"，
        // 没有日志就只能靠猜设备差异。把类型与消息原样留下。
        YosDiagnostics.log(
            "VSEP_ERR", "what" to "model_load",
            "type" to t.javaClass.simpleName,
            "msg" to runCatching { t.message }.getOrNull(),
            "model_len" to runCatching { cachedFile?.length() }.getOrNull()
        )
    }.getOrNull()

    /** assets → filesDir 拷贝；已存在且长度一致则直接复用。 */
    private fun ensureModelFile(context: Context): File? {
        cachedFile?.let { if (it.exists()) return it }
        val dir = File(context.filesDir, ASSET_DIR)
        val target = File(dir, MODEL_NAME)
        synchronized(this) {
            cachedFile?.let { if (it.exists()) return it }
            val assetLength = assetLength(context) ?: return null
            if (target.exists() && target.length() == assetLength) {
                cachedFile = target
                return target
            }
            if (!dir.exists() && !dir.mkdirs()) return null
            runCatching {
                context.assets.open("$ASSET_DIR/$MODEL_NAME").use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }.onFailure {
                // 半截文件必须删掉，否则下次启动长度不符又会走进这条路径且留下脏数据
                target.delete()
                return null
            }
            cachedFile = target
            return target
        }
    }

    /**
     * assets 里模型的原长。
     *
     * `openFd` 只在未压缩资源上可用（build.gradle 已把 `tflite` 加进 noCompress）；
     * 万一某次构建漏了那条配置，退回 `available()` —— 对压缩资源它返回的也是解压后长度。
     * 两条都拿不到就当作模型不可用，绝不猜一个长度去覆盖已有文件。
     */
    private fun assetLength(context: Context): Long? {
        runCatching {
            context.assets.openFd("$ASSET_DIR/$MODEL_NAME").use { return it.length }
        }
        return runCatching {
            context.assets.open("$ASSET_DIR/$MODEL_NAME").use { it.available().toLong() }
        }.getOrNull()
    }
}
