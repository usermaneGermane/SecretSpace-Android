package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import secretspace.core.AccountCrypto as ac

/** A new master password, recovery with the 12 words, a new phrase, and the delay after wrong passwords. */
class RecoveryTest {
    private class Local(val store: AccountStore, val phrase: String, val open: OpenAccount)

    /** An account on this phone only, with a phrase the test knows. */
    private fun local(): Local {
        val store = AccountStore(Files.createTempDirectory("phone").toFile(), PasswordSecretStore(1, 64, 1))
        val keyset = ac.newKeyset()
        val secret = ac.newSecretKey()
        val salt = ac.randomBytes(16)
        val phrase = ac.newRecoveryPhrase()
        val (auk, login) = ac.deriveAccountKeys("pw", secret, "dana", salt, 1, 64, 1)
        val me = JsonObject(mapOf(
            "personal_vault" to JsonPrimitive(ac.newVaultId().toHex()),
            "public_key" to JsonPrimitive(ac.b64(ac.publicKeyOf(keyset))),
            "keyset" to JsonPrimitive(ac.sealKeyset(auk, keyset, salt, ac.KDF_ARGON2ID, 1, 64, 1)),
            "recovery" to JsonPrimitive(ac.sealRecovery(phrase, "dana", keyset)),
        ))
        val account = store.adoptFromServer("dana", me, keyset, secret, login, "pw", JsonObject(emptyMap()))
        account.saveVault(Entries.emptyVault())
        val open = OpenAccount(store.unlock("dana", "pw")).also { it.load() }
        open.add(open.account.personalId, "Bank", "me", "b4nk")
        return Local(store, phrase, open)
    }

    private fun sites(account: Account) = Entries.accountsOf(account.loadVault()).map { it["site"] }

    @Test fun aNewPasswordOnThePhone() {
        val l = local()
        AccountSync.changePassword(l.open.account, "pw-2")
        assertFailsWith<CryptoError> { l.store.unlock("dana", "pw") }
        assertEquals(listOf("Bank"), sites(l.store.unlock("dana", "pw-2")))
        assertTrue(l.open.account.checkPassword("pw-2"))
        assertFailsWith<IllegalArgumentException> { AccountSync.changePassword(l.open.account, "") }
    }

    @Test fun theWordsOpenAnAccountWhoseMasterPasswordIsForgotten() {
        val l = local()
        val oldKit = l.open.account.secretKeyText
        assertFailsWith<CryptoError> { Recovery.openWithPhrase(l.store, "dana", "abandon " + l.phrase.substringAfter(" ")) }
        assertFailsWith<IllegalArgumentException> { Recovery.openWithPhrase(l.store, "  ", l.phrase) }
        assertFailsWith<IllegalArgumentException> { Recovery.openWithPhrase(l.store, "nobody", l.phrase) }

        val opened = Recovery.openWithPhrase(l.store, " dana ", l.phrase)
        assertTrue(opened.local)
        assertNull(opened.server)
        val pending = Recovery.prepare(opened, "new-pw", 1, 64, 1)
        assertNotEquals(l.phrase, pending.recoveryPhrase)
        assertNotEquals(oldKit, pending.secretKeyText, "the old Secret Key is sealed under the forgotten password")
        assertFailsWith<CryptoError> { l.store.unlock("dana", "new-pw") }       // nothing written yet

        val account = pending.save(l.store)
        assertEquals(listOf("Bank"), sites(account), "the same keys: the vaults open as they were")
        assertEquals(listOf("Bank"), sites(l.store.unlock("dana", "new-pw")))
        assertFailsWith<CryptoError> { l.store.unlock("dana", "pw") }
        assertFailsWith<CryptoError> { Recovery.openWithPhrase(l.store, "dana", l.phrase) }   // the old words open nothing
        Recovery.openWithPhrase(l.store, "dana", pending.recoveryPhrase)

        val fresh = Recovery.NewPhrase(account)
        fresh.save()
        assertFailsWith<CryptoError> { Recovery.openWithPhrase(l.store, "dana", pending.recoveryPhrase) }
        Recovery.openWithPhrase(l.store, "dana", fresh.recoveryPhrase)
    }

