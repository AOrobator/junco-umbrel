package com.junco.adapter

import com.sparrowwallet.drongo.Network
import com.sparrowwallet.sparrow.io.Config
import com.sparrowwallet.sparrow.io.Server
import com.sparrowwallet.sparrow.net.Protocol
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.set
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.createDirectories

fun main() {
    val dataDir = Path.of(System.getenv("JUNCO_DATA") ?: "/data")
    dataDir.createDirectories()
    val sparrowHome = dataDir.resolve("sparrow")
    sparrowHome.createDirectories()
    System.setProperty("java.awt.headless", "true")
    System.setProperty("sparrow.home", sparrowHome.toString())

    val network = parseNetwork(System.getenv("JUNCO_NETWORK") ?: "mainnet")
    Network.set(network)

    val port = System.getenv("JUNCO_PORT")?.toIntOrNull() ?: 8081
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        val jsonConfig = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val authStore = AuthStore(dataDir.resolve("auth.json"), jsonConfig)
        val authManager = AuthManager(authStore)
        val sessions = SessionRegistry()
        val electrum = ElectrumClient()
        val walletService = WalletService(electrum)

        install(DefaultHeaders)
        install(CallLogging)
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(Sessions) {
            cookie<Session>("junco_session") {
                cookie.httpOnly = true
                cookie.secure = false
                cookie.path = "/"
                cookie.extensions["SameSite"] = "Strict"
            }
        }
        install(StatusPages) {
            exception<ApiException> { call, cause ->
                call.respond(cause.status, ErrorResponse(cause.message ?: "Request failed"))
            }
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Server error"))
            }
        }

        routing {
            route("/api") {
                get("/health") {
                    call.respond(mapOf("status" to "ok"))
                }

                route("/auth") {
                    get("/status") {
                        val configured = authManager.isConfigured()
                        val session = call.sessions.get<Session>()
                        val sessionData = session?.let { sessions.get(it.id) }
                        call.respond(AuthStatusResponse(configured, sessionData != null, sessionData?.csrfToken))
                    }

                    post("/setup") {
                        val body = call.receive<AuthSetupRequest>()
                        authManager.setup(body.password, body.confirm)
                        call.respond(AuthStatusResponse(true, false, null))
                    }

                    post("/login") {
                        if(!authManager.isConfigured()) {
                            throw ApiException.badRequest("Password not set")
                        }
                        val body = call.receive<AuthLoginRequest>()
                        if(!authManager.verify(body.password)) {
                            throw ApiException.unauthorized("Invalid password")
                        }
                        val secure = com.sparrowwallet.drongo.SecureString(body.password)
                        val (id, sessionData) = sessions.create(secure)
                        call.sessions.set(Session(id))
                        call.respond(AuthStatusResponse(true, true, sessionData.csrfToken))
                    }

                    post("/logout") {
                        val session = call.sessions.get<Session>()
                        if(session != null) {
                            sessions.invalidate(session.id)
                        }
                        call.sessions.clear<Session>()
                        call.respond(AuthStatusResponse(authManager.isConfigured(), false, null))
                    }
                }

                route("") {
                    intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                        val path = call.request.path()
                        if(path == "/api/health" || path.startsWith("/api/auth")) {
                            return@intercept
                        }
                        val sessionData = call.requireSession(authManager, sessions)
                        if(call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) {
                            val token = call.request.header("X-CSRF-Token")
                            if(token == null || token != sessionData.csrfToken) {
                                throw ApiException.forbidden("CSRF token missing or invalid")
                            }
                        }
                    }

                    get("/wallets") {
                        call.respond(walletService.listWallets())
                    }

                    post("/wallets/create") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val body = call.receive<CreateWalletRequest>()
                        call.respond(walletService.createWallet(body, sessionData.password))
                    }

                    post("/wallets/open") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val body = call.receive<OpenWalletRequest>()
                        val summary = walletService.getSummary(body.name, sessionData.password)
                        call.respond(summary)
                    }

                    get("/wallets/{name}") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val name = call.parameters["name"] ?: throw ApiException.badRequest("Missing wallet name")
                        call.respond(walletService.getSummary(name, sessionData.password))
                    }

                    post("/wallets/{name}/receive") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val name = call.parameters["name"] ?: throw ApiException.badRequest("Missing wallet name")
                        val body = call.receive<ReceiveRequest>()
                        call.respond(walletService.receiveAddress(name, sessionData.password, body.label))
                    }

                    post("/wallets/{name}/send") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val name = call.parameters["name"] ?: throw ApiException.badRequest("Missing wallet name")
                        val body = call.receive<SendRequest>()
                        call.respond(walletService.sendPayment(name, sessionData.password, body))
                    }

                    get("/wallets/{name}/transactions") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val name = call.parameters["name"] ?: throw ApiException.badRequest("Missing wallet name")
                        call.respond(walletService.getTransactions(name, sessionData.password))
                    }

                    get("/wallets/{name}/balance") {
                        val sessionData = call.requireSession(authManager, sessions)
                        val name = call.parameters["name"] ?: throw ApiException.badRequest("Missing wallet name")
                        call.respond(walletService.getBalanceHistory(name, sessionData.password))
                    }

                    get("/electrum") {
                        val server = Config.get().electrumServer
                        if(server == null) {
                            call.respond(ElectrumConfigRequest("", null, true, null))
                        } else {
                            call.respond(
                                ElectrumConfigRequest(
                                    host = server.host,
                                    port = server.hostAndPort.port,
                                    ssl = server.protocol == Protocol.SSL,
                                    certificatePath = Config.get().electrumServerCert?.absolutePath
                                )
                            )
                        }
                    }

                    post("/electrum") {
                        val body = call.receive<ElectrumConfigRequest>()
                        val protocol = if(body.ssl) Protocol.SSL else Protocol.TCP
                        val port = body.port ?: protocol.defaultPort
                        val server = Server(protocol.toUrlString(body.host, port))
                        electrum.configure(server, body.certificatePath)
                        call.respond(mapOf("status" to "ok"))
                    }

                    get("/electrum/status") {
                        val response = try {
                            val version = electrum.ping()
                            ElectrumStatusResponse(true, version, electrum.currentTipHeight(), null)
                        } catch(e: Exception) {
                            ElectrumStatusResponse(false, null, electrum.currentTipHeight(), e.message)
                        }
                        call.respond(response)
                    }
                }
            }
        }
    }.start(wait = true)
}

private fun parseNetwork(value: String): Network {
    return when(value.lowercase()) {
        "mainnet" -> Network.MAINNET
        "testnet" -> Network.TESTNET
        "signet" -> Network.SIGNET
        "regtest" -> Network.REGTEST
        else -> throw IllegalArgumentException("Unsupported network: $value")
    }
}

private fun io.ktor.server.application.ApplicationCall.requireSession(
    authManager: AuthManager,
    registry: SessionRegistry
): SessionData {
    if(!authManager.isConfigured()) {
        throw ApiException.unauthorized("Password not set")
    }
    val session = sessions.get<Session>() ?: throw ApiException.unauthorized("Not authenticated")
    return registry.get(session.id) ?: throw ApiException.unauthorized("Session expired")
}
