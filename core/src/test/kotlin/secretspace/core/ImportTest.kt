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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import secretspace.core.AccountCrypto as ac

/** Import and export: what the phone reads from an export, and writes, is what the PC's own code does. */
class ImportTest {
    /** Exports as browsers and password managers write them, and the awkward cases. */
    private val corpus = linkedMapOf(
        "chrome.csv" to "name,url,username,password,note\r\n" +
            "GitHub,https://github.com/login,alice,\"p,w1\",\"two\nlines\"\r\n" +
            "mail.example.com,https://mail.example.com/,alice@mail,m41l,\r\n",
        "bitwarden.csv" to "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Bank,,PIN: 1234,0,https://bank.example,bob,b4nk,JBSWY3DPEHPK3PXP\n" +
            "Work,1,login,Wiki,a note,,0,wiki.example,bob,w1k1,\n",
        "lastpass.csv" to "url,username,password,totp,extra,name,grouping,fav\n" +
            "https://shop.example/account,carol,sh0p,,Card ending 4242,Shop,Shopping,0\n" +
            "http://sn,,,,,Secure note,Notes,0\n",
        "keepass.csv" to "\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\n" +
            "\"Router\",\"admin\",\"r0uter\",\"http://192.168.1.1\",\"\"\n" +
            "\"Ünïcödé\",\"üser\",\"päss\",\"\",\"ç\"\n",
        "firefox.csv" to "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\"\n" +
            "\"https://www.example.org\",\"dave\",\"d4ve\",,\"https://www.example.org\",\"{abc}\",\"1700000000000\"\n",
        "semicolon.csv" to "name;url;username;password\nForum;forum.example;eve;f0rum\nPlain;;;\n",
        "tabs.csv" to "name\turl\tusername\tpassword\nTabbed\thttps://tab.example\tfred\tt4b\n",
        "headerless.csv" to "example.com,alice,pw1\ngithub.com,bob,pw2\n\n",
        "bom.csv" to "﻿name,username,password\r\nWith BOM,gina,b0m\r\n",
        "blank.csv" to "\n\n",
        "one-known.csv" to "Mail,alice,password\nShop,bob,s3cret\n",
        "own.json" to """{"accounts": [{"site": "Own", "username": "hal", "password": "0wn", "notes": "n", "totp": "", "url": "own.example"}]}""",
        "list.json" to """[{"title": "Listed", "login": "ivy", "pass": "l1st", "custom_fields": "x", "card_number": ""}, 7]""",
        "single.json" to """{"Name": "Single", "Email": "jo@x", "Password": 1234, "OTP": null}""",
    )
    private val links = listOf("", "  github.com/login ", "localhost:8080", "10.0.0.1:443/x", "javascript:alert(1)",
        "mailto:x@y", "http://", "https://[::1]:443/x", "ftp://a.example", "a b", "1http://x.example",
        "HTTPS://Example.COM/", "https://user@host.example:8443/p?q#f", "https://:80", "x".repeat(2049))

    private fun asLists(r: Importers.Result) = r.rows.map { listOf(it.site, it.username, it.password, it.notes, it.totp, it.url) }

    @Test fun webAddressesAsOnThePc() {
        assertEquals("https://github.com/login" to "", Weblink.clean("github.com/login"))
        assertEquals("only http:// and https:// addresses can be links.", Weblink.clean("javascript:alert(1)").second)
        assertEquals(null, Weblink.openable("file:///etc/passwd"))
        assertEquals("https://localhost:8080", Weblink.openable("localhost:8080"))
    }

