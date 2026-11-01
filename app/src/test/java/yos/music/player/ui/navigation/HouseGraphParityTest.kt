package yos.music.player.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 导航图一致性回归守卫。
 *
 * 背景：四个 house（Home / Library / Search / Favorites）各自持有一张独立的 NavHost 图，
 * 而播放器 overlay / AppNavigator 会把目标路由 push 进「用户当前所在 house」。
 * 历史上 Favorites 图漏注册 ArtistDetail，导致「喜爱」Tab 下点播放页歌手名时抛
 * `IllegalArgumentException: Navigation destination ... cannot be found ... route=Favorites`。
 *
 * 本测试断言每个 house 的 NavHost 都接入了共享的 [artistGraph] 与 settingsGraph，
 * 因此今后新增 house 或漏接共享图时会直接失败，而不是等到真机点一下就崩。
 */
class HouseGraphParityTest {

    /** house → 各自 NavHost 源文件名。新增 house 时这里也要补。 */
    private val houseNavHosts = listOf(
        "HomeNavHost.kt",
        "LibraryNavHost.kt",
        "SearchNavHost.kt",
        "FavoritesNavHost.kt"
    )

    /** 每个 house 都必须接入的共享图（overlay/导航器可能把目标 push 进任意 house）。 */
    private val requiredGraphs = listOf("artistGraph(", "settingsGraph(")

    /**
     * 播放页可直接 push 的单页目的地。本地专辑页属于这一类：播放页「更多 → 前往该歌曲专辑」
     * 推送本地歌时目标是它，而 overlay 只会往 activeHouseController（当前 Tab）push，
     * 四张图缺任何一张都会当场崩溃。每新增一个能被播放页触达的单页路由都要加进来。
     */
    private val requiredComposables = listOf("composable(UI.AlbumInfo)")

    private val navSourceDir: File = sequenceOf(
        File("src/main/java/yos/music/player/ui/navigation"),
        File("app/src/main/java/yos/music/player/ui/navigation")
    ).firstOrNull { it.isDirectory }
        ?: error("找不到 navigation 源码目录，当前工作目录=${File(".").absolutePath}")

    @Test
    fun everyHouseNavHostRegistersSharedGraphs() {
        for (house in houseNavHosts) {
            val file = File(navSourceDir, house)
            assertTrue("$house 不存在：${file.absolutePath}", file.exists())
            val source = file.readText()
            for (graph in requiredGraphs) {
                assertTrue(
                    "$house 未接入 $graph —— 该 house 的图会缺少对应路由，" +
                        "届时在当前 Tab 内 push 该路由即崩（destination cannot be found）",
                    source.contains(graph)
                )
            }
            for (composable in requiredComposables) {
                assertTrue(
                    "$house 未注册 $composable —— 播放页往当前 Tab 的图里 push 该路由时会崩" +
                        "（Navigation destination ... cannot be found）",
                    source.contains(composable)
                )
            }
        }
    }
}
