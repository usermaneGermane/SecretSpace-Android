package secretspace.core

import secretspace.core.Entries.isArchived
import secretspace.core.Py.asList
import secretspace.core.Py.asObj
import secretspace.core.Py.text

/**
 * Three-way merge of two copies of a vault, with the last agreed copy as
 * the common ancestor: the PC's sync.py, line for line. Nothing is ever
 * dropped -- the losing side of a conflict becomes a revision, an archived
 * copy or a second entry -- and ``updated_at`` never decides anything.
 */
object Merge {
    val CONTENT_FIELDS = listOf("site", "username", "password", "notes", "totp", "url", "archived_at")
    val KNOWN_VAULT_KEYS = setOf("accounts", "sync", "changes", "base_origin", "archive_purge_days", Entries.NEEDS_KEY)
    const val KEEP_LOCAL = "local"
    const val KEEP_REMOTE = "remote"
    const val KEEP_BOTH = "both"
    const val DEFAULT_RESOLUTION = KEEP_BOTH

    fun content(entry: Map<String, Any?>): List<String> =
        CONTENT_FIELDS.filter { it != "archived_at" }.map { text(entry, it) } +
            (if (isArchived(entry)) "archived" else "") + Entries.extraKey(entry)

    fun same(a: Map<String, Any?>, b: Map<String, Any?>) = content(a) == content(b)

    private fun newer(a: Obj, b: Obj): Obj = if (Py.textNoOr(a, "updated_at") >= Py.textNoOr(b, "updated_at")) a else b

    /** One entry both sides changed. ``kind``: edit, archive, purge or missing. */
    class Conflict(val id: String, val base: Obj?, val local: Obj?, val remote: Obj?, val kind: String = "edit") {
        val site: String get() = listOf(local, remote, base).firstOrNull { it != null }?.let { Py.textNoOr(it, "site") } ?: ""
        override fun toString() = "<Conflict $kind '$site' id=${id.take(8)}>"
    }

    class Result(val accounts: List<Obj>, val conflicts: List<Conflict>, val stats: Map<String, Int>,
                 val extra: Obj = linkedMapOf()) {
        val clean get() = conflicts.isEmpty()
        val pulled get() = listOf("new", "updated", "archived", "removed").sumOf { stats.getValue(it) }
        val needsPush get() = conflicts.isNotEmpty() || stats.getValue("push") > 0 ||
            stats.getValue("history_out") > 0 || stats.getValue("extra_out") > 0
        val changed get() = needsPush || pulled > 0 || stats.getValue("history_in") > 0 || stats.getValue("extra_in") > 0

        fun summary(): String {
            val parts = listOf("new" to "added", "updated" to "updated", "archived" to "archived",
                "removed" to "removed").filter { stats.getValue(it.first) > 0 }
                .map { "${stats.getValue(it.first)} ${it.second}" }.toMutableList()
            if (stats.getValue("push") > 0) parts += "${stats.getValue("push")} uploaded"
            if (conflicts.isNotEmpty()) parts += "${conflicts.size} to review"
            if (parts.isEmpty() && (stats.getValue("history_in") > 0 || stats.getValue("history_out") > 0)) {
                return "revision history updated"
            }
            if (parts.isEmpty() && (stats.getValue("extra_in") > 0 || stats.getValue("extra_out") > 0)) {
                return "vault details updated"
            }
            return if (parts.isEmpty()) "already up to date" else parts.joinToString(", ")
        }
    }

    private fun byId(vault: Map<String, Any?>?): LinkedHashMap<String, Obj> {
        val out = linkedMapOf<String, Obj>()
        if (vault.isNullOrEmpty()) return out
        for (a in asList(vault["accounts"]) ?: emptyList()) {
            val e = asObj(a)!!
            if (Py.truthy(e["id"])) out[e["id"] as String] = e
        }
        return out
    }

    private fun extraKeys(data: Map<String, Any?>?): Obj =
        (data ?: emptyMap()).filterTo(linkedMapOf()) { it.key !in KNOWN_VAULT_KEYS }

    private fun plain(value: Any?) = Py.dumps(value, sortKeys = true)

    private fun needs(data: Map<String, Any?>): Obj =
        if (Entries.neededLevel(data) > 0) linkedMapOf(Entries.NEEDS_KEY to data[Entries.NEEDS_KEY]) else linkedMapOf()

    /** The top-level keys this version does not know, from both sides. */
    fun mergeExtra(base: Map<String, Any?>?, local: Map<String, Any?>?, remote: Map<String, Any?>?): Obj {
        val b = extraKeys(base)
        val l = extraKeys(local)
        val r = extraKeys(remote)
        val merged: Obj = LinkedHashMap(r).apply { putAll(l) }
        val lo = local ?: emptyMap()
        val ro = remote ?: emptyMap()
        // max() keeps the first of equals: this device's
        val needs = needs(if (Entries.neededLevel(ro) > Entries.neededLevel(lo)) ro else lo)
        for (key in l.keys intersect r.keys) {
            if (key in b && plain(l[key]) == plain(b[key])) merged[key] = r[key]
        }
        return merged.apply { putAll(needs) }
    }

