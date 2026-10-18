package yos.music.player.data.repositories

import org.json.JSONArray
import org.json.JSONObject
import yos.music.player.native.KugouApiService
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 歌曲评论数据层（对齐 md3Music `lib/services/kugou_api/comment_*.dart`）。
 *
 * 网络侧全部走本地 Rust server 的 `/comment/...` 路由（参数契约见
 * `kugou_api_server/rust/src/modules/comment_music.rs` / `comment_more.rs` / `comment_send.rs`），
 * Kotlin 只负责组装参数与解析。
 *
 * 解析约定与仓库其它部分一致：`org.json` + 多键兜底（上游字段名不统一），
 * 单条脏数据跳过而不是整页失败。
 */
object KugouCommentRepository {

    /** 评论列表页大小（cmtlist / topliked 上游默认 30）。 */
    const val PAGE_SIZE = 30

    /** 楼层回复页大小（上游硬上限 50）。 */
    const val FLOOR_PAGE_SIZE = 50

    /** 单条评论。 */
    data class KugouComment(
        val id: String,
        val username: String,
        val avatar: String?,
        val content: String,
        val timeSec: Long,
        val likes: Int,
        val replyCount: Int,
        val isHot: Boolean,
        val isStar: Boolean,
        /** 楼中楼父回复 id；null / "0" 表示直接回复楼主。 */
        val parentId: String?,
        /** 评论资源 id（列表项 `special_child_id`），发送/删除/拉楼层时用。 */
        val specialId: String?,
        /** 楼层根评论 id（列表项 `tid`，缺省回落到自身 id）。 */
        val tid: String?,
        val userId: String?
    )

    /** 一页评论。 */
    data class KugouCommentPage(
        val comments: List<KugouComment>,
        /** 歌手评论（`star_cmts` / `weight_list`），所有排序模式共用。 */
        val hotComments: List<KugouComment>,
        val total: Int,
        /** 评论区 id（响应顶层 `childrenid`），「最热」接口必传。 */
        val childrenId: String,
        /** 上游本页原始条数——hasMore 判据（解析后条数会被脏数据过滤掉）。 */
        val rawCount: Int
    )

    /** 楼中楼展开后的一行。 */
    data class CommentReplyNode(val reply: KugouComment, val depth: Int, val isOrphan: Boolean)

    /** 发送/删除结果（上游 `status` + `error_code`）。 */
    data class CommentSendResult(
        val ok: Boolean,
        val errorCode: Int,
        val message: String,
        /** 20028 / 60045：上游风控要求验证（本次不做验证码流程，只提示）。 */
        val needVerify: Boolean
    )

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    /** 评论列表（最新/最早共用同一份数据，顺序由 UI 决定）。 */
    suspend fun loadComments(
        mixsongid: Long,
        page: Int = 1,
        pageSize: Int = PAGE_SIZE
    ): Result<KugouCommentPage> =
        KugouApiService.getInstance().getMusicComments(mixsongid.toString(), page, pageSize)
            .mapCatching { parsePage(it) }

    /** 最热评论（全局按点赞降序）。 */
    suspend fun loadTopLiked(
        childrenId: String,
        page: Int = 1,
        pageSize: Int = PAGE_SIZE
    ): Result<KugouCommentPage> =
        KugouApiService.getInstance().getTopLikedComments(childrenId, page, pageSize)
            .mapCatching { parsePage(it) }

    /** 楼层回复（按时间倒序）。 */
    suspend fun loadFloor(
        specialId: String,
        tid: String,
        mixsongid: Long?,
        page: Int = 1,
        pageSize: Int = FLOOR_PAGE_SIZE
    ): Result<KugouCommentPage> =
        KugouApiService.getInstance().getCommentFloor(
            specialId = specialId,
            tid = tid,
            mixsongid = mixsongid?.takeIf { it > 0 }?.toString(),
            page = page,
            pageSize = pageSize
        ).mapCatching { parsePage(it) }

