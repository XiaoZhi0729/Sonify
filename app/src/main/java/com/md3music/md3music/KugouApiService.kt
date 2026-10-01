package com.md3music.premium

import android.util.Log

/**
 * libkugou_server.so 的 JNI shim。
 *
 * 重要：本类的全限定类名（com.md3music.premium.KugouApiService）与方法名
 * （nativeStartNode / nativeIsNodeRunning / nativeStopNode）必须与官方 .so
 * 保持完全一致，因为 .so 导出的 JNI 符号是：
 *   Java_com_md3music_premium_KugouApiService_nativeStartNode
 *   Java_com_md3music_premium_KugouApiService_nativeIsNodeRunning
 *   Java_com_md3music_premium_KugouApiService_nativeStopNode
 * ART 按「Java_<声明类全限定名>_<方法名>」解析 external fun，改包名/类名/方法名
 * 都会导致 UnsatisfiedLinkError。
 *
 * JNI 声明原样复用自官方 md3Music 导出类
 * android/app/src/main/kotlin/com/md3music/premium/KugouApiService.kt。
 * 原版中 private external 声明在此保留为 private，并通过包装方法暴露给桥接层。
 *
 * 注意：不要把 external fun 声明为 internal —— Kotlin 会对 internal 成员做
 * 名称改写（追加模块名后缀），会破坏 JNI 符号匹配。
 */
class KugouApiService {

    companion object {
        private const val TAG = "KugouApiService"

        init {
            Log.d(TAG, "Loading libkugou_server.so...")
            System.loadLibrary("kugou_server")
            Log.d(TAG, "✓ libkugou_server.so loaded successfully")
        }

        /** 引用伴生对象即触发 System.loadLibrary，供桥接层显式触发加载。 */
        @JvmStatic
        fun ensureLoaded() = Unit
    }

    // ---- JNI 声明（与 md3Music 原版逐字一致） ----
    private external fun nativeStartNode(port: Int, dataDir: String): Int
    private external fun nativeIsNodeRunning(): Boolean
    private external fun nativeStopNode()

    // ---- 包装方法（供 yos.music.player.native.KugouNativeBridge 调用） ----

    /** 启动服务器。port=0 表示随机端口；返回实际端口（0=失败）。已在运行时返回当前端口。 */
    fun startNode(port: Int, dataDir: String): Int = nativeStartNode(port, dataDir)

    /** 服务器是否正在运行。 */
    fun isNodeRunning(): Boolean = nativeIsNodeRunning()

    /** 停止服务器。 */
    fun stopNode() = nativeStopNode()
}
