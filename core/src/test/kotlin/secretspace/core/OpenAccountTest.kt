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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import secretspace.core.AccountCrypto as ac

/** What the app does with an open account: edits, readers, moves, the archive, and Sync. */
class OpenAccountTest {
    /** An account on this phone only, made the way a sign-in writes one. */
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

    private fun OpenAccount.personal() = account.personalId
    private fun sites(list: List<Obj>) = list.map { it["site"] }

    @Test fun everyChangeIsWrittenAndSigned() {
        val open = local()
        val p = open.personal()
        val bank = open.add(p, "Bank", "me", "pw-1", url = "https://bank.example")
        open.add(p, "Mail", "me", "m")
        assertTrue(open.edit(p, bank["id"] as String, mapOf("password" to "pw-2")))
        assertFalse(open.edit(p, bank["id"] as String, mapOf("password" to "pw-2")))     // nothing changed
        open.archive(p, bank["id"] as String)
        assertEquals(listOf("Mail"), sites(open.entries(p)))
        assertEquals(listOf("Bank"), sites(open.entries(p, archived = true)))
        open.restore(p, bank["id"] as String)

        // what is on disk is what is shown, and every line checks out
        val again = OpenAccount(open.account).also { it.load() }
        assertEquals(open.data[p], again.data[p])
        val lines = Changelog.linesOf(again.data[p])
        assertEquals(listOf("added", "added", "edited", "archived", "restored"), lines.map { it["action"] })
        val key = ac.signingKeyOf(open.account.keyset)
        assertTrue(lines.all { Changelog.check(it, open.account.vaultFile(p).hex, key) == Changelog.OK })
        assertEquals("pw-1", (Py.asList(open.find(p, bank["id"] as String)!!["history"])!!.first() as Map<*, *>)["password"])

        open.purge(p, bank["id"] as String)
        assertNull(open.find(p, bank["id"] as String))
    }

    @Test fun searchAndOrderAsOnThePc() {
        val open = local()
        val p = open.personal()
        open.change(p) { d ->
            Entries.accountsOf(d).addAll(listOf(
                linkedMapOf("id" to "1", "site" to "b", "username" to "x", "password" to "", "updated_at" to "2026-01-02T00:00:00Z"),
                linkedMapOf("id" to "2", "site" to "A", "username" to "x", "password" to "", "updated_at" to "2026-01-02T00:00:00Z"),
                linkedMapOf("id" to "3", "site" to "c", "username" to "x", "password" to "", "url" to "https://shop"),
                linkedMapOf("id" to "4", "site" to "d", "username" to "x", "password" to "", "updated_at" to "2026-01-03T00:00:00Z"),
            ))
        }
        assertEquals(listOf("d", "A", "b", "c"), sites(open.entries(p)))                    // newest first, undated last
        assertEquals(listOf("A", "b", "d", "c"), sites(open.entries(p, order = "modified_asc")))
        assertEquals(listOf("A", "b", "c", "d"), sites(open.entries(p, order = "name_asc")))
        assertEquals(listOf("d", "c", "b", "A"), sites(open.entries(p, order = "name_desc")))
        assertEquals(listOf("c"), sites(open.entries(p, query = "SHOP")))
    }

    @Test fun aReadersVaultAndANewerAppsAreNotChanged() {
        val open = local()
        val team = open.account.createVault("Team").hex
        open.load()
        open.account.markVault(team, "role" to JsonPrimitive(AccountStore.ROLE_READER))
        val before = open.account.vaultFile(team).path.readText()
        val e = assertFailsWith<ReadOnlyVault> { open.add(team, "x", "y", "z") }
        assertContains(e.message!!, "reading only")
        assertEquals(before, open.account.vaultFile(team).path.readText())

        val p = open.personal()
        open.change(p) { it["needs_app"] = linkedMapOf<String, Any?>("level" to 9L, "android" to "2.0") }
        open.load()
        assertFalse(open.account.canWrite(p))
        assertEquals("SecretSpace 2.0 or newer", open.vaults().first { it.personal }.newerAppText)
        assertFailsWith<ReadOnlyVault> { open.add(p, "x", "y", "z") }
    }

    @Test fun anEntryMovesAndTheOriginalGoesOnceTheCopyIsThere() {
        val open = local()
        val p = open.personal()
        val team = open.account.createVault("Team").hex
        open.load()
        val bank = open.add(p, "Bank", "me", "pw")
        val copy = assertNotNull(open.move(p, bank["id"] as String, team))
        assertEquals(listOf("Bank"), sites(open.entries(team)))
        assertEquals(copy["id"], open.entries(team).single()["id"])
        assertTrue(open.entries(p).isEmpty() && open.entries(p, archived = true).isEmpty(), "no server: gone at once")
        assertEquals("moved", Changelog.linesOf(open.data[p]).last()["action"])
    }

