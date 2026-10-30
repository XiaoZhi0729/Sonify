package yos.music.player.data.vocalsep

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite 实现的 [MssModel]：把平面频谱与 28 路状态搬进解释器，再把结果搬回来。
 *
 * 张量契约见 [MssModel] 的注释。所有 ByteBuffer 都是直接缓冲（direct），
 * 避免每次推理多一次堆内拷贝。
 */
class TfliteMssModel(private val interpreter: Interpreter) : MssModel {

    companion object {
        /** 输入频谱元素数：8 帧 × 4 平面 × 1024 bin。 */
        const val SPECTRUM_IN_SIZE = 8 * 4 * 1024

        /** 输出频谱元素数：8 帧 × 16 平面 × 1024 bin。 */
        const val SPECTRUM_OUT_SIZE = 8 * 16 * 1024
    }

    private val spectrumInBuf: ByteBuffer =
        ByteBuffer.allocateDirect(SPECTRUM_IN_SIZE * 4).order(ByteOrder.nativeOrder())
    private val spectrumOutBuf: ByteBuffer =
        ByteBuffer.allocateDirect(SPECTRUM_OUT_SIZE * 4).order(ByteOrder.nativeOrder())

    private val stateIn: Array<ByteBuffer>
    private val stateOut: Array<ByteBuffer>

    override val stateSizes: IntArray

    init {
        // 状态路数与形状**从解释器动态读**，绝不硬编码：模型换了形状就得跟着走
        val count = interpreter.inputTensorCount - 1
        stateSizes = IntArray(count)
        stateIn = Array(count) { ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()) }
        stateOut = Array(count) { ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()) }
        for (i in 0 until count) {
            val shape = interpreter.getInputTensor(i + 1).shape()
            var size = 1
            for (d in shape) size *= d
            stateSizes[i] = size
            stateIn[i] = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
            stateOut[i] = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        }
    }

    override fun run(spectrumIn: FloatArray, states: Array<FloatArray>, spectrumOut: FloatArray) {
        spectrumInBuf.clear()
        spectrumInBuf.asFloatBuffer().put(spectrumIn, 0, SPECTRUM_IN_SIZE)

        for (i in states.indices) {
            val buf = stateIn[i]
            buf.clear()
            buf.asFloatBuffer().put(states[i], 0, minOf(states[i].size, stateSizes[i]))
        }

        val inputs = arrayOfNulls<Any>(states.size + 1)
        inputs[0] = spectrumInBuf.rewind()
        for (i in states.indices) inputs[i + 1] = stateIn[i].rewind()

        val outputs = HashMap<Int, Any>(states.size + 1)
        spectrumOutBuf.clear()
        outputs[0] = spectrumOutBuf
        for (i in states.indices) outputs[i + 1] = stateOut[i].rewind()

        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        spectrumOutBuf.rewind()
        spectrumOutBuf.asFloatBuffer().get(spectrumOut, 0, SPECTRUM_OUT_SIZE)

        // 28 路状态逐块原地回灌
        for (i in states.indices) {
            stateOut[i].rewind()
            stateOut[i].asFloatBuffer().get(states[i], 0, minOf(states[i].size, stateSizes[i]))
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }
}
