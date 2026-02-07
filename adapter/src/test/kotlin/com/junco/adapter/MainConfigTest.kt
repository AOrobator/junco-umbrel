package com.junco.adapter

import com.sparrowwallet.drongo.Network
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class MainConfigTest {
    @Test
    fun loadPortUsesSystemProperty() {
        val original = System.getProperty("junco.port")
        System.setProperty("junco.port", "9099")
        try {
            assertEquals(9099, loadPort())
        } finally {
            if(original == null) {
                System.clearProperty("junco.port")
            } else {
                System.setProperty("junco.port", original)
            }
        }
    }

    @Test
    fun loadConfigReadsNetwork() {
        val originalNet = System.getProperty("junco.network")
        val originalData = System.getProperty("junco.data")
        val dataDir = Files.createTempDirectory("junco-config")
        System.setProperty("junco.network", "testnet")
        System.setProperty("junco.data", dataDir.toString())
        try {
            val config = loadConfig()
            assertEquals(Network.TESTNET, config.network)
            assertEquals(dataDir, config.dataDir)
        } finally {
            if(originalNet == null) System.clearProperty("junco.network") else System.setProperty("junco.network", originalNet)
            if(originalData == null) System.clearProperty("junco.data") else System.setProperty("junco.data", originalData)
        }
    }

    @Test
    fun loadConfigThrowsOnUnknownNetwork() {
        val originalNet = System.getProperty("junco.network")
        System.setProperty("junco.network", "unknown")
        try {
            assertThrows(IllegalArgumentException::class.java) { loadConfig() }
        } finally {
            if(originalNet == null) System.clearProperty("junco.network") else System.setProperty("junco.network", originalNet)
        }
    }

    @Test
    fun configureEnvironmentSetsProperties() {
        val dataDir = Files.createTempDirectory("junco-env")
        val config = AppConfig(dataDir, Network.SIGNET)
        configureEnvironment(config)
        assertEquals(dataDir.resolve("sparrow").toString(), System.getProperty("sparrow.home"))
        assertEquals("true", System.getProperty("java.awt.headless"))
        assertEquals(Network.SIGNET, Network.get())
    }
}
