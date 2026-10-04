package yos.music.player.data.repositories

import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 挂起歌单日志（创建中歌单的单一事实源）。
 *
 * 背景（真机实测）：/playlist/add 本身 <1s 返回，但酷狗 cloudlist 服务端要 ~5-8 秒才
 * 传播出真实 listid（/user/playlist 可见）。为让用户「创建后立刻能进详情页加歌删歌」：
 *
 *  - 创建成功即登记本日志（PENDING，localId 代替 listid），详情页以 localId 打开；
 *  - 详情页里的加歌/删歌先入本地 ops 队列（A=添加 / D=删除）；
 *  - 对账协程每 8 秒按名字在 /user/playlist 里找真实 listid（最多 ~90 秒），
 *    找到即绑定（realListid）并按序补发 ops，之后加删走服务端直调；
 *  - 超时仍未传播 → 标记 FAILED：列表行与详情页展示「创建未成功」，用户改动全部
 *    保留，可一键重试创建（成功后重新对账补发）；绝不静默丢弃用户操作。
 */
object PendingPlaylistStore {

    private const val TAG = "PendingPlaylistStore"
    private const val KEY_JOURNAL = "journal"

    /** 对账节奏：8 秒 × 11 次 ≈ 90 秒窗口（实测传播 ~5-8 秒，留足余量）。 */
    private const val RECONCILE_ATTEMPTS = 11
    private const val RECONCILE_INTERVAL_MS = 8_000L

