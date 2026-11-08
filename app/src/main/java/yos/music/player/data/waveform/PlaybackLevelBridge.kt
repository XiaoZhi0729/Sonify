package yos.music.player.data.waveform

import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放链路低音能量 → 背景渲染器的进程级单向桥。
 *
 * ## 为什么需要它
 * 分析发生在播放服务里的 `ProbingAudioSink.handleBuffer`（ExoPlayer 播放线程），
 * 消费方是 Compose 里的 `PearMeshState`，两者分处不同层、互不引用。
 * 项目既有的跨层通信范式就是进程级单例（见 `VocalSeparationController`、
 * `MediaViewModelObject`），这里照同一范式做一条只写一个 float 的单向通道。
 *
 * ## 为什么是「有界」的
 * 桥里存的是**最近一次**的能量值，不是队列。渲染侧以 30fps 轮询取最新值，
 * 分析侧约 43Hz 写入，两者频率接近，丢中间帧没有任何视觉影响，也就不需要缓冲。
 *
 * ## crossfade 约束
 * 交叉淡化期间主副两台播放器都会调 `handleBuffer`。若两边都写，能量会翻倍，
 * 所以只有 `playerKey == PLAYER_PRIMARY` 的分析器允许写入；扶正时
 * (`VocalSeparationController.promoteSecondaryToPrimary`) 身份随之迁移。
 */
object PlaybackLevelBridge {

    /**
     * 用 raw bits 存 float：`AtomicInteger` 不装箱，`AtomicReference<Float>` 每次写都要装箱。
     * 写点在播放线程、读点在渲染线程，30~43Hz，虽然量级可忽略，但这里没有理由多分配。
     */
    private val levelBits = AtomicInteger(0f.toRawBits())

    /** 最近一次能量值（0..1）。没有数据时返回 0。 */
    fun read(): Float = Float.fromBits(levelBits.get())

    /** 播放线程调用，约每个 hop（≈23ms）一次。 */
    fun write(level: Float) {
        val clamped = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        levelBits.set(clamped.toRawBits())
    }

    /** 停止播放、暂停、或关闭律动时清零，避免画面停在放大态。 */
    fun reset() {
        levelBits.set(0f.toRawBits())
    }
}
