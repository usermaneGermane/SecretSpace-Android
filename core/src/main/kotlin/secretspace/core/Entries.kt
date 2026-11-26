package secretspace.core

import secretspace.core.Py.asList
import secretspace.core.Py.asObj
import secretspace.core.Py.text
import secretspace.core.Py.truthy
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A vault's entries and their history: the PC's vault.py (the parts that
 * are not files) and moves.py, line for line. Two devices that hold the
 * same entries must compute the same bytes, or every sync would find a
 * difference to chase.
 */
object Entries {
    val OPTIONAL_FIELDS = listOf("notes", "totp", "url", "updated_at", "archived_at", "history_cleared_at",
        "updated_by")
    val REVISION_FIELDS = listOf("site", "username", "password", "notes", "totp", "url")
    private val KEY_FIELDS_BEFORE_URL = listOf("site", "username", "password", "notes", "totp")
    val KNOWN_ENTRY_FIELDS = (listOf("id", "site", "username", "password", "history", "moved") + OPTIONAL_FIELDS).toSet()
    const val MOVED_KEY = "moved"
    val REVISION_META = setOf("replaced_at", "changed", "by", "rid")
    const val HISTORY_LIMIT = 10
    private val ID_NAMESPACE: UUID = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed")

    // -- the clock and fresh ids; tests replace them to compare with the PC byte for byte
    var newId: () -> String = { UUID.randomUUID().toString().replace("-", "") }
    var utcNow: () -> String = { Disk.utcNow() }
    var historyNow: () -> String = ::realHistoryNow

    private var lastStamp: Instant? = null
    private val MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    /** To the microsecond, and always later than the one before. */
    @Synchronized
    private fun realHistoryNow(): String {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        lastStamp?.let { if (!now.isAfter(it)) now = it.plus(1, ChronoUnit.MICROS) }
        lastStamp = now
        return MICROS.format(now)
    }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    /** uuid.uuid5(namespace, site + "\0" + username).hex */
    fun legacyId(site: String, username: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val ns = java.nio.ByteBuffer.allocate(16).putLong(ID_NAMESPACE.mostSignificantBits)
            .putLong(ID_NAMESPACE.leastSignificantBits).array()
        md.update(ns)
        md.update("$site\u0000$username".toByteArray(Charsets.UTF_8))
        val h = md.digest().copyOf(16)
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        return h.toHex()
    }

    // ------------------------------------------------------------------ the schema
    /**
     * Check that decrypted data really is a vault and normalise it, in
     * place (vault.validate_vault): every entry gets an id and its history
     * in its one canonical form; what a newer version added is kept.
     */
    fun validateVault(data: Any?): Obj {
        val vault = asObj(data) ?: throw IllegalArgumentException("This file is not a SecretSpace vault.")
        if (!vault.containsKey("accounts") || vault["accounts"] == null) throw IllegalArgumentException(
            "This vault has no 'accounts' section; it may have been written by another program.",
        )
        val accounts = asList(vault["accounts"]) ?: throw IllegalArgumentException(
            "This vault's 'accounts' section is malformed.",
        )
        val clean = mutableListOf<Any?>()
        val seen = mutableSetOf<String>()
        for (raw in accounts) {
            val entry = asObj(raw) ?: continue
            val row: Obj = linkedMapOf(
                "site" to Py.textNoOr(entry, "site"),
                "username" to Py.textNoOr(entry, "username"),
                "password" to Py.textNoOr(entry, "password"),
            )
            for (key in OPTIONAL_FIELDS) {
                val value = text(entry, key)
                if (value.isNotEmpty()) row[key] = value
            }
            if (truthy(row["archived_at"]) && asObj(entry[MOVED_KEY]) != null) row[MOVED_KEY] = entry[MOVED_KEY]
            for ((key, value) in entry) if (key !in KNOWN_ENTRY_FIELDS) row[key] = value
            var id = text(entry, "id")
            if (id.isEmpty() || id in seen) id = legacyId(row["site"] as String, row["username"] as String)
            if (id in seen) id = newId()
            seen += id
            row["id"] = id
            asList(entry["history"])?.let { row["history"] = it }
            normalizeHistory(row)
            clean += row
        }
        vault["accounts"] = clean
        return vault
    }

    fun emptyVault(): Obj = linkedMapOf("accounts" to mutableListOf<Any?>())

    @Suppress("UNCHECKED_CAST")
    fun accountsOf(vault: Map<String, Any?>): MutableList<Obj> = vault["accounts"] as MutableList<Obj>

    // ------------------------------------------------------------------ versions
    fun extraFields(entry: Map<String, Any?>): Obj =
        entry.filterTo(linkedMapOf()) { (k, _) -> k !in KNOWN_ENTRY_FIELDS && k !in REVISION_META }

    fun extraKey(entry: Map<String, Any?>): String {
        val extra = extraFields(entry)
        return if (extra.isNotEmpty()) Py.dumps(extra, sortKeys = true) else ""
    }

