package yos.music.player.data.repositories

import yos.music.player.data.libraries.FavPlayListLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.native.KugouApiService

/** 歌曲来源：收藏按来源分流（本地 ↔ 酷狗云端完全分离，互不同步）。 */
enum class MusicSource { LOCAL, KUGOU }

/**
 * 统一收藏门面：UI 只有一个「收藏」概念，底层按歌曲来源分流。
 *
 *   LOCAL  → FlamingoSank 原有 FavPlayListLibrary（本地持久化，不上传酷狗）
 *   KUGOU  → 当前登录酷狗账号的系统「我喜欢」歌单（is_def==2，云端存储，主键 hash）
 *
 * 在线收藏状态来自 [KugouRepository.favoriteHashes]（App 启动/登录恢复后同步一次的
 * 服务器状态缓存），isFavorite 同步读取内存集合，绝不在重组时发网络请求；
 * 写操作采用乐观更新，服务端失败自动回滚并由调用方提示。
 */
object FavoriteRepository {

    /** 来源判定：在线歌曲 mediaId 形如 kugou-online-<hash>（播放链既有约定），其余为本地。 */
    fun sourceOf(song: YosMediaItem): MusicSource =
        if (song.mediaId?.startsWith("kugou-online-") == true) MusicSource.KUGOU else MusicSource.LOCAL

    private fun kugouHash(song: YosMediaItem): String? =
        song.mediaId?.removePrefix("kugou-online-")?.lowercase()?.takeIf { it.isNotEmpty() }

    /** 收藏状态（同步读）：本地查 FavPlayListLibrary，在线查云端同步缓存。 */
    fun isFavorite(song: YosMediaItem): Boolean = when (sourceOf(song)) {
        MusicSource.LOCAL -> FavPlayListLibrary.isFavorite(song)
        MusicSource.KUGOU -> {
            val hash = kugouHash(song) ?: return false
            KugouRepository.favoriteHashes.value.contains(hash)
        }
    }

    /**
     * 添加收藏。
     * LOCAL：原逻辑不变；KUGOU：乐观更新 → /playlist/tracks/add → 失败回滚。
     * 未登录时返回失败（不改变任何状态、不影响播放），由调用方提示登录。
     */
    suspend fun addFavorite(song: YosMediaItem): Result<Unit> {
        return when (sourceOf(song)) {
        MusicSource.LOCAL -> {
            FavPlayListLibrary.addMusic(song)
            Result.success(Unit)
        }

        MusicSource.KUGOU -> {
            val hash = kugouHash(song)
                ?: return Result.failure(IllegalStateException("在线歌曲缺少 hash 标识"))
            if (!KugouApiService.isLoggedIn()) {
                return Result.failure(IllegalStateException("请先登录酷狗账号"))
            }
            KugouRepository.setFavoriteLocal(hash, true)
            val result = KugouRepository.cloudAddFavorite(hash, song.title ?: "")
            if (result.isFailure) {
                KugouRepository.setFavoriteLocal(hash, false)
            } else {
                // 写成功：通知「喜爱」/歌单等页面刷新，展示最新云端状态
                KugouSyncCoordinator.notifyFavoritesChanged()
            }
            result
        }
        }
    }
    /**
     * 移除收藏。
     * LOCAL：原逻辑不变；KUGOU：乐观更新 → /playlist/tracks/del（fileid 优先/hash 兜底）→ 失败回滚。
     */
    suspend fun removeFavorite(song: YosMediaItem): Result<Unit> {
        return when (sourceOf(song)) {
        MusicSource.LOCAL -> {
            FavPlayListLibrary.removeMusic(song)
            Result.success(Unit)
        }

        MusicSource.KUGOU -> {
            val hash = kugouHash(song)
                ?: return Result.failure(IllegalStateException("在线歌曲缺少 hash 标识"))
            if (!KugouApiService.isLoggedIn()) {
                return Result.failure(IllegalStateException("请先登录酷狗账号"))
            }
            KugouRepository.setFavoriteLocal(hash, false)
            val result = KugouRepository.cloudRemoveFavorite(hash)
            if (result.isFailure) {
                KugouRepository.setFavoriteLocal(hash, true)
            } else {
                KugouSyncCoordinator.notifyFavoritesChanged()
            }
            result
        }
        }
    }
}
