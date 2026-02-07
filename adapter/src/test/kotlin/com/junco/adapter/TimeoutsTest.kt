package com.junco.adapter

import com.sparrowwallet.sparrow.io.Config
import com.sparrowwallet.sparrow.io.Server
import com.sparrowwallet.sparrow.net.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class TimeoutsTest {
    @Test
    fun runTimesOut() {
        assertThrows(TimeoutException::class.java) {
            Timeouts.run(1) {
                Thread.sleep(20)
                "done"
            }
        }
    }

    @Test
    fun electrumTimeoutOverrideWins() {
        val original = System.getProperty("junco.electrumTimeoutSec")
        System.setProperty("junco.electrumTimeoutSec", "1")
        try {
            assertEquals(1000L, Timeouts.electrumMs())
        } finally {
            if(original == null) {
                System.clearProperty("junco.electrumTimeoutSec")
            } else {
                System.setProperty("junco.electrumTimeoutSec", original)
            }
        }
    }

    @Test
    fun electrumTimeoutUsesProxyOrOnion() {
        val config = Config.get()
        val originalServer = config.electrumServer
        val originalProxy = config.isUseProxy
        try {
            config.setUseProxy(true)
            assertEquals(30_000L, Timeouts.electrumMs())

            config.setUseProxy(false)
            val onion = Server(Protocol.TCP.toUrlString("example.onion", 50001))
            config.setElectrumServer(onion)
            assertEquals(30_000L, Timeouts.electrumMs())

            val clearnet = Server(Protocol.TCP.toUrlString("example.com", 50001))
            config.setElectrumServer(clearnet)
            assertEquals(12_000L, Timeouts.electrumMs())
        } finally {
            config.setUseProxy(originalProxy)
            config.setElectrumServer(originalServer)
        }
    }
}