    fun snapshot(entry: Map<String, Any?>): Obj {
        val snap: Obj = linkedMapOf()
        for (field in REVISION_FIELDS) {
            val value = text(entry, field)
            if (value.isNotEmpty() || field in listOf("site", "username", "password")) snap[field] = value
        }
        snap.putAll(Py.deepCopyObj(extraFields(entry)))
        return snap
    }

    fun contentKey(fields: Map<String, Any?>): List<String> {
        val key = KEY_FIELDS_BEFORE_URL.map { text(fields, it) }
        val url = text(fields, "url")
        val extra = extraKey(fields)
        if (extra.isNotEmpty()) return key + listOf(url, extra)
        return if (url.isNotEmpty()) key + url else key
    }

    fun changedFields(old: Map<String, Any?>, new: Map<String, Any?>): List<String> =
        REVISION_FIELDS.filter { text(old, it) != text(new, it) }

    private fun revisionId(rev: Map<String, Any?>): String =
        sha256Hex(Py.dumps(listOf(Py.textNoOr(rev, "replaced_at"), contentKey(rev)))).take(16)

    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")

    private fun cleanRevision(raw: Any?): Obj? {
        val r = asObj(raw) ?: return null
        val rev = snapshot(r)
        rev["replaced_at"] = text(r, "replaced_at")
        val changed = asList(r["changed"]) ?: mutableListOf()
        rev["changed"] = REVISION_FIELDS.filter { it in changed } +
            changed.filterIsInstance<String>().filter { it.isNotEmpty() && it !in REVISION_FIELDS }.toSortedSet().toList()
        val by = Py.take(text(r, "by"), 64)
        if (by.isNotEmpty()) rev["by"] = by
        rev["rid"] = revisionId(rev)
        return rev
    }

    private val revisionOrder = compareBy<Obj>({ it["replaced_at"] as String }, { it["rid"] as String })

    /** An entry's history in its one canonical form, in place (vault.normalize_history). */
    fun normalizeHistory(entry: Obj): Obj {
        val cutoff = text(entry, "history_cleared_at")
        val current = contentKey(snapshot(entry))
        val best = linkedMapOf<List<String>, Obj>()
        for (raw in asList(entry["history"]).takeIf { truthy(it) } ?: emptyList()) {
            val rev = cleanRevision(raw) ?: continue
            if (cutoff.isNotEmpty() && (rev["replaced_at"] as String) <= cutoff) continue
            val key = contentKey(rev)
            if (key == current) continue
            val kept = best[key]
            if (kept == null || revisionOrder.compare(rev, kept) > 0) best[key] = rev
        }
        val history = best.values.sortedWith(revisionOrder.reversed()).take(HISTORY_LIMIT)
        if (history.isNotEmpty()) entry["history"] = history.toMutableList<Any?>() else entry.remove("history")
        return entry
    }

    fun historySignature(entry: Map<String, Any?>): String = Py.dumps(listOf(
        entry["history"].takeIf { truthy(it) } ?: emptyList<Any?>(),
        if ("history_cleared_at" in entry) entry["history_cleared_at"] else "",
    ), sortKeys = true)

    fun addRevision(entry: Obj, old: Map<String, Any?>, changed: List<String>, at: String? = null): Obj {
        val rev = snapshot(old)
        rev["replaced_at"] = at ?: historyNow()
        rev["changed"] = changed.toMutableList()
        entry["history"] = ((asList(entry["history"]) ?: emptyList()) + listOf(rev)).toMutableList()
        return normalizeHistory(entry)
    }

    /** The union of ``target``'s history and the others', in place; the latest clear wins. */
    fun mergeHistory(target: Obj, vararg others: Map<String, Any?>?): Obj {
        val pool = (asList(target["history"]) ?: emptyList<Any?>()).toMutableList()
        var cutoff = text(target, "history_cleared_at")
        for (other in others) {
            if (other.isNullOrEmpty()) continue
            pool.addAll(asList(other["history"]) ?: emptyList())
            val theirs = text(other, "history_cleared_at")
            if (theirs > cutoff) cutoff = theirs
        }
        target["history"] = pool
        if (cutoff.isNotEmpty()) target["history_cleared_at"] = cutoff
        return normalizeHistory(target)
    }

    // ------------------------------------------------------------------ edits
    private fun setField(entry: Obj, field: String, value: Any?) {
        val v = if (truthy(value)) Py.str(value) else ""
        if (v.isNotEmpty() || field in listOf("site", "username", "password")) entry[field] = v else entry.remove(field)
    }

    private fun noteEdit(entry: Obj, old: Map<String, Any?>): Boolean {
        val changed = changedFields(old, entry)
        if (changed.isEmpty() && extraKey(old) == extraKey(entry)) return false
        addRevision(entry, old, changed)
        touch(entry)
        return true
    }

    /** The single path for user edits: what the fields were becomes a revision. */
    fun updateEntry(entry: Obj, fields: Map<String, String?>): Boolean {
        val unknown = fields.keys - REVISION_FIELDS.toSet()
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Not editable fields: ${unknown.sorted()}")
        val old = snapshot(entry)
        for ((field, value) in fields) setField(entry, field, value)
        return noteEdit(entry, old)
    }

