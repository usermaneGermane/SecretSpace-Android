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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import secretspace.core.AccountCrypto as ac

/**
 * The Vaults screen: make, rename, delete, put back, leave -- on the phone
 * alone, and with dave's PC through a real server (tools/lab_server.py),
 * the PC's own code checking what arrives.
 */
class VaultsTest {
    /** An account on this phone only. */
    private fun local(): OpenAccount {
        val store = AccountStore(Files.createTempDirectory("phone").toFile(), PasswordSecretStore(1, 64, 1))
        val keyset = ac.newKeyset()
        val secret = ac.newSecretKey()
        val salt = ac.randomBytes(16)
        val (auk, login) = ac.deriveAccountKeys("pw", secret, "dana", salt, 1, 64, 1)
        val me = JsonObject(mapOf(
            "personal_vault" to JsonPrimitive(ac.newVaultId().toHex()),
            "public_key" to JsonPrimitive(ac.b64(ac.publicKeyOf(keyset))),
            "keyset" to JsonPrimitive(ac.sealKeyset(auk, keyset, salt, ac.KDF_ARGON2ID, 1, 64, 1)),
            "recovery" to JsonPrimitive(ac.sealRecovery(ac.newRecoveryPhrase(), "dana", keyset)),
        ))
        val account = store.adoptFromServer("dana", me, keyset, secret, login, "pw", JsonObject(emptyMap()))
        account.saveVault(Entries.emptyVault())
        return OpenAccount(store.unlock("dana", "pw")).also { it.load() }
    }

    private fun names(open: OpenAccount) = open.vaults().map { it.name }

    @Test fun onAPhoneOnlyAccountVaultsComeAndGo() {
        val open = local()
        val home = open.createVault("  Home  ")
        assertEquals(listOf("Personal", "Home"), names(open))
        assertEquals("On this phone." to false, open.vaultStatus(home))
        open.add(home, "Router", "admin", "r0uter")
        assertFailsWith<IllegalArgumentException> { open.createVault("home") }      // the PC's rule: no two alike
        assertFailsWith<IllegalArgumentException> { open.createVault("Personal") }
        open.renameVault(home, "House")
        assertFalse(open.account.vaultRecord(home)["rename_pending"].toString() == "true", "no server: nothing to tell")

        // a fresh unlock reads the same
        val again = OpenAccount(open.account).also { it.load() }
        assertEquals(listOf("Personal", "House"), names(again))
        assertEquals("Router", Entries.accountsOf(again.data.getValue(home)).single()["site"])

        open.deleteVault(home)
        assertEquals(listOf("Personal"), names(open))
        assertFalse(open.account.vaultFile(open.account.personalId).path.parentFile.listFiles()!!
            .any { it.name.startsWith(home) }, "its files are gone")
        assertFailsWith<IllegalArgumentException> { open.deleteVault(open.account.personalId) }
        assertFailsWith<IllegalArgumentException> { open.renameVault(open.account.personalId, "Mine") }
    }

    @Test fun allVaultsInOneList() {
        val open = local()
        val p = open.account.personalId
        val home = open.createVault("Home")
        open.change(p) { d -> Entries.accountsOf(d).add(linkedMapOf("id" to "1", "site" to "Bank", "username" to "me",
            "password" to "x", "updated_at" to "2026-01-01T00:00:00Z")) }
        open.change(home) { d -> Entries.accountsOf(d).add(linkedMapOf("id" to "1", "site" to "Alarm", "username" to "me",
            "password" to "y", "updated_at" to "2026-02-01T00:00:00Z")) }
        val shed = open.add(home, "Shed", "you", "z")
        open.archive(home, shed["id"] as String)
        // newest first across vaults; the same id in two vaults stays two entries, each with its vault
        assertEquals(listOf(home to "Alarm", p to "Bank"), open.allEntries().map { it.first to it.second["site"] })
        assertEquals(listOf(p to "Bank", home to "Alarm"), open.allEntries(order = "name_desc").map { it.first to it.second["site"] })
        assertEquals(listOf("Alarm"), open.allEntries("ALA").map { it.second["site"] })
        assertEquals(emptyList(), open.allEntries("Shed"), "archived entries are not in the list")
    }

