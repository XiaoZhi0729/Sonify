package yos.music.player.data.repositories

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Absence of a success marker is never proof that a mutation failed. */
internal enum class PlaylistBusinessOutcome { SUCCESS, FAILURE, UNKNOWN }
class PlaylistMutationUnknownException(message: String, cause: Throwable? = null) : IOException(message, cause)
class PlaylistBusinessException(message: String) : IOException(message)

internal fun playlistBusinessOutcome(fields: Map<*, *>): PlaylistBusinessOutcome {
    val nested = fields["data"] as? Map<*, *>
    val envelopes = listOfNotNull(fields, nested)
    var success = false
    for (envelope in envelopes) {
        for (key in listOf("errcode", "error_code", "errorcode")) {
            val value = envelope[key]?.toString()?.toLongOrNull() ?: continue
            if (value != 0L) return PlaylistBusinessOutcome.FAILURE
            success = true
        }
        envelope["status"]?.let { raw ->
            when (raw.toString()) {
                "1", "true" -> success = true
                "0", "false" -> return PlaylistBusinessOutcome.FAILURE
                else -> if (raw.toString().toIntOrNull() != null) return PlaylistBusinessOutcome.FAILURE
            }
        }
        if (envelope["error"] is String && (envelope["error"] as String).isNotBlank()) {
            return PlaylistBusinessOutcome.FAILURE
        }
    }
    return if (success) PlaylistBusinessOutcome.SUCCESS else PlaylistBusinessOutcome.UNKNOWN
}

internal data class PlaylistSourceIdentity(val gid: String, val userid: String, val listid: String)
internal fun playlistSourceIdentity(gid: String): PlaylistSourceIdentity? {
    val match = Regex("^collection_[0-9]+_([0-9]+)_([0-9]+)_[0-9]+$").matchEntire(gid) ?: return null
    return PlaylistSourceIdentity(gid, match.groupValues[1], match.groupValues[2])
        .takeIf { it.userid != "0" && it.listid != "0" }
}

internal data class UserPlaylistPage(val entries: List<KugouPlaylist>, val total: Int? = null)

/** No partial success, no page cap silently truncating large libraries. */
internal suspend fun loadAllUserPlaylistPages(
    pageSize: Int,
    fetch: suspend (Int) -> Result<UserPlaylistPage>
): Result<List<KugouPlaylist>> {
    require(pageSize > 0)
    return try {
        val output = mutableListOf<KugouPlaylist>()
        val seen = mutableSetOf<String>()
        var page = 1
        var expectedTotal: Int? = null
        while (true) {
            val batch = fetch(page).getOrThrow()
            val keys = batch.entries.map { it.listid.ifBlank { "gid:${it.gid}" } }
            if (keys.any { it == "gid:" } || keys.toSet().size != keys.size || keys.any { !seen.add(it) }) {
                throw IOException("歌单分页重复或缺少条目身份")
            }
            batch.total?.takeIf { it >= 0 }?.let { total ->
                if (expectedTotal != null && expectedTotal != total) throw IOException("歌单分页总数发生变化，请刷新")
                expectedTotal = total
            }
            output += batch.entries
            if (expectedTotal != null && output.size > expectedTotal!!) throw IOException("歌单分页总数不一致")
            if (expectedTotal == output.size) break
            if (batch.entries.size < pageSize) {
                if (expectedTotal != null && output.size < expectedTotal!!) throw IOException("歌单分页提前结束")
                break
            }
            if (page++ >= 10_000) throw IOException("歌单分页超出安全限制")
        }
        Result.success(output)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/** Reset invalidates work even when the account and credential version happen to be unchanged. */
internal data class PlaylistGeneration(val userid: String?, val authVersion: Long, val epoch: Long) {
    fun matches(userid: String?, authVersion: Long, epoch: Long): Boolean =
        this.userid == userid && this.authVersion == authVersion && this.epoch == epoch
}

internal fun mergeCollectedPlaylistEntries(
    raw: List<KugouPlaylist>, collected: List<KugouPlaylist>, hiddenSourceGids: Set<String> = emptySet()
): List<KugouPlaylist> {
    val bySource = collected.filter { it.isCollectedPlaylist && it.sourceGid.isNotBlank() }.associateBy { it.sourceGid }
    val seen = mutableSetOf<String>()
    val result = raw.filter { it.source != 2 }.mapNotNull { entry ->
        if (!entry.isCollectedPlaylist) return@mapNotNull entry
        val source = entry.sourceGid
        if (source in hiddenSourceGids || (source.isNotBlank() && !seen.add(source))) return@mapNotNull null
        bySource[source]?.let { local ->
            local.copy(listid = entry.listid.ifBlank { local.listid }, gid = entry.gid.ifBlank { local.gid })
        } ?: entry
    }.toMutableList()
    collected.filter { it.sourceGid !in hiddenSourceGids && seen.add(it.sourceGid) }.forEach { result.add(it) }
    return result
}
