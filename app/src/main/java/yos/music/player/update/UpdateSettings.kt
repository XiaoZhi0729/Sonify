package yos.music.player.update

import com.tencent.mmkv.MMKV

/** Independent MMKV namespace. Application MMKV initialization must run first. */
object UpdateSettings {
    private val store by lazy { MMKV.mmkvWithID("sonify_cloud_updates") }
    var autoCheck: Boolean
        get() = store.decodeBool("auto_check", true)
        set(value) { check(store.encode("auto_check", value)) }
    var lastSuccessAt: Long
        get() = store.decodeLong("last_success_at", 0L)
        set(value) { check(store.encode("last_success_at", value)) }
    var lastFailureAt: Long
        get() = store.decodeLong("last_failure_at", 0L)
        set(value) { check(store.encode("last_failure_at", value)) }
    var ignoredVersion: Long
        get() = store.decodeLong("ignored_version", 0L)
        set(value) { check(store.encode("ignored_version", value)) }
}

object UpdateCheckPolicy {
    const val SUCCESS_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val FAILURE_INTERVAL_MS = 15L * 60 * 1000
    const val MAX_SESSION_CHECKS = 2

    /** Automatic attempts only. Manual checks should not be subject to this policy. */
    fun shouldCheck(
        autoCheck: Boolean,
        now: Long,
        lastSuccessAt: Long,
        lastFailureAt: Long,
        sessionChecks: Int
    ): Boolean {
        if (!autoCheck || now < 0 || sessionChecks !in 0 until MAX_SESSION_CHECKS) return false
        fun elapsed(timestamp: Long, interval: Long): Boolean =
            timestamp <= 0 || (now >= timestamp && now - timestamp >= interval)
        return elapsed(lastSuccessAt, SUCCESS_INTERVAL_MS) && elapsed(lastFailureAt, FAILURE_INTERVAL_MS)
    }
}
