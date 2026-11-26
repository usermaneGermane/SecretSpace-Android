package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Signing in to a real server, with accounts the PC client made
 * (tools/lab_server.py starts both and sets SECRETSPACE_LAB). Without it
 * these are skipped -- except in CI, which sets SECRETSPACE_LAB_REQUIRED so
 * a lab that did not start fails instead of passing quietly.
 */
class SignInTest {
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }

    private val lab: JsonObject by lazy {
        Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject
    }

    private fun s(key: String) = lab[key]!!.jsonPrimitive.content
    private val cheap = PasswordSecretStore(1, 64, 1)
    private fun freshStore() = AccountStore(Files.createTempDirectory("phone").toFile(), cheap)

    private fun needLab() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") {
            assertNotNull(labFile, "SECRETSPACE_LAB is not set: the lab server did not start")
        }
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
    }

    private fun signIn(store: AccountStore, password: String = s("password"), kit: String? = s("secret_key_text"),
                       ca: String = s("ca_file")) =
        AccountSync.signIn(store, s("url"), ca, s("username"), password, kit)

    @Test fun aNewPhoneSignsInAndHasEverythingThePcHas() {
        needLab()
        val store = AccountStore(File(s("phone_home")), cheap)
        val signed = signIn(store)
        val account = signed.account
        val vaults = lab["vaults"]!!.jsonObject
        val names = account.vaultIds().associateBy { account.vaultFile(it).name }
        assertEquals(vaults.keys, names.keys)
        for ((name, data) in vaults) assertEquals(data, account.vaultFile(names[name]).load(), name)
        assertEquals(vaults["Personal"], signed.personal)
        assertEquals(listOf("From Bob", "Team"), signed.changes.added.sorted())
        assertEquals("reader", account.vaultRole(names["From Bob"]))
        assertFalse(account.canWrite(names["From Bob"]))
        assertEquals("owner", account.vaultRole(names["Team"]))
        assertEquals("bob", account.vaultRecord(names["From Bob"]!!)["key_signer"]!!.jsonPrimitive.content)
        val bob = assertNotNull(account.contact("bob"))
        assertFalse(bob.verified)                       // seen, not compared by the user
        assertEquals(s("bob_fingerprint"), AccountCrypto.accountFingerprint(bob.publicKey, bob.signingKey))
        for (id in account.vaultIds()) assertTrue(account.vaultFile(id).basePath.exists())

        // Next time the password alone opens it: the Secret Key is kept here.
        val again = store.unlock(s("username"), s("password"))
        assertEquals(vaults["Personal"], again.loadVault())
        assertFailsWith<CryptoError> { store.unlock(s("username"), s("password") + "x") }

        // Signing in again on this phone needs no kit and keeps its files.
        val twice = signIn(store, kit = null)
        assertTrue(twice.changes.added.isEmpty())
        assertEquals(null, twice.personal)

        // A vault made on the phone goes onto the server with its first entry.
        val made = again.createVault("Made on the phone")
        val data = JsonObject(mapOf("accounts" to JsonArray(listOf(JsonObject(mapOf(
            "id" to JsonPrimitive("phone-1"), "site" to JsonPrimitive("Phone ✓"),
            "username" to JsonPrimitive("me"), "password" to JsonPrimitive("fr0m-the-phone"),
        ))))))
        made.save(data)
        val server = AccountSync.loggedIn(again)
        try {
            assertEquals(listOf("Made on the phone"), AccountSync.reconcileVaults(again, server).uploaded)
            val revision = server.putVault(made.hex, made.encryptForServer(data), 0)
            made.saveBase(data, baseOrigin(server.url, revision))
        } finally {
            server.logout()
        }
        File(s("phone_made")).writeText(JsonObject(mapOf(
            "name" to JsonPrimitive(made.name), "data" to data)).toString(), Charsets.UTF_8)
    }

    @Test fun aServerThatGoesBackToAnOlderKeyOrSwapsOneIsRefused() {
        needLab()
        val account = signIn(freshStore()).account
        val team = account.vaultIds().first { account.vaultFile(it).name == "Team" }
        val record = account.vaultRecord(team)
        fun reconcile() {
            val server = AccountSync.loggedIn(account)
            try {
                AccountSync.reconcileVaults(account, server)
            } finally {
                server.logout()
            }
        }
        reconcile()                                     // as it is: nothing to say

        // This phone holds generation 5; the server's 0 can only be a lie or an old backup.
        account.markVault(team, "key_generation" to JsonPrimitive(5))
        val older = assertFailsWith<KeyNotTrusted> { reconcile() }
        assertContains(older.message!!, "older key")

        // The same generation with another key: nothing honest does that.
        val other = AccountCrypto.wrapKey(AccountCrypto.publicKeyOf(account.keyset), AccountCrypto.newVaultKey(),
            team.fromHex(), account.username)
        account.markVault(team, "key_generation" to record["key_generation"]!!, "wrapped_key" to JsonPrimitive(other))
        val swapped = assertFailsWith<KeyNotTrusted> { reconcile() }
        assertContains(swapped.message!!, "not the one this phone holds")
    }

    @Test fun aWrongPasswordOrKitIsTheSameAnswer() {
        needLab()
        val e = assertFailsWith<LoginFailed> { signIn(freshStore(), password = "not it") }
        assertEquals("Wrong username, master password or Secret Key.", e.message)
        val other = AccountCrypto.formatSecretKey(AccountCrypto.newSecretKey())
        assertFailsWith<LoginFailed> { signIn(freshStore(), kit = other) }
    }

    @Test fun aNewPhoneAsksForTheKit() {
        needLab()
        val store = freshStore()
        assertFailsWith<SecretKeyNeeded> { signIn(store, kit = null) }
        assertTrue(store.listAccounts().isEmpty())
    }

    @Test fun anotherCertificateIsRefused() {
        needLab()
        val e = assertFailsWith<SyncError> { signIn(freshStore(), ca = s("impostor_ca_file")) }
        assertContains(e.message!!, "certificate was rejected")
    }

    @Test fun nothingAnswersIsSaidSo() {
        assertFailsWith<ServerUnreachable> {
            AccountSync.signIn(freshStore(), "http://127.0.0.1:9", "", "alice", "x", null)
        }
    }
}
