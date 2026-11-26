package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import secretspace.core.Disk.flag
import secretspace.core.Disk.text
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** An account made on the phone: the kit and words first, then the server, then this phone. */
class NewAccountTest {
    private fun store() = AccountStore(Files.createTempDirectory("phone").toFile(), PasswordSecretStore(1, 64, 1))
    private fun prepare(store: AccountStore, name: String, pw: String = "pw") = NewAccount.prepare(store, name, pw, 1, 64, 1)

    @Test fun nothingIsWrittenUntilTheServerHasIt() {
        val store = store()
        val made = prepare(store, "dana")
        assertTrue(made.secretKeyText.startsWith("A4-"), made.secretKeyText)
        assertEquals(12, made.recoveryPhrase.split(" ").size)
        assertFalse(store.accountsDir.exists())
        assertFailsWith<IllegalArgumentException> { prepare(store, "dana", "") }
        assertFailsWith<IllegalArgumentException> { prepare(store, "../x") }
    }

    @Test fun theWordsTypedBackAreChecked() {
        val made = prepare(store(), "dana")
        val words = made.recoveryPhrase.split(" ")
        assertEquals(emptyList(), made.phraseMismatches(NewAccount.CHECK_WORDS.associateWith { " " + words[it - 1].uppercase() }))
        assertEquals(listOf(7), made.phraseMismatches(mapOf(3 to words[2], 7 to "nope", 11 to words[10])))
    }

    @Test fun theKitHoldsTheSecretKeyAndNotTheWords() {
        val made = prepare(store(), "dana")
        val kit = made.emergencyKitText("https://10.0.0.1:59872/", "2026-01-02T03:04:05Z")
        assertContains(kit, "Secret Key:   ${made.secretKeyText}")
        assertContains(kit, "Server:       https://10.0.0.1:59872\n")
        assertContains(kit, "Account:      dana")
        val words = made.recoveryPhrase.split(" ")
        assertFalse((0..9).any { kit.contains(words.subList(it, it + 3).joinToString(" ")) }, "no run of the words")
    }

    @Test fun anAccountOnThisPhoneOnlyNeedsNoServer() {
        val store = store()
        val made = prepare(store, "gail", "gail's pass")
        val kit = made.emergencyKitText(null, "2026-01-02T03:04:05Z")
        assertContains(kit, "Server:       (none - this account is on this phone only)\n")
        val account = made.createHere(store)
        assertEquals(null, account.server)
        assertEquals(made.fingerprint, account.fingerprint)
        assertFailsWith<IllegalStateException> { made.createHere(store) }
        assertFailsWith<IllegalStateException> { prepare(store, "gail") }

        val open = OpenAccount(store.unlock("gail", "gail's pass")).also { it.load() }
        open.add(open.account.personalId, "Kept here", "gail", "pw1")
        val reopened = OpenAccount(store.unlock("gail", "gail's pass")).also { it.load() }
        assertEquals(listOf("Kept here"), reopened.entries(reopened.account.personalId).map { it["site"] })
        val sync = assertFailsWith<SyncError> { reopened.sync { _, _ -> null } }
        assertContains(sync.message!!, "not on a sync server")
    }

    @Test fun anotherServerStartsTheVaultsAfresh() {
        val store = store()
        val account = prepare(store, "hal").createHere(store)
        val team = account.createVault("Team").hex
        account.setServer("https://10.0.0.1:59872/", "/ca.crt")
        assertEquals("https://10.0.0.1:59872", account.server!!.text("url"))
        account.markVault(team, "on_server" to kotlinx.serialization.json.JsonPrimitive(true),
            "role" to kotlinx.serialization.json.JsonPrimitive(AccountStore.ROLE_READER))
        account.setServer("http://10.0.0.1:59872", "")          // the same server, HTTPS turned off: kept
        assertTrue(account.vaultRecord(team).flag("on_server"))
        assertEquals(AccountStore.ROLE_READER, account.vaultRole(team))
        account.setServer(null)                                   // none: the vault is this phone's own again
        assertEquals(null, account.server)
        assertFalse(account.vaultRecord(team).flag("on_server"))
        assertEquals(AccountStore.ROLE_OWNER, account.vaultRole(team))
        assertEquals("Team", account.vaultFile(team).name, "the name and key are kept")
        val again = store.unlock("hal", "pw")
        assertEquals(null, again.server, "and it is on disk")
    }

