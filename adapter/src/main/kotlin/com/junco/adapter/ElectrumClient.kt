package com.junco.adapter

import com.sparrowwallet.drongo.KeyPurpose
import com.sparrowwallet.drongo.Network
import com.sparrowwallet.drongo.wallet.BlockTransactionHash
import com.sparrowwallet.drongo.wallet.KeystoreSource
import com.sparrowwallet.drongo.wallet.Wallet
import com.sparrowwallet.drongo.wallet.WalletNode
import com.sparrowwallet.sparrow.io.Config
import com.sparrowwallet.sparrow.io.Server
import com.sparrowwallet.sparrow.net.BlockHeaderTip
import com.sparrowwallet.sparrow.net.ElectrumServer
import com.sparrowwallet.sparrow.net.ServerConfigException
import com.sparrowwallet.sparrow.net.ServerType
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface ElectrumGateway {
    fun configure(server: Server, certificatePath: String?)
    fun ping(): List<String>
    fun currentTipHeight(): Int?
    fun refreshWallet(wallet: Wallet): Int?
}

interface ElectrumRpc {
    fun isConnected(): Boolean
    fun close()
    fun connect()
    fun readRunnable(): Runnable
    fun ping()
    fun getServerVersion(): List<String>
    fun subscribeBlockHeaders(): BlockHeaderTip
    fun getHistory(wallet: Wallet): Map<WalletNode, Set<BlockTransactionHash>>
    fun getReferencedTransactions(wallet: Wallet, nodeTxMap: Map<WalletNode, Set<BlockTransactionHash>>)
    fun calculateNodeHistory(wallet: Wallet, nodeTxMap: Map<WalletNode, Set<BlockTransactionHash>>)
    fun getScriptHash(node: WalletNode): String
}

class SparrowElectrumRpc : ElectrumRpc {
    override fun isConnected(): Boolean = ElectrumServer.isConnected()

    override fun close() {
        ElectrumServer.closeActiveConnection()
    }

    override fun connect() {
        ElectrumServer().connect()
    }

    override fun readRunnable(): Runnable = ElectrumServer.ReadRunnable()

    override fun ping() {
        ElectrumServer().ping()
    }

    override fun getServerVersion(): List<String> = ElectrumServer().getServerVersion()

    override fun subscribeBlockHeaders(): BlockHeaderTip = ElectrumServer().subscribeBlockHeaders()

    override fun getHistory(wallet: Wallet): Map<WalletNode, Set<BlockTransactionHash>> = ElectrumServer().getHistory(wallet)

    override fun getReferencedTransactions(wallet: Wallet, nodeTxMap: Map<WalletNode, Set<BlockTransactionHash>>) {
        ElectrumServer().getReferencedTransactions(wallet, nodeTxMap)
    }

    override fun calculateNodeHistory(wallet: Wallet, nodeTxMap: Map<WalletNode, Set<BlockTransactionHash>>) {
        ElectrumServer().calculateNodeHistory(wallet, nodeTxMap)
    }

    override fun getScriptHash(node: WalletNode): String = ElectrumServer.getScriptHash(node)
}