    /** Merge ``remote`` into ``local`` with ``base`` (or null) as the common ancestor. */
    fun mergeVaults(base: Map<String, Any?>?, local: Map<String, Any?>?, remote: Map<String, Any?>?): Result {
        val baseById = byId(base)
        val localById = byId(local)
        val remoteById = byId(remote)
        val merged = mutableListOf<Obj>()
        val conflicts = mutableListOf<Conflict>()
        val stats = linkedMapOf("new" to 0, "updated" to 0, "archived" to 0, "removed" to 0, "unchanged" to 0,
            "push" to 0, "history_in" to 0, "history_out" to 0, "extra_in" to 0, "extra_out" to 0)
        fun bump(key: String) { stats[key] = stats.getValue(key) + 1 }
        val extra = mergeExtra(base, local, remote)
        if (plain(extra) != plain(extraKeys(local).apply { putAll(needs(local ?: emptyMap())) })) stats["extra_in"] = 1
        if (plain(extra) != plain(extraKeys(remote).apply { putAll(needs(remote ?: emptyMap())) })) stats["extra_out"] = 1

        fun takeRemote(entry: Obj, was: Obj?) {
            when {
                was == null -> bump("new")
                isArchived(entry) && !isArchived(was) -> bump("archived")
                else -> bump("updated")
            }
            merged += entry
        }

        fun keepLocal(entry: Obj) {
            bump("push")
            merged += entry
        }

        fun joined(chosen: Obj, l: Obj, r: Obj): Obj {
            val out: Obj = LinkedHashMap(chosen)
            Entries.mergeHistory(out, l, r)
            val signature = Entries.historySignature(out)
            if (signature != Entries.historySignature(l)) bump("history_in")
            if (signature != Entries.historySignature(r)) bump("history_out")
            return out
        }

        val ordered = localById.keys.toMutableList()
        ordered += remoteById.keys.filter { it !in localById }
        ordered += baseById.keys.filter { it !in localById && it !in remoteById }

        for (id in ordered) {
            val b = baseById[id]
            val l = localById[id]
            val r = remoteById[id]
            if (l == null && r == null) continue
            if (l == null) {
                when {
                    b == null -> takeRemote(r!!, null)
                    same(r!!, b) -> bump("push")
                    else -> conflicts += Conflict(id, b, null, r, "purge")
                }
                continue
            }
            if (r == null) {
                when {
                    b == null -> keepLocal(l)
                    !same(l, b) -> conflicts += Conflict(id, b, l, null, "purge")
                    isArchived(l) -> bump("removed")
                    Changelog.movedAway(id, local, remote) -> bump("removed")
                    else -> conflicts += Conflict(id, b, l, null, "missing")
                }
                continue
            }
            when {
                same(l, r) -> {
                    merged += joined(newer(l, r), l, r)
                    bump("unchanged")
                }
                b == null -> conflicts += Conflict(id, null, l, r, "edit")
                same(l, b) -> takeRemote(joined(r, l, r), l)
                same(r, b) -> keepLocal(joined(l, l, r))
                else -> conflicts += Conflict(id, b, l, r, if (isArchived(l) != isArchived(r)) "archive" else "edit")
            }
        }
        return Result(merged, conflicts, stats, extra)
    }

    private fun archivedCopy(entry: Obj): Obj {
        val copy: Obj = LinkedHashMap(entry)
        copy["id"] = Entries.newId()
        copy["archived_at"] = if (Py.truthy(entry["archived_at"])) entry["archived_at"] else Entries.utcNow()
        copy.remove(Entries.MOVED_KEY)
        copy["updated_at"] = Entries.utcNow()
        return copy
    }

    private fun activeCopy(entry: Obj): Obj {
        val copy: Obj = LinkedHashMap(entry)
        copy["id"] = Entries.newId()
        copy["updated_at"] = Entries.utcNow()
        copy.remove(Entries.MOVED_KEY)
        return copy
    }

    private fun winner(kept: Obj, lost: Obj): Obj {
        val out: Obj = LinkedHashMap(kept)
        Entries.mergeHistory(out, kept, lost)
        Entries.addRevision(out, Entries.snapshot(lost), Entries.changedFields(lost, kept))
        return out
    }

    /** The user's decisions folded back in: the final list of entries. */
    fun resolve(result: Result, choices: Map<String, String>? = null): MutableList<Obj> {
        val accounts = result.accounts.toMutableList()
        for (conflict in result.conflicts) {
            val choice = choices?.get(conflict.id) ?: DEFAULT_RESOLUTION
            val local = conflict.local
            val remote = conflict.remote
            if (local == null || remote == null) {
                val survivor = local ?: remote!!
                if (choice == (if (local == null) KEEP_REMOTE else KEEP_LOCAL) || choice == KEEP_BOTH) {
                    accounts += survivor
                } else {
                    accounts += archivedCopy(survivor)
                }
                continue
            }
            when (choice) {
                KEEP_LOCAL -> accounts += winner(local, remote)
                KEEP_REMOTE -> accounts += winner(remote, local)
                else -> {
                    accounts += local
                    accounts += activeCopy(remote)
                }
            }
        }
        return accounts
    }

    /** A whole vault around a merged list, keeping the vault's other keys. */
    fun mergedVault(local: Map<String, Any?>?, accounts: List<Obj>, extra: Map<String, Any?>? = null): Obj {
        val out: Obj = LinkedHashMap(local ?: emptyMap())
        out.putAll(extra ?: emptyMap())
        out["accounts"] = accounts.toMutableList<Any?>()
        return out
    }
}