    fun findRevision(entry: Map<String, Any?>, rid: String): Obj? =
        (asList(entry["history"]) ?: emptyList()).mapNotNull { asObj(it) }.firstOrNull { it["rid"] == rid }

    fun revertEntry(entry: Obj, rid: String): Boolean {
        val rev = findRevision(entry, rid) ?: return false
        val old = snapshot(entry)
        for (field in REVISION_FIELDS) setField(entry, field, rev[field] ?: "")
        entry.putAll(Py.deepCopyObj(extraFields(rev)))
        return noteEdit(entry, old)
    }

    fun clearHistory(entry: Obj) {
        entry["history_cleared_at"] = historyNow()
        normalizeHistory(entry)
    }

    fun touch(entry: Obj): Obj = entry.also { it["updated_at"] = utcNow() }

    fun addAccount(vault: Obj, site: String, username: String, password: String, notes: String = "",
                   totp: String = "", url: String = ""): Obj {
        val entry: Obj = linkedMapOf("id" to newId(), "site" to site, "username" to username,
            "password" to password, "updated_at" to utcNow())
        if (notes.isNotEmpty()) entry["notes"] = notes
        if (totp.isNotEmpty()) entry["totp"] = totp
        if (url.isNotEmpty()) entry["url"] = url
        accountsOf(vault).add(entry)
        return entry
    }

    fun isArchived(entry: Map<String, Any?>): Boolean = truthy(entry["archived_at"])

    fun findEntry(vault: Map<String, Any?>, id: String): Obj? = accountsOf(vault).firstOrNull { it["id"] == id }

    fun archiveAccount(vault: Obj, id: String): Boolean {
        val entry = findEntry(vault, id) ?: return false
        if (isArchived(entry)) return false
        entry["archived_at"] = utcNow()
        touch(entry)
        return true
    }

    fun restoreAccount(vault: Obj, id: String): Boolean {
        val entry = findEntry(vault, id) ?: return false
        if (!isArchived(entry)) return false
        entry.remove("archived_at")
        entry.remove(MOVED_KEY)
        touch(entry)
        return true
    }

    fun purgeAccount(vault: Obj, id: String): Boolean {
        val accounts = accountsOf(vault)
        val i = accounts.indexOfFirst { it["id"] == id }
        if (i < 0) return false
        accounts.removeAt(i)
        return true
    }

    // ------------------------------------------------------------------ "needs a newer app"
    const val NEEDS_KEY = "needs_app"
    const val DATA_LEVEL = 1

    fun neededLevel(data: Map<String, Any?>?): Int {
        val level = asObj(data?.get(NEEDS_KEY))?.get("level")
        return if (level is Long && level > 0) level.toInt() else 0
    }

    fun tooNew(data: Map<String, Any?>): Boolean = neededLevel(data) > DATA_LEVEL

    fun neededVersion(data: Map<String, Any?>, platform: String = AppVersion.PLATFORM): String {
        if (!tooNew(data)) return ""
        val text = Py.textNoOr(asObj(data[NEEDS_KEY])!!, platform)
        return if (Regex("""\d{1,4}(\.\d{1,4}){0,2}""").matches(text)) text else ""
    }

    // ------------------------------------------------------------------ moves (moves.py)
    fun moveSum(entry: Map<String, Any?>): String = sha256Hex(Py.dumps(listOf(
        contentKey(snapshot(entry)),
        (asList(entry["history"]) ?: emptyList()).map { asObj(it)?.get("rid") },
        text(entry, "history_cleared_at"),
    )))

    /** The mark of an entry moved away and not touched since, else null. */
    fun moveMarker(entry: Map<String, Any?>): Obj? {
        val mark = asObj(entry[MOVED_KEY])
        if (mark == null || !isArchived(entry)) return null
        return if (mark["at"] == entry["archived_at"]) mark else null
    }

    /** Move a live entry from ``source`` into ``target`` (vault ``targetId``); returns the copy. */
    fun move(source: Obj, target: Obj, id: String, targetId: String): Obj? {
        val entry = findEntry(source, id) ?: return null
        if (isArchived(entry) || source === target) return null
        val moved = Py.deepCopyObj(entry)
        moved.remove(MOVED_KEY)
        moved["id"] = sha256Hex("move|$id|$targetId").take(32)
        if (findEntry(target, moved["id"] as String) != null) moved["id"] = newId()
        accountsOf(target).add(moved)
        archiveAccount(source, id)
        entry[MOVED_KEY] = linkedMapOf<String, Any?>("vault" to targetId, "id" to moved["id"],
            "at" to entry["archived_at"], "sum" to moveSum(moved))
        return moved
    }

    /** Delete the originals whose copy has arrived (``arrived(vault, entry)``). */
    fun settle(data: Obj, arrived: (String?, String?) -> Boolean): Int {
        val done = accountsOf(data).filter { entry ->
            val mark = moveMarker(entry)
            mark != null && mark["sum"] == moveSum(entry) && arrived(mark["vault"] as? String, mark["id"] as? String)
        }.map { it["id"] as String }
        done.forEach { purgeAccount(data, it) }
        return done.size
    }
}