    /** 发顶层评论。[specialId] 为空时 Rust 会先用 mixsongid 反查（多一次上游请求）。 */
    suspend fun sendComment(
        content: String,
        mixsongid: Long?,
        specialId: String?
    ): Result<CommentSendResult> =
        KugouApiService.getInstance().sendMusicComment(
            content = content,
            mixsongid = mixsongid?.takeIf { it > 0 }?.toString(),
            specialId = specialId?.takeIf { it.isNotBlank() }
        ).mapCatching { parseSendResult(it) }

    /** 回复楼层。[pid] 非空表示回复楼内某条回复（否则回复楼主）。 */
    suspend fun sendReply(
        content: String,
        specialId: String,
        tid: String,
        pid: String?,
        mixsongid: Long?,
        replyUserName: String?,
        replyContent: String?
    ): Result<CommentSendResult> =
        KugouApiService.getInstance().sendFloorReply(
            content = content,
            specialId = specialId,
            tid = tid,
            pid = pid,
            mixsongid = mixsongid?.takeIf { it > 0 }?.toString(),
            replyUserName = replyUserName,
            replyContent = replyContent
        ).mapCatching { parseSendResult(it) }

    /** 删除自己的评论。删楼中楼回复时 [tid] 必须是楼层根评论 id。 */
    suspend fun deleteComment(
        cid: String,
        specialId: String?,
        mixsongid: Long?,
        tid: String?
    ): Result<CommentSendResult> =
        KugouApiService.getInstance().deleteComment(
            cid = cid,
            specialId = specialId?.takeIf { it.isNotBlank() },
            mixsongid = mixsongid?.takeIf { it > 0 }?.toString(),
            tid = tid?.takeIf { it.isNotBlank() }
        ).mapCatching { parseSendResult(it) }