class ElectrumClient(private val rpc: ElectrumRpc = SparrowElectrumRpc()) : ElectrumGateway {
    private val log = LoggerFactory.getLogger(ElectrumClient::class.java)
    private val lock = ReentrantLock()
    private var connected = false
    private var readThread: Thread? = null
    private var tipHeight: Int? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "electrum-timeout").apply { isDaemon = true }
    }

    override fun configure(server: Server, certificatePath: String?) {
        lock.withLock {
            val hostAndPort = server.hostAndPort
            val port = if(hostAndPort.hasPort()) hostAndPort.port else null
            log.info(
                "Configuring Electrum server url={} host={} port={} ssl={} useProxy={} proxyServer={}",
                server.url,
                server.host,
                port,
                server.protocol == com.sparrowwallet.sparrow.net.Protocol.SSL,
                Config.get().isUseProxy,
                Config.get().proxyServer
            )
            Config.get().setServerType(ServerType.ELECTRUM_SERVER)
            Config.get().setElectrumServer(server)
            if(certificatePath != null && certificatePath.isNotBlank()) {
                Config.get().setElectrumServerCert(File(certificatePath))
            } else {
                Config.get().setElectrumServerCert(null)
            }
            reset()
        }
    }

    fun reset() {
        lock.withLock {
            try {
                if(rpc.isConnected()) {
                    rpc.close()
                }
            } catch(_: Exception) {
                // ignore
            }
            connected = false
            readThread?.interrupt()
            readThread = null
        }
    }

    private fun ensureConfigured() {
        if(Config.get().electrumServer == null) {
            throw ServerConfigException("Electrum server URL not specified")
        }
    }

    private fun <T> withTimeout(action: () -> T): T {
        val future = executor.submit(Callable { action() })
        val timeoutMillis = Timeouts.electrumMs()
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch(e: TimeoutException) {
            future.cancel(true)
            reset()
            throw ServerConfigException("Electrum request timed out", e)
        }
    }

    fun connectIfNeeded() {
        lock.withLock {
            if(connected && rpc.isConnected()) return
            log.info("Opening Electrum connection")
            rpc.connect()
            readThread = Thread(rpc.readRunnable(), "electrum-read").apply {
                isDaemon = true
                start()
            }
            connected = true
        }
    }

    fun fetchTipHeight(): Int? {
        connectIfNeeded()
        log.info("Electrum RPC: blockchain.headers.subscribe")
        val tip = rpc.subscribeBlockHeaders()
        tipHeight = tip.height
        System.setProperty(Network.BLOCK_HEIGHT_PROPERTY, tip.height.toString())
        return tip.height
    }

    override fun ping(): List<String> {
        return withTimeout {
            ensureConfigured()
            connectIfNeeded()
            log.info("Electrum RPC: server.ping")
            rpc.ping()
            log.info("Electrum RPC: server.version")
            val version = rpc.getServerVersion()
            runCatching { fetchTipHeight() }
                .onFailure { log.warn("Electrum tip height fetch failed", it) }
            version
        }
    }

    override fun currentTipHeight(): Int? = tipHeight

    override fun refreshWallet(wallet: Wallet): Int? {
        return withTimeout {
            ensureConfigured()
            connectIfNeeded()
            log.info("Electrum refresh start wallet={} watchOnly={} scriptType={}", wallet.name, wallet.containsSource(KeystoreSource.SW_WATCH), wallet.scriptType)
            val tip = fetchTipHeight()
            log.info("Electrum RPC: blockchain.scripthash.get_history (wallet)")
            val nodeTxMap = rpc.getHistory(wallet)
            traceScriptHashHistory(wallet, nodeTxMap)
            log.info("Electrum RPC: blockchain.transaction.get (referenced)")
            rpc.getReferencedTransactions(wallet, nodeTxMap)
            log.info("Electrum RPC: calculate node history")
            rpc.calculateNodeHistory(wallet, nodeTxMap)
            wallet.setStoredBlockHeight(tip)
            log.info("Electrum refresh complete wallet={} tipHeight={}", wallet.name, tip)
            tip
        }
    }

    private fun traceScriptHashHistory(
        wallet: Wallet,
        nodeTxMap: Map<WalletNode, Set<BlockTransactionHash>>
    ) {
        if(!wallet.containsSource(KeystoreSource.SW_WATCH)) return
        val maxEntries = 8
        for(purpose in listOf(KeyPurpose.RECEIVE, KeyPurpose.CHANGE)) {
            val nodes = wallet.getNode(purpose).children.take(maxEntries)
            if(nodes.isEmpty()) {
                log.info("Scripthash trace wallet={} purpose={} nodes=0", wallet.name, purpose)
                continue
            }
            for(node in nodes) {
                val scriptHash = rpc.getScriptHash(node)
                val history = nodeTxMap[node]
                val txCount = history?.size ?: 0
                val address = runCatching { node.address?.toString() }.getOrNull()
                log.info(
                    "Scripthash trace wallet={} purpose={} path={} address={} scriptHash={} txCount={}",
                    wallet.name,
                    purpose,
                    node.derivationPath,
                    address,
                    scriptHash,
                    txCount
                )
            }
        }
    }
}
