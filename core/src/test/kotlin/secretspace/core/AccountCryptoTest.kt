package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import secretspace.core.AccountCrypto as ac
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every expected value here was made by the PC client's own code (tools/make_vectors.py). */
class AccountCryptoTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/vectors.json")!!.readText(Charsets.UTF_8),
    ).jsonObject

    private fun list(name: String): List<JsonObject> = v[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String) = this[key]!!.jsonPrimitive.int
    private fun JsonObject.b(key: String) = ac.unb64(s(key), key)
    private fun JsonObject.map(key: String) = this[key]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test fun argon2idMatches() {
        for (c in list("argon2id")) {
            assertContentEquals(c.b("out"), ac.argon2id(c.b("secret"), c.b("salt"), c.i("passes"),
                c.i("memory_kib"), c.i("lanes")))
        }
    }

    @Test fun secretKeysAreSpelledAlike() {
        for (c in list("secret_keys")) {
            assertEquals(c.s("text"), ac.formatSecretKey(c.b("raw")))
            assertContentEquals(c.b("raw"), ac.parseSecretKey(c.s("text")))
        }
        for (c in list("secret_key_spellings")) assertContentEquals(c.b("raw"), ac.parseSecretKey(c.s("text")))
    }

    @Test fun aWrongSecretKeyIsExplainedAlike() {
        for (c in list("secret_key_errors")) {
            val e = assertFailsWith<CryptoError> { ac.parseSecretKey(c.s("text")) }
            assertEquals(c.s("error"), e.message, c.s("text"))
        }
    }

    @Test fun accountKeysMatchInBothFormats() {
        for (c in list("account_keys")) {
            val (auk, login) = ac.deriveAccountKeys(c.s("password"), c.b("secret_key"), c.s("username"),
                c.b("salt"), c.i("passes"), c.i("memory_kib"), c.i("lanes"), ac.formatOf(c.i("format")))
            assertContentEquals(c.b("auk"), auk)
            assertContentEquals(c.b("login_key"), login)
        }
    }

    @Test fun aKeysetFromThePcOpens() {
        for (c in list("keysets")) {
            val opened = ac.unlockAccount(c.s("username"), c.s("password"), c.b("secret_key"), c.s("sealed"))
            assertEquals(c.map("keyset"), opened.keyset)
            assertContentEquals(c.b("login_key"), opened.loginKey)
            assertContentEquals(c.b("public_key"), ac.publicKeyOf(opened.keyset))
            assertContentEquals(c.b("signing_key"), ac.signingKeyOf(opened.keyset))
            assertEquals(c.s("fingerprint"), ac.accountFingerprint(ac.publicKeyOf(opened.keyset),
                ac.signingKeyOf(opened.keyset)))
            assertEquals(c.s("signature_of_hello"), ac.sign(opened.keyset, "hello".toByteArray()))
            assertTrue(ac.verify(c.b("signing_key"), "hello".toByteArray(), c.s("signature_of_hello")))
            assertFalse(ac.verify(c.b("signing_key"), "hellO".toByteArray(), c.s("signature_of_hello")))
            assertContentEquals(c.b("recovery_proof"), ac.recoveryProof(opened.keyset))
            val e = assertFailsWith<CryptoError> {
                ac.unlockAccount(c.s("username"), c.s("password") + "x", c.b("secret_key"), c.s("sealed"))
            }
            assertEquals("Wrong master password or Secret Key.", e.message)
        }
    }

    @Test fun aKeysetSealedHereOpensWithTheSamePasswordAndIsTheCurrentFormat() {
        val c = list("keysets")[0]
        val keyset = c.map("keyset")
        val (sealed, login) = ac.resealWithNewPassword("alex", keyset, "new-pass", c.b("secret_key"), 1, 64, 1)
        assertEquals(2, ac.keysetHeader(sealed).format)
        val opened = ac.unlockAccount("alex", "new-pass", c.b("secret_key"), sealed)
        assertEquals(keyset, opened.keyset)
        assertContentEquals(login, opened.loginKey)
    }

    @Test fun anOldKeysetDerivesItsMissingSecretsAsThePcDoes() {
        val c = v["completed_keyset"]!!.jsonObject
        assertEquals(c.map("out"), ac.completeKeyset(c.map("in")))
    }

    @Test fun phrasesMatch() {
        for (c in list("phrases")) {
            assertEquals(c.s("phrase"), ac.entropyToPhrase(c.b("entropy")))
            assertContentEquals(c.b("entropy"), ac.phraseToEntropy(c.s("phrase").uppercase()))
            assertContentEquals(c.b("recovery_key"), ac.recoveryKey(c.s("phrase"), c.s("username")))
            assertContentEquals(c.b("recovery_key_old"), ac.recoveryKey(c.s("phrase"), c.s("username"), ac.OLD))
            assertEquals(c.map("keyset"), ac.recoverAccount(c.s("username"), c.s("phrase"), c.s("sealed_recovery")))
        }
        val other = list("phrases")[1].s("phrase")
        val e = assertFailsWith<CryptoError> {
            ac.recoverAccount("alex", other, list("phrases")[0].s("sealed_recovery"))
        }
        assertEquals("That recovery phrase is not the one for this account.", e.message)
    }

    @Test fun aWrongPhraseIsExplainedAlike() {
        for (c in list("phrase_errors")) {
            val e = assertFailsWith<CryptoError> { ac.phraseToEntropy(c.s("phrase")) }
            assertEquals(c.s("error"), e.message)
        }
    }

    @Test fun vaultsFromThePcOpenInBothFormats() {
        val owner = list("keysets")[0].map("keyset")
        for (c in list("vaults")) {
            val id = c.b("vault_id")
            assertEquals(c.i("format"), ac.wrapFormat(c.s("wrapped")).number)
            assertContentEquals(c.b("vault_key"), ac.unwrapKey(owner, c.s("wrapped"), id, c.s("member")))
            assertFailsWith<CryptoError> { ac.unwrapKey(owner, c.s("wrapped"), id, "maria") }
            val head = ac.vaultHeader(c.s("blob"))
            assertEquals(c.i("format"), head.format)
            assertEquals(c.i("key_generation").toLong(), head.keyGeneration)
            assertEquals(c["data"], ac.decryptVault(c.b("vault_key"), c.s("blob"), id))
            assertFailsWith<CryptoError> { ac.decryptVault(c.b("vault_key"), c.s("blob"), ByteArray(16)) }
            assertEquals(c.s("name"), ac.openVaultName(c.b("vault_key"), id, c.s("sealed_name")))
        }
    }

    @Test fun whatIsMadeHereOpensHere() {
        val owner = list("keysets")[0].map("keyset")
        val id = ac.newVaultId()
        val key = ac.newVaultKey()
        val wrapped = ac.wrapKey(ac.publicKeyOf(owner), key, id, "alex")
        assertContentEquals(key, ac.unwrapKey(owner, wrapped, id, "alex"))
        val data = list("vaults")[0]["data"]!!
        assertEquals(data, ac.decryptVault(key, ac.encryptVault(key, id, 3, data), id))
        assertEquals("Ünïcode ✓", ac.openVaultName(key, id, ac.sealVaultName(key, id, "Ünïcode ✓")))
        val phrase = ac.newRecoveryPhrase()
        assertEquals(owner, ac.recoverAccount("alex", phrase, ac.sealRecovery(phrase, "alex", owner)))
    }

    @Test fun damagedFilesAreNamed() {
        assertFailsWith<CryptoError> { ac.keysetHeader("not base64!") }
        assertFailsWith<CryptoError> { ac.keysetHeader(ac.b64(ByteArray(80))) }
        assertFailsWith<CryptoError> { ac.vaultHeader(ac.b64(ByteArray(80))) }
        val sealed = list("keysets")[0].s("sealed")
        val raw = ac.unb64(sealed, "x").also { it[6] = 0x7F }           // memory: far too much
        assertFailsWith<CryptoError> { ac.keysetHeader(ac.b64(raw)) }
    }

    @Test fun theVectorsCoverWhatTheyShould() {
        assertEquals(2, (v["vaults"] as JsonArray).size)
        assertTrue(list("argon2id").any { it.i("memory_kib") == ac.ARGON2_MEMORY_KIB })
    }
}
