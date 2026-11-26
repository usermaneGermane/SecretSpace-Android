package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import secretspace.core.AccountCrypto as ac

/** Whose vault key to take, and the Secret Key kept on the device -- against the PC's own output. */
class KeyTrustTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/vectors.json")!!.readText(Charsets.UTF_8),
    ).jsonObject

    private fun list(name: String) = v[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private val owner: Map<String, String> =
        list("keysets")[0]["keyset"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    /** alex, open, on a phone of its own (no server: these checks need none). */
    private fun alex(): Account {
        val store = AccountStore(Files.createTempDirectory("phone").toFile(), PasswordSecretStore(1, 64, 1))
        val info = JsonObject(mapOf("format" to JsonPrimitive(2), "personal_vault" to JsonPrimitive("00".repeat(16))))
        store.accountDir("alex").mkdirs()
        store.writeInfo("alex", info)
        return Account(store, "alex", info, owner, ByteArray(16), null)
    }

    private val noServer = AccountServer("http://127.0.0.1:9")

    @Test fun theSignedMessageIsThePcsByteForByte() {
        for (c in list("key_messages")) {
            val made = AccountSync.keyMessage(c.s("vault_hex"), c["generation"]!!.jsonPrimitive.long,
                c.s("member"), c.s("wrapped"), c.s("signer"))
            assertContentEquals(ac.unb64(c.s("message"), "message"), made, c.s("member"))
            assertTrue(ac.verify(ac.signingKeyOf(owner), made, c.s("signature")))
        }
    }

    private fun offered(member: String = "alex", generation: Int = 0, signer: String = "alex",
                        sig: String? = null, wrapped: String = list("vaults")[0].s("wrapped")): JsonObject {
        val vault = list("vaults")[0].s("vault_id").let { ac.unb64(it, "id").toHex() }
        val signature = sig ?: ac.sign(owner, AccountSync.keyMessage(vault, generation.toLong(), member, wrapped, signer))
        return JsonObject(mapOf("id" to JsonPrimitive(vault), "key_generation" to JsonPrimitive(generation),
            "wrapped_key" to JsonPrimitive(wrapped), "key_signer" to JsonPrimitive(signer),
            "key_sig" to JsonPrimitive(signature)))
    }

    @Test fun aKeySignedByThisAccountIsTaken() {
        assertEquals("alex", AccountSync.checkKey(alex(), noServer, offered(), "Team"))
    }

    @Test fun aKeyWithoutASignatureIsRefused() {
        val e = assertFailsWith<KeyNotTrusted> { AccountSync.checkKey(alex(), noServer, offered(sig = ""), "Team") }
        assertContains(e.message!!, "has no signature")
    }

    @Test fun aKeyTheSignatureDoesNotCoverIsRefused() {
        val good = offered()
        val swapped = JsonObject(good + ("wrapped_key" to JsonPrimitive(list("vaults")[1].s("wrapped"))))
        val e = assertFailsWith<KeyNotTrusted> { AccountSync.checkKey(alex(), noServer, swapped, "Team") }
        assertContains(e.message!!, "is not signed by")
        val otherGeneration = JsonObject(good + ("key_generation" to JsonPrimitive(1)))
        assertFailsWith<KeyNotTrusted> { AccountSync.checkKey(alex(), noServer, otherGeneration, "Team") }
    }

    @Test fun aNewKeyFromSomeoneNeverCheckedIsRefusedForAVaultHeldHere() {
        val account = alex()
        // maria's keys are alex's own here, so her signature checks out -- only trust is missing.
        account.rememberContact("maria", ac.publicKeyOf(owner), ac.signingKeyOf(owner), false)
        val fromMaria = offered(generation = 3, signer = "maria")
        val e = assertFailsWith<KeyNotTrusted> {
            AccountSync.checkKey(account, noServer, fromMaria, "Team", heldFrom = "alex")
        }
        assertContains(e.message!!, "never checked")
        // the one who signed the key held now may change it ...
        assertEquals("maria", AccountSync.checkKey(account, noServer, fromMaria, "Team", heldFrom = "maria"))
        // ... and so may anyone whose fingerprint the user compared
        account.rememberContact("maria", ac.publicKeyOf(owner), ac.signingKeyOf(owner), true)
        assertEquals("maria", AccountSync.checkKey(account, noServer, fromMaria, "Team", heldFrom = "alex"))
        // for a vault new to this phone, a known signer is enough
        account.rememberContact("maria", ac.publicKeyOf(owner), ac.signingKeyOf(owner), false)
        assertEquals("maria", AccountSync.checkKey(account, noServer, fromMaria, "Team"))
    }

    @Test fun aSecretKeyKeptByThePcOpensHere() {
        val store = PasswordSecretStore()
        for (c in list("secret_store")) {
            val record = c["record"]!!.jsonObject
            assertContentEquals(ac.unb64(c.s("secret"), "s"), store.unprotect(record, c.s("username"), c.s("password")))
            val e = assertFailsWith<CryptoError> { store.unprotect(record, c.s("username"), c.s("password") + "x") }
            assertEquals("Wrong master password or Secret Key.", e.message)
            // bound to its account: another name does not open it
            assertFailsWith<CryptoError> { store.unprotect(record, "someone", c.s("password")) }
        }
    }

    @Test fun aSecretKeyKeptHereOpensHereAndOnlyWithItsPassword() {
        val store = PasswordSecretStore(1, 64, 1)
        val secret = ac.newSecretKey()
        val record = store.protect(secret, "Mária", "ﬁancé Ω")
        assertEquals(1, record["passes"]!!.jsonPrimitive.int)
        assertContentEquals(secret, store.unprotect(record, "Mária", "fiancé Ω"))   // NFKC, as on the PC
        assertFailsWith<SecretKeyUnavailable> { store.unprotect(null, "Mária", "x") }
        assertFailsWith<SecretKeyUnavailable> {
            store.unprotect(JsonObject(mapOf("scheme" to JsonPrimitive("dpapi"))), "Mária", "x")
        }
    }
}
