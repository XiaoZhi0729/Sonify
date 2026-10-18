package yos.music.player.data.libraries

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 主页板块身份。key 是持久化标识，永不更改；显示名复用现有 string 资源
 * （home_continue_listening / home_everyday_recommend_title / discovery_recommend_playlists_title
 *  / discovery_new_songs_title / discovery_new_albums_title / discovery_rank_title）。
 */
enum class HomeSectionId(val key: String) {
    RecentlyPlayed("recently_played"),
    EverydayRecommend("everyday_recommend"),
    RecommendPlaylists("recommend_playlists"),
    NewSongs("new_songs"),
    NewAlbums("new_albums"),
    Rank("rank");

    companion object {
        fun fromKey(key: String): HomeSectionId? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 主页板块配置（持久化 DTO）。
 *
 * 每字段必须 @SerializedName：Gson 反射用运行时字段名，release 混淆后与 debug / 跨版本键名漂移，
 * 读回时字段整体为 null（项目两次启动崩溃循环根因）。R8 另需在 proguard-rules.pro 保留本类字段。
 */
data class HomeSectionConfig(
    @SerializedName("id") val id: String,
    @SerializedName("visible") val visible: Boolean = true
)

/**
 * 主页板块布局的**纯逻辑**（无 MMKV / 无 Compose 依赖），便于 JVM 单测。
 *
 * 不变量：结果恒为 [可见组...][隐藏组...] —— 编辑态的「分割线」依赖该分组边界。
 */
object HomeLayoutLogic {

    /** 默认布局：现有源码顺序、全部显示。 */
    fun default(): List<HomeSectionConfig> =
        HomeSectionId.entries.map { HomeSectionConfig(it.key, visible = true) }

    /**
     * 归一化：① 丢未知 id ② 去重（保首个）③ 补齐缺失的已知 id（visible=true）
     * ④ 最后固定重排为 [visible=true...][visible=false...]（组内保相对顺序）⑤ 空 → 默认布局。
     */
    fun normalize(raw: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val seen = LinkedHashSet<String>()
        val known = ArrayList<HomeSectionConfig>()
        for (c in raw) {
            if (HomeSectionId.fromKey(c.id) == null) continue // ① 丢未知
            if (!seen.add(c.id)) continue                     // ② 去重
            known.add(c)
        }
        for (id in HomeSectionId.entries) {                   // ③ 补齐缺失
            if (known.none { it.id == id.key }) known.add(HomeSectionConfig(id.key, true))
        }
        if (known.isEmpty()) return default()                 // ⑤
        return known.filter { it.visible } + known.filterNot { it.visible } // ④ 分组
    }

    /** 翻转某板块可见性：隐藏 → 移到隐藏组末尾；恢复 → 追加到显示组末尾。 */
    fun toggle(list: List<HomeSectionConfig>, id: HomeSectionId): List<HomeSectionConfig> {
        val target = list.firstOrNull { it.id == id.key } ?: return list
        val others = list.filterNot { it.id == id.key }
        val flipped = target.copy(visible = !target.visible)
        val visible = others.filter { it.visible } + listOfNotNull(flipped.takeIf { it.visible })
        val hidden = others.filterNot { it.visible } + listOfNotNull(flipped.takeIf { !it.visible })
        return visible + hidden
    }

    /** 仅在「显示组」内重排（隐藏组保持在后）。from / to 为显示组下标。 */
    fun reorderVisible(list: List<HomeSectionConfig>, from: Int, to: Int): List<HomeSectionConfig> {
        val visible = list.filter { it.visible }.toMutableList()
        val hidden = list.filterNot { it.visible }
        if (from !in visible.indices || to !in visible.indices || from == to) return list
        val moved = visible.removeAt(from)
        visible.add(to, moved)
        return visible + hidden
    }

    /**
     * 拖拽落位（显示/隐藏两组都能拖，可跨组）：把 [fromIndex] 处的胶囊移到 [toIndex]，
     * 并按落点区域决定其显示/隐藏。
     *
     * 不变量：列表恒为 [显示...][隐藏...]。移除被拖项后，前 [boundary] 个位置属于显示区
     * （boundary = 剩余项中可见的数量）；落点 toIndex <= boundary → 显示，否则隐藏。
     * 最后重新分组为 [显示][隐藏]（组内保序）。
     */
    fun dragCommit(full: List<HomeSectionConfig>, fromIndex: Int, toIndex: Int): List<HomeSectionConfig> {
        if (fromIndex !in full.indices) return full
        val list = full.toMutableList()
        val moved = list.removeAt(fromIndex)
        val boundary = list.count { it.visible }
        val visible = toIndex <= boundary
        list.add(toIndex.coerceIn(0, list.size), moved.copy(visible = visible))
        return list.filter { it.visible } + list.filterNot { it.visible }
    }
}

/**
 * 主页板块布局 Store（进程内单例）。
 *
 * 范式对齐 SearchObject：内存 mutableStateOf + 手动 MMKV/Gson 落盘（不依赖 DataSaver 类型转换器）。
 */
@Stable
object HomeLayoutStore {

    private const val MMKV_ID = "yos_home_layout"
    private const val KEY = "home_section_layout_v1" // 版本化键名：未来 schema 变更用 _v2 + 迁移分支

    private val gson = Gson()

    private fun mmkv() = MMKV.mmkvWithID(MMKV_ID)

    /** 当前布局（Compose 状态：组合内读取即重组）。 */
    val sections = mutableStateOf(load())

    fun defaultLayout(): List<HomeSectionConfig> = HomeLayoutLogic.default()

    fun update(list: List<HomeSectionConfig>) {
        val normalized = HomeLayoutLogic.normalize(list)
        sections.value = normalized
        persist(normalized)
    }

    fun toggle(id: HomeSectionId) = update(HomeLayoutLogic.toggle(sections.value, id))

    fun reorderVisible(from: Int, to: Int) =
        update(HomeLayoutLogic.reorderVisible(sections.value, from, to))

    fun reset() = update(HomeLayoutLogic.default())

    private fun load(): List<HomeSectionConfig> = runCatching {
        val raw = mmkv().decodeString(KEY)
        if (raw.isNullOrEmpty()) return@runCatching null
        gson.fromJson(raw, Array<HomeSectionConfig>::class.java)?.toList()
    }.getOrNull().let { HomeLayoutLogic.normalize(it ?: HomeLayoutLogic.default()) }

    private fun persist(list: List<HomeSectionConfig>) {
        runCatching { mmkv().encode(KEY, gson.toJson(list)) }
            .onFailure { YosDiagnostics.log("HOME_LAYOUT_PERSIST_FAIL", "err" to it.message) }
    }
}
