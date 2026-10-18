package yos.music.player.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistPageAppearanceTest {
    private fun staticTransition() = PageScrimTransition(
        progress = mutableStateOf(1f),
        transitioning = mutableStateOf(false)
    )

    private fun transitioningRegistration(
        id: String,
        color: Color,
        progress: Float
    ): ScrimRegistration = ArtistPageAppearance.register(
        id,
        mutableStateOf(color),
        PageScrimTransition(
            progress = mutableStateOf(progress),
            transitioning = mutableStateOf(true)
        )
    )

    @Test
    fun retainedPagesKeepIndependentColors() {
        val home = mutableStateOf(Color.Red)
        val library = mutableStateOf(Color.Blue)
        val homeRegistration = ArtistPageAppearance.register("test-home", home, staticTransition())
        val libraryRegistration = ArtistPageAppearance.register("test-library", library, staticTransition())
        try {
            assertEquals(Color.Red, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            home.value = Color.Green
            assertEquals(Color.Green, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            assertNull(ArtistPageAppearance.colorFor("test-unregistered"))
        } finally {
            ArtistPageAppearance.unregister("test-home", homeRegistration)
            ArtistPageAppearance.unregister("test-library", libraryRegistration)
        }
    }

    @Test
    fun departingCompositionCannotRemoveNewRegistration() {
        val old = mutableStateOf(Color.Red)
        val replacement = mutableStateOf(Color.Blue)
        val oldRegistration = ArtistPageAppearance.register("test-replaced", old, staticTransition())
        val replacementRegistration = ArtistPageAppearance.register("test-replaced", replacement, staticTransition())
        try {
            // 离场组合持旧句柄注销，不得移除同 id 的新注册（unregister 靠实例同一性判断）
            ArtistPageAppearance.unregister("test-replaced", oldRegistration)
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-replaced"))
        } finally {
            ArtistPageAppearance.unregister("test-replaced", replacementRegistration)
        }
        assertNull(ArtistPageAppearance.colorFor("test-replaced"))
    }

    @Test
    fun settledPagesContributeNoBackdropColor() {
        // 转场结束/常驻页不参与底衬——静止时底衬被不透明页面遮住，不该被染色
        val settled =
            ArtistPageAppearance.register("test-settled", mutableStateOf(Color.Red), staticTransition())
        try {
            assertNull(ArtistPageAppearance.transitioningBackdropColor("test-settled"))
        } finally {
            ArtistPageAppearance.unregister("test-settled", settled)
        }
    }

    @Test
    fun sameColorPagesYieldExactlyThatColor() {
        // 艺人页 ↔ 子页：两页都是艺人域、底色相同（同一提取色），底衬必须是该色本身。
        // 这正是「进二级页闪主题色」的修复目标：交叉淡化中点透出的底色不再是白/黑。
        val artist = transitioningRegistration("test-artist", Color.Red, 0.5f)
        val child = transitioningRegistration("test-child", Color.Red, 0.5f)
        try {
            assertEquals(Color.Red, ArtistPageAppearance.transitioningBackdropColor("test-child"))
        } finally {
            ArtistPageAppearance.unregister("test-artist", artist)
            ArtistPageAppearance.unregister("test-child", child)
        }
    }

    @Test
    fun differingColorsBlendByProgress() {
        // 不同艺人页互转：按进出场进度加权平均（连续滑移），中点即两色中点；
        // 「取进度最高者」会在进度交叉处跳变，故定案为加权平均。
        val first = transitioningRegistration("test-first", Color.Red, 0.5f)
        val second = transitioningRegistration("test-second", Color.Blue, 0.5f)
        try {
            assertEquals(
                lerp(Color.Red, Color.Blue, 0.5f),
                ArtistPageAppearance.transitioningBackdropColor("test-second")
            )
        } finally {
            ArtistPageAppearance.unregister("test-first", first)
            ArtistPageAppearance.unregister("test-second", second)
        }
    }

    @Test
    fun artistToOrdinaryPageFallsBackToTheme() {
        // 艺人页 pop 回普通页：只有艺人页在注册表里（普通页不注册），dest 无注册对应页
        // → 不得染色，否则普通页整块变成提取色（本轮修复的回归点）。
        val artist = transitioningRegistration("test-artist", Color.Red, 0.4f)
        try {
            assertNull(ArtistPageAppearance.transitioningBackdropColor("test-origin"))
        } finally {
            ArtistPageAppearance.unregister("test-artist", artist)
        }
    }

    @Test
    fun unregisteredActivePageReturnsNull() {
        // 活跃 entry 本身不是艺人域页 → 无论谁在转场，底衬都是主题色
        val artist = transitioningRegistration("test-artist", Color.Red, 0.6f)
        try {
            assertNull(ArtistPageAppearance.transitioningBackdropColor(null))
            assertNull(ArtistPageAppearance.transitioningBackdropColor("test-plain"))
        } finally {
            ArtistPageAppearance.unregister("test-artist", artist)
        }
    }
}
