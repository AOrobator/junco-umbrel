package com.junco.adapter

import com.sparrowwallet.drongo.KeyPurpose
import com.sparrowwallet.drongo.Network
import com.sparrowwallet.drongo.SecureString
import com.sparrowwallet.drongo.crypto.ECKey
import com.sparrowwallet.drongo.crypto.EncryptionType
import com.sparrowwallet.drongo.crypto.Key
import com.sparrowwallet.drongo.policy.Policy
import com.sparrowwallet.drongo.policy.PolicyType
import com.sparrowwallet.drongo.protocol.ScriptType
import com.sparrowwallet.drongo.address.Address
import com.sparrowwallet.drongo.address.InvalidAddressException
import com.sparrowwallet.drongo.wallet.*
import com.sparrowwallet.sparrow.io.Storage
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class WalletService(private val electrum: ElectrumClient) {
    data class WalletHandle(val wallet: Wallet, val storage: Storage, var lastUpdated: Long? = null)

    private val wallets = ConcurrentHashMap<String, WalletHandle>()

    fun listWallets(): List<String> {
        val dir = Storage.getWalletsDir()
        if(!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { Storage.isWalletFile(it) }
            ?.map { Storage(it).getWalletName(null) }
            ?.sorted()
            ?: emptyList()
    }

    fun openWallet(name: String, password: SecureString): WalletHandle {
        val existing = wallets[name]
        if(existing != null) return existing

        val walletFile = Storage.getExistingWallet(Storage.getWalletsDir(), name)
            ?: throw ApiException.notFound("Wallet not found")

        val storage = Storage(walletFile)
        val wallet = if(storage.isEncrypted()) {
            storage.loadEncryptedWallet(password).wallet
        } else {
            storage.loadUnencryptedWallet().wallet
        }

        val handle = WalletHandle(wallet, storage)
        wallets[name] = handle
        refresh(handle)
        return handle
    }

    fun createWallet(req: CreateWalletRequest, password: SecureString): CreateWalletResponse {
        val name = req.name.trim()
        if(name.isBlank()) {
            throw ApiException.badRequest("Wallet name is required")
        }
        if(Storage.RESERVED_WALLET_NAMES.contains(name)) {
            throw ApiException.badRequest("Wallet name is reserved")
        }
        if(Storage.walletExists(name)) {
            throw ApiException.badRequest("Wallet already exists")
        }

        val policyType = runCatching { PolicyType.valueOf(req.policyType.uppercase()) }
            .getOrElse { throw ApiException.badRequest("Unsupported policy type") }
        val scriptType = runCatching { ScriptType.valueOf(req.scriptType.uppercase()) }
            .getOrElse { throw ApiException.badRequest("Unsupported script type") }

        val seed = try {
            if(req.generate || req.mnemonic.isNullOrBlank()) {
                val bits = req.entropyBits
                DeterministicSeed(SecureRandom(), bits, req.passphrase ?: "")
            } else {
                DeterministicSeed(req.mnemonic.trim(), req.passphrase ?: "", Instant.now().epochSecond, DeterministicSeed.Type.BIP39)
            }
        } catch(e: Exception) {
            throw ApiException.badRequest("Invalid mnemonic or entropy settings")
        }

        val wallet = Wallet(name, policyType, scriptType)
        wallet.setNetwork(Network.get())
        val keystore = Keystore.fromSeed(seed, scriptType.defaultDerivation)
        wallet.keystores.clear()
        wallet.keystores.add(keystore)
        wallet.defaultPolicy = Policy.getPolicy(policyType, scriptType, wallet.keystores, null)

        val storage = Storage(Storage.getWalletFile(name))
        if(password.length == 0) {
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY)
            storage.saveWallet(wallet)
            storage.restorePublicKeysFromSeed(wallet, null)
        } else {
            val encryptionKey = storage.getEncryptionKey(password)
                ?: throw ApiException.badRequest("Password required to encrypt wallet")
            val key = Key(encryptionKey.privKeyBytes, storage.keyDeriver.salt, EncryptionType.Deriver.ARGON2)
            wallet.encrypt(key)
            storage.setEncryptionPubKey(ECKey.fromPublicOnly(encryptionKey))
            storage.saveWallet(wallet)
            storage.restorePublicKeysFromSeed(wallet, key)
            key.clear()
            encryptionKey.clear()
        }

        wallet.clearPrivate()
        val handle = WalletHandle(wallet, storage)
        wallets[name] = handle
        refresh(handle)

        val summary = toSummary(handle)
        val mnemonic = if(req.generate || req.mnemonic.isNullOrBlank()) seed.mnemonicString.asString() else null
        seed.clear()
        return CreateWalletResponse(summary, mnemonic)
    }

    fun getSummary(name: String, password: SecureString): WalletSummary {
        val handle = openWallet(name, password)
        return toSummary(handle)
    }

    fun receiveAddress(name: String, password: SecureString, label: String?): ReceiveResponse {
        val handle = openWallet(name, password)
        val node = handle.wallet.getFreshNode(KeyPurpose.RECEIVE)
        if(!label.isNullOrBlank()) {
            node.label = label
        }
        handle.storage.updateWallet(handle.wallet)
        return ReceiveResponse(node.address.toString(), node.derivationPath)
    }

    fun sendPayment(name: String, password: SecureString, request: SendRequest): SendResponse {
        val handle = openWallet(name, password)
        val wallet = handle.wallet

        if(request.outputs.isEmpty()) {
            throw ApiException.badRequest("At least one output is required")
        }
        if(request.feeRate <= 0.0) {
            throw ApiException.badRequest("Fee rate must be greater than zero")
        }

        val payments = request.outputs.map { output ->
            val address = try {
                Address.fromString(output.address)
            } catch(e: InvalidAddressException) {
                throw ApiException.badRequest("Invalid address: ${output.address}")
            }
            if(output.amountSats <= 0L) {
                throw ApiException.badRequest("Amount must be greater than zero")
            }
            Payment(address, output.label, output.amountSats, false)
        }

        val params = TransactionParameters(
            emptyList(),
            listOf(SpentTxoFilter(), FrozenTxoFilter(), CoinbaseTxoFilter(wallet)),
            payments,
            emptyList(),
            emptySet(),
            request.feeRate,
            request.feeRate,
            com.sparrowwallet.drongo.protocol.Transaction.DEFAULT_MIN_RELAY_FEE,
            null,
            electrum.currentTipHeight(),
            true,
            true,
            request.allowRbf
        )

        val walletTx = try {
            wallet.createWalletTransaction(params)
        } catch(e: Exception) {
            throw ApiException.badRequest(e.message ?: "Unable to create transaction")
        }
        val psbt = walletTx.createPSBT()
        val signingWallet = wallet.copy()
        signingWallet.decrypt(password)
        try {
            signingWallet.sign(psbt)
            signingWallet.finalise(psbt)
        } finally {
            signingWallet.clearPrivate()
        }

        val tx = psbt.extractTransaction()
        val electrumServer = com.sparrowwallet.sparrow.net.ElectrumServer()
        val txid = electrumServer.broadcastTransaction(tx, psbt.fee)
        refresh(handle)
        return SendResponse(txid.toString(), psbt.fee, walletTx.total)
    }

    fun getTransactions(name: String, password: SecureString): TransactionsResponse {
        val handle = openWallet(name, password)
        refresh(handle)
        val tipHeight = electrum.currentTipHeight() ?: handle.wallet.storedBlockHeight
        val transactions = buildTransactions(handle.wallet, tipHeight)
        return TransactionsResponse(currentBalance(handle.wallet), transactions)
    }

    fun getBalanceHistory(name: String, password: SecureString): BalanceHistoryResponse {
        val handle = openWallet(name, password)
        refresh(handle)
        val tipHeight = electrum.currentTipHeight() ?: handle.wallet.storedBlockHeight
        val transactions = buildTransactions(handle.wallet, tipHeight)
        val history = buildBalanceHistory(transactions)
        return BalanceHistoryResponse(currentBalance(handle.wallet), history)
    }

    private fun refresh(handle: WalletHandle) {
        try {
            val tip = electrum.refreshWallet(handle.wallet)
            handle.lastUpdated = tip?.toLong()
            handle.storage.updateWallet(handle.wallet)
        } catch(e: Exception) {
            throw ApiException.badRequest(e.message ?: "Failed to sync with Electrum server")
        }
    }

    private fun toSummary(handle: WalletHandle): WalletSummary {
        return WalletSummary(
            id = handle.wallet.name,
            name = handle.wallet.fullDisplayName,
            network = handle.wallet.network.name,
            policyType = handle.wallet.policyType.name,
            scriptType = handle.wallet.scriptType.name,
            balanceSats = currentBalance(handle.wallet),
            lastUpdated = handle.lastUpdated
        )
    }

    private fun currentBalance(wallet: Wallet): Long {
        return wallet.walletUtxos.keys.sumOf { it.value }
    }

    private fun buildTransactions(wallet: Wallet, tipHeight: Int?): List<TransactionSummary> {
        val walletTxos = wallet.walletTxos
        val transactions = wallet.walletTransactions.values
        val summaries = transactions.map { tx ->
            val incomingValue = walletTxos.keys.filter { it.hash == tx.hash }.sumOf { it.value }
            val outgoingValue = walletTxos.keys.filter { it.spentBy != null && it.spentBy.hash == tx.hash }.sumOf { it.value }
            val netValue = incomingValue - outgoingValue
            val confirmations = if(tx.height > 0 && tipHeight != null) (tipHeight - tx.height + 1) else 0
            TransactionSummary(
                txid = tx.hash.toString(),
                valueSats = netValue,
                feeSats = computeFee(wallet, tx),
                confirmations = confirmations,
                height = tx.height,
                timestamp = tx.date?.time?.div(1000),
                label = tx.label
            )
        }

        return summaries.sortedWith(compareByDescending<TransactionSummary> { it.height <= 0 }
            .thenByDescending { it.height }
            .thenByDescending { it.timestamp ?: 0L })
    }

    private fun computeFee(wallet: Wallet, tx: com.sparrowwallet.drongo.wallet.BlockTransaction): Long? {
        var fee = 0L
        for(input in tx.transaction.inputs) {
            if(input.isCoinBase) return 0L
            val prev = wallet.getWalletTransaction(input.outpoint.hash) ?: return null
            val outputIndex = input.outpoint.index.toInt()
            if(prev.transaction.outputs.size <= outputIndex) return null
            fee += prev.transaction.outputs[outputIndex].value
        }
        for(output in tx.transaction.outputs) {
            fee -= output.value
        }
        return fee
    }

    private fun buildBalanceHistory(transactions: List<TransactionSummary>): List<BalancePoint> {
        val confirmed = transactions
            .filter { it.height > 0 && it.timestamp != null }
            .sortedBy { it.height }

        var running = 0L
        val points = mutableListOf<BalancePoint>()
        for(tx in confirmed) {
            running += tx.valueSats
            points.add(BalancePoint(tx.timestamp!!, running))
        }

        return points
    }
}
