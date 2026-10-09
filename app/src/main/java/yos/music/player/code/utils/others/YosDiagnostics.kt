package yos.music.player.code.utils.others

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.repositories.NetworkKind
import yos.music.player.data.repositories.NetworkObserver

/**
 * 应用侧诊断日志：唯一能在事后回答"声音什么时候停的、停之前最后一条事件是什么"的东西。
 *
 * **为什么自己写文件，而不是沿用 println / android.util.Log**：
 * app/proguard-rules.pro 用 `-assumenosideeffects` 把 `java.io.PrintStream.println/print`
 * 与 `android.util.Log.*` 在 release 里整条剥掉了。于是"熄屏播放几分钟后无声"这类只在夜间
 * 发生、又必须事后取证的问题，设备上留不下任何应用侧证据——上次排查只能靠 AudioFlinger 的
 * 出声时长归因与 batterystats 的 AudioMix wakelock 反推。所以这里刻意**不借道 Log/println**：
 * 渲染成字符串后直接落盘，R8 无从剥离。
 *
 * 落点：`<getExternalFilesDir>/logs/yos-diag-YYYYMMDD.txt`
 * 免权限、`adb pull /sdcard/Android/data/<包名>/files/logs/` 直接可取，设置页另有一键导出合并报告。
 *
 * **每行同时记两个单调钟**，这是判"进程被冻结 vs 设备深睡"的唯一手段（线程被冻时自己无法感知，
 * 只能在恢复后从相邻两行的间隔看出来）：
 *  - `up=` [SystemClock.uptimeMillis]：不含深睡；
 *  - `el=` [SystemClock.elapsedRealtime]：含深睡。
 * upΔ ≫ 周期且 elΔ≈upΔ → 设备醒着而进程没被调度（冻结/剥夺 CPU）；elΔ ≫ upΔ → 设备进了内核
 * 休眠（此时本就不可能出声）。再叠加 HB 行的 `pos` 是否推进，就能把"无声"分成三类：
 * 引擎被冻 / 引擎在跑但网络断供（pos 不推进、state=BUFFERING）/ 被正常暂停（play=false）。
 */
object YosDiagnostics {

    /**
     * 导出报告里单独汇总的关键事件前缀：肉眼扫几 MB 文件不现实，先把这些挑出来。
     * 匹配规则见 [isKeyEvent]：以 `_` 结尾的条目（`LYRIC_`）是**前缀型**，命中它旗下全部事件。
     */
    private val KEY_EVENTS = setOf(
        "APP_CREATE", "SVC_CREATE", "SVC_DESTROY", "PLAY_ERROR", "SKIP_TO_NEXT", "SKIP_LIMIT",
        "SKIP_HOLD", "SKIP_REARM", "AUTH_INVALID",
        "RESOLVE_FAIL", "RESOLVE_SLOW", "OPEN_FAIL", "OPEN_SLOW", "STATE", "ISPLAYING", "SUPPRESSION",
        "GAP", "STALL", "STATE_LIE", "SCREEN_ON", "SCREEN_OFF", "DOZE", "SAVER",
        "SLEEP_START", "SLEEP_STOP", "SLEEP_FIRED", "PREPARE", "TRANSITION", "CRASH", "HEADSET", "EXPORT",
        "SVC_CMD", "TLMUT", "SRC", "LYRIC_"
    )

    /** 行格式固定是 `… pid=<数字> <事件名> k=v …`，事件名就取 pid 后第一个 token。 */
    private val eventRegex = Regex("\\spid=\\d+\\s([A-Za-z_][A-Za-z0-9_]*)")

    private fun eventOf(line: String): String? = eventRegex.find(line)?.groupValues?.get(1)

    /**
     * 关键事件判定。旧实现是 `line.contains(" $prefix ")`：事件名与后续字段之间没有空格
     * 分隔，前缀型条目（`LYRIC_`）永远配不上任何一行——写了等于没写，
     * 歌词链路只能去 TAIL 全量里翻。
     */
    private fun isKeyEvent(line: String): Boolean {
        val ev = eventOf(line) ?: return false
        return KEY_EVENTS.any { if (it.endsWith("_")) ev.startsWith(it) else ev == it }
    }

