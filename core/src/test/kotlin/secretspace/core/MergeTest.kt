package secretspace.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The merge, entry history, change log and moves against the PC's own
 * output (tools/make_merge_vectors.py), with the same counters in place of
 * the clock and fresh ids, so every value must match exactly.
 */
class MergeTest {
    @Suppress("UNCHECKED_CAST")
    private val v = Py.native(Json.parseToJsonElement(
        javaClass.getResource("/merge-vectors.json")!!.readText(Charsets.UTF_8))) as Obj

    private var ids = 0
    private var stamps = 0
    private var lines = 0L
    private val saved = listOf(Entries.newId, Entries.utcNow, Entries.historyNow)
    private val savedLine = Changelog.lineId

    private fun reset() {
        ids = 0; stamps = 0; lines = 0
    }

    @BeforeEach fun fixed() {
        reset()
        Entries.newId = { "%032x".format(++ids) }
        Entries.utcNow = { v["utc_now"] as String }
        Entries.historyNow = { "2026-01-02T03:04:05.%06dZ".format(++stamps) }
        Changelog.lineId = { java.nio.ByteBuffer.allocate(8).putLong(++lines).array().toHex() }
    }

    @AfterEach fun real() {
        Entries.newId = saved[0]; Entries.utcNow = saved[1]; Entries.historyNow = saved[2]
        Changelog.lineId = savedLine
    }

    private fun obj(x: Any?): Obj = Py.asObj(x)!!
    private fun objOrNull(x: Any?): Obj? = Py.asObj(x)
    private fun list(key: String): List<Obj> = Py.asList(v[key])!!.map { obj(it) }
    private fun copy(x: Any?): Obj? = Py.asObj(Py.deepCopy(x))
    @Suppress("UNCHECKED_CAST")
    private val keyset get() = obj(v["keyset"]) as Map<String, String>

    @Test fun vaultsAreTidiedAsOnThePc() {
        for (c in list("validate")) {
            reset()
            assertEquals(c["out"], Entries.validateVault(copy(c["in"])))
        }
        for (c in list("validate_errors")) {
            val e = assertFailsWith<IllegalArgumentException> { Entries.validateVault(Py.deepCopy(c["in"])) }
            assertEquals(c["error"], e.message)
        }
    }

    @Test fun everyMergeComesOutAsOnThePc() {
        val merges = list("merges")
        assertTrue(merges.size > 160)
        for (c in merges) {
            val name = c["name"] as String
            reset()
            val result = Merge.mergeVaults(copy(c["base"]), copy(c["local"]), copy(c["remote"]))
            assertEquals(c["accounts"], result.accounts, name)
            assertEquals(c["conflicts"], result.conflicts.map { listOf(it.id, it.kind) }, name)
            assertEquals(c["stats"], result.stats.mapValues { it.value.toLong() }, name)
            assertEquals(c["extra"], result.extra, name)
            assertEquals(c["summary"], result.summary(), name)
            assertEquals(c["needs_push"], result.needsPush, name)
            assertEquals(c["changed"], result.changed, name)
            for ((choice, expected) in obj(c["resolved"])) {
                reset()
                val again = Merge.mergeVaults(copy(c["base"]), copy(c["local"]), copy(c["remote"]))
                val resolved = Merge.resolve(again, again.conflicts.associate { it.id to choice })
                assertEquals(expected, Merge.mergedVault(copy(c["local"]), resolved, again.extra), "$name / $choice")
            }
        }
    }

    @Test fun anEntrysHistoryIsKeptAsOnThePc() {
        val h = obj(v["history"])
        val e = copy(h["in"])!!
        val steps = Py.asList(h["steps"])!!
        Entries.updateEntry(e, mapOf("password" to "one", "notes" to "n1"))
        assertEquals(steps[0], e)
        assertEquals(false, Entries.updateEntry(e, mapOf("password" to "one", "notes" to "n1")))
        Entries.updateEntry(e, mapOf("url" to "https://bank.example", "totp" to "JBSWY3DPEHPK3PXP"))
        assertEquals(steps[1], e)
        val rid = (Py.asList(e["history"])!!.last() as Map<*, *>)["rid"] as String
        assertEquals(h["rid"], rid)
        Entries.revertEntry(e, rid)
        assertEquals(steps[2], e)
        Entries.clearHistory(e)
        assertEquals(steps[3], e)
        val other = copy(h["in"])!!
        Entries.updateEntry(other, mapOf("username" to "someone"))
        Entries.mergeHistory(e, other, null, mutableMapOf())
        assertEquals(steps[4], e)
    }

    @Test fun theChangeLogIsWrittenAsOnThePcAndSignedTheSame() {
        for (c in list("changelog")) {
            assertEquals(c["diff"], Changelog.diff(copy(c["before"]), copy(c["after"])))
            reset()
            val data = copy(c["after"])!!
            assertEquals(c["count"], Changelog.record(data, copy(c["before"]), "alex", keyset,
                v["vault_hex"] as String).toLong())
            assertEquals(c["recorded"], data)
            reset()
            val old = copy(c["after"])!!
            Changelog.record(old, copy(c["before"]), "alex", keyset, v["vault_hex"] as String, AccountCrypto.OLD)
            assertEquals(c["recorded_old"], old)
        }
        val m = obj(v["log_merge"])
        val all = Py.asList(m["in"])!!
        assertEquals(m["out"], Changelog.merge(all, all.take(2)))
        val check = obj(v["log_check"])
        val key = AccountCrypto.unb64(check["signing_key"] as String, "key")
        assertEquals(check["ok"], Changelog.check(check["line"], v["vault_hex"] as String, key))
        assertEquals(check["other_vault"], Changelog.check(check["line"], "00".repeat(16), key))
        assertEquals(Changelog.UNKNOWN, Changelog.check(check["line"], v["vault_hex"] as String, null))
    }

    @Test fun anEntryMovesAsOnThePc() {
        val m = obj(v["moves"])
        val (src, dst) = Py.asList(Py.deepCopy(m["in"]))!!.map { obj(it) }
        val copied = Entries.move(src, dst, "e", "ef".repeat(16))!!
        Entries.move(src, dst, "s", "ef".repeat(16))
        assertEquals(m["source"], src)
        assertEquals(m["target"], dst)
        assertEquals(m["sum"], Entries.moveSum(copied))
        val settled = copy(src)!!
        assertEquals(m["settled_count"], Entries.settle(settled) { _, id -> id == copied["id"] }.toLong())
        assertEquals(m["settled"], settled)
    }

    @Test fun theSameServerAndATrustedBaseAsOnThePc() {
        for (c in list("same_server")) {
            assertEquals(c["same"], Servers.same(c["a"] as String, c["b"] as String), "${c["a"]} ${c["b"]}")
        }
        for (c in list("base_matches")) {
            assertEquals(c["matches"], Servers.baseMatches(objOrNull(c["base"]), c["url"] as String,
                (c["revision"] as Long).toInt()), c.toString())
        }
    }

    @Test fun jsonIsWrittenAsPythonWritesIt() {
        assertEquals("""["a", ["b", 1], {"z": null, "\u00fc": true}]""",
            Py.dumps(listOf("a", listOf("b", 1L), linkedMapOf("z" to null, "ü" to true))))
        assertEquals("""{"a":1,"b":[]}""", Py.dumps(linkedMapOf("b" to listOf<Any?>(), "a" to 1L), true, true))
        assertEquals("\"\\ud83d\\ude00 \\\" \\\\ \\n \\u007f\"", Py.dumps("\uD83D\uDE00 \" \\ \n \u007f"))
    }
}