    @Test fun wrongPasswordsCostTime() {
        var now = 1_000L
        val lock = Lockout(File(Files.createTempDirectory("lock").toFile(), "unlock_failures.json")) { now }
        assertEquals(listOf(0, 0, 0, 5, 15, 60, 300, 300), (1..8).map { Lockout.penaltyFor(it) })
        repeat(3) { assertEquals(0, lock.recordFailure("dana")) }
        assertEquals(0, lock.remaining("dana"))
        assertEquals(5, lock.recordFailure("dana"))
        assertEquals(5, lock.remaining("dana"))
        now += 3
        assertEquals(2, lock.remaining("dana"))
        assertEquals(0, lock.remaining("someone else"))
        assertEquals(15, lock.recordFailure("dana"))
        lock.reset("dana")
        assertEquals(0, lock.remaining("dana"))
        assertEquals(0, lock.failures("dana"))
    }

    // ------------------------------------------------------------------ with the lab server
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }
    private val lab by lazy { Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject }

    private fun pc(action: String, body: Map<String, Any?> = emptyMap()): Obj {
        val c = URI(lab["control"]!!.jsonPrimitive.content + "/" + action).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.readTimeout = 180_000
        c.outputStream.use { it.write(Py.dumps(body).toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val reply = Py.asObj(Py.native(Json.parseToJsonElement(text)))!!
        if (status != 200) fail("the PC could not $action: ${reply["error"]}\n${reply["trace"]}")
        return reply
    }

    /** A PC that does not have gus signs in with these, and opens his account with the phrase. */
    private fun pcSignIn(password: String, kit: String, phrase: String): Obj =
        pc("pc_sign_in", mapOf("who" to "gus", "password" to password, "secret_key_text" to kit, "phrase" to phrase))

    private fun pcSites(reply: Obj) = Entries.accountsOf(Py.asObj(Py.asObj(Py.asObj(reply["vaults"])!!["Personal"])!!["data"])!!)
        .map { it["site"] }.toSet()

    @Test fun onAServerTheServerIsChangedFirst() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val gus = lab["gus"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val url = lab["url"]!!.jsonPrimitive.content
        val ca = lab["ca_file"]!!.jsonPrimitive.content
        val store = AccountStore(Files.createTempDirectory("gus-phone").toFile(), PasswordSecretStore())
        val account = AccountSync.signIn(store, url, ca, "gus", gus.getValue("password"),
            gus.getValue("secret_key_text")).account
        val open = OpenAccount(account).also { it.load() }
        open.add(account.personalId, "Boat", "me", "b0at")
        open.sync { _, r -> fail("no conflicts expected: ${r.conflicts}") }

        // a new master password: the server has it, so a PC signs in with it (same kit, same phrase)
        AccountSync.changePassword(account, "gus's new pass")
        open.sync { _, r -> fail("no conflicts expected: ${r.conflicts}") }        // the new login key works
        var onPc = pcSignIn("gus's new pass", gus.getValue("secret_key_text"), gus.getValue("phrase"))
        assertEquals(setOf("Bike", "Boat"), pcSites(onPc))
        assertEquals(true, onPc["phrase_opens_it"])

        // a phone without the account recovers it from the server with the words
        val other = AccountStore(Files.createTempDirectory("gus-new-phone").toFile(), PasswordSecretStore())
        assertFailsWith<IllegalArgumentException> { Recovery.openWithPhrase(other, "gus", gus.getValue("phrase")) }
        val opened = Recovery.openWithPhrase(other, "gus", gus.getValue("phrase"), url, ca)
        assertFalse(opened.local)
        val pending = Recovery.prepare(opened, "gus's third pass")
        val recovered = pending.save(other)
        assertEquals(setOf("Bike", "Boat"), Entries.accountsOf(recovered.loadVault()).map { it["site"] }.toSet())
        onPc = pcSignIn("gus's third pass", pending.secretKeyText, pending.recoveryPhrase)
        assertEquals(setOf("Bike", "Boat"), pcSites(onPc))
        assertEquals(true, onPc["phrase_opens_it"])
        assertFailsWith<CryptoError> {
            Recovery.openWithPhrase(AccountStore(Files.createTempDirectory("x").toFile()), "gus",
                gus.getValue("phrase"), url, ca)
        }

        // the first phone: its login no longer works; the words it has are old, but the server has the new copy
        assertFailsWith<SyncError> { open.sync { _, _ -> null } }
        Recovery.openWithPhrase(store, "gus", pending.recoveryPhrase)

        // a new phrase on the recovered phone reaches the server
        val fresh = Recovery.NewPhrase(recovered)
        fresh.save()
        onPc = pcSignIn("gus's third pass", pending.secretKeyText, fresh.recoveryPhrase)
        assertEquals(true, onPc["phrase_opens_it"])
    }
}
