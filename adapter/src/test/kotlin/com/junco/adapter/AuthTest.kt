package com.junco.adapter

import com.sparrowwallet.drongo.SecureString
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AuthTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun authStoreRoundTrip() {
        val dir = Files.createTempDirectory("junco-auth")
        val file = dir.resolve("auth.json")
        val store = AuthStore(file, json)
        val record = AuthRecord("salt", "hash", "now")
        store.save(record)

        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("salt", loaded?.salt)
        assertEquals("hash", loaded?.hash)
    }

    @Test
    fun authStoreSaveFailsWhenTargetIsDirectory() {
        val dir = Files.createTempDirectory("junco-auth-dir")
        val store = AuthStore(dir, json)
        val record = AuthRecord("salt", "hash", "now")
        val error = assertThrows(ApiException::class.java) { store.save(record) }
        assertEquals(HttpStatusCode.InternalServerError, error.status)
    }

    @Test
    fun authManagerSetupAndVerify() {
        val dir = Files.createTempDirectory("junco-auth-manager")
        val file = dir.resolve("auth.json")
        val store = AuthStore(file, json)
        val manager = AuthManager(store)

        manager.setup("correct-horse-battery-staple", "correct-horse-battery-staple")
        assertTrue(manager.isConfigured())
        assertTrue(manager.verify("correct-horse-battery-staple"))
        assertFalse(manager.verify("wrong-password"))
    }

    @Test
    fun authManagerValidatesInputs() {
        val dir = Files.createTempDirectory("junco-auth-validate")
        val file = dir.resolve("auth.json")
        val store = AuthStore(file, json)
        val manager = AuthManager(store)

        assertThrows(ApiException::class.java) { manager.setup("short", "short") }
        assertThrows(ApiException::class.java) { manager.setup("long-enough", "mismatch") }

        manager.setup("long-enough", "long-enough")
        assertThrows(ApiException::class.java) { manager.setup("another-pass", "another-pass") }
    }

    @Test
    fun sessionRegistryExpires() {
        val registry = SessionRegistry(ttlSeconds = -1)
        val (id, data) = registry.create(SecureString("pw"))
        assertNotNull(data.csrfToken)
        val fetched = registry.get(id)
        assertEquals(null, fetched)
    }

    @Test
    fun apiExceptionHelpers() {
        assertEquals(HttpStatusCode.BadRequest, ApiException.badRequest("bad").status)
        assertEquals(HttpStatusCode.Unauthorized, ApiException.unauthorized("no").status)
        assertEquals(HttpStatusCode.Forbidden, ApiException.forbidden("no").status)
        assertEquals(HttpStatusCode.NotFound, ApiException.notFound("no").status)
        assertEquals(HttpStatusCode.InternalServerError, ApiException.internal("no").status)
    }
}
