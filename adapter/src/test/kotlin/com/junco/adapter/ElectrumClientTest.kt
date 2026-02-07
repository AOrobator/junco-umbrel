package com.junco.adapter

import com.sparrowwallet.drongo.ExtendedKey
import com.sparrowwallet.drongo.KeyDerivation
import com.sparrowwallet.drongo.Network
import com.sparrowwallet.drongo.policy.Policy
import com.sparrowwallet.drongo.policy.PolicyType
import com.sparrowwallet.drongo.protocol.ScriptType
import com.sparrowwallet.drongo.wallet.BlockTransactionHash
import com.sparrowwallet.drongo.wallet.Keystore
import com.sparrowwallet.drongo.wallet.KeystoreSource
import com.sparrowwallet.drongo.wallet.Wallet
import com.sparrowwallet.drongo.wallet.WalletModel
import com.sparrowwallet.sparrow.io.Server
import com.sparrowwallet.sparrow.net.BlockHeaderTip
import com.sparrowwallet.sparrow.net.Protocol
import com.sparrowwallet.sparrow.net.ServerConfigException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class ElectrumClientTest {
    private class FakeRpc : ElectrumRpc {
        var connected = false
        var pinged = false
        var referenced = false
        var calculated = false
        var historyRequested = false
        var connectCalls = 0
        var closeCalls = 0
        var slowHistoryMs = 0L
        val tracedScriptHashes = mutableListOf<String>()
        private val tip = BlockHeaderTip().apply { height = 100 }
        val historyMap = ConcurrentHashMap<com.sparrowwallet.drongo.wallet.WalletNode, Set<BlockTransactionHash>>()

        override fun isConnected(): Boolean = connected
        override fun close() {
            closeCalls += 1
            connected = false
        }

        override fun connect() {
            connectCalls += 1
            connected = true
        }

        override fun readRunnable(): Runnable = Runnable { }

        override fun ping() {
            pinged = true
        }

        override fun getServerVersion(): List<String> = listOf("fake", "1.0")

        override fun subscribeBlockHeaders(): BlockHeaderTip = tip

        override fun getHistory(wallet: Wallet): Map<com.sparrowwallet.drongo.wallet.WalletNode, Set<BlockTransactionHash>> {
            if(slowHistoryMs > 0) {
                Thread.sleep(slowHistoryMs)
            }
            historyRequested = true
            return historyMap
        }

        override fun getReferencedTransactions(
            wallet: Wallet,
            nodeTxMap: Map<com.sparrowwallet.drongo.wallet.WalletNode, Set<BlockTransactionHash>>
        ) {
            referenced = true
        }

        override fun calculateNodeHistory(
            wallet: Wallet,
            nodeTxMap: Map<com.sparrowwallet.drongo.wallet.WalletNode, Set<BlockTransactionHash>>
        ) {
            calculated = true
        }

        override fun getScriptHash(node: com.sparrowwallet.drongo.wallet.WalletNode): String {
            return "hash-${node.derivationPath}".also { tracedScriptHashes.add(it) }
        }
    }

    private fun createWatchWallet(): Wallet {
        val tpub =
            "tpubD9429UXFGCTKJ9NdiNK4rC5ygqSUkginycYHccqSg5gkmyQ7PZRHNjk99M6a6Y3NY8ctEUUJvCu6iCCui8Ju3xrHRu3Ez1CKB4ZFoRZDdP9"
        val extendedKey = ExtendedKey.fromDescriptor(tpub)
        val wallet = Wallet("TestWallet")
        wallet.setPolicyType(PolicyType.SINGLE)
        wallet.setScriptType(ScriptType.P2WPKH)
        wallet.setNetwork(Network.TESTNET)

        val keystore = Keystore("Watch Only")
        keystore.setSource(KeystoreSource.SW_WATCH)
        keystore.setWalletModel(WalletModel.SPARROW)
        keystore.setKeyDerivation(KeyDerivation(KeyDerivation.DEFAULT_WATCH_ONLY_FINGERPRINT, "m/84'/1'/0'"))
        keystore.setExtendedPublicKey(extendedKey)

        wallet.keystores.clear()
        wallet.keystores.add(keystore)
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.keystores, null))
        wallet.getFreshNode(com.sparrowwallet.drongo.KeyPurpose.RECEIVE)
        wallet.getFreshNode(com.sparrowwallet.drongo.KeyPurpose.CHANGE)
        return wallet
    }

    @Test
    fun throwsWhenNotConfigured() {
        val client = ElectrumClient(FakeRpc())
        assertThrows(ServerConfigException::class.java) { client.ping() }
    }

    @Test
    fun refreshesWalletAndUpdatesTip() {
        val rpc = FakeRpc()
        val client = ElectrumClient(rpc)
        val server = Server(Protocol.TCP.toUrlString("example.com", 50001))
        client.configure(server, null)

        val wallet = createWatchWallet()
        val tip = client.refreshWallet(wallet)

        assertEquals(100, tip)
        assertEquals(100, client.currentTipHeight())
        assertEquals(true, rpc.historyRequested)
        assertEquals(true, rpc.referenced)
        assertEquals(true, rpc.calculated)
    }

    @Test
    fun pingSetsTipHeight() {
        val rpc = FakeRpc()
        val client = ElectrumClient(rpc)
        val server = Server(Protocol.TCP.toUrlString("example.com", 50001))
        client.configure(server, null)

        val version = client.ping()
        assertNotNull(version)
        assertEquals(true, rpc.pinged)
        assertEquals(100, client.currentTipHeight())
    }

    @Test
    fun watchOnlyRefreshTracesScriptHashes() {
        val rpc = FakeRpc()
        val client = ElectrumClient(rpc)
        val server = Server(Protocol.TCP.toUrlString("example.com", 50001))
        client.configure(server, null)

        val wallet = createWatchWallet()
        client.refreshWallet(wallet)

        assertEquals(true, rpc.tracedScriptHashes.isNotEmpty())
    }

    @Test
    fun timeoutDuringRefreshResetsConnection() {
        val original = System.getProperty("junco.electrumTimeoutSec")
        System.setProperty("junco.electrumTimeoutSec", "1")
        try {
            val rpc = FakeRpc().apply {
                slowHistoryMs = 1_500
            }
            val client = ElectrumClient(rpc)
            val server = Server(Protocol.TCP.toUrlString("example.com", 50001))
            client.configure(server, null)

            val wallet = createWatchWallet()
            val error = assertThrows(ServerConfigException::class.java) { client.refreshWallet(wallet) }

            assertEquals("Electrum request timed out", error.message)
            assertEquals(1, rpc.connectCalls)
            assertEquals(1, rpc.closeCalls)
            assertEquals(false, rpc.connected)
        } finally {
            if(original == null) {
                System.clearProperty("junco.electrumTimeoutSec")
            } else {
                System.setProperty("junco.electrumTimeoutSec", original)
            }
        }
    }
}
