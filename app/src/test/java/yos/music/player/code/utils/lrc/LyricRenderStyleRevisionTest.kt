package yos.music.player.code.utils.lrc

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricRenderStyleRevisionTest {

    @Test
    fun advanceInvalidatesStyleExactlyOnce() {
        val revision = LyricRenderStyleRevision()

        assertEquals(0, revision.state.value)
        revision.advance()
        assertEquals(1, revision.state.value)
        revision.advance()
        assertEquals(2, revision.state.value)
    }
}