    private const val MEM_RING_LIMIT = 3000
    private const val FILE_MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_KEEP_COUNT = 8
    private const val QUEUE_LIMIT = 4096
    private const val BATCH = 256
    private const val HB_PLAYING_MS = 5_000L
    private const val HB_IDLE_MS = 60_000L
    private const val REPORT_TAIL_LINES = 4000

    // SimpleDateFormat 非线程安全，而 log() 会从任意线程（加载线程、主线程、心跳线程）进来；
    // 共享一个实例会在并发下产出错乱时间戳，而那恰恰是取证最不能坏的东西。
    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val dayFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMdd", Locale.US)
    }

    private val pid = Process.myPid()
    private val bootWallClock = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    private val dropped = AtomicLong(0)
    private val written = AtomicLong(0)

    private val ringLock = Any()
    private val ring = ArrayDeque<String>()

    private var ioExec: ExecutorService? = null
    private var hbExec: ScheduledExecutorService? = null
    private var queue: LinkedBlockingQueue<String>? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var writerFile: File? = null

    /** 心跳读的播放器：服务销毁后可能已 release，所有读取一律 runCatching 包着。 */
    @Volatile
    private var hbPlayer: Player? = null

    private var hbFuture: ScheduledFuture<*>? = null
    private var hbPeriodMs = HB_PLAYING_MS
    private var lastUp = 0L
    private var lastEl = 0L
    private var lastPos = -1L
    private var lastPlaying = false

    private var receiverRegistered = false

    /** 取播放器数据必须回主线程（ExoPlayer 的应用线程校验），心跳只负责定时。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------------------------------------------------------------- 生命周期

    /**
     * 幂等初始化：Application 与播放服务各调一次即可。
     * 开关关掉时仍然初始化（只是不再写行），这样"关掉日志"不会让导出按钮丢掉已有历史。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            appContext = app
            if (initialized) return
            initialized = true
            queue = LinkedBlockingQueue(QUEUE_LIMIT)
            ioExec = Executors.newSingleThreadExecutor { r ->
                Thread(r, "YosDiagWriter").apply { isDaemon = true }
            }
            hbExec = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "YosDiagHeartbeat").apply { isDaemon = true }
            }
        }
        registerSystemReceiver(app)
        log(
            "APP_CREATE", "sdk" to Build.VERSION.SDK_INT, "model" to Build.MODEL,
            "pkg" to app.packageName, "dir" to logsDir(app).absolutePath
        )
    }

    /** 播放服务 onCreate/onDestroy 调用：服务重启、被系统停掉都会在这里留下痕迹。 */
    fun attachPlayback(context: Context, player: Player?) {
        init(context)
        hbPlayer = player
        synchronized(this) {
            runCatching { hbFuture?.cancel(false) }
            hbFuture = null
        }
        if (player != null) scheduleHeartbeat(HB_PLAYING_MS)
    }

    private fun scheduleHeartbeat(delayMs: Long) {
        val exec = hbExec ?: return
        hbPeriodMs = delayMs
        hbFuture = runCatching {
            exec.scheduleWithFixedDelay({ runCatching { heartbeat() } }, delayMs, delayMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * 总开关的内存副本。**刻意不在 [log] 里现读 [SettingsLibrary]**：
     * SettingsSaver 在构造时就 `MMKV.mmkvWithID()`，而本对象的 [init] 跑在 Application 的
     * `MMKV.initialize()` 之前（崩溃兜底必须先于一切可用）——那时去读会把 YosDataSaver
     * 的类初始化搞成 ExceptionInInitializerError，连带把整个设置层抹掉。
     */
    @Volatile
    private var loggingEnabled = true

    /** MMKV 就绪后由 Application 调一次，把持久化的开关值取进内存。 */
    fun syncEnabledFromSettings() {
        loggingEnabled = runCatching { SettingsLibrary.DiagLogEnabled }.getOrDefault(true)
        colorOsLyricDiag =
            runCatching { SettingsLibrary.ColorOsLyricDiagEnabled }.getOrDefault(false)
    }

    /** 设置页切换开关时同步（避免等到下次启动才生效）。 */
    fun setLoggingEnabled(value: Boolean) {
        loggingEnabled = value
    }

    /** ColorOS 歌词诊断开关同步：独立于总开关，开着即整体落盘。 */
    fun setColorOsLyricDiagEnabled(value: Boolean) {
        colorOsLyricDiag = value
    }

    @Volatile
    private var colorOsLyricDiag = false

    private fun enabled(): Boolean = loggingEnabled || colorOsLyricDiag

    private fun logsDir(context: Context): File {
        val base = context.getExternalFilesDir("logs")
            ?: File(context.filesDir, "logs").also { it.mkdirs() }
        if (!base.exists()) base.mkdirs()
        return base
    }

    // ---------------------------------------------------------------- 写入主链路

    /**
     * 记一行结构化事件。时间戳与两个单调钟在**调用点**取（写线程可能排队），否则最想看到的
     * 冻结/停顿恰恰会被抹平。值里的空白换下划线，保证一行一条、可 grep。
     */
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        val line = buildString(128) {
            append(timeFmt.get()!!.format(Date(now)))
            append(" up=").append(SystemClock.uptimeMillis())
            append(" el=").append(SystemClock.elapsedRealtime())
            append(" pid=").append(pid)
            append(' ').append(event)
            fields.forEach { (k, v) ->
                append(' ').append(k).append('=')
                append(v?.toString()?.replace('\n', ' ')?.replace(' ', '_') ?: "-")
            }
        }
        synchronized(ringLock) {
            if (ring.size >= MEM_RING_LIMIT) ring.removeFirst()
            ring.addLast(line)
        }
        val q = queue ?: return
        // 队满就丢最脏的情况（计数留给报告），绝不在调用方线程等磁盘
        if (!q.offer(line)) dropped.incrementAndGet()
        ioExec?.execute { drainToDisk() }
    }

    /** 消费队列落到当日文件；只在写线程执行。 */
    private fun drainToDisk() {
        val q = queue ?: return
        val app = appContext ?: return
        try {
            var n = 0
            while (n < BATCH) {
                val line = q.poll() ?: break
                n++
                val w = ensureWriter(app) ?: return
                w.write(line)
                w.write("\n")
                written.incrementAndGet()
            }
            writer?.flush()
            rotateIfNeeded(app)
        } catch (_: Exception) {
            // 诊断通道本身不能成为崩溃源；出错静默降级，内存环仍在
        }
    }

    private fun ensureWriter(app: Context): BufferedWriter? {
        val target = File(logsDir(app), "yos-diag-${dayFmt.get()!!.format(Date())}.txt")
        if (writerFile?.name == target.name) writer?.let { return it }
        closeWriter()
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), "UTF-8"), 32 * 1024)
        writerFile = target
        return writer
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    /** 单文件超阈值就切分片，并只保留最近 [FILE_KEEP_COUNT] 个文件。 */
    private fun rotateIfNeeded(app: Context) {
        val cur = writerFile ?: return
        if (cur.length() < FILE_MAX_BYTES) return
        closeWriter()
        runCatching {
            cur.renameTo(File(cur.parentFile, "${cur.nameWithoutExtension}.${System.currentTimeMillis() / 1000}.txt"))
        }
        runCatching {
            logsDir(app).listFiles { f -> f.name.startsWith("yos-diag-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(FILE_KEEP_COUNT)
                ?.forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- 播放心跳

    private fun heartbeat() {
        val p = hbPlayer ?: return
        val up = SystemClock.uptimeMillis()
        val el = SystemClock.elapsedRealtime()
        // ExoPlayer 的方法带应用线程校验，从心跳线程直读会全部抛异常（真机跑出来就是一片 -1）。
        // 所以取数回到主线程做，但**时间戳仍在心跳时刻取**：主线程卡住与整进程被冻是两回事，
        // 靠 main_d（回主线程的往返延迟）与 up_d 分开看。
        val postedAt = SystemClock.uptimeMillis()
        mainHandler.post {
            runCatching { heartbeatOnMainThread(p, up, el, postedAt - up) }
        }
    }

    private fun heartbeatOnMainThread(p: Player, up: Long, el: Long, mainDelay: Long) {
        val playing = runCatching { p.isPlaying }.getOrDefault(false)
        val pos = runCatching { p.currentPosition }.getOrDefault(-1L)

        if (lastUp != 0L) {
            val upD = up - lastUp
            val elD = el - lastEl
            if (upD > hbPeriodMs * 3) {
                log("GAP", "up_d" to upD, "el_d" to elD, "wasPlaying" to lastPlaying, "main_d" to mainDelay)
            }
        }
        // 播着、上一拍也在播、位置一点没动 → 引擎活着但没出声（缓冲断供）或被抑制
        if (playing && lastPlaying && lastPos >= 0 && pos == lastPos) {
            log(
                "STALL", "pos" to pos, "state" to stateName(p),
                "buf" to runCatching { p.bufferedPosition }.getOrDefault(-1L),
                "sup" to runCatching { p.playbackSuppressionReason }.getOrDefault(-1)
            )
        }
        lastUp = up
        lastEl = el
        lastPos = pos
        lastPlaying = playing

        // 引擎真实出声态 vs 会话对外报的态。ForwardingPlayer.isPlaying() 被重写成
        // FadeExo.targetStatus != 0（一个全局字段），所以“看起来在播”与“真在播”可以长期不一致；
        // 上次排查无法区分“暂停”还是“被冻”，根子就在这里。两者不一致时单独记一条。
        val reported = runCatching { yos.music.player.code.utils.player.FadeExo.targetStatus != 0 }.getOrDefault(false)
        if (reported != playing) {
            log("STATE_LIE", "real" to playing, "reported" to reported, "pos" to pos, "state" to stateName(p))
        }

        log(
            "HB",
            "play" to playing,
            "tgt" to runCatching { yos.music.player.code.utils.player.FadeExo.targetStatus }.getOrDefault(-99),
            "state" to stateName(p),
            "pos" to pos,
            "buf" to runCatching { p.bufferedPosition }.getOrDefault(-1L),
            "dur" to runCatching { p.duration }.getOrDefault(-1L),
            "sup" to runCatching { p.playbackSuppressionReason }.getOrDefault(-1),
            "pwr" to runCatching { p.playWhenReady }.getOrDefault(false),
            "q" to runCatching { p.mediaItemCount }.getOrDefault(-1),
            "idx" to runCatching { p.currentMediaItemIndex }.getOrDefault(-1),
            "main_d" to mainDelay
        )

        // 播放中 5s 一条；停着降到 60s 一条——既能看出"什么时候悄悄停了"，又不堆文件
        val want = if (playing) HB_PLAYING_MS else HB_IDLE_MS
        if (want != hbPeriodMs) {
            runCatching { hbFuture?.cancel(false) }
            scheduleHeartbeat(want)
        }
    }

    private fun stateName(p: Player): String = stateLabel(runCatching { p.playbackState }.getOrDefault(-1))

    /** 播放态名称：诊断行里写数字没人会去查表，包括调用方在内的所有地方都用这个。 */
    fun stateLabel(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        -1 -> "gone"
        else -> "?$state"
    }

    /** 在线 URL 解析结果：慢与失败都是夜间无声的候选原因，必须留下耗时。 */
    fun resolve(hash: String, costMs: Long, ok: Boolean, detail: String?) {
        if (ok) {
            if (costMs > 3000) log("RESOLVE_SLOW", "hash" to hash, "cost" to costMs)
        } else {
            log("RESOLVE_FAIL", "hash" to hash, "cost" to costMs, "why" to detail)
        }
    }

    // ---------------------------------------------------------------- 系统侧埋点

    /**
     * 熄屏 / 省电 / Doze 通断：上次排查最缺的相关性信号（当时只能从 batterystats 历史反推）。
     * 全是受保护的系统广播，动态注册即可；用 Compat 屏蔽 33+ 的导出标志位要求。
     */
    private fun registerSystemReceiver(app: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        when (i.action) {
                            Intent.ACTION_SCREEN_ON ->
                                log("SCREEN_ON", "doze" to dozeOf(c), "saver" to saverOf(c), "pwr" to interactiveOf(c))

                            Intent.ACTION_SCREEN_OFF ->
                                log("SCREEN_OFF", "doze" to dozeOf(c), "saver" to saverOf(c))

                            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                                log("DOZE", "idle" to dozeOf(c))

                            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> log("SAVER", "on" to saverOf(c))

                            Intent.ACTION_HEADSET_PLUG -> log("HEADSET", "state" to i.getIntExtra("state", -1))
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun pm(context: Context): PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // runCatching 包住的表达式类型是 Boolean?（pm() 可空），不先用 == true 收敛就得
    // 写 getOrDefault 到内层；这里统一收成非空 Boolean，否则三个探针全部编译不过
    private fun dozeOf(context: Context): Boolean =
        runCatching { pm(context)?.isDeviceIdleMode == true }.getOrDefault(false)

    private fun saverOf(context: Context): Boolean =
        runCatching { pm(context)?.isPowerSaveMode == true }.getOrDefault(false)

    private fun interactiveOf(context: Context): Boolean =
        runCatching { pm(context)?.isInteractive == true }.getOrDefault(true)

    private fun batteryPercent(context: Context): Int = runCatching {
        val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }.getOrDefault(-1)

    // ---------------------------------------------------------------- 崩溃与导出

    /** 崩溃兜底：只留头若干帧，避免一行塞进整棵因果链。 */
    fun crash(stack: String) {
        log("CRASH", "stack" to stack.lineSequence().take(24).joinToString(" | "))
    }

    /** 供设置页显示"日志在哪、多大"。 */
    fun stats(context: Context): Pair<Long, String> {
        val dir = logsDir(context)
        val bytes = runCatching {
            dir.listFiles { f -> f.name.startsWith("yos-diag-") }?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        return bytes to dir.absolutePath
    }

    /**
     * 生成单文件合并报告：策略快照 + 关键事件 + 日志尾部 + 对照取证提示。
     * 返回 null 表示写失败（存储不可用）；文件同时留在 logs/reports/ 下，adb 也能直接 pull。
     */
    fun export(context: Context): File? {
        val app = context.applicationContext
        init(app)
        log("EXPORT", "dropped" to dropped.get())
        awaitDrain()
        val reportDir = File(logsDir(app), "reports").also { it.mkdirs() }
        val out = File(reportDir, "yos-report-${System.currentTimeMillis()}.txt")
        val lines = tailLines(app)
        runCatching {
            BufferedWriter(OutputStreamWriter(FileOutputStream(out), "UTF-8")).use { w ->
                w.write(header(app))
                w.write("\n== KEY EVENTS ==\n")
                lines.filter { l -> isKeyEvent(l) }.takeLast(600)
                    .forEach { w.write(it); w.write("\n") }
                w.write("\n== TAIL ${REPORT_TAIL_LINES} ==\n")
                lines.takeLast(REPORT_TAIL_LINES).forEach { w.write(it); w.write("\n") }
                w.write("\n== 字段图例 ==\n")
                w.write("up=/el= 两个单调钟（up 不含深睡）；相邻两行的 up_d/el_d 分别指向‘进程被冻’与‘设备深睡’\n")
                w.write("HB/GAP/STALL/STATE_LIE：引擎真态与 ForwardingPlayer 上报态不一致时出 STATE_LIE；main_d 为回主线程往返延迟\n")
                w.write("STATE.s: 1=IDLE 2=BUFFERING 3=READY 4=ENDED（本版本直译）；TRANSITION.reason 为 media3 原值\n")
                w.write("SRC fn=ui_next/ui_prev: 手动切歌入口（按下即记，带 idx/q/state，用于分辨“没发出/被吞/被打回”）\n")
                w.write("SKIP_HOLD/SKIP_REARM: 连续失败达阈值后不再逐首跳，挂退避重试；AUTH_INVALID: 上游鉴权失效（illegal_key）\n")
                w.write("\n== 系统侧对照（本机 adb 执行，用来验证本报告的 pos 推进是否等于真出声）==\n")
                w.write("dumpsys media.audio_flinger | grep -A2 'yos.music'\n")
                w.write("  每行 sessions/actual_seconds 是真出声时长；frozen-while-active 计数=播放中被冻结次数\n")
                w.write("dumpsys batterystats | grep -E 'u0a[0-9]+:\"AudioMix\"' | tail -n 60\n")
                w.write("  AudioMix wakelock 通断=混音线程活跃区间，应与 HB 的 play=true 区间吻合\n")
                w.flush()
            }
        }.onFailure { return null }
        return if (out.exists() && out.length() > 0) out else null
    }

    /** 排空写队列（最多等 ~600ms），否则报告尾部会缺最后几行。 */
    private fun awaitDrain() {
        repeat(30) {
            drainToDisk()
            if (queue?.isEmpty() == true) return
            runCatching { Thread.sleep(20) }
        }
    }

    /**
     * ColorOS 锁屏歌词专用报告：只挑歌词链路事件（LYRIC_* 系列 / 切歌 / 开播 / 屏亮灭 /
     * 外部命令），头部加 ColorOS ROM 版本与歌词开关快照，尾部附测试者自查步骤。
     * 主报告 export() 的轻量分卷——反馈"锁屏不出歌词"时让用户只发这一份。
     */
    fun exportColorOsLyric(context: Context): File? {
        val app = context.applicationContext
        init(app)
        log("EXPORT", "kind" to "coloros_lyric", "dropped" to dropped.get())
        awaitDrain()
        val reportDir = File(logsDir(app), "reports").also { it.mkdirs() }
        val out = File(reportDir, "coloros-lyric-report-${System.currentTimeMillis()}.txt")
        val lines = tailLines(app)
        runCatching {
            BufferedWriter(OutputStreamWriter(FileOutputStream(out), "UTF-8")).use { w ->
                w.write(header(app))
                w.write("lyric_switch=")
                w.write(runCatching { SettingsLibrary.SuperIslandLyricEnabled }.getOrDefault("?").toString())
                w.write(" lyric_diag=" + colorOsLyricDiag + "\n")
                w.write("rom_props=" + romProps() + "\n")
                w.write("\n== LYRIC EVENTS ==\n")
                lines.filter { l -> l.contains(" LYRIC_") || l.contains(" TRANSITION ") }
                    .takeLast(1200)
                    .forEach { w.write(it); w.write("\n") }
                w.write("\n== CONTEXT (prepare/external commands/screen) ==\n")
                lines.filter { l ->
                    l.contains(" PREPARE ") || l.contains(" SVC_CMD ") ||
                        l.contains(" SCREEN_ON") || l.contains(" SCREEN_OFF")
                }.takeLast(400)
                    .forEach { w.write(it); w.write("\n") }
                w.write("\n== TAIL 200 ==\n")
                lines.takeLast(200).forEach { w.write(it); w.write("\n") }
                w.write("\n== 字段图例 ==\n")
                w.write("LYRIC_REQ: 歌词就绪待发(id/lines 行数/raw 是否逐字/delay 首发延迟)\n")
                w.write("LYRIC_SKIP: 目标与已发布内容相同,无需提交; LYRIC_CLEAR: 先清空 extras 一帧(下轮补写)\n")
                w.write("LYRIC_ADD: 单次 replaceMediaItem 写入 lyricInfo(len=JSON 字节数, gen=会话代次)\n")
                w.write("LYRIC_VERIFY: 提交后回读条目 extras,确认 app 侧已带歌词(ok=1 即应用侧链路完整)\n")
                w.write("LYRIC_NOREADY: 当前条目与目标不符仍在重试; LYRIC_ERR: 提交抛异常\n")
                w.write("LYRIC_DISABLED: 总开关(锁屏/超级岛歌词)未开——歌词被开关拦下,先去打开\n")
                w.write("\n== 测试步骤（反馈前请完整走一遍）==\n")
                w.write("1. 设置>其他>ColorOS 歌词诊断 打开后,播放一首歌等 10 秒\n")
                w.write("2. 熄屏锁屏,等 10 秒看锁屏是否出现歌词,再亮屏解锁\n")
                w.write("3. 切 2-3 首歌重复锁屏观察,然后回到本页点\"导出 ColorOS 歌词诊断日志\"\n")
                w.write("4. 本报告 LYRIC_VERIFY 全为 ok=1 而锁屏仍无歌词 → 系统侧拦截,请连同手机型号/系统版本一起反馈\n")
                w.write("\n== 系统侧对照（可让反馈者执行）==\n")
                w.write("adb shell dumpsys media_session  → 找本应用的 session,看 description/metadata 是否更新\n")
                w.flush()
            }
        }.onFailure { return null }
        return if (out.exists() && out.length() > 0) out else null
    }

    /** ColorOS/OPPO ROM 版本号（系统隐藏属性，反射取，取不到不碍事）。 */
    private fun romProps(): String = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        listOf(
            "ro.build.version.oplusrom",
            "ro.vendor.oplus.version",
            "ro.oplus.version",
            "ro.build.display.id"
        ).joinToString(" ") { key ->
            "$key=" + (get.invoke(null, key, "?") as? String ?: "?")
        }
    }.getOrDefault("?")

    /**
     * 把报告换成可临时授权的 content:// uri（系统分享面板用）。
     * authority 用 `${applicationId}.fileprovider`，与 Manifest 里声明一致；
     * 文件不在白名单目录时抛 IllegalArgumentException，这里统一降级为 null 交回调用方提示。
     */
    fun shareUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun header(app: Context): String = buildString {
        append("Yos 诊断报告  ").append(timeFmt.get()!!.format(Date())).append("\n")
        append("model=").append(Build.MODEL)
            .append(" sdk=").append(Build.VERSION.SDK_INT)
            .append(" rom=").append(runCatching { Build.DISPLAY }.getOrDefault("?")).append("\n")
        append("pkg=").append(app.packageName)
            .append(" ver=").append(runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrDefault("?"))
            .append(" pid=").append(pid).append("\n")
        append("wall_boot=").append(timeFmt.get()!!.format(Date(bootWallClock)))
            .append(" up=").append(SystemClock.uptimeMillis())
            .append(" el=").append(SystemClock.elapsedRealtime()).append("\n")
        append("exempt=").append(runCatching { BatteryOptimization.isIgnoringBatteryOptimization(app) }.getOrDefault(false))
            .append(" doze=").append(dozeOf(app))
            .append(" saver=").append(saverOf(app))
            .append(" interactive=").append(interactiveOf(app))
            .append(" net=").append(runCatching { NetworkObserver.kind(app) }.getOrDefault(NetworkKind.UNKNOWN))
            .append(" batt=").append(batteryPercent(app)).append("%\n")
        append("playback=").append(playbackSnapshot()).append("\n")
        append("written=").append(written.get())
            .append(" dropped=").append(dropped.get())
            .append(" mem=").append(synchronized(ringLock) { ring.size }).append("\n")
    }

    /** 播放态快照：只读内存状态，取不到就 unknown——导出路径上绝不抛。 */
    private fun playbackSnapshot(): String = runCatching {
        val m = yos.music.player.code.MediaController
        val music = m.musicPlaying.value
        val c = m.mediaControl
        "mediaId=${music?.mediaId} online=${music?.uri?.scheme == yos.music.player.data.repositories.KugouRepository.PLACEHOLDER_SCHEME}" +
                " pos=${c?.currentPosition} playing=${c?.isPlaying} q=${c?.mediaItemCount}" +
                " sleepRemain=${m.sleepTimerRemainingSeconds.intValue} state=${c?.playbackState}"
    }.getOrDefault("unknown")

    /** 取最近 3 个日志文件的行（按时间正序拼），够还原一晚的时间线。 */
    private fun tailLines(app: Context): List<String> {
        val files = runCatching {
            logsDir(app).listFiles { f -> f.name.startsWith("yos-diag-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }?.take(3)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        files.reversed().forEach { f -> out.addAll(runCatching { f.readLines() }.getOrDefault(emptyList())) }
        return out
    }
}
