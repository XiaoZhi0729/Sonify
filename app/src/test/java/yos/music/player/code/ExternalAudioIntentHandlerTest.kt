package yos.music.player.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioIntentHandlerTest {

    @Test
    fun `isSupportedAudioScheme accepts content and file only`() {
        assertTrue(ExternalAudioIntentHandler.isSupportedAudioScheme("content"))
        assertTrue(ExternalAudioIntentHandler.isSupportedAudioScheme("CONTENT"))
        assertTrue(ExternalAudioIntentHandler.isSupportedAudioScheme("file"))
        assertTrue(ExternalAudioIntentHandler.isSupportedAudioScheme("FILE"))

        assertFalse(ExternalAudioIntentHandler.isSupportedAudioScheme("http"))
        assertFalse(ExternalAudioIntentHandler.isSupportedAudioScheme("https"))
        assertFalse(ExternalAudioIntentHandler.isSupportedAudioScheme(""))
        assertFalse(ExternalAudioIntentHandler.isSupportedAudioScheme(null))
    }

    @Test
    fun `stripAudioExtension removes trailing extension and handles edge cases`() {
        assertEquals("夜曲", ExternalAudioIntentHandler.stripAudioExtension("夜曲.flac"))
        assertEquals("song.v2", ExternalAudioIntentHandler.stripAudioExtension("song.v2.mp3"))
        assertEquals("no_extension", ExternalAudioIntentHandler.stripAudioExtension("no_extension"))
        assertEquals(".hidden", ExternalAudioIntentHandler.stripAudioExtension(".hidden"))
        assertNull(ExternalAudioIntentHandler.stripAudioExtension(""))
        assertNull(ExternalAudioIntentHandler.stripAudioExtension("   "))
        assertNull(ExternalAudioIntentHandler.stripAudioExtension(null))
    }

    @Test
    fun `extractAudioViewRequest returns null for null intent`() {
        assertNull(ExternalAudioIntentHandler.extractAudioViewRequest(null))
    }
}
