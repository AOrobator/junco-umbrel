package com.junco.adapter

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AuthStoreTest {
    @Test
    fun `setup and verify password`() {
        val tempDir = Files.createTempDirectory("junco-auth")
        val store = AuthStore(tempDir.resolve("auth.json"), Json { encodeDefaults = true })
        val manager = AuthManager(store)

        manager.setup("correct-horse-battery-staple", "correct-horse-battery-staple")

        assertTrue(manager.verify("correct-horse-battery-staple"))
        assertFalse(manager.verify("wrong-password"))
    }
}
