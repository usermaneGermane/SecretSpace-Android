package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sentence the user can be shown: wrong password, damaged file, typo. */
class CryptoError(message: String) : Exception(message)

/**
 * What differs between the account formats (the PC's account_crypto.Format):
 * the word every label starts with, the file markers, how a Secret Key is
 * spelled. Algorithms and layouts are the same in both.
 */
class Format(
    val number: Int, val prefix: String, val keysetMagic: String,
    val keysetVersion: Int, val vaultMagic: String, val secretPrefix: String,
    val wrapVersion: Int,
) {
    fun label(what: String): ByteArray = "$prefix $what".toByteArray(Charsets.US_ASCII)
    override fun toString() = "<Format $number>"
}

/**
 * The cryptography of SecretSpace accounts, ported from the PC client's
 * account_crypto.py. Every value it makes must be the PC's byte for byte:
 * tests/vectors.json is made by the PC's own code.
 */
object AccountCrypto {
    const val KEY_LEN = 32
    const val SALT_LEN = 16
    const val NONCE_LEN = 12
    const val SECRET_KEY_LEN = 16
    const val VAULT_ID_LEN = 16
    private const val TAG_LEN = 16

    const val ARGON2_MEMORY_KIB = 64 * 1024
    const val ARGON2_PASSES = 3
    const val ARGON2_LANES = 1
    const val ARGON2_MAX_MEMORY_KIB = 1024 * 1024
    const val ARGON2_MAX_PASSES = 20
    const val ARGON2_MAX_LANES = 16

    /** What the PC writes now; and what SecretSpace 3 wrote (read only). */
    val NOW = Format(2, "SecretSpace4", "SSK4", 2, "SSV4", "A4", 2)
    val OLD = Format(1, "PassMan3", "PMK3", 1, "PMV3", "A3", 1)

    fun formatOf(number: Int?): Format = if (number == NOW.number) NOW else OLD

    private val random = SecureRandom()
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun b64(raw: ByteArray): String = Base64.getEncoder().encodeToString(raw)

