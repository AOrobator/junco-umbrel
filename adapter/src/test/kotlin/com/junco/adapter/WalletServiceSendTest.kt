package com.junco.adapter

import com.sparrowwallet.drongo.SecureString
import com.sparrowwallet.drongo.protocol.Sha256Hash
import com.sparrowwallet.drongo.protocol.Transaction
import com.sparrowwallet.drongo.psbt.PSBT
import com.sparrowwallet.drongo.wallet.KeystoreSource
import com.sparrowwallet.drongo.wallet.Wallet
import com.sparrowwallet.drongo.wallet.WalletTransaction
import com.sparrowwallet.sparrow.io.Storage
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class WalletServiceSendTest {
    private class FakeElectrum : ElectrumGateway {
        override fun configure(server: com.sparrowwallet.sparrow.io.Server, certificatePath: String?) = Unit
        override fun ping(): List<String> = emptyList()
        override fun currentTipHeight(): Int? = 0
        override fun refreshWallet(wallet: Wallet): Int? = 0
    }

    private class FakeBroadcaster : TransactionBroadcaster {
        override fun broadcast(transaction: Transaction, fee: Long?): Sha256Hash {
            return Sha256Hash.wrap("00".repeat(32))
        }
    }

    @Test
    fun sendPaymentBroadcastsAndReturnsResponse() {
        val wallet = mockk<Wallet>()
        val signingWallet = mockk<Wallet>()
        val walletTx = mockk<WalletTransaction>()
        val psbt = mockk<PSBT>()
        val tx = mockk<Transaction>()
        val storage = mockk<Storage>(relaxed = true)

        every { wallet.containsSource(KeystoreSource.SW_WATCH) } returns false
        every { wallet.createWalletTransaction(any()) } returns walletTx
        every { wallet.copy() } returns signingWallet
        every { signingWallet.decrypt(any()) } just runs
        every { signingWallet.sign(psbt) } just runs
        every { signingWallet.finalise(psbt) } just runs
        every { signingWallet.clearPrivate() } just runs

        every { walletTx.createPSBT() } returns psbt
        every { walletTx.total } returns 1234L
        every { psbt.fee } returns 55L
        every { psbt.extractTransaction() } returns tx

        val service = WalletService(FakeElectrum(), FakeBroadcaster())
        val handle = WalletService.WalletHandle(wallet, storage)
        val field = WalletService::class.java.getDeclaredField("wallets")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(service) as ConcurrentHashMap<String, WalletService.WalletHandle>
        map["TestWallet"] = handle

        val request = SendRequest(
            outputs = listOf(
                SendOutput(
                    address = "bc1qrg4664h72fqsswn849hhk953kpematpvsz6z4s",
                    amountSats = 5000
                )
            ),
            feeRate = 2.0,
            allowRbf = true
        )

        val response = service.sendPayment("TestWallet", SecureString("pw"), request)
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", response.txid)
        assertEquals(55L, response.feeSats)
        assertEquals(1234L, response.valueSats)
    }

    @Test
    fun sendPaymentFailsForWatchOnlyWallet() {
        val wallet = mockk<Wallet>()
        val storage = mockk<Storage>(relaxed = true)
        every { wallet.containsSource(KeystoreSource.SW_WATCH) } returns true

        val service = WalletService(FakeElectrum(), FakeBroadcaster())
        val handle = WalletService.WalletHandle(wallet, storage)
        val field = WalletService::class.java.getDeclaredField("wallets")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(service) as ConcurrentHashMap<String, WalletService.WalletHandle>
        map["WatchWallet"] = handle

        val request = SendRequest(
            outputs = listOf(
                SendOutput(
                    address = "bc1qrg4664h72fqsswn849hhk953kpematpvsz6z4s",
                    amountSats = 5000
                )
            ),
            feeRate = 2.0,
            allowRbf = true
        )

        assertThrows(ApiException::class.java) {
            service.sendPayment("WatchWallet", SecureString("pw"), request)
        }
    }
}