    // ------------------------------------------------------------------ with the lab server
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }
    private val lab by lazy { Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject }
    private fun s(key: String) = lab[key]!!.jsonPrimitive.content

    private fun pc(action: String, body: Map<String, Any?>): Obj {
        val c = URI(s("control") + "/" + action).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(Py.dumps(body).toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val reply = Py.asObj(Py.native(Json.parseToJsonElement(text)))!!
        if (status != 200) fail("the PC could not $action: ${reply["error"]}\n${reply["trace"]}")
        return reply
    }

    @Test fun anAccountMadeOnThePhoneOpensOnAPc() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val store = store()
        val made = prepare(store, "frank", "frank's pass")

        val none = assertFailsWith<LoginFailed> { made.createOn(store, s("url"), s("ca_file"), "") }
        assertContains(none.message!!, "sign-up code")
        val wrong = assertFailsWith<LoginFailed> { made.createOn(store, s("url"), s("ca_file"), "not-the-code") }
        assertEquals("The server did not accept that sign-up code.", wrong.message)
        assertFalse(store.accountExists("frank"), "nothing written while the server refuses")

        // the same Secret Key and words, tried again with the right code
        val account = made.createOn(store, s("url"), s("ca_file"), " " + s("sign_up_code") + " ")
        assertEquals(made.fingerprint, account.fingerprint)
        assertTrue(store.accountExists("frank"))
        assertFailsWith<IllegalStateException> { made.createOn(store, s("url"), s("ca_file"), s("sign_up_code")) }

        // it opens again with the password only, and syncs
        val open = OpenAccount(store.unlock("frank", "frank's pass")).also { it.load() }
        open.add(open.account.personalId, "Made on the phone", "frank", "s3cret")
        open.sync { _, _ -> fail("nothing to ask") }

        val onPc = pc("pc_sign_in", mapOf("who" to "frank", "password" to "frank's pass",
            "secret_key_text" to made.secretKeyText, "phrase" to made.recoveryPhrase))
        assertEquals(made.fingerprint, onPc["fingerprint"])
        assertEquals(true, onPc["phrase_opens_it"], "the 12 words open the server's copy on a PC")
        val personal = Py.asObj(Py.asObj(onPc["vaults"])!!["Personal"])!!
        val entries = Entries.accountsOf(Py.asObj(personal["data"])!!)
        assertEquals(listOf("Made on the phone"), entries.map { it["site"] })
        assertEquals("s3cret", entries.single()["password"])
        assertTrue(Py.asList(personal["log"])!!.all { it == Changelog.OK }, "the phone's change log checks out on the PC")

        // the name is taken now: another phone's new "frank" is refused, and writes nothing
        val other = store()
        val again = prepare(other, "frank", "another pass")
        val taken = assertFailsWith<SyncError> { again.createOn(other, s("url"), s("ca_file"), s("sign_up_code")) }
        assertContains(taken.message!!, "already has an account called “frank”")
        assertFalse(other.accountExists("frank"))
    }

    @Test fun anAccountOnThisPhoneOnlyGoesOnAServerLater() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val store = store()
        val made = prepare(store, "ivy", "ivy's pass")
        made.createHere(store)
        val open = OpenAccount(store.unlock("ivy", "ivy's pass")).also { it.load() }
        open.add(open.account.personalId, "Before the server", "ivy", "first")
        val team = open.account.createVault("Ivy's team").hex
        open.load()
        open.add(team, "Shared later", "ivy", "second")

        val none = assertFailsWith<LoginFailed> { AccountSync.connect(open.account, s("url"), s("ca_file"), "") }
        assertContains(none.message!!, "sign-up code")
        val wrong = assertFailsWith<LoginFailed> { AccountSync.connect(open.account, s("url"), s("ca_file"), "nope") }
        assertEquals("The server did not accept that sign-up code.", wrong.message)
        assertEquals(null, store.unlock("ivy", "ivy's pass").server, "nothing remembered while refused")

        assertEquals("created", AccountSync.connect(open.account, s("url"), s("ca_file"), s("sign_up_code")))
        assertEquals(s("url").trimEnd('/'), store.unlock("ivy", "ivy's pass").server!!.text("url"))
        open.sync { _, _ -> fail("nothing to ask") }

        val onPc = pc("pc_sign_in", mapOf("who" to "ivy", "password" to "ivy's pass",
            "secret_key_text" to made.secretKeyText, "phrase" to made.recoveryPhrase))
        assertEquals(made.fingerprint, onPc["fingerprint"])
        assertEquals(true, onPc["phrase_opens_it"])
        val vaults = Py.asObj(onPc["vaults"])!!
        fun sites(name: String) = Entries.accountsOf(Py.asObj(Py.asObj(vaults[name])!!["data"])!!).map { it["site"] }
        assertEquals(listOf("Before the server"), sites("Personal"))
        assertEquals(listOf("Shared later"), sites("Ivy's team"), "a vault made before connecting goes there too")

        // stop syncing, then connect again: the server has it already, so no code is needed
        open.account.setServer(null)
        assertEquals("joined", AccountSync.connect(open.account, s("url"), s("ca_file"), ""))
        open.load()
        open.sync { _, _ -> fail("nothing to ask") }
        assertEquals(listOf("Before the server"), open.entries(open.account.personalId).map { it["site"] })

        // a phone-only account whose name is taken on the server is refused, and stays phone-only
        val other = store()
        val twin = prepare(other, "ivy", "another pass").createHere(other)
        val taken = assertFailsWith<SyncError> { AccountSync.connect(twin, s("url"), s("ca_file"), s("sign_up_code")) }
        assertContains(taken.message!!, "already has an account called “ivy”")
        assertEquals(null, other.unlock("ivy", "another pass").server)
    }
}
