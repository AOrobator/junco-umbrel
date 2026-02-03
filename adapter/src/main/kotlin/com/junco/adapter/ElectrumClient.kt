package com.junco.adapter

import com.sparrowwallet.sparrow.io.Config
import com.sparrowwallet.sparrow.io.Server
import com.sparrowwallet.sparrow.net.ElectrumServer
import com.sparrowwallet.sparrow.net.ServerException
import com.sparrowwallet.sparrow.net.ServerType
import com.sparrowwallet.drongo.Network
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ElectrumClient {
    private val lock = ReentrantLock()
    private var connected = false
    private var readThread: Thread? = null
    private var tipHeight: Int? = null

    fun configure(server: Server, certificatePath: String?) {
        lock.withLock {
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
                if(ElectrumServer.isConnected()) {
                    ElectrumServer.closeActiveConnection()
                }
            } catch(_: Exception) {
                // ignore
            }
            connected = false
            readThread?.interrupt()
            readThread = null
        }
    }

    fun connectIfNeeded() {
        lock.withLock {
            if(connected && ElectrumServer.isConnected()) return
            val electrumServer = ElectrumServer()
            electrumServer.connect()
            readThread = Thread(ElectrumServer.ReadRunnable(), "electrum-read").apply {
                isDaemon = true
                start()
            }
            connected = true
        }
    }

    fun fetchTipHeight(): Int? {
        connectIfNeeded()
        val electrumServer = ElectrumServer()
        val tip = electrumServer.subscribeBlockHeaders()
        tipHeight = tip.height
        System.setProperty(Network.BLOCK_HEIGHT_PROPERTY, tip.height.toString())
        return tip.height
    }

    fun ping(): List<String> {
        connectIfNeeded()
        val electrumServer = ElectrumServer()
        electrumServer.ping()
        return electrumServer.getServerVersion()
    }

    fun currentTipHeight(): Int? = tipHeight

    fun refreshWallet(wallet: com.sparrowwallet.drongo.wallet.Wallet): Int? {
        connectIfNeeded()
        val electrumServer = ElectrumServer()
        val tip = fetchTipHeight()
        val nodeTxMap = electrumServer.getHistory(wallet)
        electrumServer.getReferencedTransactions(wallet, nodeTxMap)
        electrumServer.calculateNodeHistory(wallet, nodeTxMap)
        wallet.setStoredBlockHeight(tip)
        return tip
    }
}
