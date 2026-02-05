package com.junco.adapter

import com.sparrowwallet.drongo.ExtendedKey
import com.sparrowwallet.drongo.KeyDerivation
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
import com.sparrowwallet.drongo.crypto.InvalidPasswordException
import com.sparrowwallet.drongo.wallet.*
import com.sparrowwallet.sparrow.io.Storage
import com.sparrowwallet.sparrow.io.PersistenceType
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

class WalletService(private val electrum: ElectrumGateway) {
    data class WalletHandle(val wallet: Wallet, val storage: Storage, var lastUpdated: Long? = null)

    private val log = LoggerFactory.getLogger(WalletService::class.java)
    private val wallets = ConcurrentHashMap<String, WalletHandle>()

    fun listWallets(): List<String> {
        val dir = Storage.getWalletsDir()
        if(!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { Storage.isWalletFile(it) }
            ?.mapNotNull { file -> runCatching { Storage(file).getWalletName(null) }.getOrNull() }
            ?.sorted()
            ?: emptyList()
    }

    fun openWallet(name: String, password: SecureString): WalletHandle {
        val existing = wallets[name]
        if(existing != null) return existing

        val walletFile = Storage.getExistingWallet(Storage.getWalletsDir(), name)
            ?: throw ApiException.notFound("Wallet not found")

        val storage = Storage(walletFile)
        val wallet = try {
            if(storage.isEncrypted()) {
                storage.loadEncryptedWallet(password).wallet
            } else {
                storage.loadUnencryptedWallet().wallet
            }
        } catch(e: Exception) {
            if(e is InvalidPasswordException || e.cause is InvalidPasswordException) {
                throw ApiException.badRequest("Incorrect wallet password")
            }
            throw ApiException.badRequest("Unable to open wallet. Check password or wallet file integrity.")
        }

        val handle = WalletHandle(wallet, storage)
        wallets[name] = handle
        refresh(handle, allowFailure = true)
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
        if(walletExists(name)) {
            throw ApiException.badRequest("Wallet already exists")
        }

        val policyType = runCatching { PolicyType.valueOf(req.policyType.uppercase()) }
            .getOrElse { throw ApiException.badRequest("Unsupported policy type") }

        val xpub = req.xpub?.trim()?.takeIf { it.isNotBlank() }
        if(xpub != null) {
            if(policyType != PolicyType.SINGLE) {
                throw ApiException.badRequest("Watch-only wallets must use a single-signature policy")
            }
            val requestedPath = req.derivationPath?.trim()?.takeIf { it.isNotBlank() }
            log.info(
                "Creating watch-only wallet name={} policyType={} requestedScriptType={} derivationPath={} xpub={}",
                name,
                policyType,
                req.scriptType,
                requestedPath,
                scrubXpub(xpub)
            )
            val scriptType = resolveWatchOnlyScriptType(req, xpub)
            val derivationPath = req.derivationPath?.trim()?.takeIf { it.isNotBlank() } ?: scriptType.defaultDerivationPath
            if(!KeyDerivation.isValid(derivationPath)) {
                throw ApiException.badRequest("Invalid derivation path")
            }
            log.info("Resolved watch-only wallet name={} scriptType={} derivationPath={}", name, scriptType, derivationPath)
            return createWatchOnlyWallet(name, policyType, scriptType, xpub, derivationPath)
        }

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

        val wallet = Wallet(name)
        wallet.setPolicyType(policyType)
        wallet.setScriptType(scriptType)
        wallet.setNetwork(Network.get())
        val keystore = Keystore.fromSeed(seed, scriptType.defaultDerivation)
        wallet.keystores.clear()
        wallet.keystores.add(keystore)
        wallet.setDefaultPolicy(Policy.getPolicy(policyType, scriptType, wallet.keystores, null))

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
        refresh(handle, allowFailure = true)

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
        if(wallet.containsSource(KeystoreSource.SW_WATCH)) {
            throw ApiException.badRequest("Watch-only wallet cannot send payments")
        }

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
        refresh(handle, allowFailure = true)
        return SendResponse(txid.toString(), psbt.fee, walletTx.total)
    }

    fun getTransactions(name: String, password: SecureString): TransactionsResponse {
        val handle = openWallet(name, password)
        refresh(handle, allowFailure = true)
        val tipHeight = electrum.currentTipHeight() ?: handle.wallet.storedBlockHeight
        val transactions = runCatching { buildTransactions(handle.wallet, tipHeight) }.getOrElse { emptyList() }
        return TransactionsResponse(currentBalance(handle.wallet), transactions)
    }

    fun getBalanceHistory(name: String, password: SecureString): BalanceHistoryResponse {
        val handle = openWallet(name, password)
        refresh(handle, allowFailure = true)
        val tipHeight = electrum.currentTipHeight() ?: handle.wallet.storedBlockHeight
        val transactions = runCatching { buildTransactions(handle.wallet, tipHeight) }.getOrElse { emptyList() }
        val history = runCatching { buildBalanceHistory(transactions) }.getOrElse { emptyList() }
        return BalanceHistoryResponse(currentBalance(handle.wallet), history)
    }

    private fun refresh(handle: WalletHandle, allowFailure: Boolean = false) {
        try {
            val wallet = handle.wallet
            val receiveCount = wallet.getNode(KeyPurpose.RECEIVE).children.size
            val changeCount = wallet.getNode(KeyPurpose.CHANGE).children.size
            log.info(
                "Refreshing wallet name={} watchOnly={} scriptType={} receiveNodes={} changeNodes={}",
                wallet.name,
                wallet.containsSource(KeystoreSource.SW_WATCH),
                wallet.scriptType,
                receiveCount,
                changeCount
            )
            if(wallet.containsSource(KeystoreSource.SW_WATCH)) {
                val keystore = wallet.keystores.firstOrNull()
                val derivation = keystore?.keyDerivation?.derivationPath
                val xpub = keystore?.extendedPublicKey?.toString()
                log.info(
                    "Watch-only details name={} derivationPath={} xpub={}",
                    wallet.name,
                    derivation,
                    xpub?.let { scrubXpub(it) }
                )
            }
            val tip = Timeouts.runElectrum { electrum.refreshWallet(handle.wallet) }
            handle.lastUpdated = tip?.toLong()
            handle.storage.updateWallet(handle.wallet)
        } catch(e: Exception) {
            if(!allowFailure) {
                val message = if(e is TimeoutException) {
                    "Electrum request timed out"
                } else {
                    e.message ?: "Failed to sync with Electrum server"
                }
                throw ApiException.badRequest(message)
            }
        }
    }

    private fun walletExists(name: String): Boolean {
        val trimmed = name.trim()
        val dir = Storage.getWalletsDir()
        val encrypted = File(dir, trimmed)
        if(encrypted.exists()) {
            return true
        }

        for(type in PersistenceType.values()) {
            val file = File(dir, "$trimmed.${type.extension}")
            if(file.exists()) {
                return true
            }
        }

        return Storage.RESERVED_WALLET_NAMES.contains(trimmed)
    }

    private fun toSummary(handle: WalletHandle): WalletSummary {
        return WalletSummary(
            id = handle.wallet.name,
            name = handle.wallet.fullDisplayName,
            network = handle.wallet.network.name,
            policyType = handle.wallet.policyType.name,
            scriptType = handle.wallet.scriptType.name,
            watchOnly = handle.wallet.containsSource(KeystoreSource.SW_WATCH),
            balanceSats = currentBalance(handle.wallet),
            lastUpdated = handle.lastUpdated
        )
    }

    private fun resolveWatchOnlyScriptType(req: CreateWalletRequest, xpub: String): ScriptType {
        val requested = req.scriptType.trim()
        if(requested.isNotBlank() && !requested.equals("AUTO", ignoreCase = true)) {
            return runCatching { ScriptType.valueOf(requested.uppercase()) }
                .getOrElse { throw ApiException.badRequest("Unsupported script type") }
        }
        val path = req.derivationPath?.trim()?.takeIf { it.isNotBlank() }
        val fromPath = path?.let { inferScriptTypeFromDerivationPath(it) }
        val header = try {
            ExtendedKey.Header.fromExtendedKey(xpub)
        } catch(e: Exception) {
            throw ApiException.badRequest(e.message ?: "Invalid extended public key")
        }
        val fromHeader = header.defaultScriptType
        val resolved = fromPath ?: fromHeader ?: ScriptType.P2WPKH
        log.info(
            "Watch-only script inference requested={} derivationPath={} pathScriptType={} headerScriptType={} resolved={}",
            requested,
            path,
            fromPath,
            fromHeader,
            resolved
        )
        if(fromPath != null && fromHeader != null && fromPath != fromHeader) {
            log.warn(
                "Watch-only script mismatch: derivationPath implies {} but xpub header implies {} (using {})",
                fromPath,
                fromHeader,
                resolved
            )
        }
        return resolved
    }

    private fun createWatchOnlyWallet(
        name: String,
        policyType: PolicyType,
        scriptType: ScriptType,
        xpub: String,
        derivationPath: String
    ): CreateWalletResponse {
        val extendedKey = try {
            ExtendedKey.fromDescriptor(xpub)
        } catch(e: Exception) {
            throw ApiException.badRequest("Invalid extended public key")
        }
        if(!extendedKey.key.isPubKeyOnly) {
            throw ApiException.badRequest("Extended key must be public")
        }

        val wallet = Wallet(name)
        wallet.setPolicyType(policyType)
        wallet.setScriptType(scriptType)
        wallet.setNetwork(Network.get())

        val keystore = Keystore("Watch Only")
        keystore.setSource(KeystoreSource.SW_WATCH)
        keystore.setWalletModel(WalletModel.SPARROW)
        keystore.setKeyDerivation(KeyDerivation(KeyDerivation.DEFAULT_WATCH_ONLY_FINGERPRINT, derivationPath))
        keystore.setExtendedPublicKey(extendedKey)
        wallet.keystores.clear()
        wallet.keystores.add(keystore)
        wallet.setDefaultPolicy(Policy.getPolicy(policyType, scriptType, wallet.keystores, null))

        val storage = Storage(Storage.getWalletFile(name))
        storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY)
        storage.saveWallet(wallet)

        val handle = WalletHandle(wallet, storage)
        wallets[name] = handle
        refresh(handle, allowFailure = true)
        return CreateWalletResponse(toSummary(handle), null)
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

    private fun inferScriptTypeFromDerivationPath(path: String): ScriptType? {
        val trimmed = path.trim()
        if(trimmed.isBlank()) return null
        val normalized = when {
            trimmed.startsWith("m/", ignoreCase = true) -> trimmed.substring(2)
            trimmed.startsWith("m", ignoreCase = true) -> trimmed.substring(1)
            else -> trimmed
        }
        val firstSegment = normalized.split("/").firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val indexMatch = Regex("""\d+""").find(firstSegment) ?: return null
        val index = indexMatch.value.toIntOrNull() ?: return null
        return when(index) {
            44 -> ScriptType.P2PKH
            49 -> ScriptType.P2SH
            84 -> ScriptType.P2WPKH
            86 -> ScriptType.P2TR
            else -> null
        }
    }

    private fun scrubXpub(xpub: String): String {
        val trimmed = xpub.trim()
        if(trimmed.length <= 16) return trimmed
        return "${trimmed.take(8)}…${trimmed.takeLast(8)}"
    }
}