    @Test fun csvAsPythonReadsAndWritesIt() {
        assertEquals(listOf(listOf("a", "b,c", "d\"e", "f\ng"), listOf("", "x")), Csv.read("a,\"b,c\",\"d\"\"e\",\"f\ng\"\r\n,x", Csv.Dialect(',')))
        val out = StringBuilder()
        Csv.writeRow(listOf("plain", "with,comma", "with\"quote", "two\nlines", ""), out)
        assertEquals("plain,\"with,comma\",\"with\"\"quote\",\"two\nlines\",\r\n", out.toString())
        assertEquals(';', Csv.sniff("a;b;c\n1;2;3\n").delimiter)
        assertEquals('\t', Csv.sniff("a\tb\n1\t2\n").delimiter)
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

    private fun needLab() {
        if (System.getenv("SECRETSPACE_LAB_REQUIRED") == "1") assertNotNull(labFile, "the lab server did not start")
        assumeTrue(labFile != null, "no lab server (tools/lab_server.py)")
    }

    @Test fun everyExportReadsAsOnThePc() {
        needLab()
        val reply = pc("imports", mapOf("files" to corpus, "links" to links))
        val onPc = Py.asObj(reply["files"])!!
        for ((name, text) in corpus) {
            val mine = Importers.parse(name, text)
            val theirs = Py.asObj(onPc[name])!!
            assertEquals<Any?>(theirs["rows"], asLists(mine), name)
            assertEquals<Any?>(theirs["dropped"], mine.dropped, name)
            assertEquals<Any?>(theirs["fields"], Importers.importedFields(mine.rows), name)
        }
        assertEquals<Any?>(reply["links"], links.map { Weblink.clean(it).toList() })
    }

    @Test fun anExportIsTheSameFileAsThePcs() {
        needLab()
        val data = Entries.emptyVault()
        Entries.addAccount(data, "Mail", "me", "p,w\"1", notes = "two\nlines", url = "https://mail.example")
        Entries.addAccount(data, "Ünïcödé 🔑", "üser", "päss", totp = "JBSWY3DPEHPK3PXP")
        val old = Entries.addAccount(data, "Old", "me", "0ld")
        Entries.archiveAccount(data, old["id"] as String)
        Entries.addAccount(data, "", "", "")
        Entries.addAccount(data, "Control", "del\u007fchar", "tab\there\u0001")
        val reply = pc("export", mapOf("data" to data))
        val (csv, rows) = Importers.exportCsv(data)
        val (json, count) = Importers.exportJson(data)
        assertEquals(reply["csv"], csv)
        assertEquals(reply["csv_rows"], rows.toLong())
        assertEquals(reply["json"], json)
        assertEquals(reply["json_rows"], count.toLong())
        assertTrue("\"Old\"" !in csv && "\"Old\"" in json, "the CSV leaves the archive out; the JSON keeps it")
    }

    @Test fun importingAddsWhatIsNewOnce() {
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
        store.adoptFromServer("dana", me, keyset, secret, login, "pw", JsonObject(emptyMap())).saveVault(Entries.emptyVault())
        val open = OpenAccount(store.unlock("dana", "pw")).also { it.load() }
        val p = open.account.personalId
        open.add(p, "GitHub", "alice", "p,w1")
        // A quote inside a password, as Chrome writes it: doubled. (The PC's csv.Sniffer may decide the
        // file has no doubled quotes and read `"a""b"` as `a"b"`; the phone reads it as the format says.)
        val parsed = Importers.parse("chrome.csv", corpus.getValue("chrome.csv") + "GitHub,,alice,\"p,w1\",\r\n" +
            "Quote,,q,\"a\"\"b\",\r\njavascript,javascript:alert(1),x,y,\r\n")
        val plan = open.planImport(p, parsed)
        assertEquals(2, plan.duplicates, "the one already here, and the one twice in the file")
        assertEquals(listOf("mail.example.com", "Quote", "javascript"), plan.rows.map { it.site })
        assertEquals(3, open.importRows(p, plan.rows))
        val byName = Entries.accountsOf(open.data.getValue(p)).associateBy { it["site"] }
        assertEquals("https://mail.example.com/", byName.getValue("mail.example.com")["url"])
        assertEquals("a\"b", byName.getValue("Quote")["password"])
        assertEquals("", Py.text(byName.getValue("javascript"), "url"), "a non-web address is not kept as a link")
        assertEquals(0, open.planImport(p, parsed).rows.size)
    }
}
