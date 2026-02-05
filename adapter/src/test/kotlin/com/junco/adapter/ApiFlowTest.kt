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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeoutException

class ApiFlowTest {
    private class FakeElectrumClient : ElectrumGateway {
        override fun configure(server: Server, certificatePath: String?) = Unit

        override fun ping(): List<String> = listOf("fake", "0.0.0")

        override fun currentTipHeight(): Int? = 0

        override fun refreshWallet(wallet: Wallet): Int? = 0
    }

    private class TimeoutElectrumClient : ElectrumGateway {
        override fun configure(server: Server, certificatePath: String?) = Unit

        override fun ping(): List<String> = throw TimeoutException("timeout")

        override fun currentTipHeight(): Int? = null

        override fun refreshWallet(wallet: Wallet): Int? = throw TimeoutException("timeout")
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
        assertFalse(createResponse.wallet.watchOnly)
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

    @Test
    fun watchOnlyWalletFlow() = testApplication {
        val dataDir = Files.createTempDirectory("junco-watch-test")
        application {
            juncoModule(AppConfig(dataDir, Network.TESTNET), FakeElectrumClient())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpCookies)
        }

        val password = "correct-horse-battery-staple"
        client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(AuthSetupRequest(password, password))
        }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthLoginRequest(password))
        }.body<AuthStatusResponse>()
        val csrf = login.csrfToken
        assertNotNull(csrf)

        val tpub =
            "tpubD9429UXFGCTKJ9NdiNK4rC5ygqSUkginycYHccqSg5gkmyQ7PZRHNjk99M6a6Y3NY8ctEUUJvCu6iCCui8Ju3xrHRu3Ez1CKB4ZFoRZDdP9"
        val createResponse = client.post("/api/wallets/create") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(
                CreateWalletRequest(
                    name = "WatchOnly",
                    policyType = "SINGLE",
                    scriptType = "AUTO",
                    xpub = tpub
                )
            )
        }.body<CreateWalletResponse>()

        assertEquals("WatchOnly", createResponse.wallet.name)
        assertTrue(createResponse.wallet.watchOnly)
        assertEquals("P2PKH", createResponse.wallet.scriptType)
        assertEquals(null, createResponse.mnemonic)

        val openResponse = client.post("/api/wallets/open") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(OpenWalletRequest("WatchOnly"))
        }.body<WalletSummary>()
        assertTrue(openResponse.watchOnly)

        val sendResponse = client.post("/api/wallets/WatchOnly/send") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            expectSuccess = false
            setBody(
                SendRequest(
                    outputs = listOf(
                        SendOutput(
                            address = "tb1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                            amountSats = 1000
                        )
                    ),
                    feeRate = 1.0
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, sendResponse.status)
        val error = sendResponse.body<ErrorResponse>()
        assertTrue(error.error.contains("Watch-only", ignoreCase = true))
    }

    @Test
    fun watchOnlyDerivationPathOverridesHeader() = testApplication {
        val dataDir = Files.createTempDirectory("junco-watch-path-test")
        application {
            juncoModule(AppConfig(dataDir, Network.TESTNET), FakeElectrumClient())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpCookies)
        }

        val password = "correct-horse-battery-staple"
        client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(AuthSetupRequest(password, password))
        }

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthLoginRequest(password))
        }.body<AuthStatusResponse>()
        val csrf = login.csrfToken
        assertNotNull(csrf)

        val tpub =
            "tpubD9429UXFGCTKJ9NdiNK4rC5ygqSUkginycYHccqSg5gkmyQ7PZRHNjk99M6a6Y3NY8ctEUUJvCu6iCCui8Ju3xrHRu3Ez1CKB4ZFoRZDdP9"
        val createResponse = client.post("/api/wallets/create") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(
                CreateWalletRequest(
                    name = "WatchOnlyBip84",
                    policyType = "SINGLE",
                    scriptType = "AUTO",
                    xpub = tpub,
                    derivationPath = "m/84'/1'/0'"
                )
            )
        }.body<CreateWalletResponse>()

        assertEquals("WatchOnlyBip84", createResponse.wallet.name)
        assertTrue(createResponse.wallet.watchOnly)
        assertEquals("P2WPKH", createResponse.wallet.scriptType)
    }

    @Test
    fun electrumStatusTimeout() = testApplication {
        val dataDir = Files.createTempDirectory("junco-timeout-test")
        application {
            juncoModule(AppConfig(dataDir, Network.TESTNET), TimeoutElectrumClient())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            install(HttpCookies)
        }

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

        val status = client.get("/api/electrum/status").body<ElectrumStatusResponse>()
        assertFalse(status.connected)
        assertEquals("Electrum request timed out", status.error)
    }

    @Test
    fun electrumConfigStoresProxy() = testApplication {
        val dataDir = Files.createTempDirectory("junco-proxy-test")
        application {
            juncoModule(AppConfig(dataDir, Network.TESTNET), FakeElectrumClient())
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }

        val request = ElectrumConfigRequest(
            host = "4d4452je7owp5pcthfzgidhq2biyo3p5pxxkagkhpylzdqhtizowu7yd.onion",
            port = 50001,
            ssl = false,
            certificatePath = null,
            useProxy = true,
            proxyServer = "tor:9050"
        )

        client.post("/api/electrum") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val config = client.get("/api/electrum").body<ElectrumConfigRequest>()
        assertEquals(request.host, config.host)
        assertEquals(request.port, config.port)
        assertEquals(request.ssl, config.ssl)
        assertEquals(request.useProxy, config.useProxy)
        assertEquals(request.proxyServer, config.proxyServer)
    }

    @Test
    fun subscriptionServiceIsHeadlessSafe() {
        val service = com.sparrowwallet.sparrow.net.SubscriptionService()
        val header = com.sparrowwallet.sparrow.net.BlockHeaderTip().apply {
            height = 1
            hex = null
        }

        assertDoesNotThrow { service.newBlockHeaderTip(header) }
    }
}
