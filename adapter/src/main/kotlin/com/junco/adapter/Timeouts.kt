package com.junco.adapter

import com.sparrowwallet.sparrow.io.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Timeouts {
    private val executor = Executors.newCachedThreadPool(DaemonThreadFactory("junco-timeout"))
    private const val DEFAULT_ELECTRUM_MS = 12_000L
    private const val PROXY_ELECTRUM_MS = 30_000L

    fun <T> run(timeoutMs: Long, block: () -> T): T {
        val future = executor.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch(e: TimeoutException) {
            future.cancel(true)
            throw e
        }
    }

    fun electrumMs(): Long {
        val overrideSeconds = System.getProperty("junco.electrumTimeoutSec")?.toLongOrNull()
        if (overrideSeconds != null && overrideSeconds > 0) {
            return overrideSeconds * 1000
        }
        val config = Config.get()
        val host = config.electrumServer?.host?.lowercase()
        val useProxy = config.isUseProxy
        val isOnion = host?.endsWith(".onion") == true
        return if (useProxy || isOnion) PROXY_ELECTRUM_MS else DEFAULT_ELECTRUM_MS
    }

    fun <T> runElectrum(block: () -> T): T = run(electrumMs(), block)

    private class DaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private var count = 0

        override fun newThread(runnable: Runnable): Thread {
            count += 1
            return Thread(runnable, "$prefix-$count").apply { isDaemon = true }
        }
    }
}
