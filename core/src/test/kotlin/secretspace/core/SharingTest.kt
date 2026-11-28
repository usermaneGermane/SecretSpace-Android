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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Members screen: the phone shares a vault with bob, whose PC (the
 * PC's own code, through the lab server) gets it; a role is changed, the
 * key is changed, and bob is removed with a new key in the same step.
 */
class SharingTest {
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

    private fun sync(open: OpenAccount) = open.sync { _, result -> fail("no conflicts expected: ${result.conflicts}") }

    @Test fun thePhoneSharesAVaultWithBob() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val dave = lab["dave"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val store = AccountStore(Files.createTempDirectory("dave-sharing").toFile(), PasswordSecretStore())
        val signed = AccountSync.signIn(store, lab["url"]!!.jsonPrimitive.content, lab["ca_file"]!!.jsonPrimitive.content,
            "dave", dave.getValue("password"), dave.getValue("secret_key_text"))
        val open = OpenAccount(signed.account).also { it.load() }

        assertEquals("The Personal vault is yours alone. Make another vault to share.", open.whyNotShare(open.account.personalId))
        val team = open.createVault("Dave's team")
        assertEquals("Press Sync once, so the vault is on the server.", open.whyNotShare(team))
        open.add(team, "Alarm", "me", "4l4rm")
        sync(open)
        assertEquals("", open.whyNotShare(team))

        // bob's fingerprint, as bob reads it out from his PC
        val bob = open.lookUp(" bob ")
        assertEquals("new", bob.status)
        assertEquals(lab["bob_fingerprint"]!!.jsonPrimitive.content, bob.fingerprint)
        assertFailsWith<SyncError> { open.lookUp("dave") }                 // your own account
        assertFailsWith<SyncError> { open.lookUp("nobody-here") }

        open.share(team, bob, AccountStore.ROLE_EDITOR)
        val members = open.members(team).associateBy { it.username }
        assertEquals(setOf("dave", "bob"), members.keys)
        assertTrue(members.getValue("dave").me)
        assertTrue(members.getValue("bob").verified, "compared before sharing")
        assertEquals(AccountStore.ROLE_EDITOR, members.getValue("bob").role)
        assertEquals("Shared with 1 other person." to false, open.vaultStatus(team))
        var onBob = pcVaults("bob").getValue("Dave's team")
        assertEquals("editor", onBob["role"])
        assertEquals(listOf("Alarm"), sitesIn(onBob))

        // a reader now
        open.setRole(team, "bob", AccountStore.ROLE_READER)
        assertEquals("reader", pcVaults("bob").getValue("Dave's team")["role"])

        // a new key, nobody removed: bob still reads what is written afterwards
        open.add(team, "Gate", "me", "g4te")
        open.changeKey(team)
        assertEquals(1, open.account.vaultFile(team).generation.toInt())
        onBob = pcVaults("bob").getValue("Dave's team")
        assertEquals(setOf("Alarm", "Gate"), sitesIn(onBob).toSet())
        sync(open)                                                        // nothing left to merge after the rotation

        // bob removed: a new key in the same step, and his PC keeps only what it had, marked
        open.add(team, "Safe", "me", "s4fe")
        open.changeKey(team, listOf("bob"))
        assertEquals(listOf("dave"), open.members(team).map { it.username })
        assertEquals("On the server — your other devices get it when they sync." to false, open.vaultStatus(team))
        onBob = pcVaults("bob").getValue("Dave's team")
        assertEquals(true, onBob["gone"])
        assertFalse("Safe" in sitesIn(onBob), "nothing written after the removal reaches bob")
        assertFailsWith<SyncError> { open.changeKey(team, listOf("bob")) }  // not a member any more

        // the phone's own copy opens with the new key, and dave's PC agrees
        val again = OpenAccount(open.account).also { it.load() }
        assertEquals(setOf("Alarm", "Gate", "Safe"), Entries.accountsOf(again.data.getValue(team)).map { it["site"] }.toSet())
        assertEquals(setOf("Alarm", "Gate", "Safe"), sitesIn(pcVaults("dave")["Dave's team"]).toSet())
    }
}
