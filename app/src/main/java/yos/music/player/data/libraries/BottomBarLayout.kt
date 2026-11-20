package yos.music.player.data.libraries

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 底栏标签身份。key 是持久化标识，永不更改；显示名与图标复用现有四页资源。
 */
enum class BottomBarTabId(val key: String) {
    Home("home"),
    Favorites("favorites"),
    Library("library"),
    Search("search");

    companion object {
        fun fromKey(key: String?): BottomBarTabId? =
            if (key == null) null else entries.firstOrNull { it.key == key }
    }
}

/**
 * 底栏标签配置（持久化 DTO）。
 *
 * 每字段必须 @SerializedName 并加入 proguard-rules.pro 保留规则，
 * 防止 release 包混淆导致 Gson 读写键名漂移。
 */
data class BottomBarTabConfig(
    @SerializedName("id") val id: String,
    @SerializedName("visible") val visible: Boolean = true
)

/**
 * 底栏自定义布局的**纯逻辑**（无 MMKV / 无 Compose 依赖），便于 JVM 单测。
 *
 * 不变量：
 * 1. 结果恒为 `[可见组...][隐藏组...]`（组内保相对顺序）。
 * 2. 可见标签数始终 `>= MIN_VISIBLE_TABS`（2），保证底栏始终可切换且设置入口永不锁死。
 */
object BottomBarLayoutLogic {

    const val MIN_VISIBLE_TABS = 2

    /** 默认布局：主页、喜爱、资料库、搜索全部显示。 */
    fun default(): List<BottomBarTabConfig> =
        BottomBarTabId.entries.map { BottomBarTabConfig(it.key, visible = true) }

    /**
     * 归一化：
     * ① 丢未知 id ② 去重（保首个）③ 补齐缺失的已知 id（visible=true）
     * ④ 若可见数量不足 [MIN_VISIBLE_TABS]，将隐藏组前几项自动恢复为可见
     * ⑤ 固定重排为 `[visible=true...][visible=false...]` ⑥ 空 → 默认布局。
     */
    fun normalize(raw: List<BottomBarTabConfig>): List<BottomBarTabConfig> {
        val seen = LinkedHashSet<String>()
        val known = ArrayList<BottomBarTabConfig>()
        for (c in raw) {
            val id = BottomBarTabId.fromKey(c.id) ?: continue // ① 丢未知 / null
            if (!seen.add(id.key)) continue                   // ② 去重
            known.add(BottomBarTabConfig(id = id.key, visible = c.visible))
        }
        for (id in BottomBarTabId.entries) {                  // ③ 补齐缺失
            if (known.none { it.id == id.key }) {
                known.add(BottomBarTabConfig(id.key, visible = true))
            }
        }
        if (known.isEmpty()) return default()                 // ⑥

        val visible = known.filter { it.visible }.toMutableList()
        val hidden = known.filterNot { it.visible }.toMutableList()
        while (visible.size < MIN_VISIBLE_TABS && hidden.isNotEmpty()) { // ④ 保底可见数
            val promoted = hidden.removeAt(0)
            visible.add(promoted.copy(visible = true))
        }
        return visible + hidden                               // ⑤ 分组
    }

    /** 是否允许隐藏 [id]（当前必须可见，且隐藏后剩余可见数不少于 [MIN_VISIBLE_TABS]）。 */
    fun canHide(list: List<BottomBarTabConfig>, id: BottomBarTabId): Boolean {
        val target = list.firstOrNull { it.id == id.key } ?: return false
        if (!target.visible) return false
        return list.count { it.visible } > MIN_VISIBLE_TABS
    }

    /** 翻转某标签可见性：隐藏 → 移到隐藏组末尾（受下限保护）；恢复 → 追加到显示组末尾。 */
    fun toggle(list: List<BottomBarTabConfig>, id: BottomBarTabId): List<BottomBarTabConfig> {
        val target = list.firstOrNull { it.id == id.key } ?: return list
        if (target.visible && !canHide(list, id)) return list
        val others = list.filterNot { it.id == id.key }
        val flipped = target.copy(visible = !target.visible)
        val visible = others.filter { it.visible } + listOfNotNull(flipped.takeIf { it.visible })
        val hidden = others.filterNot { it.visible } + listOfNotNull(flipped.takeIf { !it.visible })
        return visible + hidden
    }

    /** 仅在「显示组」内重排（隐藏组保持在后）。from / to 为显示组下标。 */
    fun reorderVisible(list: List<BottomBarTabConfig>, from: Int, to: Int): List<BottomBarTabConfig> {
        val visible = list.filter { it.visible }.toMutableList()
        val hidden = list.filterNot { it.visible }
        if (from !in visible.indices || to !in visible.indices || from == to) return list
        val moved = visible.removeAt(from)
        visible.add(to, moved)
        return visible + hidden
    }

    /**
     * 拖拽落位（显示/隐藏两组都能拖，可跨组）：把 [fromIndex] 处的标签移到 [toIndex]，
     * 并按落点区域决定其显示/隐藏；若试图把可见项拖入隐藏区导致可见数 `< MIN_VISIBLE_TABS`，
     * 则拒绝隐藏并钳位到显示区末尾。
     */
    fun dragCommit(full: List<BottomBarTabConfig>, fromIndex: Int, toIndex: Int): List<BottomBarTabConfig> {
        if (fromIndex !in full.indices) return full
        val list = full.toMutableList()
        val moved = list.removeAt(fromIndex)
        val remainingVisible = list.count { it.visible }
        val desiredVisible = toIndex <= remainingVisible
        if (!desiredVisible && remainingVisible < MIN_VISIBLE_TABS) {
            list.add(remainingVisible, moved.copy(visible = true))
        } else {
            list.add(toIndex.coerceIn(0, list.size), moved.copy(visible = desiredVisible))
        }
        return list.filter { it.visible } + list.filterNot { it.visible }
    }

