package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone and carol's PC take turns on one account through a real
 * server (tools/lab_server.py): each change made on one side arrives on the
 * other, a conflict is settled here and the PC agrees, and a vault shared
 * by bob takes an edit from the phone, signed so bob can check it.
 */
class SyncTest {
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }
    private val lab by lazy { Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject }
    private fun s(key: String) = lab[key]!!.jsonPrimitive.content
    private val carol by lazy { lab["carol"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } }

    private fun needLab() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
    }

    /** One step on a PC, done by the PC's own code. */
    private fun pc(action: String, body: Map<String, Any?> = emptyMap()): Obj {
        val c = URI(s("control") + "/" + action).toURL().openConnection() as HttpURLConnection
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

    private fun entries(data: Map<String, Any?>) = Entries.accountsOf(data).associateBy { it["site"] as String }

    /** What the app does on Save: change, note who did, write. */
    private fun edit(account: Account, vaultId: String?, change: (Obj) -> Unit) {
        val file = account.vaultFile(vaultId)
        val data = file.load()
        val before = Py.deepCopyObj(data)
        change(data)
        Changelog.record(data, before, account.username, account.keyset, file.hex, file.fmt)
        file.save(data)
    }

    private fun sync(account: Account, vaultId: String?, choices: Map<String, String>? = null,
                     expectConflicts: Int = 0): Merge.Result {
        val session = VaultSync(account, null, vaultId)
        try {
            val result = session.prepare(session.file.load())
            assertEquals(expectConflicts, result.conflicts.size, "conflicts: ${result.conflicts}")
            if (session.hasWork()) session.commit(session.file.load(), choices)
            return result
        } finally {
            session.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pcVault(reply: Obj, name: String): Obj = Py.asObj(Py.asObj(reply["vaults"])!![name])!!

    @Test fun thePhoneAndThePcTakeTurns() {
        needLab()
        val home = Files.createTempDirectory("carol-phone").toFile()
        val store = AccountStore(home, PasswordSecretStore(1, 64, 1))
        val phone = AccountSync.signIn(store, s("url"), s("ca_file"), "carol", carol.getValue("password"),
            carol.getValue("secret_key_text")).account
        val family = phone.vaultIds().first { phone.vaultFile(it).name == "Family" }
        assertEquals(AccountStore.ROLE_EDITOR, phone.vaultRole(family))

        // 1. Changes made on the phone reach the PC, with who made them.
        edit(phone, null) { data ->
            val e = entries(data)
            Entries.updateEntry(e.getValue("Bank"), mapOf("password" to "bank-from-phone"))
            Entries.addAccount(data, "Phone entry", "me", "ph0ne")
            Entries.archiveAccount(data, e.getValue("Shop")["id"] as String)
        }
        val pushed = sync(phone, null)
        assertTrue(pushed.needsPush)
        var onPc = pcVault(pc("carol_sync"), "Personal")
        var pcEntries = entries(Py.asObj(onPc["data"])!!)
        assertEquals("bank-from-phone", pcEntries.getValue("Bank")["password"])
        assertEquals("carol", pcEntries.getValue("Bank")["updated_by"])
        val revisions = Py.asList(pcEntries.getValue("Bank")["history"])!!.map { Py.asObj(it)!! }
        assertTrue(revisions.any { it["password"] == "bank-0" && it["by"] == "carol" }, "$revisions")
        assertTrue("Phone entry" in pcEntries)
        assertTrue(Entries.isArchived(pcEntries.getValue("Shop")))
        assertTrue(Py.asList(onPc["log"])!!.isNotEmpty())
        assertEquals(setOf(Changelog.OK), Py.asList(onPc["log"])!!.toSet(), "the PC checks every line's signature")

        // 2. The PC changes Mail and Bank; the phone changes Bank too: one conflict, Mail just arrives.
        pc("carol_edit")
        edit(phone, null) { data -> Entries.updateEntry(entries(data).getValue("Bank"), mapOf("password" to "bank-phone-2")) }
        val both = sync(phone, null, choices = null, expectConflicts = 1).also {
            assertEquals("edit", it.conflicts.single().kind)
            assertEquals("Bank", it.conflicts.single().site)
        }
        assertTrue(both.stats.getValue("updated") >= 1)          // Mail, from the PC
        // that sync kept both (the default); settle it properly in a second round
        var data = phone.loadVault()
        val banks = Entries.accountsOf(data).filter { it["site"] == "Bank" }
        assertEquals(2, banks.size, "keep both = two live entries")
        edit(phone, null) { d ->
            val copyOfPc = Entries.accountsOf(d).first { it["site"] == "Bank" && it["password"] == "bank-from-pc" }
            Entries.archiveAccount(d, copyOfPc["id"] as String)
        }
        sync(phone, null)
        data = phone.loadVault()
        assertEquals("mail-from-pc", entries(data).getValue("Mail")["username"])

        onPc = pcVault(pc("carol_sync"), "Personal")
        pcEntries = entries(Py.asObj(onPc["data"])!!)
        val live = Entries.accountsOf(Py.asObj(onPc["data"])!!).filter { it["site"] == "Bank" && !Entries.isArchived(it) }
        assertEquals(listOf("bank-phone-2"), live.map { it["password"] }, "the PC ends with the phone's choice")
        assertEquals("mail-from-pc", pcEntries.getValue("Mail")["username"])

        // 3. The other way to settle: choose a side, and the loser becomes a revision.
        pc("carol_edit")                                  // Bank back to bank-from-pc on the PC
        edit(phone, null) { d ->
            val bank = Entries.accountsOf(d).first { it["site"] == "Bank" && !Entries.isArchived(it) }
            Entries.updateEntry(bank, mapOf("password" to "bank-phone-3"))
        }
        val third = VaultSync(phone)
        try {
            val result = third.prepare(phone.loadVault())
            assertEquals(1, result.conflicts.size)
            // a change while the question is open is not lost: the commit refuses
            edit(phone, null) { d -> Entries.addAccount(d, "Typed meanwhile", "me", "x") }
            val e = assertFailsWith<SyncError> {
                third.commit(phone.loadVault(), mapOf(result.conflicts.single().id to Merge.KEEP_LOCAL))
            }
            assertContains(e.message!!, "changed while the sync was open")
        } finally {
            third.close()
        }
        val fourth = VaultSync(phone)
        try {
            val result = fourth.prepare(phone.loadVault())
            fourth.commit(phone.loadVault(), mapOf(result.conflicts.single().id to Merge.KEEP_LOCAL))
        } finally {
            fourth.close()
        }
        onPc = pcVault(pc("carol_sync"), "Personal")
        val bank = Entries.accountsOf(Py.asObj(onPc["data"])!!).first { it["site"] == "Bank" && !Entries.isArchived(it) }
        assertEquals("bank-phone-3", bank["password"])
        assertTrue(Py.asList(bank["history"])!!.any { Py.asObj(it)!!["password"] == "bank-from-pc" })
        assertTrue("Typed meanwhile" in entries(Py.asObj(onPc["data"])!!))

        // 4. bob's vault: the phone, an editor, adds an entry; bob's PC takes it and checks the signature.
        edit(phone, family) { d -> Entries.addAccount(d, "Router", "admin", "r0uter") }
        sync(phone, family)
        val onBob = pcVault(pc("bob_sync"), "Family")
        val router = entries(Py.asObj(onBob["data"])!!).getValue("Router")
        assertEquals("carol", router["updated_by"])
        assertEquals(setOf(Changelog.OK), Py.asList(onBob["log"])!!.toSet(), "bob checks carol's signature")

        // 5. In the end the PC finds nothing to merge in the phone's folder.
        for (id in phone.vaultIds()) sync(phone, id)
        val folder = Py.asObj(pc("check_folder", mapOf("home" to home.path, "username" to "carol",
            "password" to carol.getValue("password")))["nothing_to_merge"])!!
        assertEquals(setOf("Personal", "Family"), folder.keys)
        assertTrue(folder.values.all { it == true }, "$folder")
    }
}
