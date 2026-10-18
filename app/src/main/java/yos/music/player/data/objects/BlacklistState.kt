package yos.music.player.data.objects

import androidx.compose.runtime.mutableStateOf
import yos.music.player.native.KugouBlacklistApi

/**
 * 内容黑名单（不感兴趣）的进程内状态缓存，语义对齐 md3Music BlacklistProvider：
 *
 * - 首次打开歌曲菜单时全量拉取一次，之后本地判定，避免每次开菜单都打上游；
 * - 标记/取消成功后立即本地更新，菜单里的图标与文案马上翻转；
 * - 登录态变化时 [reset]（由 [yos.music.player.native.KugouApiService] 调用），防账号串数据。
 */
object BlacklistState {
    /** null = 尚未拉取；空集合 = 已拉取且没有标记。 */
    val markedHashes = mutableStateOf<Set<String>?>(null)

    private var fullyLoaded = false
    private var loading = false

    /** 全量拉取期间用户又做了标记/取消：拉取结果回来后用这些本地改动覆盖，避免被旧列表回滚。 */
    private val localOverrides = mutableMapOf<String, Boolean>()

    fun isMarked(hash: String): Boolean =
        markedHashes.value?.contains(hash.lowercase()) == true

    /** 首次打开菜单时后台加载；失败保持 null，下次打开重试。 */
    suspend fun ensureLoaded() {
        if (fullyLoaded || loading) return
        loading = true
        try {
            val overrides = localOverrides.toMap()
            KugouBlacklistApi.fetchMarkedHashes().onSuccess { fetched ->
                markedHashes.value = fetched.toMutableSet().apply {
                    overrides.forEach { (key, marked) ->
                        if (marked) add(key) else remove(key)
                    }
                }
                fullyLoaded = true
            }
        } finally {
            loading = false
        }
    }

    /** 标记/取消成功后本地翻转（未全量加载时也先让当前歌曲状态正确）。 */
    fun applyLocal(hash: String, marked: Boolean) {
        val key = hash.lowercase()
        localOverrides[key] = marked
        val now = markedHashes.value
        markedHashes.value = when {
            marked -> (now ?: emptySet()) + key
            now == null -> emptySet()
            else -> now - key
        }
    }

    fun reset() {
        markedHashes.value = null
        fullyLoaded = false
        localOverrides.clear()
    }
}
