package com.junco.adapter

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PriceServiceTest {
    @Test
    fun returnsFreshAndCachesQuote() {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/prices") { exchange ->
            hits.incrementAndGet()
            val payload = """{"USD": 45000}"""
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val url = "http://localhost:${server.address.port}/prices"
            val service = PriceService(priceUrl = url, cacheTtlSeconds = 60)
            val first = runBlocking { service.getUsdQuote() }
            val second = runBlocking { service.getUsdQuote() }
            assertEquals(1, hits.get())
            assertEquals(45000.0, first.usd)
            assertEquals(45000.0, second.usd)
            assertNotNull(first.updatedAt)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fallsBackToCachedQuoteOnFailure() {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/prices") { exchange ->
            hits.incrementAndGet()
            val payload = if(hits.get() == 1) """{"USD": 10000}""" else """{"nope": 1}"""
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val url = "http://localhost:${server.address.port}/prices"
            val service = PriceService(priceUrl = url, cacheTtlSeconds = 0)
            val first = runBlocking { service.getUsdQuote() }
            val second = runBlocking { service.getUsdQuote() }
            assertEquals(2, hits.get())
            assertEquals(10000.0, first.usd)
            assertEquals(10000.0, second.usd)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun returnsEmptyWhenNoCacheAndFailure() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/prices") { exchange ->
            val payload = """{"nope": 1}"""
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()

        try {
            val url = "http://localhost:${server.address.port}/prices"
            val service = PriceService(priceUrl = url, cacheTtlSeconds = 0)
            val quote = runBlocking { service.getUsdQuote() }
            assertNull(quote.usd)
        } finally {
            server.stop(0)
        }
    }
}
