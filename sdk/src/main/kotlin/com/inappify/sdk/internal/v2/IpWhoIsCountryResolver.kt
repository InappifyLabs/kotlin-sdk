package com.inappify.sdk.internal.v2

import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Optional targeting hint. Failure must never prevent Configure. */
internal class IpWhoIsCountryResolver(
    private val url: HttpUrl = "https://ipwho.is/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    suspend fun resolve(): String? = try {
        runInterruptible(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body?.source() ?: return@use null
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) return@use null
                val json = JsonParser.parseString(source.buffer.readUtf8()).asJsonObject
                if (json.get("success")?.asBoolean != true) return@use null
                json.get("country_code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.takeIf(::validCountry)?.let(::normalizeCountry)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16L * 1024L
    }
}
