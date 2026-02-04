package com.junco.adapter

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class PriceService(
    private val priceUrl: String = System.getenv("JUNCO_PRICE_URL")
        ?: "https://mempool.space/api/v1/prices",
    private val cacheTtlSeconds: Long = System.getenv("JUNCO_PRICE_TTL")?.toLongOrNull() ?: 60
) {
    private data class PriceCache(val usd: Double, val updatedAt: Long, val source: String)

    private val cache = AtomicReference<PriceCache?>(null)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()
    private val gson = Gson()

    suspend fun getUsdQuote(): PriceQuoteResponse {
        val now = Instant.now().epochSecond
        val cached = cache.get()
        if(cached != null && now - cached.updatedAt < cacheTtlSeconds) {
            return PriceQuoteResponse(cached.usd, cached.updatedAt, cached.source)
        }

        val fresh = fetchUsd()
        if(fresh != null) {
            cache.set(fresh)
            return PriceQuoteResponse(fresh.usd, fresh.updatedAt, fresh.source)
        }

        return if(cached != null) {
            PriceQuoteResponse(cached.usd, cached.updatedAt, cached.source)
        } else {
            PriceQuoteResponse()
        }
    }

    private suspend fun fetchUsd(): PriceCache? = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(priceUrl))
                .timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if(response.statusCode() !in 200..299) return@withContext null

            val json = gson.fromJson(response.body(), JsonObject::class.java) ?: return@withContext null
            val usdElement = when {
                json.has("USD") -> json.get("USD")
                json.has("usd") -> json.get("usd")
                else -> null
            }
            val usd = usdElement?.asDouble ?: return@withContext null
            val source = runCatching { URI.create(priceUrl).host }.getOrNull() ?: "price"
            PriceCache(usd, Instant.now().epochSecond, source)
        } catch(_: Exception) {
            null
        }
    }
}
