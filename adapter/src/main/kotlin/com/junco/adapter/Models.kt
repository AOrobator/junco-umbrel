package com.junco.adapter

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class AuthStatusResponse(
    val configured: Boolean,
    val authenticated: Boolean,
    val csrfToken: String? = null
)

@Serializable
data class AuthSetupRequest(
    val password: String,
    val confirm: String
)

@Serializable
data class AuthLoginRequest(
    val password: String
)

@Serializable
data class WalletSummary(
    val id: String,
    val name: String,
    val network: String,
    val policyType: String,
    val scriptType: String,
    val balanceSats: Long,
    val lastUpdated: Long?
)

@Serializable
data class CreateWalletRequest(
    val name: String,
    val mnemonic: String? = null,
    val generate: Boolean = false,
    val entropyBits: Int = 128,
    val passphrase: String? = null,
    val policyType: String = "SINGLE",
    val scriptType: String = "P2WPKH"
)

@Serializable
data class CreateWalletResponse(
    val wallet: WalletSummary,
    val mnemonic: String? = null
)

@Serializable
data class OpenWalletRequest(
    val name: String
)

@Serializable
data class ReceiveRequest(
    val label: String? = null
)

@Serializable
data class ReceiveResponse(
    val address: String,
    val derivationPath: String
)

@Serializable
data class SendOutput(
    val address: String,
    val amountSats: Long,
    val label: String? = null
)

@Serializable
data class SendRequest(
    val outputs: List<SendOutput>,
    val feeRate: Double,
    val allowRbf: Boolean = true
)

@Serializable
data class SendResponse(
    val txid: String,
    val feeSats: Long,
    val valueSats: Long
)

@Serializable
data class TransactionSummary(
    val txid: String,
    val valueSats: Long,
    val feeSats: Long? = null,
    val confirmations: Int,
    val height: Int,
    val timestamp: Long? = null,
    val label: String? = null
)

@Serializable
data class TransactionsResponse(
    val balanceSats: Long,
    val transactions: List<TransactionSummary>
)

@Serializable
data class BalancePoint(
    val timestamp: Long,
    val balanceSats: Long
)

@Serializable
data class BalanceHistoryResponse(
    val balanceSats: Long,
    val history: List<BalancePoint>
)

@Serializable
data class ElectrumConfigRequest(
    val host: String,
    val port: Int? = null,
    val ssl: Boolean = true,
    val certificatePath: String? = null
)

@Serializable
data class ElectrumStatusResponse(
    val connected: Boolean,
    val serverVersion: List<String>? = null,
    val tipHeight: Int? = null,
    val error: String? = null
)