    private val mmkv: MMKV by lazy { MMKV.mmkvWithID("kugou_pending_playlists") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class PendingPlaylist(
        val localId: String,
        val name: String,
        val realListid: String = "",
        val failed: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        /** 待补发操作："A|title|hash|albumId|mixsongId" / "D|fileidOrHash"。 */
        val ops: List<String> = emptyList()
    ) {
        val isBound: Boolean get() = realListid.isNotEmpty()
    }

    // ---------------- 持久化 ----------------

    private fun persist(entries: List<PendingPlaylist>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("localId", e.localId)
                    .put("name", e.name)
                    .put("realListid", e.realListid)
                    .put("failed", e.failed)
                    .put("createdAt", e.createdAt)
                    .put("ops", JSONArray(e.ops))
            )
        }
        mmkv.encode(KEY_JOURNAL, arr.toString())
    }

    fun all(): List<PendingPlaylist> {
        val raw = mmkv.decodeString(KEY_JOURNAL) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val opsArr = o.optJSONArray("ops")
                PendingPlaylist(
                    localId = o.optString("localId"),
                    name = o.optString("name"),
                    realListid = o.optString("realListid"),
                    failed = o.optBoolean("failed", false),
                    createdAt = o.optLong("createdAt", 0L),
                    ops = opsArr?.let { a ->
                        (0 until a.length()).mapNotNull { j -> a.optString(j).takeIf { it.isNotEmpty() } }
                    } ?: emptyList()
                )
            }
        }.getOrDefault(emptyList()).filter { it.localId.isNotEmpty() && it.name.isNotEmpty() }
    }

    fun get(localId: String): PendingPlaylist? = all().firstOrNull { it.localId == localId }

    private fun update(entry: PendingPlaylist) {
        persist(all().filterNot { it.localId == entry.localId } + entry)
    }

    // ---------------- 生命周期 ----------------

    /** 创建成功后登记挂起条目并启动对账。 */
    fun registerCreated(name: String): PendingPlaylist {
        val entry = PendingPlaylist(
            localId = "local_" + UUID.randomUUID().toString(),
            name = name
        )
        update(entry)
        scheduleReconcile(entry.localId)
        return entry
    }

    /** 用户删除挂起条目（列表页长按删除）。取消对账由找不到条目自然终止。 */
    fun delete(localId: String) {
        persist(all().filterNot { it.localId == localId })
    }

    /** FAILED 后一键重试创建：重新提交 /playlist/add，成功则回到 PENDING 重新对账。 */
    suspend fun retryCreate(localId: String): Result<Unit> {
        val entry = get(localId) ?: return Result.failure(IllegalStateException("挂起歌单不存在"))
        return KugouRepository.createMyPlaylist(entry.name).map {
            update(entry.copy(failed = false, createdAt = System.currentTimeMillis()))
            scheduleReconcile(localId)
        }
    }

    // ---------------- 对账 ----------------

    /**
     * 对账：按名字在 /user/playlist 找真实 listid。命中即绑定并补发 ops。
     * 幂等：已绑定或条目已删除时直接返回。列表页合并时也会调用（提前命中）。
     */
    fun scheduleReconcile(localId: String) {
        scope.launch {
            repeat(RECONCILE_ATTEMPTS) { attempt ->
                delay(RECONCILE_INTERVAL_MS)
                val entry = get(localId) ?: return@launch
                if (entry.isBound) return@launch
                if (tryBind(entry)) return@launch
                Log.d(TAG, "reconcile #$attempt miss: ${entry.name}")
            }
            // 窗口耗尽仍未传播 → 标记失败（改动保留，等用户重试或删除）
            get(localId)?.let { entry ->
                if (!entry.isBound && !entry.failed) {
                    update(entry.copy(failed = true))
                    Log.w(TAG, "reconcile timeout, mark failed: ${entry.name}")
                }
            }
        }
    }

    /** 尝试立即绑定（列表页拿到最新 /user/playlist 时调用）。返回是否绑定成功。 */
    suspend fun tryBind(entry: PendingPlaylist): Boolean {
        if (entry.isBound) return true
        val match = KugouRepository.getMyPlaylists().getOrNull()
            ?.firstOrNull { it.name == entry.name } ?: return false
        val realId = match.listid.ifEmpty { match.gid }
        if (realId.isEmpty()) return false
        val bound = entry.copy(realListid = realId)
        update(bound)
        Log.d(TAG, "bound ${entry.name} -> $realId, ops=${entry.ops.size}")
        flushOps(bound)
        return true
    }

    /** 按序补发 ops；单条失败保留余下（含失败条）等下次合并/重试。 */
    private suspend fun flushOps(entry: PendingPlaylist) {
        var remaining = entry.ops
        for (op in entry.ops) {
            val ok = when (op.substringBefore('|')) {
                "A" -> {
                    val parts = op.split('|')
                    if (parts.size < 3) true else KugouRepository.addTracksToPlaylist(
                        listid = entry.realListid,
                        title = parts[1],
                        hash = parts[2]
                    ).isSuccess
                }
                "D" -> KugouRepository.removeTrackFromPlaylist(
                    listid = entry.realListid, fileId = 0, hash = op.substringAfter('|')
                ).isSuccess
                else -> true
            }
            if (!ok) {
                Log.w(TAG, "flush op failed, stop: $op")
                break
            }
            remaining = remaining - op
        }
        if (remaining != entry.ops) update(entry.copy(ops = remaining))
    }

    // ---------------- 详情页加/删歌 ----------------

    /**
     * 加歌：已绑定 → 服务端直调；未绑定 → 入 ops 队列（绑定后自动补发）。
     * 调用方负责去重提示（同 hash 的待加操作重复会失败）。
     */
    suspend fun addSong(localId: String, title: String, hash: String): Result<Unit> {
        val entry = get(localId) ?: return Result.failure(IllegalStateException("歌单不存在"))
        if (entry.ops.any { it.startsWith("A|") && it.split('|').getOrNull(2) == hash }) {
            return Result.failure(IllegalStateException("歌曲已在歌单中"))
        }
        return if (entry.isBound) {
            KugouRepository.addTracksToPlaylist(entry.realListid, title, hash)
        } else {
            val id = KugouRepository.songIdentityFor(hash)
            update(entry.copy(ops = entry.ops + "A|$title|$hash|${id?.albumId ?: 0}|${id?.mixsongId ?: 0}"))
            Result.success(Unit)
        }
    }

    /** 删歌：已绑定 → 服务端直调（fileid 优先）；未绑定 → 删除队列先撤同 hash 的待加，否则入删除队列。 */
    suspend fun removeSong(localId: String, hash: String, fileId: Long = 0): Result<Unit> {
        val entry = get(localId) ?: return Result.failure(IllegalStateException("歌单不存在"))
        if (!entry.isBound) {
            val queued = entry.ops.firstOrNull { it.startsWith("A|") && it.split('|').getOrNull(2) == hash }
            return if (queued != null) {
                update(entry.copy(ops = entry.ops - queued))
                Result.success(Unit)
            } else {
                update(entry.copy(ops = entry.ops + "D|$hash"))
                Result.success(Unit)
            }
        }
        return KugouRepository.removeTrackFromPlaylist(entry.realListid, fileId, hash)
    }
}
