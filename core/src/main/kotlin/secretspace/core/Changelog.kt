package secretspace.core

import secretspace.core.Entries.isArchived
import secretspace.core.Py.asList
import secretspace.core.Py.asObj
import secretspace.core.Py.text
import secretspace.core.Py.truthy
import secretspace.core.AccountCrypto as ac

/**
 * The change log inside a vault: who changed what and when, each line
 * signed by its author (the PC's changelog.py, line for line). Never a
 * value: no password, no username, no note.
 */
object Changelog {
    const val CHANGES_KEY = "changes"
    const val CHANGES_LIMIT = 1000
    const val BULK = 20
    const val ADDED = "added"
    const val EDITED = "edited"
    const val ARCHIVED = "archived"
    const val RESTORED = "restored"
    const val DELETED = "deleted"
    const val REVERTED = "reverted"
    const val IMPORTED = "imported"
    const val DELETED_MANY = "deleted_many"
    const val HISTORY_CLEARED = "history_cleared"
    const val MOVED = "moved"
    const val OK = "ok"
    const val BAD = "bad"
    const val UNKNOWN = "unknown"
    private const val TITLE_CHARS = 120
    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")
    private const val MAX_FIELDS = 64

    /** Python's int() of what a stored line may hold, or null when it would raise. */
    private fun pyInt(value: Any?): Long? = when (value) {
        null -> null
        is Boolean -> if (value) 1 else 0
        is Long -> value
        is Int -> value.toLong()
        is Double -> if (value.isFinite()) value.toLong() else null
        is String -> value.trim().replace("_", "").toLongOrNull()
        else -> null
    }

    /** The account_crypto.format_of of a stored number. */
    fun formatOf(number: Any?): Format {
        val n = pyInt(if (truthy(number)) number else ac.OLD.number.toLong())
        return if (n == ac.NOW.number.toLong()) ac.NOW else ac.OLD
    }

    /** A stored line in its one form, or null for junk. */
    fun clean(raw: Any?): Obj? {
        val line = asObj(raw) ?: return null
        val action = line["action"] as? String ?: return null
        if (!NAME.matches(action)) return null
        val fields = asList(line["fields"])
        val count = try {
            maxOf(0L, pyInt(if (truthy(line["count"])) line["count"] else 0L) ?: 0L)
        } catch (e: Exception) {
            0L
        }
        val out: Obj = linkedMapOf(
            "id" to Py.take(text(line, "id"), 32),
            "at" to Py.take(text(line, "at"), 40),
            "by" to Py.take(text(line, "by"), 64),
            "action" to action,
            "entry" to Py.take(text(line, "entry"), 64),
            "title" to Py.take(text(line, "title"), TITLE_CHARS),
            "fields" to (fields?.filter { it is String && NAME.matches(it) }?.take(MAX_FIELDS)?.toMutableList()
                ?: mutableListOf<Any?>()),
            "count" to count,
            "sig" to Py.take(text(line, "sig"), 200),
        )
        if (formatOf(line["v"]) === ac.NOW) out["v"] = ac.NOW.number.toLong()
        if ((out["id"] as String).isEmpty() || (out["at"] as String).isEmpty() || (out["by"] as String).isEmpty()) {
            return null
        }
        return out
    }

    /** A vault's log, cleaned, oldest first. */
    fun linesOf(data: Map<String, Any?>?): List<Obj> = merge(asList(data?.get(CHANGES_KEY)) ?: emptyList())

    /** The union of several copies of a log: each line once, oldest first, the newest kept. */
    fun merge(vararg logs: List<Any?>?): List<Obj> {
        val found = linkedMapOf<String, Obj>()
        for (log in logs) for (raw in log ?: emptyList()) {
            val line = clean(raw) ?: continue
            found.putIfAbsent(line["id"] as String, line)
        }
        return found.values.sortedWith(compareBy({ it["at"] as String }, { it["id"] as String })).takeLast(CHANGES_LIMIT)
    }

    /** Do both copies' logs end with this entry's move to another vault? */
    fun movedAway(entryId: String, here: Map<String, Any?>?, there: Map<String, Any?>?): Boolean {
        fun last(lines: List<Obj>) = lines.lastOrNull { it["entry"] == entryId }?.get("action")
        val remote = linesOf(there)
        return last(remote) == MOVED && last(merge(linesOf(here), remote)) == MOVED
    }

    private fun byId(data: Map<String, Any?>?): LinkedHashMap<Any?, Obj> {
        val out = linkedMapOf<Any?, Obj>()
        for (a in asList(data?.get("accounts")) ?: emptyList()) {
            val entry = asObj(a) ?: continue
            if (truthy(entry["id"])) out[entry["id"]] = entry
        }
        return out
    }

