package yos.music.player.code.vocalsep

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.data.vocalsep.MssEngine
import yos.music.player.data.vocalsep.TfliteMssModel
import yos.music.player.data.vocalsep.VocalSepModels
import java.util.concurrent.ConcurrentHashMap

/**
 * 人声分离的运行时中枢：管理「每个播放器一份处理器」的注册表，并广播开关与占比。
 *
 * ## 为什么按播放器注册而不是单例
 * 交叉淡化期间主副两台播放器**同时出声**，而 TFLite 的 Interpreter 不是线程安全的。
 * 每台播放器必须各自持有一份 [VocalSeparationProcessor]（含独立引擎）。
 * 注册键用播放器身份字符串（[PLAYER_PRIMARY] / [PLAYER_SECONDARY]），
 * 扶正时由服务层把副 player 的处理器换成主身份。
 *
 * ## 模型加载
 * 首次启用时才在后台线程建解释器（数百毫秒），不阻塞播放。加载失败（模型缺失 /
 * 文件损坏）会把 [available] 置 false，UI 据此置灰并提示，而不是反复重试。
 */
@UnstableApi
object VocalSeparationController {

    const val PLAYER_PRIMARY = "primary"
    const val PLAYER_SECONDARY = "secondary"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val processors = ConcurrentHashMap<String, VocalSeparationProcessor>()

    /** 处理器 → 它的引擎懒加载闭包（每个处理器独立一份引擎，绝不共享 Interpreter）。 */
    private val engineProviders = ConcurrentHashMap<String, () -> MssEngine?>()

    private val _available = MutableStateFlow(false)

    /** 模型是否可用（存在且加载成功）。UI 用它决定按钮是否可点。 */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** 全局开关。处理器逐个 apply，保证两者同步。 */
    @Volatile
    private var enabled = false

    /** 全局占比 0..1（原声含量；0 = 纯伴奏，1 = 原声）。 */
    @Volatile
    private var ratio = 0.5f

    @Volatile
    private var appContext: Context? = null

    /** 由服务创建时注入（幂等）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        // 只查 assets 存在性，不解码模型：真正加载推迟到首次启用
        val ctx = appContext ?: return
        scope.launch {
            val present = withContext(Dispatchers.IO) { VocalSepModels.isModelPresent(ctx) }
            _available.value = present
        }
    }

    /**
     * 注册一台播放器的处理器。重复注册同一 [playerKey] 会替换旧的（扶正时用）。
     *
     * 新处理器会立即套用当前的全局开关与占比，避免"注册后要再点一次才生效"。
     */
    fun attach(playerKey: String, processor: VocalSeparationProcessor) {
        processor.setEnabled(enabled && processor.isUsable())
        processor.setRatio(ratio)
        processors[playerKey] = processor
    }

    fun detach(playerKey: String) {
        processors.remove(playerKey)
        engineProviders.remove(playerKey)
    }

    /**
     * 交叉淡化扶正：副 player 成为主 player，处理器身份跟着搬过去。
     *
     * **不清引擎状态**——正在淡入的新曲必须保持分离效果，清掉会在接缝处听到
     * 一瞬间的原声。旧主 player 的处理器（已随 player 释放）直接丢掉。
     */
    fun promoteSecondaryToPrimary() {
        val secondary = processors.remove(PLAYER_SECONDARY) ?: return
        val secondaryProvider = engineProviders.remove(PLAYER_SECONDARY)
        // 旧主 player 即将被 release，它的处理器不再需要
        processors.remove(PLAYER_PRIMARY)
        engineProviders.remove(PLAYER_PRIMARY)
        processors[PLAYER_PRIMARY] = secondary
        if (secondaryProvider != null) engineProviders[PLAYER_PRIMARY] = secondaryProvider
    }

    /** 该 [playerKey] 的引擎懒加载闭包（由 [yos.music.player.code.YosRenderFactory] 取用）。 */
    fun engineProviderFor(playerKey: String): () -> MssEngine? =
        engineProviders.getOrPut(playerKey) { { loadEngineSync() } }

    fun release() {
        processors.clear()
        engineProviders.clear()
        enabled = false
        ratio = 1f
        _available.value = false
    }

    fun setEnabled(value: Boolean) {
        if (value && !_available.value) {
            // 模型没就绪：先尝试加载，成功后再打开
            scope.launch { ensureModelThen(value) }
            return
        }
        enabled = value
        broadcastEnabled()
    }

    fun setRatio(value: Float) {
        ratio = value.coerceIn(0f, 1f)
        processors.values.forEach { it.setRatio(ratio) }
    }

    /** 换曲 / seek：清所有处理器的引擎状态（不动开关）。 */
    fun requestResetAll() {
        processors.values.forEach { it.resetEngineState() }
    }

    /** 切歌：清状态并关闭开关（产品决定：换歌自动关闭）。 */
    fun resetAndDisable() {
        enabled = false
        processors.values.forEach {
            it.resetEngineState()
            it.setEnabled(false)
        }
    }

    fun isEnabled(): Boolean = enabled

    fun currentRatio(): Float = ratio

    // ---------------------------------------------------------------- 内部

    private suspend fun ensureModelThen(turnOn: Boolean) {
        val ctx = appContext ?: return
        val ok = withContext(Dispatchers.IO) {
            // 只验证能建出解释器；建完立刻关掉——真正推理用的引擎由处理器各自持有
            val interpreter = runCatching { VocalSepModels.load(ctx) }.getOrNull()
            if (interpreter == null) {
                false
            } else {
                runCatching { interpreter.close() }
                true
            }
        }
        _available.value = ok
        if (ok && turnOn) {
            enabled = true
            broadcastEnabled()
        }
    }

    private fun broadcastEnabled() {
        processors.values.forEach { p ->
            // 引擎已判故障的处理器不要被重新打开（否则每块都白跑一次失败路径）
            p.setEnabled(enabled && p.isUsable())
        }
    }

    /** 同步加载一份新引擎（调用方：处理器在播放线程上首次需要时）。 */
    private fun loadEngineSync(): MssEngine? {
        val ctx = appContext ?: return null
        val interpreter = VocalSepModels.load(ctx) ?: return null
        val model = runCatching { TfliteMssModel(interpreter) }.getOrElse {
            runCatching { interpreter.close() }
            return null
        }
        return runCatching { MssEngine(model) }.getOrElse {
            runCatching { model.close() }
            null
        }
    }
}
