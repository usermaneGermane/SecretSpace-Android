package secretspace.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import secretspace.core.Disk.int
import secretspace.core.Disk.text
import secretspace.core.AccountCrypto as ac

/** This device cannot read the stored Secret Key: ask for the Emergency Kit. */
class SecretKeyUnavailable(message: String) : Exception(message)

/**
 * How this device keeps an account's Secret Key, so that unlocking needs
 * only the master password (the PC's secret_store). A record is a small
 * JSON object in account.json; ``scheme`` says which kind it is.
 */
interface SecretStore {
    fun protect(secret: ByteArray, username: String, password: String): JsonObject

    /** The Secret Key, [SecretKeyUnavailable], or CryptoError for a wrong password. */
    fun unprotect(record: JsonElement?, username: String, password: String): ByteArray
}

/**
 * The PC's "password" scheme, byte for byte: Argon2id of the master
 * password with its own salt, then AES-GCM bound to the account. A record
 * written here opens on the PC and the other way round. (The phone app
 * will add the Android Keystore as a scheme of its own.)
 */
class PasswordSecretStore(
    private val passes: Int = ac.ARGON2_PASSES,
    private val memoryKib: Int = ac.ARGON2_MEMORY_KIB,
    private val lanes: Int = ac.ARGON2_LANES,
) : SecretStore {
    companion object {
        const val SCHEME = "password"
        const val RECORD_VERSION = 2

        fun context(username: String): ByteArray =
            "SecretSpace 4 Secret Key\u0000".toByteArray(Charsets.US_ASCII) + username.toByteArray(Charsets.UTF_8)
    }

    private fun key(password: String, salt: ByteArray, passes: Int, memoryKib: Int, lanes: Int): ByteArray {
        ac.checkCost(passes, memoryKib, lanes)
        return ac.argon2id(ac.nfkc(password), salt, passes, memoryKib, lanes)
    }

    override fun protect(secret: ByteArray, username: String, password: String): JsonObject {
        val salt = ac.randomBytes(ac.SALT_LEN)
        val nonce = ac.randomBytes(ac.NONCE_LEN)
        val sealed = ac.gcmSeal(key(password, salt, passes, memoryKib, lanes), nonce, secret, context(username))
        return JsonObject(mapOf(
            "scheme" to JsonPrimitive(SCHEME), "v" to JsonPrimitive(RECORD_VERSION),
            "salt" to JsonPrimitive(ac.b64(salt)), "nonce" to JsonPrimitive(ac.b64(nonce)),
            "passes" to JsonPrimitive(passes), "memory_kib" to JsonPrimitive(memoryKib),
            "lanes" to JsonPrimitive(lanes), "sealed" to JsonPrimitive(ac.b64(sealed)),
        ))
    }

    override fun unprotect(record: JsonElement?, username: String, password: String): ByteArray {
        if (record !is JsonObject) throw SecretKeyUnavailable("This device does not hold the Secret Key yet.")
        when (record.text("scheme")) {
            SCHEME -> {}
            "dpapi" -> throw SecretKeyUnavailable("The Secret Key was stored by Windows.")
            else -> throw SecretKeyUnavailable("The stored Secret Key is in a form this SecretSpace does not know.")
        }
        // A record without "v" was written by SecretSpace 3, which never ran here.
        if (record.int("v") != RECORD_VERSION) throw SecretKeyUnavailable("The stored Secret Key is damaged.")
        val parts = try {
            listOf("salt", "nonce", "sealed").map { ac.unb64(record.text(it), "Secret Key") }
        } catch (e: CryptoError) {
            throw SecretKeyUnavailable("The stored Secret Key is damaged.")
        }
        val cost = listOf("passes", "memory_kib", "lanes").map {
            record.int(it) ?: throw SecretKeyUnavailable("The stored Secret Key is damaged.")
        }
        val raw = ac.gcmOpen(key(password, parts[0], cost[0], cost[1], cost[2]), parts[1], parts[2],
            context(username)) ?: throw CryptoError("Wrong master password or Secret Key.")
        if (raw.size != ac.SECRET_KEY_LEN) throw SecretKeyUnavailable("The stored Secret Key is damaged.")
        return raw
    }
}