    fun unb64(text: String?, what: String): ByteArray = try {
        Base64.getDecoder().decode(text ?: throw IllegalArgumentException())
    } catch (e: IllegalArgumentException) {
        throw CryptoError("The $what is damaged (not valid base64).")
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_LEN): ByteArray {
        val out = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, salt, info))
            generateBytes(out, 0, length)
        }
        return out
    }

    // ---------------------------------------------------------------- AES-GCM
    fun gcmSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            updateAAD(aad)
            doFinal(plain)
        }

    /** The plain bytes, or null when the key or the data is wrong. */
    fun gcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
                updateAAD(aad)
                doFinal(sealed)
            }
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    // ---------------------------------------------------------------- Argon2id
    fun argon2id(
        secret: ByteArray, salt: ByteArray, passes: Int = ARGON2_PASSES,
        memoryKib: Int = ARGON2_MEMORY_KIB, lanes: Int = ARGON2_LANES, length: Int = KEY_LEN,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(passes).withMemoryAsKB(memoryKib).withParallelism(lanes)
            .withSalt(salt).build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(params); generateBytes(secret, out) }
        return out
    }

    private fun checkCost(passes: Int, memoryKib: Int, lanes: Int) {
        if (passes !in 1..ARGON2_MAX_PASSES || memoryKib < 8 * lanes ||
            memoryKib > ARGON2_MAX_MEMORY_KIB || lanes !in 1..ARGON2_MAX_LANES
        ) throw CryptoError(
            "The account file asks for an unreasonable key derivation cost; " +
                "it is damaged or not SecretSpace's.",
        )
    }

    // ---------------------------------------------------------------- Secret Key
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32(raw: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in raw) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(B32[(buffer shr (bits - 5)) and 31]); bits -= 5
            }
        }
        if (bits > 0) out.append(B32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun unbase32(text: String, bytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 5) or B32.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF); bits -= 8
            }
        }
        return out.toByteArray().copyOf(bytes)
    }

    private fun secretCheckChar(raw: ByteArray, fmt: Format = NOW): Char =
        B32[(sha256(fmt.label("secret key"), raw)[0].toInt() and 0xFF) % 32]

    fun newSecretKey(): ByteArray = randomBytes(SECRET_KEY_LEN)

    /** `A4-XXXXX-XXXXX-XXXXX-XXXXXX-XXXXXX`, with a check character. */
    fun formatSecretKey(raw: ByteArray): String {
        require(raw.size == SECRET_KEY_LEN) { "a Secret Key is 16 bytes" }
        val body = base32(raw) + secretCheckChar(raw)
        val groups = listOf(body.substring(0, 5), body.substring(5, 10),
            body.substring(10, 15), body.substring(15, 21), body.substring(21, 27))
        return NOW.secretPrefix + "-" + groups.joinToString("-")
    }

    /** The 16 bytes of a typed Secret Key, or CryptoError saying what is wrong. */
    fun parseSecretKey(text: String?): ByteArray {
        val cleaned = (text ?: "").filterNot { it.isWhitespace() }.replace("-", "").uppercase()
        if (cleaned.isEmpty()) throw CryptoError("Enter your Secret Key (it starts with A4-).")
        val fmt = listOf(NOW, OLD).firstOrNull { cleaned.startsWith(it.secretPrefix) }
            ?: throw CryptoError(
                "A Secret Key starts with A4- (A3- on an Emergency Kit from " +
                    "SecretSpace 3); check your Emergency Kit.",
            )
        val body = cleaned.substring(fmt.secretPrefix.length)
        if (body.length != 27) throw CryptoError(
            "A Secret Key has 27 characters after ${fmt.secretPrefix}-; this one has ${body.length}.",
        )
        val bad = body.filter { it !in B32 }.toSortedSet()
        if (bad.isNotEmpty()) {
            val hint = if (bad.any { it in "01" }) " (0 and 1 are never used: did you mean O or I?)" else ""
            throw CryptoError(
                "A Secret Key uses only A-Z and 2-7; this one also has " +
                    bad.joinToString(" ") + hint + ".",
            )
        }
        val raw = unbase32(body.substring(0, 26), SECRET_KEY_LEN)
        if (base32(raw) != body.substring(0, 26) || secretCheckChar(raw, fmt) != body[26]) {
            throw CryptoError("That Secret Key has a typo; check it against your Emergency Kit.")
        }
        return raw
    }

    // ---------------------------------------------------------------- AUK + Login Key
    fun nfkc(text: String): ByteArray =
        Normalizer.normalize(text, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)

    /** (AUK, Login Key); both need the password AND the Secret Key. */
    fun deriveAccountKeys(
        masterPassword: String, secretKey: ByteArray, username: String, salt: ByteArray,
        passes: Int = ARGON2_PASSES, memoryKib: Int = ARGON2_MEMORY_KIB,
        lanes: Int = ARGON2_LANES, fmt: Format = NOW,
    ): Pair<ByteArray, ByteArray> {
        if (masterPassword.isEmpty()) throw CryptoError("Enter the master password.")
        require(secretKey.size == SECRET_KEY_LEN && salt.size == SALT_LEN) {
            "bad Secret Key or salt length"
        }
        checkCost(passes, memoryKib, lanes)
        val stretched = argon2id(nfkc(masterPassword), salt, passes, memoryKib, lanes)
        val who = nfkc(username)
        return Pair(
            hkdf(stretched, secretKey, fmt.label("account unlock key|") + who),
            hkdf(stretched, secretKey, fmt.label("login key|") + who),
        )
    }

    // ---------------------------------------------------------------- sealed keysets
    const val KDF_NONE = 0
    const val KDF_ARGON2ID = 1
    private const val KEYSET_HEADER_LEN = 28     // >4sBBIBB16s

    class KeysetHeader(
        val kdf: Int, val memoryKib: Int, val passes: Int, val lanes: Int,
        val salt: ByteArray, val format: Int,
    )

    private fun keysetHeaderBytes(magic: String, version: Int, kdf: Int, memoryKib: Int,
                                  passes: Int, lanes: Int, salt: ByteArray): ByteArray =
        ByteBuffer.allocate(KEYSET_HEADER_LEN).put(magic.toByteArray(Charsets.US_ASCII))
            .put(version.toByte()).put(kdf.toByte()).putInt(memoryKib)
            .put(passes.toByte()).put(lanes.toByte()).put(salt).array()

    /** Always the current format, as the PC does. */
    fun sealKeyset(
        key: ByteArray, keyset: Map<String, String>, salt: ByteArray, kdf: Int = KDF_ARGON2ID,
        passes: Int = ARGON2_PASSES, memoryKib: Int = ARGON2_MEMORY_KIB, lanes: Int = ARGON2_LANES,
    ): String {
        val header = keysetHeaderBytes(NOW.keysetMagic, NOW.keysetVersion, kdf, memoryKib,
            passes, lanes, salt)
        val nonce = randomBytes(NONCE_LEN)
        val body = JsonObject(completeKeyset(keyset).toSortedMap()
            .mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        return b64(header + nonce + gcmSeal(key, nonce, body, header))
    }

    fun keysetHeader(blob: String): KeysetHeader {
        val raw = unb64(blob, "account file")
        if (raw.size < KEYSET_HEADER_LEN + NONCE_LEN + TAG_LEN) {
            throw CryptoError("The account file is too short to be SecretSpace's.")
        }
        val buf = ByteBuffer.wrap(raw, 0, KEYSET_HEADER_LEN)
        val magic = String(ByteArray(4).also { buf.get(it) }, Charsets.ISO_8859_1)
        val version = buf.get().toInt() and 0xFF
        val kdf = buf.get().toInt() and 0xFF
        val memoryKib = buf.int
        val passes = buf.get().toInt() and 0xFF
        val lanes = buf.get().toInt() and 0xFF
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val fmt = listOf(NOW, OLD).firstOrNull { it.keysetMagic == magic }
            ?: throw CryptoError("That is not a SecretSpace account file.")
        if (version != fmt.keysetVersion) throw CryptoError(
            "The account file was written by a newer SecretSpace; update this one.",
        )
        if (kdf != KDF_ARGON2ID && kdf != KDF_NONE) {
            throw CryptoError("The account file uses an unknown key derivation.")
        }
        if (kdf == KDF_ARGON2ID) checkCost(passes, memoryKib, lanes)
        return KeysetHeader(kdf, memoryKib, passes, lanes, salt, fmt.number)
    }

    fun openKeyset(key: ByteArray, blob: String): Map<String, String> {
        keysetHeader(blob)
        val raw = unb64(blob, "account file")
        val header = raw.copyOfRange(0, KEYSET_HEADER_LEN)
        val nonce = raw.copyOfRange(KEYSET_HEADER_LEN, KEYSET_HEADER_LEN + NONCE_LEN)
        val body = gcmOpen(key, nonce, raw.copyOfRange(KEYSET_HEADER_LEN + NONCE_LEN, raw.size), header)
            ?: throw CryptoError("Wrong master password or Secret Key.")
        val parsed = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        return completeKeyset(parsed.mapValues { it.value.jsonPrimitive.content })
    }

    fun newKeyset(): Map<String, String> = mapOf(
        "x25519_private" to b64(randomBytes(KEY_LEN)),
        "personal_vault_key" to b64(randomBytes(KEY_LEN)),
        "ed25519_seed" to b64(randomBytes(KEY_LEN)),
        "recovery_proof" to b64(randomBytes(KEY_LEN)),
    )

    /** A keyset from SecretSpace 3 derives its two missing secrets once. */
    fun completeKeyset(keyset: Map<String, String>): Map<String, String> {
        if (("ed25519_seed" in keyset && "recovery_proof" in keyset) || "x25519_private" !in keyset) {
            return keyset
        }
        val private = unb64(keyset["x25519_private"], "account key")
        val done = keyset.toMutableMap()
        done.putIfAbsent("ed25519_seed", b64(hkdf(private, OLD.label("signing"), OLD.label("signing key"))))
        done.putIfAbsent("recovery_proof",
            b64(hkdf(private, OLD.label("recovery proof"), OLD.label("recovery proof|v1"))))
        return done
    }

    fun publicKeyOf(keyset: Map<String, String>): ByteArray =
        X25519PrivateKeyParameters(unb64(keyset["x25519_private"], "account key"), 0)
            .generatePublicKey().encoded

    // ---------------------------------------------------------------- wrapped vault keys
    private fun wrapContext(vaultId: ByteArray, member: String, fmt: Format): ByteArray =
        fmt.label("wrap|") + vaultId + "|".toByteArray() + nfkc(member)

    private fun x25519(private: ByteArray, public: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519Agreement().apply {
            init(X25519PrivateKeyParameters(private, 0))
            calculateAgreement(X25519PublicKeyParameters(public, 0), out, 0)
        }
        return out
    }

    fun wrapKey(recipientPublic: ByteArray, vaultKey: ByteArray, vaultId: ByteArray,
                member: String, fmt: Format = NOW): String {
        val ephemeral = randomBytes(32)
        val ephPub = X25519PrivateKeyParameters(ephemeral, 0).generatePublicKey().encoded
        val key = hkdf(x25519(ephemeral, recipientPublic), ephPub + recipientPublic, fmt.label("wrap key"))
        val nonce = randomBytes(NONCE_LEN)
        return b64(byteArrayOf(fmt.wrapVersion.toByte()) + ephPub + nonce +
            gcmSeal(key, nonce, vaultKey, wrapContext(vaultId, member, fmt)))
    }

    fun wrapFormat(wrapped: String): Format {
        val raw = unb64(wrapped, "shared vault key")
        return listOf(NOW, OLD).firstOrNull { raw.isNotEmpty() && raw[0].toInt() == it.wrapVersion } ?: OLD
    }

    fun unwrapKey(keyset: Map<String, String>, wrapped: String, vaultId: ByteArray, member: String): ByteArray {
        val raw = unb64(wrapped, "shared vault key")
        val fmt = listOf(NOW, OLD).firstOrNull { raw.isNotEmpty() && raw[0].toInt() == it.wrapVersion }
        if (raw.size != 1 + 32 + NONCE_LEN + KEY_LEN + TAG_LEN || fmt == null) {
            throw CryptoError("A shared vault key is damaged or from a newer SecretSpace.")
        }
        val ephPub = raw.copyOfRange(1, 33)
        val nonce = raw.copyOfRange(33, 33 + NONCE_LEN)
        val ct = raw.copyOfRange(33 + NONCE_LEN, raw.size)
        val private = unb64(keyset["x25519_private"], "account key")
        val key = hkdf(x25519(private, ephPub), ephPub + publicKeyOf(keyset), fmt.label("wrap key"))
        return gcmOpen(key, nonce, ct, wrapContext(vaultId, member, fmt))
            ?: throw CryptoError("This shared vault key was not made for this account and vault.")
    }

    /** Six groups of four digits, read aloud before the first share. */
    fun fingerprint(publicKey: ByteArray): String {
        val digest = sha256(NOW.label("fingerprint"), publicKey)
        val digits = BigInteger(1, digest.copyOf(12)).mod(BigInteger.TEN.pow(24))
            .toString().padStart(24, '0')
        return digits.chunked(4).joinToString(" ")
    }

    fun accountFingerprint(publicKey: ByteArray, signingKey: ByteArray): String =
        fingerprint(publicKey + signingKey)

    // ---------------------------------------------------------------- signing
    private fun signingPrivate(keyset: Map<String, String>) =
        Ed25519PrivateKeyParameters(unb64(completeKeyset(keyset)["ed25519_seed"], "signing key"), 0)

    fun signingKeyOf(keyset: Map<String, String>): ByteArray = signingPrivate(keyset).generatePublicKey().encoded

    fun sign(keyset: Map<String, String>, message: ByteArray): String = Ed25519Signer().run {
        init(true, signingPrivate(keyset)); update(message, 0, message.size); b64(generateSignature())
    }

    /** Never throws. */
    fun verify(signingKey: ByteArray, message: ByteArray, signature: String): Boolean = try {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(signingKey, 0))
            update(message, 0, message.size)
            verifySignature(Base64.getDecoder().decode(signature))
        }
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- vaults
    const val VAULT_VERSION = 1
    private const val VAULT_HEADER_LEN = 25      // >4sB16sI

    class VaultHeader(val vaultId: ByteArray, val keyGeneration: Long, val format: Int)

    fun newVaultId(): ByteArray = randomBytes(VAULT_ID_LEN)
    fun newVaultKey(): ByteArray = randomBytes(KEY_LEN)

    fun encryptVault(vaultKey: ByteArray, vaultId: ByteArray, keyGeneration: Long,
                     data: JsonElement, fmt: Format = NOW): String {
        val header = ByteBuffer.allocate(VAULT_HEADER_LEN).put(fmt.vaultMagic.toByteArray(Charsets.US_ASCII))
            .put(VAULT_VERSION.toByte()).put(vaultId).putInt(keyGeneration.toInt()).array()
        val nonce = randomBytes(NONCE_LEN)
        return b64(header + nonce + gcmSeal(vaultKey, nonce, data.toString().toByteArray(Charsets.UTF_8), header))
    }

    fun vaultHeader(blob: String): VaultHeader {
        val raw = unb64(blob, "vault")
        if (raw.size < VAULT_HEADER_LEN + NONCE_LEN + TAG_LEN) {
            throw CryptoError("The vault file is too short to be a SecretSpace vault.")
        }
        val buf = ByteBuffer.wrap(raw, 0, VAULT_HEADER_LEN)
        val magic = String(ByteArray(4).also { buf.get(it) }, Charsets.ISO_8859_1)
        val version = buf.get().toInt() and 0xFF
        val vaultId = ByteArray(VAULT_ID_LEN).also { buf.get(it) }
        val generation = buf.int.toLong() and 0xFFFFFFFFL
        val fmt = listOf(NOW, OLD).firstOrNull { it.vaultMagic == magic }
            ?: throw CryptoError("That is not a SecretSpace vault.")
        if (version != VAULT_VERSION) throw CryptoError(
            "The vault was written by a newer SecretSpace; update this one.",
        )
        return VaultHeader(vaultId, generation, fmt.number)
    }

    fun decryptVault(vaultKey: ByteArray, blob: String, expectVaultId: ByteArray? = null): JsonElement {
        val head = vaultHeader(blob)
        if (expectVaultId != null && !MessageDigest.isEqual(head.vaultId, expectVaultId)) {
            throw CryptoError("That file belongs to a different vault.")
        }
        val raw = unb64(blob, "vault")
        val header = raw.copyOfRange(0, VAULT_HEADER_LEN)
        val nonce = raw.copyOfRange(VAULT_HEADER_LEN, VAULT_HEADER_LEN + NONCE_LEN)
        val body = gcmOpen(vaultKey, nonce, raw.copyOfRange(VAULT_HEADER_LEN + NONCE_LEN, raw.size), header)
            ?: throw CryptoError("The vault could not be decrypted: wrong key, or the file is damaged.")
        return Json.parseToJsonElement(body.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- vault names
    const val VAULT_NAME_CHARS = 60
    const val VAULT_NAME_BYTES = 200

    private fun nameKey(vaultKey: ByteArray, vaultId: ByteArray, fmt: Format) =
        hkdf(vaultKey, vaultId, fmt.label("vault name"))

    fun sealVaultName(vaultKey: ByteArray, vaultId: ByteArray, name: String, fmt: Format = NOW): String {
        val raw = name.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty() || raw.size > VAULT_NAME_BYTES) throw CryptoError("A vault name must be 1 to 200 bytes long.")
        val nonce = randomBytes(NONCE_LEN)
        return b64(nonce + gcmSeal(nameKey(vaultKey, vaultId, fmt), nonce, raw, vaultId))
    }

    fun openVaultName(vaultKey: ByteArray, vaultId: ByteArray, sealed: String): String {
        val raw = unb64(sealed, "vault name")
        if (raw.size < NONCE_LEN + 1 + TAG_LEN) throw CryptoError("The vault's name is damaged.")
        for (fmt in listOf(NOW, OLD)) {
            gcmOpen(nameKey(vaultKey, vaultId, fmt), raw.copyOf(NONCE_LEN),
                raw.copyOfRange(NONCE_LEN, raw.size), vaultId)?.let { return it.toString(Charsets.UTF_8) }
        }
        throw CryptoError("The vault's name could not be read: wrong key, or it is damaged.")
    }

    // ---------------------------------------------------------------- recovery phrase
    const val PHRASE_WORDS = 12
    val WORDS: List<String> by lazy {
        AccountCrypto::class.java.getResourceAsStream("/secretspace/core/bip39-english.txt")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
    }
    private val INDEX: Map<String, Int> by lazy { WORDS.withIndex().associate { it.value to it.index } }

    fun entropyToPhrase(entropy: ByteArray): String {
        require(entropy.size == 16) { "a 12-word phrase holds 16 bytes" }
        val bits = BigInteger(1, entropy).shiftLeft(4)
            .or(BigInteger.valueOf(((sha256(entropy)[0].toInt() and 0xFF) shr 4).toLong()))
        return (0 until PHRASE_WORDS).joinToString(" ") { i ->
            WORDS[bits.shiftRight(11 * (PHRASE_WORDS - 1 - i)).toInt() and 0x7FF]
        }
    }

    fun newRecoveryPhrase(): String = entropyToPhrase(randomBytes(16))

    fun phraseToEntropy(phrase: String?): ByteArray {
        val words = (phrase ?: "").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size != PHRASE_WORDS) throw CryptoError(
            "The recovery phrase has $PHRASE_WORDS words; ${words.size} were entered.",
        )
        val unknown = words.withIndex().filter { it.value !in INDEX }
            .map { "${it.index + 1}. “${it.value}”" }
        if (unknown.isNotEmpty()) throw CryptoError(
            "Not in the word list: " + unknown.joinToString(", ") +
                ". Check the spelling against your paper.",
        )
        var bits = BigInteger.ZERO
        for (w in words) bits = bits.shiftLeft(11).or(BigInteger.valueOf(INDEX.getValue(w).toLong()))
        val raw = bits.shiftRight(4).toByteArray()
        val entropy = ByteArray(16)
        val tail = raw.takeLast(16).toByteArray()
        tail.copyInto(entropy, 16 - tail.size)
        if (((sha256(entropy)[0].toInt() and 0xFF) shr 4) != bits.toInt() and 0xF) throw CryptoError(
            "Those words are all in the list, but not in this order: two may be swapped or one replaced.",
        )
        return entropy
    }

    fun recoveryKey(phrase: String, username: String, fmt: Format = NOW): ByteArray =
        hkdf(phraseToEntropy(phrase), fmt.label("recovery"), fmt.label("recovery key|") + nfkc(username))

    // ---------------------------------------------------------------- one account
    class Unlocked(val keyset: Map<String, String>, val loginKey: ByteArray)

    /** The keyset and the login key of its format. */
    fun unlockAccount(username: String, masterPassword: String, secretKey: ByteArray,
                      sealedKeyset: String): Unlocked {
        val head = keysetHeader(sealedKeyset)
        if (head.kdf != KDF_ARGON2ID) throw CryptoError("That is a recovery copy, not the account file.")
        val (auk, login) = deriveAccountKeys(masterPassword, secretKey, username, head.salt,
            head.passes, head.memoryKib, head.lanes, formatOf(head.format))
        return Unlocked(openKeyset(auk, sealedKeyset), login)
    }

    fun sealRecovery(phrase: String, username: String, keyset: Map<String, String>): String =
        sealKeyset(recoveryKey(phrase, username), keyset, ByteArray(SALT_LEN), KDF_NONE, 0, 0, 0)

    fun recoveryProof(keyset: Map<String, String>): ByteArray =
        unb64(completeKeyset(keyset)["recovery_proof"], "recovery proof")

    fun recoverAccount(username: String, phrase: String, sealedRecovery: String): Map<String, String> {
        val head = keysetHeader(sealedRecovery)
        if (head.kdf != KDF_NONE) throw CryptoError("That is the account file, not its recovery copy.")
        return try {
            openKeyset(recoveryKey(phrase, username, formatOf(head.format)), sealedRecovery)
        } catch (e: CryptoError) {
            if (e.message?.contains("Wrong master password") == true) {
                throw CryptoError("That recovery phrase is not the one for this account.")
            }
            throw e
        }
    }

    /** The same keyset under a new password, with a fresh salt: (sealed, login key). */
    fun resealWithNewPassword(username: String, keyset: Map<String, String>, newPassword: String,
                              secretKey: ByteArray, passes: Int = ARGON2_PASSES,
                              memoryKib: Int = ARGON2_MEMORY_KIB,
                              lanes: Int = ARGON2_LANES): Pair<String, ByteArray> {
        val salt = randomBytes(SALT_LEN)
        val (auk, login) = deriveAccountKeys(newPassword, secretKey, username, salt, passes, memoryKib, lanes)
        return Pair(sealKeyset(auk, keyset, salt, KDF_ARGON2ID, passes, memoryKib, lanes), login)
    }
}
