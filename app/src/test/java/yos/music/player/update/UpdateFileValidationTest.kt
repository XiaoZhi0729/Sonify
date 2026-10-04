package yos.music.player.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

class UpdateFileValidationTest {
    @Test fun boundedReadRejectsBodyWithoutTrustingContentLength() {
        assertArrayEquals(byteArrayOf(1, 2), readBounded(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        try {
            readBounded(ByteArrayInputStream(ByteArray(3)), 2)
            fail("Expected size limit")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun boundedReadPreservesCancellation() {
        try {
            readBounded(ByteArrayInputStream(ByteArray(3)), 3) { throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun hashAndExactSizeAreRequired() {
        val file = File.createTempFile("sonify-update-test", ".apk")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            file.writeBytes(bytes)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val manifest = UpdateManifest(1, "com.sonify.music", 2, "0.2", "2026-10-04T00:00:00Z", 23,
                listOf("arm64-v8a"), "https://github.com/XiaoZhi0729/Sonify/releases/download/v2/app.apk",
                3, hash, "https://github.com/XiaoZhi0729/Sonify/releases/tag/v2", mapOf("en" to listOf("Update")))
            verifyHash(file, manifest)
            rejects { verifyHash(file, manifest.copy(apkSize = 4)) }
            rejects { verifyHash(file, manifest.copy(sha256 = "0".repeat(64))) }
            file.appendBytes(byteArrayOf(4))
            rejects { verifyHash(file, manifest) }
        } finally { file.delete() }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
}