    @Test fun oldArchivedEntriesGoWhenTheVaultSaysSo() {
        val open = local()
        val p = open.personal()
        open.change(p) { d ->
            Entries.accountsOf(d).add(linkedMapOf("id" to "old", "site" to "Old", "username" to "", "password" to "",
                "archived_at" to "2026-01-01T00:00:00Z"))
            Entries.accountsOf(d).add(linkedMapOf("id" to "new", "site" to "New", "username" to "", "password" to "",
                "archived_at" to "2026-03-01T00:00:00Z"))
            Entries.accountsOf(d).add(linkedMapOf("id" to "odd", "site" to "Odd", "username" to "", "password" to "",
                "archived_at" to "long ago"))
        }
        assertEquals(0, open.autoPurge(Instant.parse("2026-03-15T00:00:00Z")))       // off by default
        open.setPurgeDays(p, 30)
        assertEquals(30, open.purgeDays(p))
        assertEquals(1, open.autoPurge(Instant.parse("2026-03-15T00:00:00Z")))
        assertEquals(listOf("New", "Odd"), sites(open.entries(p, archived = true)).sortedBy { it as String })
    }

    // ------------------------------------------------------------------ with the lab server
    private val labFile = System.getenv("SECRETSPACE_LAB")?.takeIf { it.isNotEmpty() }
    private val lab by lazy { Json.parseToJsonElement(File(labFile!!).readText(Charsets.UTF_8)).jsonObject }

    private fun pc(action: String, body: Map<String, Any?>): Obj {
        val c = URI(lab["control"]!!.jsonPrimitive.content + "/" + action).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(Py.dumps(body).toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val reply = Py.asObj(Py.native(Json.parseToJsonElement(text)))!!
        if (status != 200) fail("the PC could not $action: ${reply["error"]}\n${reply["trace"]}")
        return reply
    }

    @Test fun syncAsksAboutAConflictAndThePcAgrees() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val erin = lab["erin"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val store = AccountStore(Files.createTempDirectory("erin-phone").toFile(), PasswordSecretStore(1, 64, 1))
        val signed = AccountSync.signIn(store, lab["url"]!!.jsonPrimitive.content, lab["ca_file"]!!.jsonPrimitive.content,
            "erin", erin.getValue("password"), erin.getValue("secret_key_text"))
        val open = OpenAccount(signed.account).also { it.load() }
        val p = open.account.personalId
        val bank = open.entries(p).first { it["site"] == "Bank" }
        open.edit(p, bank["id"] as String, mapOf("password" to "from-the-phone"))
        open.add(p, "New on the phone", "me", "n")
        pc("edit", mapOf("who" to "erin", "site" to "Bank", "fields" to mapOf("password" to "from-the-pc")))

        var asked: Merge.Result? = null
        val report = open.sync { name, result ->
            assertEquals("Personal", name)
            asked = result
            result.conflicts.associate { it.id to Merge.KEEP_REMOTE }
        }
        assertEquals(1, asked!!.conflicts.size)
        assertEquals("from-the-pc", open.find(p, bank["id"] as String)!!["password"])
        assertTrue(report.changes.single().startsWith("Personal: "))
        val onPc = Py.asObj(Py.asObj(pc("sync", mapOf("who" to "erin"))["vaults"])!!["Personal"])!!
        val pcBank = Entries.accountsOf(Py.asObj(onPc["data"])!!).first { it["site"] == "Bank" }
        assertEquals("from-the-pc", pcBank["password"])
        assertTrue(Py.asList(pcBank["history"])!!.any { Py.asObj(it)!!["password"] == "from-the-phone" },
            "the phone's version is kept as an earlier one")
        assertTrue(Entries.accountsOf(Py.asObj(onPc["data"])!!).any { it["site"] == "New on the phone" })

        // left for later: nothing is written, the next Sync asks again
        pc("edit", mapOf("who" to "erin", "site" to "Bank", "fields" to mapOf("password" to "pc-again")))
        open.edit(p, bank["id"] as String, mapOf("password" to "phone-again"))
        val later = open.sync { _, _ -> null }
        assertEquals(listOf("Personal"), later.leftForLater)
        assertEquals("phone-again", open.find(p, bank["id"] as String)!!["password"])
        val third = open.sync { _, r -> r.conflicts.associate { it.id to Merge.KEEP_LOCAL } }
        assertTrue(third.leftForLater.isEmpty())
        assertEquals("phone-again", open.find(p, bank["id"] as String)!!["password"])
    }
}
