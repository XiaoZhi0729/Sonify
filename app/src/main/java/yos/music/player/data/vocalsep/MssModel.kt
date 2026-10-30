package yos.music.player.data.vocalsep

/**
 * 分离引擎对模型的抽象。
 *
 * 把「张量搬运」从「STFT/OLA 数学」里切出来，有两个目的：
 * 1. 引擎的频谱布局、重叠相加、尾量分段这些**最容易出错且后果最严重**的部分可以用
 *    一个恒等模型在纯 JVM 单测里验证（不需要 TFLite native 库）；
 * 2. 生产路径用 [TfliteMssModel] 做批量 ByteBuffer 拷贝，比逐 float 读写快得多。
 *
 * 契约（实测确定）：
 * - [spectrumIn] 长度 `FRAMES * 4 * BINS`，布局 `t*4096 + lane*1024 + f`，
 *   lane 顺序 `L_re, L_im, R_re, R_im`（**平面，不是 re/im 交错**）
 * - [spectrumOut] 长度 `FRAMES * 16 * BINS`，布局 `t*16384 + plane*1024 + f`，
 *   plane 顺序 `stem*4 + ch*2`（re）与 `+1`（im）；轨序 = 人声/贝斯/鼓/其他
 * - [states] 路数与形状由 [stateShapes] 给出，每块推理后原地回灌
 */
interface MssModel {

    /** 28 路流式状态各自的元素个数（形状连乘）。 */
    val stateSizes: IntArray

    /**
     * 跑一次推理。
     *
     * @param spectrumIn 见契约，只读
     * @param states 流式状态，实现方**必须原地回灌**下一块的状态
     * @param spectrumOut 见契约，实现方负责填满
     */
    fun run(spectrumIn: FloatArray, states: Array<FloatArray>, spectrumOut: FloatArray)

    /** 释放底层资源（解释器）。可重复调用。 */
    fun close()
}
