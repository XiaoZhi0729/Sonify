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

    /** 冷启动首段发布目标：凑到这么多首立即发布并关 loading（用户裁决先出 10 首）。 */
    const val FIRST_PUBLISH_TARGET = 10

    val songs = mutableStateOf<List<KugouNewSong>>(emptyList())
    val loading = mutableStateOf(true)
    // 防重入守卫与 UI 态分离：loading 初始为 true（首屏转圈），若直接以 loading 判重会拦掉首拉
    val inFlight = mutableStateOf(false)
    val appending = mutableStateOf(false)
    val endReached = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    private val cursorHash = mutableStateOf("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 进入页面时调用：已有内容则跳过，否则重置队列并首拉补到 [BUFFER_TARGET]。 */
    fun ensureLoaded() {
        if (songs.value.isNotEmpty() || inFlight.value) return
        load()
    }

    fun load() {
        if (inFlight.value) return
        inFlight.value = true
        loading.value = true
        loadError.value = null
        endReached.value = false
        songs.value = emptyList()
        cursorHash.value = ""
        scope.launch {
            var firstError: String? = null
            var attempts = 0
            var cursor = ""
            // **分两段发布**（用户裁决：冷启动凑满 50 首太慢，先出 10 首立即可用）：
            //   第一段：凑到 [FIRST_PUBLISH_TARGET]（约 2 批）整段发布并关掉 loading
            // ——首屏转圈只等 2 个网络往返，不再"加载很长一段时间"。
            //   第二段：后台继续补到 [BUFFER_TARGET]，每批到货即增量发布（追加只发生
            // 在队尾，聚焦卡下标映射不变；计数器分母 10→15→…→50 跳变属可接受代价，
            // 与 appendMore 的快滑补货同一策略）。
            val accumulated = ArrayList<KugouNewSong>(BUFFER_TARGET)
            while (!endReached.value && accumulated.size < FIRST_PUBLISH_TARGET && attempts < TOPUP_CAP) {
                attempts++
                val result = KugouRepository.getPersonalFmSongs(hash = cursor)
                val fresh = result.getOrDefault(emptyList())
                val known = accumulated.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    if (accumulated.isEmpty()) {
                        firstError = result.exceptionOrNull()?.message
                    } else {
                        endReached.value = true
                    }
                    break
                }
                accumulated += batch
                cursor = batch.last().hash
            }
            cursorHash.value = cursor
            songs.value = accumulated
            loadError.value = firstError
            inFlight.value = false
            loading.value = false
            // 第二段：后台补满到 BUFFER_TARGET（loading 已关，UI 立即可交互）
            while (!endReached.value && songs.value.size < BUFFER_TARGET && attempts < TOPUP_CAP) {
                attempts++
                val result = KugouRepository.getPersonalFmSongs(hash = cursor)
                val fresh = result.getOrDefault(emptyList())
                val known = songs.value.map { it.hash }.toSet()
                val batch = fresh.filter { it.hash.isNotEmpty() && it.hash !in known }
                if (batch.isEmpty()) {
                    endReached.value = true
                    break
                }
                songs.value = songs.value + batch
                cursor = batch.last().hash
                cursorHash.value = cursor
                if (MediaController.isFmQueue.value) {
                    MediaController.appendToQueue(batch.map { KugouRepository.toQueueMediaItem(it) })
                }
            }
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