    /**
     * 解析冷启动默认页：
     * 若 [preferredKey] 对应某当前可见标签则使用它；否则回退到当前底栏第 1 个可见标签。
     */
    fun resolveLaunchTab(tabs: List<BottomBarTabConfig>, preferredKey: String?): BottomBarTabId {
        val visibleIds = tabs.filter { it.visible }.mapNotNull { BottomBarTabId.fromKey(it.id) }
        val preferred = BottomBarTabId.fromKey(preferredKey)
        if (preferred != null && preferred in visibleIds) return preferred
        return visibleIds.firstOrNull() ?: BottomBarTabId.Home
    }

    /** 若 [preferredKey] 当前已隐藏或非法，返回 null（代表回退为「跟随底栏第一项」）。 */
    fun sanitizeLaunchTabKey(tabs: List<BottomBarTabConfig>, preferredKey: String?): String? {
        if (preferredKey.isNullOrEmpty()) return null
        val visibleKeys = tabs.asSequence().filter { it.visible }.map { it.id }.toSet()
        return preferredKey.takeIf { it in visibleKeys }
    }

    /** 是否完全处于默认状态（默认顺序、全部可见、启动页跟随底栏第一项）。 */
    fun isDefault(tabs: List<BottomBarTabConfig>, launchTabKey: String?): Boolean =
        tabs == default() && launchTabKey.isNullOrEmpty()
}

/**
 * 底栏自定义布局 Store（进程内单例）。
 *
 * 范式对齐 [HomeLayoutStore]：内存 `mutableStateOf` + MMKV/Gson 落盘。
 */
@Stable
object BottomBarLayoutStore {

    private const val MMKV_ID = "yos_bottom_bar_layout"
    private const val KEY_TABS = "bottom_bar_layout_v1"
    private const val KEY_LAUNCH_TAB = "bottom_bar_launch_tab_v1"

    private val gson = Gson()

    private fun mmkv() = MMKV.mmkvWithID(MMKV_ID)

    /** 当前底栏标签布局（Compose 状态：组合内读取即重组）。 */
    val tabs = mutableStateOf(loadTabs())

    /**
     * 冷启动默认标签 key（`null` 表示跟随当前底栏第 1 项）。
     */
    val launchTabKey = mutableStateOf(loadLaunchTabKey(tabs.value))

    fun visibleTabIds(): List<BottomBarTabId> =
        tabs.value.filter { it.visible }.mapNotNull { BottomBarTabId.fromKey(it.id) }

    fun resolveLaunchTab(): BottomBarTabId =
        BottomBarLayoutLogic.resolveLaunchTab(tabs.value, launchTabKey.value)

    fun isDefault(): Boolean =
        BottomBarLayoutLogic.isDefault(tabs.value, launchTabKey.value)

    fun update(list: List<BottomBarTabConfig>) {
        val normalized = BottomBarLayoutLogic.normalize(list)
        tabs.value = normalized
        persistTabs(normalized)

        val sanitizedLaunch = BottomBarLayoutLogic.sanitizeLaunchTabKey(normalized, launchTabKey.value)
        if (sanitizedLaunch != launchTabKey.value) {
            launchTabKey.value = sanitizedLaunch
            persistLaunchTabKey(sanitizedLaunch)
        }
    }

    fun toggle(id: BottomBarTabId) = update(BottomBarLayoutLogic.toggle(tabs.value, id))

    fun reorderVisible(from: Int, to: Int) =
        update(BottomBarLayoutLogic.reorderVisible(tabs.value, from, to))

    fun dragCommit(fromIndex: Int, toIndex: Int) =
        update(BottomBarLayoutLogic.dragCommit(tabs.value, fromIndex, toIndex))

    fun setLaunchTab(id: BottomBarTabId?) {
        val sanitized = BottomBarLayoutLogic.sanitizeLaunchTabKey(tabs.value, id?.key)
        launchTabKey.value = sanitized
        persistLaunchTabKey(sanitized)
    }

    fun reset() {
        val def = BottomBarLayoutLogic.default()
        tabs.value = def
        persistTabs(def)
        launchTabKey.value = null
        persistLaunchTabKey(null)
    }

    private fun loadTabs(): List<BottomBarTabConfig> = runCatching {
        val raw = mmkv().decodeString(KEY_TABS)
        if (raw.isNullOrEmpty()) return@runCatching null
        gson.fromJson(raw, Array<BottomBarTabConfig>::class.java)?.filterNotNull()
    }.getOrNull().let { BottomBarLayoutLogic.normalize(it ?: BottomBarLayoutLogic.default()) }

    private fun loadLaunchTabKey(currentTabs: List<BottomBarTabConfig>): String? = runCatching {
        val raw = mmkv().decodeString(KEY_LAUNCH_TAB)
        BottomBarLayoutLogic.sanitizeLaunchTabKey(currentTabs, raw)
    }.getOrNull()

    private fun persistTabs(list: List<BottomBarTabConfig>) {
        runCatching { mmkv().encode(KEY_TABS, gson.toJson(list)) }
            .onFailure { YosDiagnostics.log("BOTTOM_BAR_LAYOUT_PERSIST_FAIL", "err" to it.message) }
    }

    private fun persistLaunchTabKey(key: String?) {
        runCatching { mmkv().encode(KEY_LAUNCH_TAB, key.orEmpty()) }
            .onFailure { YosDiagnostics.log("BOTTOM_BAR_LAUNCH_PERSIST_FAIL", "err" to it.message) }
    }
}
