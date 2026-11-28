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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Activity and Server log screens: every line the phone shows is the
 * sentence the PC's own code writes for it (changelog.describe and
 * sharing_ui.describe_server_line, through the lab server).
 */
class ActivityTest {
    @Test fun sentencesAsOnThePc() {
        assertEquals("added “Mail”", Changelog.describe(mapOf("action" to "added", "title" to "Mail")))
        assertEquals("changed the username and password of “Mail”", Changelog.describe(mapOf("action" to "edited",
            "title" to "Mail", "fields" to listOf("username", "password"))))
        assertEquals("changed the name, 2FA and web address of “an entry”", Changelog.describe(mapOf("action" to "edited",
            "title" to "", "fields" to listOf("site", "totp", "nonsense", "url"))))
        assertEquals("changed the details of “X”", Changelog.describe(mapOf("action" to "edited", "title" to "X")))
        assertEquals("added 0 entries at once (an import)", Changelog.describe(mapOf("action" to "imported")))
        assertEquals("changed something", Changelog.describe(mapOf("action" to "danced")))
        assertEquals("made bob an owner", Changelog.describeServerLine(mapOf("event" to "member_role", "member" to "bob",
            "role" to "owner")))
        assertEquals("added ? as member", Changelog.describeServerLine(mapOf("event" to "member_added")))
        assertEquals("did something", Changelog.describeServerLine(emptyMap()))
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

    @Test fun theLogsReadAsOnThePc() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
        val dave = lab["dave"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val store = AccountStore(Files.createTempDirectory("dave-activity").toFile(), PasswordSecretStore())
        val signed = AccountSync.signIn(store, lab["url"]!!.jsonPrimitive.content, lab["ca_file"]!!.jsonPrimitive.content,
            "dave", dave.getValue("password"), dave.getValue("secret_key_text"))
        val open = OpenAccount(signed.account).also { it.load() }

        // every kind of change, made on the phone
        val book = open.createVault("Logbook")
        val mail = open.add(book, "Mail", "me", "m-1")["id"] as String
        val shop = open.add(book, "Shop", "me", "s-1")["id"] as String
        open.edit(book, mail, mapOf("username" to "me2", "password" to "m-2", "url" to "https://mail.example"))
        open.archive(book, shop)
        open.restore(book, shop)
        val rid = Py.text((Py.asList(open.find(book, mail)!!["history"])!!.first() as Map<*, *>).let {
            @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }, "rid")
        open.revert(book, mail, rid)
        open.clearHistory(book, mail)
        open.move(book, shop, open.account.personalId)
        val gone = open.add(book, "Old", "me", "o")["id"] as String
        open.archive(book, gone)
        open.purge(book, gone)

        val rows = open.activity(book)
        assertTrue(rows.all { it.by == "dave" && it.state == Changelog.OK }, rows.map { it.state }.toString())
        val lines = Changelog.linesOf(open.data[book]).reversed()
        val onPc = Py.asList(pc("describe", mapOf("lines" to lines))["lines"])
        assertEquals<List<Any?>?>(onPc, rows.map { it.what })
        assertTrue(rows.size >= 10, "${rows.size} lines")

        // the server's log, after sharing, a role, and a removal with a new key
        sync(open)
        open.share(book, open.lookUp("bob"), AccountStore.ROLE_EDITOR)
        open.setRole(book, "bob", AccountStore.ROLE_READER)
        open.changeKey(book, listOf("bob"))
        val raw = AccountSync.sharing(open.account) { it.vaultLog(book) }.map { Py.asObj(Py.native(it))!! }
        val events = raw.map { it["event"] }
        for (e in listOf("vault_created", "upload", "member_added", "member_role", "key_changed")) {
            assertTrue(e in events, "$e in $events")
        }
        val synthetic = listOf(mapOf("event" to "member_added"), mapOf("event" to "key_changed", "removed" to listOf("x", "y")),
            mapOf("event" to "upload"), mapOf("event" to "brand_new"), emptyMap<String, Any?>())
        val pcServer = Py.asList(pc("describe", mapOf("server_lines" to raw + synthetic))["server_lines"])
        assertEquals<List<Any?>?>(pcServer, (raw + synthetic).map { Changelog.describeServerLine(it) })
        val shown = open.serverLog(book)
        assertEquals(raw.size, shown.size)
        assertTrue(shown.first().second.startsWith("dave changed the vault's key and removed bob"), shown.first().second)
        assertTrue(shown.all { "   ·   from " in it.second }, "the address each request came from")
    }

    private fun sync(open: OpenAccount) = open.sync { _, result -> fail("no conflicts expected: ${result.conflicts}") }
}