    /**
     * 解析评论接口必需的 mixsongid（= album_audio_id）。
     *
     * 首选解析时登记的身份表（搜索/歌单/榜单/FM 都会写，且落 MMKV）；
     * 未命中时用 `/audio?hash=` 兜底解析一次并回写身份表。
     */
    suspend fun resolveMixSongId(hash: String): Long {
        val key = hash.lowercase().trim()
        if (key.isEmpty()) return 0
        KugouRepository.songIdentityFor(key)?.mixsongId?.takeIf { it > 0 }?.let { return it }

        val json = KugouApiService.getInstance().getSongDetailByHash(key).getOrNull() ?: return 0
        val detail = detailItem(json) ?: return 0
        val mixSongId = extractAlbumAudioId(detail)
        if (mixSongId > 0) {
            KugouRepository.registerSongIdentity(
                hash = key,
                albumId = extractLong(detail, listOf("album_id", "AlbumID")),
                mixsongId = mixSongId,
                fileId = 0
            )
        }
        return mixSongId
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private val LIST_KEYS = listOf("list", "comments", "info")
    private val HOT_KEYS = listOf("weight_list", "hot_list", "star_cmts", "star_comment")

    private fun pageRoot(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

    /** 取数组：既接受 `{list:[...]}`，也接受裸数组。 */
    private fun firstArray(root: JSONObject, keys: List<String>): JSONArray? {
        for (key in keys) {
            root.optJSONArray(key)?.let { return it }
            root.optJSONObject(key)?.optJSONArray("list")?.let { return it }
        }
        return null
    }

    private fun firstInt(root: JSONObject, keys: List<String>): Int {
        for (key in keys) {
            val v = root.opt(key) ?: continue
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: continue
                else -> continue
            }
            return n
        }
        return 0
    }

    internal fun parsePage(json: JSONObject): KugouCommentPage {
        val root = pageRoot(json)
        // 网络/DNS 失败时 Rust 回的是 HTTP 502 + {"status":0,"msg":"...Dns Failed..."}，
        // httpGetVipBusiness 会把它当业务 JSON 返回；不校验 status 就会把失败误读成
        // 「0 条评论」，用户看到的是「暂无评论」而不是可重试的错误态。
        if (root.has("status") && root.optInt("status", 0) != 1) {
            throw java.io.IOException(root.optString("msg").ifBlank { "comment list failed" })
        }
        val raw = firstArray(root, LIST_KEYS)
        val comments = raw?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseComment(it) }
            }
        }.orEmpty()

        // 歌手评论：star_cmts 与 weight_list 可能重复，按 id 去重。
        // 徽章必须按来源判定：真实样本里 star_cmts 条目的 singer_status 是 0，
        // 只靠字段判不出 isStar，会把「歌手评论」区的条目标成「热门」。
        val hot = ArrayList<KugouComment>()
        val seen = HashSet<String>()
        for (key in HOT_KEYS) {
            val arr = firstArray(root, listOf(key)) ?: continue
            val fromStar = key.startsWith("star")
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)?.let {
                    parseComment(it, isHot = !fromStar, isStar = fromStar)
                } ?: continue
                if (c.id.isNotEmpty() && seen.add(c.id)) hot.add(c)
            }
        }

        return KugouCommentPage(
            comments = comments,
            hotComments = hot,
            total = firstInt(root, listOf("total", "count", "comment_count", "comments_num")),
            childrenId = root.optString("childrenid").takeIf { it.isNotBlank() }
                ?: comments.firstNotNullOfOrNull { it.specialId }?.takeIf { it.isNotBlank() }
                ?: "",
            rawCount = raw?.length() ?: 0
        )
    }

    internal fun parseComment(
        json: JSONObject,
        isHot: Boolean = false,
        isStar: Boolean = false
    ): KugouComment? {
        // id 为 0 的条目不是可交互评论（楼层/回复/删除都依赖它），直接丢弃
        val id = firstString(json, listOf("commentid", "id"))
            ?.takeIf { it.isNotEmpty() && it != "0" }
            ?: return null
        val user = json.optJSONObject("user")
        val like = json.optJSONObject("like")
        val vinfo9 = json.optJSONObject("vinfo9")
        val isAuthor = parseBool(json.opt("isAuthor") ?: json.opt("is_author")) ||
                (vinfo9?.optInt("singer_status", 0) == 4)

        return KugouComment(
            id = id,
            username = firstString(
                json,
                listOf("user_name", "username", "nickname")
            ) ?: user?.let { firstString(it, listOf("name", "nickname")) } ?: "匿名用户",
            avatar = firstString(
                json,
                listOf("user_pic", "user_img", "avatar")
            ) ?: user?.let { firstString(it, listOf("avatar", "pic", "img")) },
            content = firstString(json, listOf("content", "comment_text")).orEmpty(),
            timeSec = parseCommentTime(json.opt("createtime") ?: json.opt("addtime") ?: json.opt("time")),
            likes = firstInt(
                json,
                listOf("like_count", "likes", "like_num", "reply_like_count")
            ).takeIf { it > 0 }
                ?: like?.let { firstInt(it, listOf("count", "likenum")) }
                ?: 0,
            replyCount = firstInt(json, listOf("reply_num", "reply_count")),
            isHot = parseBool(json.opt("is_hot") ?: json.opt("isHot")) || isHot,
            isStar = parseBool(json.opt("is_star") ?: json.opt("isStar")) || isAuthor || isStar,
            parentId = firstString(json, listOf("pid")),
            specialId = firstString(
                json,
                listOf("special_child_id", "special_id", "specialId", "childrenid")
            ),
            // 上游顶层评论的 tid 常为 0（md3Music 用 _zeroAsNull 归空）：必须回落到自身 id，
            // 否则拉楼层（/comment/floor?tid=0）返回空、发回复报「TID不能为空」。
            tid = firstString(json, listOf("tid"))?.takeIf { it != "0" }
                ?: firstString(json, listOf("id", "comment_id")),
            userId = firstString(json, listOf("user_id", "userid", "kugouid"))
        )
    }

    internal fun parseSendResult(json: JSONObject): CommentSendResult {
        val status = json.optInt("status", 0)
        val errorCode = json.optInt("error_code", 0)
        val message = firstString(json, listOf("error_msg", "msg", "message")).orEmpty()
        val needVerify = errorCode == 20028 || errorCode == 60045
        return CommentSendResult(
            ok = status == 1 && errorCode == 0,
            errorCode = errorCode,
            message = message,
            needVerify = needVerify
        )
    }

    private fun firstString(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val v = json.opt(key)
            val s = when (v) {
                is String -> v
                is Number -> v.toString()
                else -> continue
            }
            if (s.isNotBlank() && s != "null") return s.trim()
        }
        return null
    }

    private fun parseBool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }

    /** 评论时间：秒级时间戳或 "yyyy-MM-dd HH:mm:ss"（上游按北京时间，无时区）。 */
    internal fun parseCommentTime(value: Any?): Long {
        if (value == null) return 0
        if (value is Number) {
            val n = value.toLong()
            return if (n > 10_000_000_000L) n / 1000 else n
        }
        val text = value.toString().trim()
        if (text.isEmpty()) return 0
        text.toLongOrNull()?.let { return if (it > 10_000_000_000L) it / 1000 else it }
        val iso = text.replaceFirst(' ', 'T')
        for (pattern in TIME_PATTERNS) {
            val parsed = runCatching { formatter(pattern).parse(iso) }.getOrNull() ?: continue
            return parsed.time / 1000
        }
        return 0
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm"
    )

    private fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
            isLenient = false
        }

    private fun detailItem(root: JSONObject): JSONObject? = when (val data = root.opt("data")) {
        is JSONArray -> data.optJSONObject(0)
        is JSONObject -> data
        else -> root
    }

    private val DETAIL_NESTS = listOf("base", "audio_info", "audio", "song_info", "album_info", "rec_song_info")

    private val ALBUM_AUDIO_ID_KEYS = listOf(
        "album_audio_id", "AlbumAudioID", "MixSongID", "mixsongid", "add_mixsongid", "Audioid", "audio_id"
    )

    private fun detailViews(item: JSONObject): List<JSONObject> =
        buildList {
            add(item)
            DETAIL_NESTS.forEach { key -> item.optJSONObject(key)?.let { add(it) } }
        }

    /** album_audio_id：先看条目自身，再看嵌套块（KMR 常放在 base 里）。 */
    internal fun extractAlbumAudioId(item: JSONObject): Long {
        val views = detailViews(item)
        for (key in ALBUM_AUDIO_ID_KEYS) {
            for (view in views) {
                val v = view.opt(key) ?: continue
                val n = when (v) {
                    is Number -> v.toLong()
                    is String -> v.trim().toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (n > 0) return n
            }
        }
        return 0
    }

    private fun extractLong(item: JSONObject, keys: List<String>): Long {
        for (view in detailViews(item)) {
            for (key in keys) {
                val v = view.opt(key) ?: continue
                val n = when (v) {
                    is Number -> v.toLong()
                    is String -> v.trim().toLongOrNull() ?: 0L
                    else -> 0L
                }
                if (n > 0) return n
            }
        }
        return 0
    }

    // ------------------------------------------------------------------
    // 纯逻辑（可单测）
    // ------------------------------------------------------------------

    /** 按点赞降序；同赞保持接口原顺序（翻页追加后已看过的评论不跳动）。 */
    internal fun sortByLikes(comments: List<KugouComment>): List<KugouComment> =
        comments.withIndex()
            .sortedWith(compareByDescending<IndexedValue<KugouComment>> { it.value.likes }.thenBy { it.index })
            .map { it.value }

    /** 按时间倒序（最新在前）。 */
    internal fun sortByTimeDesc(comments: List<KugouComment>): List<KugouComment> =
        comments.withIndex()
            .sortedWith(compareByDescending<IndexedValue<KugouComment>> { it.value.timeSec }.thenBy { it.index })
            .map { it.value }

    /**
     * 扁平回复列表按 `pid` 组装成树，再 DFS 展开成带 depth 的一维列表。
     * 同层时间倒序；父级指向未加载的回复时按一级渲染并标 isOrphan；
     * 迭代式遍历 + visited 防脏数据成环；不可达项兜底追加。
     */
    internal fun buildReplyTree(replies: List<KugouComment>): List<CommentReplyNode> {
        if (replies.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, KugouComment>()
        for (reply in replies) {
            if (reply.id.isEmpty() || byId.containsKey(reply.id)) continue
            byId[reply.id] = reply
        }

        fun resolvedParentId(reply: KugouComment): String? {
            val pid = reply.parentId
            if (pid.isNullOrEmpty() || pid == "0" || pid == reply.id) return null
            return pid.takeIf { byId.containsKey(it) }
        }

        fun pointsToMissingParent(reply: KugouComment): Boolean {
            val pid = reply.parentId
            if (pid.isNullOrEmpty() || pid == "0" || pid == reply.id) return false
            return !byId.containsKey(pid)
        }

        val roots = ArrayList<KugouComment>()
        val children = HashMap<String, MutableList<KugouComment>>()
        for (reply in byId.values) {
            val parentId = resolvedParentId(reply)
            if (parentId == null) roots.add(reply)
            else children.getOrPut(parentId) { ArrayList() }.add(reply)
        }
        roots.sortByDescending { it.timeSec }
        children.values.forEach { it.sortByDescending { reply -> reply.timeSec } }

        val out = ArrayList<CommentReplyNode>(byId.size)
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<KugouComment, Int>>()
        roots.asReversed().forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty()) {
            val (reply, depth) = stack.removeLast()
            if (!visited.add(reply.id)) continue
            out.add(CommentReplyNode(reply, depth, depth == 0 && pointsToMissingParent(reply)))
            children[reply.id]?.asReversed()?.forEach { kid -> stack.addLast(kid to depth + 1) }
        }
        // 成环导致不可达的项：兜底追加，不静默丢内容
        for (reply in byId.values) {
            if (visited.add(reply.id)) out.add(CommentReplyNode(reply, 0, true))
        }
        return out
    }

    /** 去掉楼中楼内容尾部的「//@用户名:原文」引用（非孤儿时才有意义）。 */
    internal fun stripReplyQuote(content: String): String {
        val idx = content.lastIndexOf("//@")
        if (idx <= 0) return content
        val ref = content.substring(idx + 3)
        if (ref.indexOf(':') <= 0) return content
        val text = content.substring(0, idx).trim()
        return text.ifEmpty { content }
    }

    /** 是否本人评论（只有自己的评论允许长按删除）。 */
    internal fun isOwnComment(comment: KugouComment, currentUserid: String?): Boolean {
        val uid = currentUserid?.trim().orEmpty()
        val author = comment.userId?.trim().orEmpty()
        return uid.isNotEmpty() && author.isNotEmpty() && uid == author
    }

    /**
     * 相对时间：只返回结构，文案交给 UI 的 string resource（四种语言都要翻译）。
     * [Date] 是超过 30 天的绝对日期，本身与语言无关。
     */
    internal sealed interface CommentTime {
        data object JustNow : CommentTime
        data class Minutes(val value: Long) : CommentTime
        data class Hours(val value: Long) : CommentTime
        data class Days(val value: Long) : CommentTime
        data class Date(val text: String) : CommentTime
    }

    internal fun commentTime(
        timeSec: Long,
        nowSec: Long = System.currentTimeMillis() / 1000
    ): CommentTime {
        if (timeSec <= 0) return CommentTime.Date("")
        val diff = nowSec - timeSec
        return when {
            diff < 60 -> CommentTime.JustNow
            diff < 3600 -> CommentTime.Minutes(diff / 60)
            diff < 86_400 -> CommentTime.Hours(diff / 3600)
            diff < 86_400 * 30 -> CommentTime.Days(diff / 86_400)
            else -> CommentTime.Date(
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
                    .format(java.util.Date(timeSec * 1000))
            )
        }
    }

    /**
     * 点赞数紧凑格式：中文/日文用「万」，其它语言用「K」。
     * 语言由调用方传入，纯函数便于单测（默认跟随系统）。
     */
    internal fun formatLikes(likes: Int, language: String = Locale.getDefault().language): String {
        if (likes < 10_000) return likes.toString()
        return if (language == "zh" || language == "ja") "${likes / 10_000}万"
        else "${likes / 1_000}K"
    }
}
