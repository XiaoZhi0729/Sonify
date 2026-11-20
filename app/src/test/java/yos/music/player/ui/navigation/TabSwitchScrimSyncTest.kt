package yos.music.player.ui.navigation

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSwitchScrimSyncTest {

    private fun houseTransition(
        initialAlpha: Float,
        targetActive: Boolean
    ): Triple<PageScrimTransition, androidx.compose.runtime.MutableFloatState, androidx.compose.runtime.MutableState<Boolean>> {
        val alpha = mutableFloatStateOf(initialAlpha)
        val active = mutableStateOf(targetActive)
        val transitioning = derivedStateOf {
            alpha.floatValue != if (active.value) 1f else 0f
        }
        return Triple(
            PageScrimTransition(progress = alpha, transitioning = transitioning),
            alpha,
            active
        )
    }

    @Test
    fun combineScrimTransitionsReturnsNavTransitionWhenHouseIsNull() {
        val nav = PageScrimTransition(
            progress = mutableFloatStateOf(0.6f),
            transitioning = mutableStateOf(true)
        )
        assertSame(nav, combineScrimTransitions(nav, null))
    }

    @Test
    fun combineScrimTransitionsMultipliesProgressAndTracksFrameZero() {
        val navProgress = mutableFloatStateOf(1f)
        val navTransitioning = mutableStateOf(false)
        val nav = PageScrimTransition(navProgress, navTransitioning)

        val (house, houseAlpha, houseActive) = houseTransition(
            initialAlpha = 0f,
            targetActive = false
        )
        val combined = combineScrimTransitions(nav, house)

        // 稳态隐藏：双方均未过渡，进度为 0
        assertEquals(0f, combined.progress.value, 0f)
        assertFalse(combined.transitioning.value)

        // 第 0 帧切入本 Tab：active 已翻为 true，alpha 动画尚未前进（仍为 0）
        houseActive.value = true
        assertTrue(combined.transitioning.value)
        assertEquals(0f, combined.progress.value, 0f)

        // 动画推进到中段：nav 已落定为 1，合流进度严格等于 houseAlpha
        houseAlpha.floatValue = 0.5f
        assertTrue(combined.transitioning.value)
        assertEquals(0.5f, combined.progress.value, 1e-6f)

        // 页内与 Tab 同时转场时，进度按乘积叠加
        navProgress.floatValue = 0.5f
        navTransitioning.value = true
        assertEquals(0.25f, combined.progress.value, 1e-6f)

        // 双方均落定后 transitioning 回到 false
        navProgress.floatValue = 1f
        navTransitioning.value = false
        houseAlpha.floatValue = 1f
        assertFalse(combined.transitioning.value)
        assertEquals(1f, combined.progress.value, 0f)
    }

    @Test
    fun switchingBetweenUnregisteredAndRegisteredTabSyncsScrimAndTint() {
        val defaultScrim = Color.White
        val defaultTint = Color.Transparent
        val tintOf: (Color) -> Color = { it.copy(alpha = 0.45f) }
        val favColor = Color(0xFFE91E63)

        val (favHouse, favAlpha, favActive) = houseTransition(initialAlpha = 0f, targetActive = false)
        val navSettled = PageScrimTransition(
            progress = mutableFloatStateOf(1f),
            transitioning = mutableStateOf(false)
        )
        val favTransition = combineScrimTransitions(navSettled, favHouse)
        val favReg = ArtistPageAppearance.register("test-fav", mutableStateOf(favColor), favTransition)

        try {
            // 1. 停留在未注册的主页（Home）：底栏保持默认色
            assertEquals(
                defaultScrim,
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home")
            )
            assertEquals(
                defaultTint,
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home", tintOf = tintOf)
            )

            // 2. 第 0 帧从主页切向「喜爱」：activeId 已切到 test-fav，但 favAlpha 仍为 0 → 绝不抢跑跳色
            favActive.value = true
            assertEquals(
                defaultScrim,
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav")
            )
            assertEquals(
                defaultTint,
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-fav", tintOf = tintOf)
            )

            // 3. 淡入中段（0.4）：遮罩与 tint 同比例插值
            favAlpha.floatValue = 0.4f
            assertEquals(
                lerp(defaultScrim, favColor, 0.4f),
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav")
            )
            assertEquals(
                lerp(defaultTint, tintOf(favColor), 0.4f),
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-fav", tintOf = tintOf)
            )

            // 4. 淡入落定（1.0）
            favAlpha.floatValue = 1f
            assertEquals(
                favColor,
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav")
            )
            assertEquals(
                tintOf(favColor),
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-fav", tintOf = tintOf)
            )

            // 5. 第 0 帧从「喜爱」切回主页：favActive=false，favAlpha 仍为 1 → 绝不立即丢色回白
            favActive.value = false
            assertEquals(
                favColor,
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home")
            )
            assertEquals(
                tintOf(favColor),
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home", tintOf = tintOf)
            )

            // 6. 淡出中段（0.35）：随页面透明度一同退回默认色
            favAlpha.floatValue = 0.35f
            assertEquals(
                lerp(defaultScrim, favColor, 0.35f),
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home")
            )
            assertEquals(
                lerp(defaultTint, tintOf(favColor), 0.35f),
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home", tintOf = tintOf)
            )

            // 7. 淡出落定（0.0）
            favAlpha.floatValue = 0f
            assertEquals(
                defaultScrim,
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home")
            )
            assertEquals(
                defaultTint,
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home", tintOf = tintOf)
            )
        } finally {
            ArtistPageAppearance.unregister("test-fav", favReg)
        }
    }

    @Test
    fun switchingBetweenTwoRegisteredTabsRespectsActiveLayerOrderRegardlessOfInsertionOrder() {
        val defaultScrim = Color.Black
        val defaultTint = Color.Transparent
        val tintOf: (Color) -> Color = { it.copy(alpha = 0.40f) }
        val homeColor = Color(0xFF1E88E5)
        val favColor = Color(0xFFD81B60)

        fun assertTabPair(registerFavFirst: Boolean) {
            val navSettled = PageScrimTransition(
                progress = mutableFloatStateOf(1f),
                transitioning = mutableStateOf(false)
            )
            val (homeHouse, homeAlpha, homeActive) = houseTransition(initialAlpha = 1f, targetActive = true)
            val (favHouse, favAlpha, favActive) = houseTransition(initialAlpha = 0f, targetActive = false)
            val homeTransition = combineScrimTransitions(navSettled, homeHouse)
            val favTransition = combineScrimTransitions(navSettled, favHouse)

            val registrations = if (registerFavFirst) {
                listOf(
                    "test-fav-order" to ArtistPageAppearance.register(
                        "test-fav-order",
                        mutableStateOf(favColor),
                        favTransition
                    ),
                    "test-home-order" to ArtistPageAppearance.register(
                        "test-home-order",
                        mutableStateOf(homeColor),
                        homeTransition
                    )
                )
            } else {
                listOf(
                    "test-home-order" to ArtistPageAppearance.register(
                        "test-home-order",
                        mutableStateOf(homeColor),
                        homeTransition
                    ),
                    "test-fav-order" to ArtistPageAppearance.register(
                        "test-fav-order",
                        mutableStateOf(favColor),
                        favTransition
                    )
                )
            }

            try {
                // Home → Favorites 第 0 帧：activeId 已是 Favorites，但 favAlpha=0, homeAlpha=1
                homeActive.value = false
                favActive.value = true
                assertEquals(
                    homeColor,
                    ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav-order")
                )
                assertEquals(
                    tintOf(homeColor),
                    ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-fav-order", tintOf = tintOf)
                )

                // 中段（退出页 0.5 在底 zIndex=0，进入页 0.5 在顶 zIndex=1）
                homeAlpha.floatValue = 0.5f
                favAlpha.floatValue = 0.5f
                assertEquals(
                    lerp(lerp(defaultScrim, homeColor, 0.5f), favColor, 0.5f),
                    ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav-order")
                )
                assertEquals(
                    lerp(lerp(defaultTint, tintOf(homeColor), 0.5f), tintOf(favColor), 0.5f),
                    ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-fav-order", tintOf = tintOf)
                )

                // Favorites 落定
                homeAlpha.floatValue = 0f
                favAlpha.floatValue = 1f
                assertEquals(
                    favColor,
                    ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-fav-order")
                )

                // 反向切回：Favorites → Home 第 0 帧
                favActive.value = false
                homeActive.value = true
                assertEquals(
                    favColor,
                    ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home-order")
                )

                // 反向中段：退出页 Favorites (0.5) 在底，进入页 Home (0.5) 在顶
                favAlpha.floatValue = 0.5f
                homeAlpha.floatValue = 0.5f
                assertEquals(
                    lerp(lerp(defaultScrim, favColor, 0.5f), homeColor, 0.5f),
                    ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home-order")
                )
                assertEquals(
                    lerp(lerp(defaultTint, tintOf(favColor), 0.5f), tintOf(homeColor), 0.5f),
                    ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home-order", tintOf = tintOf)
                )
            } finally {
                for ((id, reg) in registrations) {
                    ArtistPageAppearance.unregister(id, reg)
                }
            }
        }

        assertTabPair(registerFavFirst = false)
        assertTabPair(registerFavFirst = true)
    }

    @Test
    fun gradientRootScrimSyncsWithoutTintContribution() {
        val defaultScrim = Color.White
        val defaultTint = Color.Transparent
        val tintOf: (Color) -> Color = { it.copy(alpha = 0.45f) }
        val homeBottomColor = Color(0xFFECEFF1)

        val navSettled = PageScrimTransition(
            progress = mutableFloatStateOf(1f),
            transitioning = mutableStateOf(false)
        )
        val (homeHouse, homeAlpha, homeActive) = houseTransition(initialAlpha = 0f, targetActive = false)
        val homeReg = ArtistPageAppearance.register(
            id = "test-home-gradient",
            color = mutableStateOf(homeBottomColor),
            transition = combineScrimTransitions(navSettled, homeHouse),
            contributesTint = false
        )

        try {
            homeActive.value = true
            homeAlpha.floatValue = 0.5f
            assertEquals(
                lerp(defaultScrim, homeBottomColor, 0.5f),
                ArtistPageAppearance.blendedScrimColor(defaultScrim, activeId = "test-home-gradient")
            )
            assertEquals(
                defaultTint,
                ArtistPageAppearance.blendedTintColor(defaultTint, activeId = "test-home-gradient", tintOf = tintOf)
            )
        } finally {
            ArtistPageAppearance.unregister("test-home-gradient", homeReg)
        }
    }
}
