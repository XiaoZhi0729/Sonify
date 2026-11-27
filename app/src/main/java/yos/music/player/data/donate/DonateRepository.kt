package yos.music.player.data.donate

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.tencent.mmkv.MMKV
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DonorRecord(
    val name: String,
    val amount: Double,
    val message: String?,
    val date: String?,
    val createdAt: Long
)

data class AggregatedDonor(
    val name: String,
    val totalAmount: Double,
    val count: Int,
    val latestMessage: String?,
    val latestDate: String,
    val latestCreatedAt: Long
)

data class DonateLeaderboard(
    val totalAmount: Double,
    val supporterCount: Int,
    val recordCount: Int,
    val top3: List<AggregatedDonor>,
    val top3RankByName: Map<String, Int>,
    val recent: List<DonorRecord>
)

data class CachedDonors(
    val records: List<DonorRecord>,
    val updatedAtMs: Long
)

object DonateRepository {
    const val DONORS_API_URL = "https://donate.sonify.de5.net/api/donors"
    const val CACHE_TTL_MS = 10 * 60 * 1000L

    private const val MMKV_ID = "sonify_donate_cache"
    private const val KEY_RAW_JSON = "donors_raw_json"
    private const val KEY_UPDATED_AT = "donors_updated_at_ms"
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

    private val cacheMmkv by lazy { MMKV.mmkvWithID(MMKV_ID) }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun isCacheFresh(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return updatedAtMs > 0L && (nowMs - updatedAtMs) in 0L until CACHE_TTL_MS
    }

    fun readCache(): CachedDonors? {
        return runCatching {
            val raw = cacheMmkv.decodeString(KEY_RAW_JSON, null) ?: return null
            val updatedAt = cacheMmkv.decodeLong(KEY_UPDATED_AT, 0L)
            if (updatedAt <= 0L) return null
            val records = parseDonorsJson(raw)
            CachedDonors(records = records, updatedAtMs = updatedAt)
        }.getOrNull()
    }

