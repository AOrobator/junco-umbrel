package com.junco.adapter

import com.sparrowwallet.drongo.Network
import com.sparrowwallet.drongo.wallet.Wallet
import com.sparrowwallet.sparrow.io.Server
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ApiFlowTest {
    private class FakeElectrumClient : ElectrumGateway {
        override fun configure(server: Server, certificatePath: String?) = Unit

        override fun ping(): List<String> = listOf("fake", "0.0.0")

        override fun currentTipHeight(): Int? = 0

        override fun refreshWallet(wallet: Wallet): Int? = 0
    }

    @Test
    fun endToEndFlow() = testApplication {
        val dataDir = Files.createTempDirectory("junco-test")
        application {
            juncoModule(AppConfig(dataDir, Network.TESTNET), FakeElectrumClient())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpCookies)
        }

        val status = client.get("/api/auth/status").body<AuthStatusResponse>()
        assertFalse(status.configured)
        assertFalse(status.authenticated)

        val password = "correct-horse-battery-staple"
        client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(AuthSetupRequest(password, password))
        }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthLoginRequest(password))
        }.body<AuthStatusResponse>()
        assertTrue(login.authenticated)
        val csrf = login.csrfToken
        assertNotNull(csrf)

        val wallets = client.get("/api/wallets").body<List<String>>()
        assertTrue(wallets.isEmpty())

        val createResponse = client.post("/api/wallets/create") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(
                CreateWalletRequest(
                    name = "JuncoTest",
                    generate = true,
                    entropyBits = 128,
                    policyType = "SINGLE",
                    scriptType = "P2WPKH"
                )
            )
        }.body<CreateWalletResponse>()
        assertEquals("JuncoTest", createResponse.wallet.name)
        assertNotNull(createResponse.mnemonic)

        val openResponse = client.post("/api/wallets/open") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(OpenWalletRequest("JuncoTest"))
        }.body<WalletSummary>()
        assertEquals("JuncoTest", openResponse.name)

        val receiveResponse = client.post("/api/wallets/JuncoTest/receive") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(ReceiveRequest("Test receive"))
        }.body<ReceiveResponse>()
        assertTrue(receiveResponse.address.isNotBlank())

        val transactions = client.get("/api/wallets/JuncoTest/transactions").body<TransactionsResponse>()
        assertEquals(0, transactions.transactions.size)

        val balance = client.get("/api/wallets/JuncoTest/balance").body<BalanceHistoryResponse>()
        assertEquals(transactions.balanceSats, balance.balanceSats)
    }
}
