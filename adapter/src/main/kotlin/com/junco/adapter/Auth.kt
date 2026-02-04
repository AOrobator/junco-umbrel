package com.junco.adapter

import com.sparrowwallet.drongo.SecureString
import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AuthRecord(
    val salt: String,
    val hash: String,
    val createdAt: String
)

class AuthStore(private val file: Path, private val json: Json) {
    fun exists(): Boolean = Files.exists(file)

    fun load(): AuthRecord? {
        if(!exists()) return null
        val content = Files.readString(file)
        return json.decodeFromString(AuthRecord.serializer(), content)
    }

    fun save(record: AuthRecord) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(record))
        } catch(e: Exception) {
            throw ApiException.internal(
                "Unable to save auth data. Ensure the data volume is mounted and writable, then restart the app."
            )
        }
    }
}

data class Session(val id: String)

data class SessionData(
    val password: SecureString,
    val csrfToken: String,
    val createdAt: Instant,
    var lastUsed: Instant
)

class SessionRegistry(private val ttlSeconds: Long = 8 * 60 * 60) {
    private val sessions = ConcurrentHashMap<String, SessionData>()
    private val random = SecureRandom()

    fun create(password: SecureString): Pair<String, SessionData> {
        val id = newToken()
        val csrf = newToken()
        val now = Instant.now()
        val data = SessionData(password, csrf, now, now)
        sessions[id] = data
        return id to data
    }

    fun get(id: String): SessionData? {
        val data = sessions[id] ?: return null
        val now = Instant.now()
        if(now.epochSecond - data.lastUsed.epochSecond > ttlSeconds) {
            invalidate(id)
            return null
        }
        data.lastUsed = now
        return data
    }

    fun invalidate(id: String) {
        sessions.remove(id)?.password?.clear()
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class AuthManager(private val store: AuthStore) {
    fun isConfigured(): Boolean = store.exists()

    fun setup(password: String, confirm: String) {
        if(isConfigured()) {
            throw ApiException.badRequest("Password already set")
        }
        if(password.length < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters")
        }
        if(password != confirm) {
            throw ApiException.badRequest("Passwords do not match")
        }
        val record = createRecord(password)
        store.save(record)
    }

    fun verify(password: String): Boolean {
        val record = store.load() ?: return false
        val salt = Base64.getDecoder().decode(record.salt)
        val deriver = Argon2KeyDeriver(salt)
        val key = deriver.deriveKey(password)
        val expected = Base64.getDecoder().decode(record.hash)
        val actual = key.keyBytes
        val result = MessageDigest.isEqual(expected, actual)
        key.clear()
        return result
    }

    private fun createRecord(password: String): AuthRecord {
        val deriver = Argon2KeyDeriver()
        val key = deriver.deriveKey(password)
        val salt = Base64.getEncoder().encodeToString(deriver.salt)
        val hash = Base64.getEncoder().encodeToString(key.keyBytes)
        key.clear()
        return AuthRecord(salt, hash, Instant.now().toString())
    }
}
