package yos.music.player.native

import android.util.Log

/**
 * POC native bridge layer: loads libkugou_server.so and exposes minimal API control.
 *
 * JNI 绑定经由 com.md3music.premium.KugouApiService（shim 类，其全限定类名与
 * 官方 .so 导出的 JNI 符号精确匹配）。
 * 此前的 external fun start_server / is_server_running 等纯 C FFI 风格声明已移除：
 * Kotlin external fun 只走 JNI 协议（按 Java_<类名>_<方法名> 查符号），
 * .so 中不存在 yos.music.player.* 前缀的 JNI 符号，故原先抛 UnsatisfiedLinkError。
 *
 * JNI 层仅有 start/stop/isRunning 三个方法（无 get_server_port 的 JNI 包装），
 * 端口取自 startServer() 的返回值并缓存（Rust server::start() 语义：已在运行时
 * 返回当前端口，见 kugou_api_server/rust/src/server.rs）。
 */
object KugouNativeBridge {
    private const val TAG = "KugouNativeBridge"

    private val jni = com.md3music.premium.KugouApiService()

    @Volatile
    private var cachedPort: Int = 0

    init {
        Log.d(TAG, "Loading libkugou_server.so...")
        try {
            com.md3music.premium.KugouApiService.ensureLoaded()
            Log.d(TAG, "✓ libkugou_server.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libkugou_server.so", e)
            throw e
        }
    }

    /**
     * Start the local HTTP server.
     * @param port 0 = random port
     * @param dataDir directory for device_info.json persistence
     * @return actual port if successful, 0 if failed
     */
    fun startServer(port: Int = 0, dataDir: String): Int {
        Log.d(TAG, "Starting kugou_server on port=$port, dataDir=$dataDir")
        val result = jni.startNode(port, dataDir)
        if (result > 0) {
            cachedPort = result
            Log.d(TAG, "✓ Server started on port $result")
        } else {
            Log.e(TAG, "✗ Server start failed, returned $result")
        }
        return result
    }

    /**
     * Check if server is running.
     */
    fun isServerRunning(): Boolean {
        val running = jni.isNodeRunning()
        Log.d(TAG, "Server running: $running")
        return running
    }

    /**
     * Get current server port.
     *
     * JNI 层未导出 get_server_port，端口来自最近一次 startServer() 的返回值。
     * 若端口已丢失但服务仍在运行，可再次调用 startServer(0, ...) 取回当前端口。
     */
    fun getServerPort(): Int {
        if (cachedPort > 0) {
            Log.d(TAG, "Current server port: $cachedPort")
            return cachedPort
        }
        if (jni.isNodeRunning()) {
            Log.w(TAG, "Server running but port not cached; re-querying via startNode")
        }
        return cachedPort
    }

    /**
     * Stop the local HTTP server.
     */
    fun stopServer() {
        Log.d(TAG, "Stopping kugou_server...")
        jni.stopNode()
        cachedPort = 0
        Log.d(TAG, "✓ Server stopped")
    }

    /**
     * Get base URL for HTTP client.
     */
    fun getBaseUrl(): String {
        val port = getServerPort()
        require(port > 0) { "Server not running, cannot get URL" }
        return "http://127.0.0.1:$port"
    }
}