    suspend fun fetchAndCache(): Result<CachedDonors> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(DONORS_API_URL)
                .get()
                .header("Accept", "application/json")
                .build()
            val bodyString = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val bytes = readBoundedBytes(body.byteStream(), MAX_RESPONSE_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
            val records = parseDonorsJson(bodyString)
            val nowMs = System.currentTimeMillis()
            cacheMmkv.encode(KEY_RAW_JSON, bodyString)
            cacheMmkv.encode(KEY_UPDATED_AT, nowMs)
            Result.success(CachedDonors(records = records, updatedAtMs = nowMs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    internal fun parseDonorsJson(rawJson: String): List<DonorRecord> {
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESPONSE_BYTES) { "Donors payload exceeds size limit" }

        JsonReader(StringReader(rawJson)).use { reader ->
            reader.isLenient = false
            check(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected JSON object root" }
            reader.beginObject()
            var code: Int? = null
            var records: List<DonorRecord>? = null

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "code" -> code = readNullableInt(reader)
                    "data" -> records = readDataArray(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            check(code == 0) { "Unexpected API code: $code" }
            return records ?: throw IllegalStateException("Missing donors data array")
        }
    }

    fun buildLeaderboard(
        records: List<DonorRecord>,
        anonymousName: String = "匿名支持者",
        timeZone: TimeZone = TimeZone.getDefault()
    ): DonateLeaderboard {
        if (records.isEmpty()) {
            return DonateLeaderboard(
                totalAmount = 0.0,
                supporterCount = 0,
                recordCount = 0,
                top3 = emptyList(),
                top3RankByName = emptyMap(),
                recent = emptyList()
            )
        }

        val grouped = LinkedHashMap<String, MutableAccumulator>()
        var totalAmount = 0.0

        for (item in records) {
            val normalizedName = item.name.trim().ifBlank { anonymousName }
            val safeAmount = item.amount.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val cleanMessage = item.message?.trim()?.ifBlank { null }
            val resolvedDate = resolveDate(item.date, item.createdAt, timeZone)
            totalAmount += safeAmount

            val acc = grouped[normalizedName]
            if (acc == null) {
                grouped[normalizedName] = MutableAccumulator(
                    name = normalizedName,
                    totalAmount = safeAmount,
                    count = 1,
                    latestMessage = cleanMessage,
                    latestDate = resolvedDate,
                    latestCreatedAt = item.createdAt
                )
            } else {
                acc.totalAmount += safeAmount
                acc.count += 1
                if (item.createdAt >= acc.latestCreatedAt) {
                    acc.latestCreatedAt = item.createdAt
                    acc.latestDate = resolvedDate
                    if (cleanMessage != null) {
                        acc.latestMessage = cleanMessage
                    }
                } else if (acc.latestMessage == null && cleanMessage != null) {
                    acc.latestMessage = cleanMessage
                }
            }
        }

        val ranked = grouped.values
            .map {
                AggregatedDonor(
                    name = it.name,
                    totalAmount = it.totalAmount,
                    count = it.count,
                    latestMessage = it.latestMessage,
                    latestDate = it.latestDate,
                    latestCreatedAt = it.latestCreatedAt
                )
            }
            .sortedWith(
                compareByDescending<AggregatedDonor> { it.totalAmount }
                    .thenByDescending { it.latestCreatedAt }
            )

        val top3 = ranked.take(3)
        val top3RankByName = top3.mapIndexed { idx, donor -> donor.name to (idx + 1) }.toMap()
        val recent = records.sortedByDescending { it.createdAt }

        return DonateLeaderboard(
            totalAmount = totalAmount,
            supporterCount = grouped.size,
            recordCount = records.size,
            top3 = top3,
            top3RankByName = top3RankByName,
            recent = recent
        )
    }

    fun formatAmount(amount: Double): String {
        if (!amount.isFinite() || amount <= 0.0) return "¥0"
        val cents = (amount * 100.0).roundToLong()
        val whole = cents / 100L
        val rem = (cents % 100L).toInt()
        return when {
            rem == 0 -> "¥$whole"
            rem % 10 == 0 -> "¥$whole.${rem / 10}"
            else -> String.format(Locale.US, "¥%d.%02d", whole, rem)
        }
    }

    fun resolveDate(
        date: String?,
        createdAt: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val trimmed = date?.trim()
        if (!trimmed.isNullOrEmpty()) return trimmed
        if (createdAt > 0L) {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                this.timeZone = timeZone
            }
            return formatter.format(Date(createdAt))
        }
        return "--"
    }

    fun extractInitial(name: String, fallback: String = "友"): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fallback
        val firstCodePoint = trimmed.codePointAt(0)
        val firstChar = String(Character.toChars(firstCodePoint))
        return firstChar.uppercase(Locale.ROOT)
    }

    private fun readDataArray(reader: JsonReader): List<DonorRecord> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return emptyList()
        }
        check(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected data array" }
        val result = ArrayList<DonorRecord>()
        reader.beginArray()
        while (reader.hasNext()) {
            result.add(readDonorObject(reader))
        }
        reader.endArray()
        return result
    }

    private fun readDonorObject(reader: JsonReader): DonorRecord {
        check(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected donor object" }
        reader.beginObject()
        var name = ""
        var amount = 0.0
        var message: String? = null
        var date: String? = null
        var createdAt = 0L

        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = readNullableString(reader).orEmpty().trim()
                "amount" -> amount = readNullableDouble(reader)
                "message" -> message = readNullableString(reader)?.trim()?.ifBlank { null }
                "date" -> date = readNullableString(reader)?.trim()?.ifBlank { null }
                "createdAt" -> createdAt = readNullableLong(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return DonorRecord(
            name = name,
            amount = amount,
            message = message,
            date = date,
            createdAt = createdAt
        )
    }

    private fun readNullableString(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextString()
        }
    }

    private fun readNullableInt(reader: JsonReader): Int? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            reader.nextInt()
        }
    }

    private fun readNullableDouble(reader: JsonReader): Double {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            0.0
        } else {
            reader.nextDouble().takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        }
    }

    private fun readNullableLong(reader: JsonReader): Long {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            0L
        } else {
            reader.nextLong()
        }
    }

    private fun readBoundedBytes(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= maximum) { "Donors response exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class MutableAccumulator(
        val name: String,
        var totalAmount: Double,
        var count: Int,
        var latestMessage: String?,
        var latestDate: String,
        var latestCreatedAt: Long
    )
}