    // ------------------------------------------------------------------ with the lab server
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }
    private val lab by lazy { Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject }

    private fun pc(action: String, body: Map<String, Any?> = emptyMap()): Obj {
        val c = URI(lab["control"]!!.jsonPrimitive.content + "/" + action).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.readTimeout = 120_000
        c.outputStream.use { it.write(Py.dumps(body).toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val reply = Py.asObj(Py.native(Json.parseToJsonElement(text)))!!
        if (status != 200) fail("the PC could not $action: ${reply["error"]}\n${reply["trace"]}")
        return reply
    }

    @Suppress("UNCHECKED_CAST")
    private fun pcVaults(who: String) = Py.asObj(pc("sync", mapOf("who" to who))["vaults"])!! as Map<String, Obj>
    private fun sitesIn(vault: Obj?) = Entries.accountsOf(Py.asObj(vault!!["data"])!!).map { it["site"] }
    private fun members(who: String, name: String): List<Any?>? = Py.asList(pc("members", mapOf("who" to who, "name" to name))["members"])

    private fun sync(open: OpenAccount): OpenAccount.SyncReport =
        open.sync { _, result -> fail("no conflicts expected: ${result.conflicts}") }

    private fun idOf(open: OpenAccount, name: String) = open.vaults().single { it.name == name }.id

    @Test fun thePhoneAndThePcAgreeOnTheVaults() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val dave = lab["dave"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val store = AccountStore(Files.createTempDirectory("dave-phone").toFile(), PasswordSecretStore())
        val signed = AccountSync.signIn(store, lab["url"]!!.jsonPrimitive.content, lab["ca_file"]!!.jsonPrimitive.content,
            "dave", dave.getValue("password"), dave.getValue("secret_key_text"))
        val open = OpenAccount(signed.account).also { it.load() }

        // made on the phone: on the server at the next Sync, and on the PC
        val phone = open.createVault("Phone")
        assertEquals("On this phone until the next sync puts it on the server." to false, open.vaultStatus(phone))
        open.add(phone, "Locker", "me", "l0cker")
        assertEquals(listOf("Phone"), sync(open).vaultList!!.uploaded)
        assertEquals("On the server — your other devices get it when they sync." to false, open.vaultStatus(phone))
        assertEquals(listOf("Locker"), sitesIn(pcVaults("dave")["Phone"]))

        // renamed on the phone: the PC has the new name only
        open.renameVault(phone, "Phone things")
        sync(open)
        val afterRename = pcVaults("dave")
        assertTrue("Phone" !in afterRename && "Phone things" in afterRename, afterRename.keys.toString())

        // made on the PC and deleted there: kept here, marked, not synced; put back, it is on the PC again
        pc("make_vault", mapOf("who" to "dave", "name" to "From PC", "site" to "Desk", "password" to "d3sk"))
        assertEquals(listOf("From PC"), sync(open).vaultList!!.added)
        val fromPc = idOf(open, "From PC")
        pc("delete_vault", mapOf("who" to "dave", "name" to "From PC"))
        assertEquals(listOf("From PC"), sync(open).vaultList!!.gone)
        assertEquals(true, open.vaultStatus(fromPc).second)
        assertEquals(listOf("Desk"), Entries.accountsOf(open.data.getValue(fromPc)).map { it["site"] })
        assertTrue("From PC" !in pcVaults("dave"))
        open.putVaultBack(fromPc)
        assertEquals(listOf("From PC"), sync(open).vaultList!!.uploaded)
        assertEquals(listOf("Desk"), sitesIn(pcVaults("dave")["From PC"]))

        // bob's vault shared with dave: leaving it takes dave out, bob keeps it
        pc("make_vault", mapOf("who" to "bob", "name" to "For Dave", "site" to "Garage", "password" to "g4rage"))
        pc("share", mapOf("who" to "bob", "name" to "For Dave", "to" to "dave", "role" to "reader"))
        sync(open)
        val forDave = idOf(open, "For Dave")
        assertEquals("Shared with you — you are a reader." to false, open.vaultStatus(forDave))
        assertFailsWith<ReadOnlyVault> { open.renameVault(forDave, "Mine now") }
        assertEquals<List<Any?>?>(listOf("bob", "dave"), members("bob", "For Dave"))
        open.deleteVault(forDave)
        assertEquals<List<Any?>?>(listOf("bob"), members("bob", "For Dave"))
        assertEquals(listOf("Garage"), sitesIn(pcVaults("bob")["For Dave"]))
        sync(open)
        assertFalse("For Dave" in names(open), "a sync does not bring it back")

        // deleted on the phone: deleted on the server, and the PC keeps its copy, marked
        open.deleteVault(phone)
        assertTrue(phone !in open.data && "Phone things" !in names(open))
        assertEquals(true, pcVaults("dave")["Phone things"]!!["gone"])
        sync(open)
        assertFalse("Phone things" in names(open), "a sync does not bring it back")
    }
}
