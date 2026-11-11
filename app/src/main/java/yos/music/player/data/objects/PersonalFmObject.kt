package yos.music.player.data.objects

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yos.music.player.code.MediaController
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository

/**
 * 私人FM 队列的跨页持有者（音乐画廊页使用）。
 *
 * 补货策略原样上提自 PersonalFmDetail（策略注释见彼处头注释）：上游 /personal/fm
 * 无分页参数、每批固定下发 ~5 首，本地按末首 hash 作游标连续补多批；哈希去重，
 * 空批/整批重复即认为上游暂无更多。v1 中 PersonalFmDetail 页仍持有自己的页内
 * 队列，两者互不影响。
 */
object PersonalFmObject {

    /**
     * 聚焦位之后剩余不足这么多项就提前补货。
     * 快滑场景（用户反馈"每5首刷一次跟不上手速"）：上游每批仅 ~5 首且游标链式串行，
     * 无法并行拉批，只能靠**更早触发 + 更大目标 + 每批到货立即发布**三件套把供给
     * 流式化——触发线 20 意味着距队尾还有 4 批的余量时就开始补，够快滑吃进。
     */
    const val PREFETCH_REMAINING = 20

    /** 单次补货目标：聚焦位之后至少积压这么多项。 */
    const val BUFFER_TARGET = 50

    /** 单次补货累计上限，防御上游无去重地无限返回（须 ≥ BUFFER_TARGET 才能从 0 补满）。 */
    const val TOPUP_CAP = 60

    /**
     * 冷启动首段发布目标（**已废弃，保留仅为兼容既有引用**）。
     * v3 改成「首批到货立即发布」，[load] 不再读这个常量。
     */
    @Deprecated("首拉改为首批即发布，不要再依赖凑满 N 首")
    const val FIRST_PUBLISH_TARGET = 10

    val songs = mutableStateOf<List<KugouNewSong>>(emptyList())
    val loading = mutableStateOf(true)
    // 防重入守卫与 UI 态分离：loading 初始为 true（首屏转圈），若直接以 loading 判重会拦掉首拉
    val inFlight = mutableStateOf(false)
    val appending = mutableStateOf(false)
    /** 首拉/后台补货协程是否仍在跑。与 [inFlight] 分离：后者第一批到货就放行 UI。 */
    private val topUpRunning = mutableStateOf(false)
    val endReached = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    private val cursorHash = mutableStateOf("")

    /**
     * 主页「私人电台」板块当前聚焦的台下标。
     *
     * 为什么存在这里而不是板块自己的 `remember`：主页是 `LazyColumn`，板块滚出屏幕
     * 会被回收重建，`remember` 随之清零 → 滚回来跳回第一台。这个对象是进程级单例，
     * 存它才能"滚回来还在原处"。不做跨进程持久化：下标是会话性的浏览位置，
     * 重启后从第一批的第一台开始更符合预期。
     */
    var homeFocusIndex: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 进入页面时调用：已有内容则跳过，否则重置队列并首拉补到 [BUFFER_TARGET]。 */
    fun ensureLoaded() {
        // ★topUpRunning 必须一起判：inFlight 在第一批到货时就置 false 了，
        // 但后台还在继续补货。只看 inFlight 会让第二次 ensureLoaded() 触发
        // 又一次 load()，把 songs 清空重拉（实测会连闪两下）。
        if (songs.value.isNotEmpty() || inFlight.value || topUpRunning.value) return
        load()
    }

