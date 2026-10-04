package yos.music.player.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UpdateManifestTest {
    private fun manifest() = UpdateManifest(
        1, "com.sonify.music", 2L, "0.2.0", "2026-10-04T12:00:00Z", 23,
        listOf("arm64-v8a", "armeabi-v7a"),
        "https://github.com/XiaoZhi0729/Sonify/releases/download/v0.2.0/Sonify.apk",
        3L, "a".repeat(64), "https://github.com/XiaoZhi0729/Sonify/releases/tag/v0.2.0",
        linkedMapOf("zh-CN" to listOf("Chinese notes"), "en" to listOf("English notes"))
    )

    @Test fun roundTripAndUnknownFields() {
        val original = manifest()
        assertEquals(original, UpdateManifest.parse(original.toJson()))
        assertEquals(original, UpdateManifest.parse(original.toJson().dropLast(1) + ",\"future\":{\"enabled\":true}}"))
    }

    @Test fun localeSelectionUsesExactLanguageThenEnglish() {
        assertEquals(listOf("Chinese notes"), manifest().notesFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals(listOf("Chinese notes"), manifest().notesFor(Locale.TRADITIONAL_CHINESE))
        assertEquals(listOf("English notes"), manifest().notesFor(Locale.GERMAN))
        val chinese = manifest().copy(releaseNotes = mapOf("zh-CN" to listOf("Simplified"), "zh-TW" to listOf("Traditional"), "en" to listOf("English")))
        assertEquals(listOf("Traditional"), chinese.notesFor(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals(listOf("Traditional"), chinese.notesFor(Locale.forLanguageTag("zh-HK")))
        assertEquals(listOf("Simplified"), chinese.notesFor(Locale.forLanguageTag("zh-Hans-SG")))
    }

    @Test fun strictNumbersDuplicatesAndMissingFieldsAreRejected() {
        val json = manifest().toJson()
        rejects { UpdateManifest.parse(json.replace("\"versionCode\":2", "\"versionCode\":\"2\"")) }
        rejects { UpdateManifest.parse(json.replace("\"versionCode\":2", "\"versionCode\":2.0")) }
        rejects { UpdateManifest.parse(json.replace("\"versionCode\":2", "\"versionCode\":2e0")) }
        rejects { UpdateManifest.parse(json.replace("\"versionCode\":2", "\"versionCode\":2,\"versionCode\":3")) }
        rejects { UpdateManifest.parse(json.replace("\"versionCode\":2,", "")) }
        rejects { UpdateManifest.parse("$json {}") }
    }

    @Test fun invalidProtocolFieldsAreRejected() {
        rejects { manifest().copy(schemaVersion = 2).validate() }
        rejects { manifest().copy(applicationId = "other.app").validate() }
        rejects { manifest().copy(versionCode = 0).validate() }
        rejects { manifest().copy(versionCode = -1).validate() }
        rejects { manifest().copy(apkSize = UpdateManifest.MAX_APK_BYTES + 1).validate() }
        rejects { manifest().copy(sha256 = "z".repeat(64)).validate() }
        rejects { manifest().copy(publishedAt = "2026-02-30T12:00:00Z").validate() }
        rejects { manifest().copy(publishedAt = "2026-10-04").validate() }
        rejects { manifest().copy(abis = listOf("mips")).validate() }
        rejects { manifest().copy(abis = emptyList()).validate() }
        rejects { manifest().copy(releaseNotes = mapOf("en" to listOf(""))).validate() }
    }

    @Test fun trustedUrlsCannotBeBypassedByConstructorInjection() {
        UpdateRepository()
        rejects { UpdateRepository(manifestUrl = "http://localhost:8080/latest.json") }
        rejects { UpdateRepository(manifestUrl = "https://raw.githubusercontent.com/Other/App/master/updates/latest.json") }
        rejects { manifest().copy(apkUrl = "https://github.com/Other/App/releases/download/v1/app.apk").validate() }
        rejects { manifest().copy(apkUrl = manifest().apkUrl.replace("https:", "http:")).validate() }
        rejects { manifest().copy(apkUrl = manifest().apkUrl.replace("github.com", "github.com.evil.test")).validate() }
        rejects { manifest().copy(apkUrl = manifest().apkUrl + "?token=secret").validate() }
        rejects { UpdateUrls.redirect("https://evil.test/file.apk".toHttpUrl(), false) }
        rejects { UpdateUrls.redirect("http://release-assets.githubusercontent.com/file.apk".toHttpUrl(), false) }
        rejects { UpdateUrls.redirect("https://release-assets.githubusercontent.com.evil.test/file.apk".toHttpUrl(), false) }
        UpdateUrls.redirect("https://release-assets.githubusercontent.com/file.apk?token=example".toHttpUrl(), false)
    }

    private fun rejects(action: () -> Unit) {
        try {
            action()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
        } catch (_: com.google.gson.stream.MalformedJsonException) {
        }
    }
}
