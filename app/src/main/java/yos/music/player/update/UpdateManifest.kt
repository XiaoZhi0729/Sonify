package yos.music.player.update

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.StringReader
import java.io.StringWriter
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val publishedAt: String,
    val minSdk: Int,
    val abis: List<String>,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val releasePageUrl: String,
    val releaseNotes: Map<String, List<String>>
) {
    fun notesFor(locale: Locale): List<String> {
        val normalized = releaseNotes.mapKeys { it.key.lowercase(Locale.ROOT) }
        val tag = locale.toLanguageTag().lowercase(Locale.ROOT)
        val language = locale.language.lowercase(Locale.ROOT)
        val chinese = if (language == "zh") {
            when {
                locale.script.equals("Hant", true) || locale.country in setOf("TW", "HK", "MO") -> normalized["zh-tw"]
                locale.script.equals("Hans", true) || locale.country in setOf("CN", "SG") -> normalized["zh-cn"]
                else -> null
            }
        } else null
        return normalized[tag] ?: chinese ?: normalized[language]
            ?: normalized.entries.firstOrNull { it.key.startsWith("$language-") }?.value
            ?: normalized["en"] ?: normalized["en-us"]
            ?: normalized.values.firstOrNull() ?: emptyList()
    }

    fun validate(): UpdateManifest {
        require(schemaVersion == 1) { "Unsupported update schema" }
        require(applicationId == APPLICATION_ID) { "Unexpected application ID" }
        require(versionCode > 0) { "Invalid version code" }
        require(versionName.isNotBlank() && versionName.length <= 128) { "Invalid version name" }
        require(validTimestamp(publishedAt)) { "Invalid publication timestamp" }
        require(minSdk in 1..10000) { "Invalid minimum SDK" }
        require(abis.isNotEmpty() && abis.size <= 4 && abis.distinct().size == abis.size &&
            abis.all { it in SUPPORTED_ABIS }) { "Invalid ABI list" }
        require(apkSize in 1..MAX_APK_BYTES) { "Invalid APK size" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
        UpdateUrls.apk(apkUrl.toHttpUrl())
        UpdateUrls.releasePage(releasePageUrl.toHttpUrl())
        require(releaseNotes.isNotEmpty() && releaseNotes.size <= 32) { "Invalid release notes" }
        require(releaseNotes.keys.map { it.lowercase(Locale.ROOT) }.distinct().size == releaseNotes.size)
        releaseNotes.forEach { (locale, notes) ->
            require(locale.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*")))
            require(notes.size <= 100 && notes.all { it.isNotBlank() && it.length <= 4096 })
        }
        return this
    }

    internal fun toJson(): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.beginObject()
            writer.name("schemaVersion").value(schemaVersion)
            writer.name("applicationId").value(applicationId)
            writer.name("versionCode").value(versionCode)
            writer.name("versionName").value(versionName)
            writer.name("publishedAt").value(publishedAt)
            writer.name("minSdk").value(minSdk)
            writer.name("abis").beginArray()
            abis.forEach { writer.value(it) }
            writer.endArray()
            writer.name("apkUrl").value(apkUrl)
            writer.name("apkSize").value(apkSize)
            writer.name("sha256").value(sha256)
            writer.name("releasePageUrl").value(releasePageUrl)
            writer.name("releaseNotes").beginObject()
            releaseNotes.forEach { (locale, notes) ->
                writer.name(locale).beginArray()
                notes.forEach { writer.value(it) }
                writer.endArray()
            }
            writer.endObject().endObject()
        }
        return output.toString()
    }

    companion object {
        const val APPLICATION_ID = "com.sonify.music"
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256L * 1024
        private val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        fun parse(json: String): UpdateManifest {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT)
                reader.beginObject()
                val fields = mutableMapOf<String, Any>()
                val seen = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(seen.add(name)) { "Duplicate update field: $name" }
                    when (name) {
                        "schemaVersion", "minSdk", "versionCode", "apkSize" -> {
                            require(reader.peek() == JsonToken.NUMBER) { "Expected integer: $name" }
                            val number = reader.nextString()
                            require(number.matches(Regex("0|[1-9][0-9]*"))) { "Expected integer: $name" }
                            fields[name] = number.toLong()
                        }
                        "applicationId", "versionName", "publishedAt", "apkUrl", "sha256", "releasePageUrl" ->
                            fields[name] = reader.strictString()
                        "abis" -> fields[name] = reader.stringList(4)
                        "releaseNotes" -> {
                            require(reader.peek() == JsonToken.BEGIN_OBJECT)
                            reader.beginObject()
                            val notes = linkedMapOf<String, List<String>>()
                            while (reader.hasNext()) {
                                require(notes.size < 32)
                                val locale = reader.nextName()
                                require(!notes.containsKey(locale)) { "Duplicate notes locale" }
                                notes[locale] = reader.stringList(100)
                            }
                            reader.endObject()
                            fields[name] = notes
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing update data" }
                fun number(name: String): Long = fields[name] as? Long
                    ?: throw IllegalArgumentException("Missing update field: $name")
                fun int(name: String): Int = number(name).also { require(it <= Int.MAX_VALUE) }.toInt()
                fun string(name: String): String = fields[name] as? String
                    ?: throw IllegalArgumentException("Missing update field: $name")
                return UpdateManifest(
                    int("schemaVersion"), string("applicationId"), number("versionCode"),
                    string("versionName"), string("publishedAt"), int("minSdk"),
                    fields["abis"] as? List<String> ?: throw IllegalArgumentException("Missing ABI list"),
                    string("apkUrl"), number("apkSize"), string("sha256"), string("releasePageUrl"),
                    fields["releaseNotes"] as? Map<String, List<String>>
                        ?: throw IllegalArgumentException("Missing release notes")
                ).validate()
            }
        }

        private fun validTimestamp(value: String): Boolean {
            val match = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})").matchEntire(value)
                ?: return false
            val zone = match.groupValues[7]
            if (zone != "Z" && (zone.substring(1, 3).toInt() > 23 || zone.substring(4).toInt() > 59)) return false
            return runCatching {
                GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                    isLenient = false
                    clear()
                    set(match.groupValues[1].toInt(), match.groupValues[2].toInt() - 1,
                        match.groupValues[3].toInt(), match.groupValues[4].toInt(),
                        match.groupValues[5].toInt(), match.groupValues[6].toInt())
                }.timeInMillis
            }.isSuccess
        }

        private fun JsonReader.strictString(): String {
            require(peek() == JsonToken.STRING) { "Expected update string" }
            return nextString()
        }

        private fun JsonReader.stringList(limit: Int): List<String> {
            require(peek() == JsonToken.BEGIN_ARRAY)
            beginArray()
            val result = mutableListOf<String>()
            while (hasNext()) {
                require(result.size < limit)
                result += strictString()
            }
            endArray()
            return result
        }
    }
}