    private fun wasAVersion(before: Map<String, Any?>, after: Map<String, Any?>): Boolean {
        val now = Entries.contentKey(Entries.snapshot(after))
        return (asList(before["history"]) ?: emptyList()).any { rev ->
            asObj(rev)?.let { Entries.contentKey(it) } == now
        }
    }

    private fun item(action: String, entry: Any?, title: String, fields: List<String>, count: Long = 0): Obj =
        linkedMapOf("action" to action, "entry" to entry, "title" to title, "fields" to fields.toMutableList<Any?>(),
            "count" to count)

    /** The changes from ``before`` to ``after`` as unsigned records. */
    fun diff(before: Map<String, Any?>?, after: Map<String, Any?>?): List<Obj> {
        val old = byId(before)
        val new = byId(after)
        var added = mutableListOf<Obj>()
        var deleted = mutableListOf<Obj>()
        val rest = mutableListOf<Obj>()
        for ((id, entry) in new) {
            val was = old[id]
            val title = Py.take(text(entry, "site"), TITLE_CHARS)
            if (was == null) {
                added += item(ADDED, id, title, emptyList())
                continue
            }
            val fields = Entries.changedFields(was, entry)
            val action = when {
                isArchived(was) != isArchived(entry) ->
                    if (!isArchived(entry)) RESTORED else if (Entries.moveMarker(entry) != null) MOVED else ARCHIVED
                fields.isNotEmpty() || Entries.extraKey(was) != Entries.extraKey(entry) ->
                    if (wasAVersion(was, entry)) REVERTED else EDITED
                text(was, "history_cleared_at") != text(entry, "history_cleared_at") -> HISTORY_CLEARED
                else -> continue
            }
            rest += item(action, id, title, fields)
        }
        for ((id, was) in old) {
            if (id !in new && Entries.moveMarker(was) == null) {
                deleted += item(DELETED, id, Py.take(text(was, "site"), TITLE_CHARS), emptyList())
            }
        }
        if (added.size > BULK) added = mutableListOf(item(IMPORTED, "", "", emptyList(), added.size.toLong()))
        if (deleted.size > BULK) deleted = mutableListOf(item(DELETED_MANY, "", "", emptyList(), deleted.size.toLong()))
        return added + rest + deleted
    }

    /** What a line's signature covers: everything in it, and the vault it is in. */
    fun message(vaultHex: String, line: Map<String, Any?>): ByteArray {
        val label = String(formatOf(line["v"]).label("change"), Charsets.US_ASCII)
        return Py.dumps(listOf(label, vaultHex, line["id"], line["at"], line["by"], line["action"], line["entry"],
            line["title"], (line["fields"] as List<*>).toList(), pyInt(line["count"])), compact = true)
            .toByteArray(Charsets.US_ASCII)
    }

    /** OK when ``signingKey`` signed this line, BAD when it did not, UNKNOWN without a key. */
    fun check(line: Any?, vaultHex: String, signingKey: ByteArray?): String {
        if (signingKey == null || signingKey.isEmpty()) return UNKNOWN
        val clean = clean(line) ?: return BAD
        return if (ac.verify(signingKey, message(vaultHex, clean), clean["sig"] as String)) OK else BAD
    }

    var lineId: () -> String = { ac.randomBytes(8).toHex() }

    /**
     * Note in ``data`` what ``username`` changed since ``before``: signed
     * lines, ``updated_by`` on each entry, ``by`` on each new revision.
     */
    fun record(data: Obj, before: Map<String, Any?>?, username: String, keyset: Map<String, String>,
               vaultHex: String, fmt: Format = ac.NOW): Int {
        val found = diff(before, data)
        if (found.isEmpty()) return 0
        val old = byId(before)
        val new = byId(data)
        val lines = mutableListOf<Obj>()
        for (item in found) {
            val line: Obj = LinkedHashMap(item)
            line["id"] = lineId()
            line["at"] = Entries.historyNow()
            line["by"] = username
            line["sig"] = ""
            if (fmt === ac.NOW) line["v"] = fmt.number.toLong()
            line["sig"] = ac.sign(keyset, message(vaultHex, line))
            lines += line
            val entry = new[item["entry"]] ?: continue
            entry["updated_by"] = username
            val seen = (asList(old[item["entry"]]?.get("history")) ?: emptyList()).map { asObj(it)?.get("rid") }.toSet()
            for (rev in asList(entry["history"]) ?: emptyList()) {
                val r = asObj(rev) ?: continue
                if (r["rid"] !in seen && !truthy(r["by"])) r["by"] = username
            }
        }
        if (found.any { it["action"] == IMPORTED }) {
            for ((id, entry) in new) if (id !in old) entry["updated_by"] = username
        }
        data[CHANGES_KEY] = merge(linesOf(data), lines).toMutableList<Any?>()
        return lines.size
    }
}