    fun load() {
        if (inFlight.value || topUpRunning.value) return
        inFlight.value = true
        topUpRunning.value = true
        loading.value = true
        loadError.value = null
        endReached.value = false
        // 刻意不清空旧内容：新累积结果到货时整体替换（见下方 songs.value = ...）。
        // 先清空会让主页 Pager 经历 size=N → 0 → M 的中间态，是越界与闪动的诱因。
        cursorHash.value = ""
        scope.launch {
            var firstError: String? = null
            var attempts = 0
            var cursor = ""

            /**
             * **首批到货立即发布**（v3 加载优化）。
             *
             * 上版要凑满 `FIRST_PUBLISH_TARGET=10`（2 个串行 RTT）才 publish 一次，
             * 主页骨架因此多挂一整个网络往返。现在改成：第一批拿到就发布并关 loading，
             * 之后每批增量发布。追加只发生在队尾，已显示的下标不会变；
             * 队列长度 5→10→15… 的跳变由 UI 的 Pager `key = hash` 吸收，不闪。
             *
             * 上游每批 ~5 首，一批已经够铺满首页那几张圆封面。
             */
            val accumulated = ArrayList<KugouNewSong>(BUFFER_TARGET)
            while (!endReached.value && songs.value.size < BUFFER_TARGET && attempts < TOPUP_CAP) {
                attempts++
                val result = KugouRepository.getPersonalFmSongs(hash = cursor)
                val fresh = result.getOrDefault(emptyList())
                val known = accumulated.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    if (accumulated.isEmpty()) {
                        // 首拉就失败：记下错误并立刻收尾。
                        // ★不要像上版那样再进第二段循环重试一次——每层还有 3 次
                        // retryResult 退避，服务端挂掉时白等好几秒。
                        firstError = result.exceptionOrNull()?.message
                    } else {
                        endReached.value = true
                    }
                    break
                }
                accumulated += batch
                cursor = batch.last().hash
                cursorHash.value = cursor
                songs.value = accumulated.toList()

                // 第一批到货这一刻就关 loading / 放行 UI，不等后面的批次
                if (loading.value) {
                    loadError.value = null
                    inFlight.value = false
                    loading.value = false
                }
                if (MediaController.isFmQueue.value) {
                    MediaController.appendToQueue(batch.map { KugouRepository.toQueueMediaItem(it) })
                }
            }

            // 收尾：一次都没成功时也要关 loading，否则骨架会永远挂着
            if (inFlight.value) {
                loadError.value = firstError
                inFlight.value = false
                loading.value = false
            }
            topUpRunning.value = false
        }
    }

    /**
     * 补货：上游每批仅 ~5 首，这里连续拉多批，直到积压 >= [BUFFER_TARGET]
     * 或上游无更多（endReached）或达 [TOPUP_CAP] 为止。
     *
     * **每批到货立即发布**（与 [load] 的凑满一次发布相反）：快滑时供给越早可见越好，
     * 等凑满会让用户滑到队尾仍无新歌。追加只发生在队尾，聚焦位之前的下标映射不变；
     * 队尾之后的预览卡从"环形重复旧歌"换成本尊新歌，正是快滑想要的供给。
     * （首拉 [load] 仍凑满一次发布：冷启动增量发布会让 size 连续跳变、封面连闪。）
     *
     * **队列同步**：若当前播放队列是 FM 队列（MediaController.isFmQueue），每批同时
     * `appendToQueue` 追加进活动播放队列——songs 与播放队列同序增长，画廊/播放页/
     * 电台页三端的顺序与状态从此同源；普通播放页听 FM 由 MediaController 的换曲
     * 钩子调用本函数实现"边播边补、无限续"。
     */
    fun appendMore(remainingAtTrigger: Int) {
        if (inFlight.value || appending.value || endReached.value) return
        appending.value = true
        scope.launch {
            var remaining = remainingAtTrigger
            var added = 0
            var cursor = cursorHash.value
            while (!endReached.value && remaining < BUFFER_TARGET && added < TOPUP_CAP) {
                val fresh = KugouRepository.getPersonalFmSongs(hash = cursor).getOrDefault(emptyList())
                val known = songs.value.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    endReached.value = true
                    break
                }
                songs.value = songs.value + batch
                cursor = batch.last().hash
                cursorHash.value = cursor
                added += batch.size
                remaining += batch.size
                // 同步进活动播放队列（不打断播放）；非 FM 队列在播时跳过，songs 独自增长
                if (MediaController.isFmQueue.value) {
                    MediaController.appendToQueue(batch.map { KugouRepository.toQueueMediaItem(it) })
                }
            }
            appending.value = false
        }
    }

    /** 画廊翻页/播放页换曲预热：[queueIndex] 是 FM 播放队列(=songs 前缀)中的下标。 */
    fun ensureQueueAhead(queueIndex: Int) {
        val remaining = songs.value.size - 1 - queueIndex
        if (songs.value.isNotEmpty() && remaining < PREFETCH_REMAINING) {
            appendMore(remaining.coerceAtLeast(0))
        }
    }

    /** 画廊翻页预热：聚焦位之后剩余不足 [PREFETCH_REMAINING] 即触发连续补货。 */
    fun ensureAhead(index: Int) {
        val remaining = songs.value.size - 1 - index
        if (songs.value.isNotEmpty() && remaining < PREFETCH_REMAINING) {
            appendMore(remaining)
        }
    }
}