internal object UpdateUrls {
    private const val REPOSITORY = "/XiaoZhi0729/Sonify"
    private val CDN_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    private fun https(url: HttpUrl) {
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Update URLs must use credential-free HTTPS"
        }
        require(!Regex("(?i)%2f|%5c|%2e").containsMatchIn(url.encodedPath) && !url.encodedPath.contains('\\')) {
            "Encoded update path separators are forbidden"
        }
    }

    fun manifest(url: HttpUrl) {
        https(url)
        require(url.host == "raw.githubusercontent.com" && url.encodedPath.matches(
            Regex("$REPOSITORY/(master|main)/updates/latest\\.json")
        ) && url.query == null) { "Untrusted manifest URL" }
    }

    fun apk(url: HttpUrl) {
        https(url)
        require(url.host == "github.com" && url.encodedPath.matches(
            Regex("$REPOSITORY/releases/download/[^/]+/[^/]+\\.apk")
        ) && url.query == null) { "Untrusted APK URL" }
    }

    fun releasePage(url: HttpUrl) {
        https(url)
        require(url.host == "github.com" && (url.encodedPath == "$REPOSITORY/releases" ||
            url.encodedPath.matches(Regex("$REPOSITORY/releases/tag/[^/]+"))) && url.query == null) {
            "Untrusted release page URL"
        }
    }

    fun redirect(url: HttpUrl, manifest: Boolean) {
        if (manifest) manifest(url)
        else if (url.host == "github.com") apk(url)
        else {
            https(url)
            require(url.host in CDN_HOSTS) { "Untrusted update CDN" }
        }
    }
}
