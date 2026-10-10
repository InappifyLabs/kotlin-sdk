package com.inappify.sdk.internal.storage

import com.google.gson.Strictness
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/** Absent means pre-2.1.0; a malformed explicit marker must fail closed. */
internal fun JsonObject.readCacheRestoreBlocked(): Boolean = get("cacheRestoreBlocked")?.let { value ->
    !(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && !value.asBoolean)
} ?: false

/** Offline acceptance is strict without changing the legacy public entitlement helper. */
internal fun isValidCachedExpiration(value: String?): Boolean {
    if (value.isNullOrEmpty()) return true
    val match = CACHED_DATE_TIME_PATTERN.matchEntire(value) ?: return false
    val year = match.groupValues[1].toIntOrNull() ?: return false
    val month = match.groupValues[2].toIntOrNull() ?: return false
    val day = match.groupValues[3].toIntOrNull() ?: return false
    val hour = match.groupValues[4].toIntOrNull() ?: 0
    val minute = match.groupValues[5].toIntOrNull() ?: 0
    val second = match.groupValues[6].toIntOrNull() ?: 0
    val offsetHours = match.groupValues[10].toIntOrNull() ?: 0
    val offsetMinutes = match.groupValues[11].toIntOrNull() ?: 0
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 ||
        second !in 0..59 || offsetHours !in 0..23 || offsetMinutes !in 0..59
    ) return false
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
        isLenient = false
        gregorianChange = Date(Long.MIN_VALUE)
        clear()
        set(Calendar.ERA, if (year <= 0) GregorianCalendar.BC else GregorianCalendar.AD)
        set(Calendar.YEAR, if (year <= 0) 1 - year else year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, match.groupValues[7].take(3).padEnd(3, '0').toInt())
    }
    return try {
        val offset = (offsetHours * 60L + offsetMinutes) * 60_000L *
            if (match.groupValues[9] == "-") -1 else 1
        (calendar.timeInMillis - offset) in -8_640_000_000_000_000L..8_640_000_000_000_000L
    } catch (_: IllegalArgumentException) {
        false
    }
}

private val CACHED_DATE_TIME_PATTERN = Regex(
    "^([+-]?\\d{4,6})-?(\\d{2})-?(\\d{2})" +
        "(?:[ T](\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?" +
        "( ?[zZ]| ?([-+])(\\d{2})(?::?(\\d{2}))?)?)?$",
)

/** The offline path cannot ask the server to repair ambiguous or malformed cached JSON. */
internal fun isValidCachedJson(raw: String): Boolean {
    if (raw.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return false
    return try {
        JsonReader(StringReader(raw)).use { reader ->
            reader.strictness = Strictness.STRICT
            var nodes = 0
            fun readValue(depth: Int) {
                require(depth <= 32 && ++nodes <= 50_000)
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val names = HashSet<String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            require(names.add(reader.nextName()))
                            readValue(depth + 1)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) readValue(depth + 1)
                        reader.endArray()
                    }
                    JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("Invalid cached JSON")
                }
            }
            readValue(0)
            reader.peek() == JsonToken.END_DOCUMENT
        }
    } catch (_: Exception) {
        false
    }
}
