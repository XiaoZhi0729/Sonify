package yos.music.player.data.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 风控拦截状态机的纯逻辑：按钮选择、自动弹窗冷却、降级与清理。 */
class PlaybackBlockStateTest {

    @Before
    fun reset() {
        PlaybackBlockState.clear()
    }

    @Test
    fun actionsDependOnWhetherThereIsAUsableChallenge() {
        assertEquals(
            listOf(PlaybackBlockState.Action.Verify, PlaybackBlockState.Action.Retry),
            PlaybackBlockState.actionsOf(PlaybackBlockState.Challenge.EventId)
        )
        assertEquals(
            listOf(PlaybackBlockState.Action.Verify, PlaybackBlockState.Action.Retry),
            PlaybackBlockState.actionsOf(PlaybackBlockState.Challenge.EdtFallback)
        )
        // 没有验证入口时不能给"去验证"——那是一个点了没用的按钮
        assertEquals(
            listOf(PlaybackBlockState.Action.Login, PlaybackBlockState.Action.Retry),
            PlaybackBlockState.actionsOf(PlaybackBlockState.Challenge.None)
        )
    }

    @Test
    fun challengeTokenPrefersEventIdThenEdt() {
        val eventBlock = PlaybackBlockState.Block(
            "m", "t", PlaybackBlockState.Challenge.EventId, "evt", "edt", 0L
        )
        assertEquals("evt", eventBlock.challengeToken)
        val edtBlock = PlaybackBlockState.Block(
            "m", "t", PlaybackBlockState.Challenge.EdtFallback, null, "edt", 0L
        )
        assertEquals("edt", edtBlock.challengeToken)
        val none = PlaybackBlockState.Block("m", "t", PlaybackBlockState.Challenge.None, null, null, 0L)
        assertNull(none.challengeToken)
    }

    @Test
    fun sameBlockPromptsOnceThenRespectsCooldownButKeepsChip() {
        assertTrue(raise("hash", PlaybackBlockState.Challenge.EdtFallback, now = 1_000L))
        assertTrue(PlaybackBlockState.promptRequested)
        PlaybackBlockState.dismissPrompt()
        assertFalse(PlaybackBlockState.promptRequested)

        // 同一首同一类型：冷却内不再自动弹，但胶囊（block）仍在
        assertFalse(raise("hash", PlaybackBlockState.Challenge.EdtFallback, now = 2_000L))
        assertFalse(PlaybackBlockState.promptRequested)
        assertTrue(PlaybackBlockState.block != null)

        // 冷却到期后允许再弹
        val later = 1_000L + PlaybackBlockState.PROMPT_COOLDOWN_MS
        assertTrue(raise("hash", PlaybackBlockState.Challenge.EdtFallback, now = later))
        assertTrue(PlaybackBlockState.promptRequested)
    }

    @Test
    fun differentSongOrDifferentChallengePromptsImmediately() {
        assertTrue(raise("a", PlaybackBlockState.Challenge.None, now = 1_000L))
        PlaybackBlockState.dismissPrompt()
        assertTrue(raise("b", PlaybackBlockState.Challenge.None, now = 1_100L))
        PlaybackBlockState.dismissPrompt()
        assertTrue(raise("b", PlaybackBlockState.Challenge.EdtFallback, now = 1_200L))
    }

    @Test
    fun requestPromptOnlyWorksWhenBlocked() {
        PlaybackBlockState.requestPrompt()
        assertFalse(PlaybackBlockState.promptRequested)
        raise("a", PlaybackBlockState.Challenge.None, now = 1L)
        PlaybackBlockState.dismissPrompt()
        PlaybackBlockState.requestPrompt()
        assertTrue(PlaybackBlockState.promptRequested)
    }

    @Test
    fun downgradeRemovesTheUnusableTokenAndSwitchesButtons() {
        raise("a", PlaybackBlockState.Challenge.EdtFallback, now = 1L)
        PlaybackBlockState.downgradeToNoChallenge()
        val block = PlaybackBlockState.block!!
        assertEquals(PlaybackBlockState.Challenge.None, block.challenge)
        assertNull(block.challengeToken)
        assertEquals(
            listOf(PlaybackBlockState.Action.Login, PlaybackBlockState.Action.Retry),
            PlaybackBlockState.actionsOf(block.challenge)
        )
    }

    @Test
    fun clearResetsBlockPromptAndCooldown() {
        raise("a", PlaybackBlockState.Challenge.None, now = 1L)
        PlaybackBlockState.clear()
        assertNull(PlaybackBlockState.block)
        assertFalse(PlaybackBlockState.promptRequested)
        // 清理后同一首同一类型应立刻可以再弹（新问题，不该被旧冷却压住）
        assertTrue(raise("a", PlaybackBlockState.Challenge.None, now = 2L))
    }

    private fun raise(
        mediaId: String,
        challenge: PlaybackBlockState.Challenge,
        now: Long
    ): Boolean = PlaybackBlockState.raise(
        mediaId = mediaId,
        title = "t",
        challenge = challenge,
        eventId = if (challenge == PlaybackBlockState.Challenge.EventId) "evt" else null,
        edt = if (challenge == PlaybackBlockState.Challenge.EdtFallback) "edt" else null,
        now = now
    )
}
