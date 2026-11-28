package secretspace.core

import secretspace.core.Disk.flag
import secretspace.core.Disk.int
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A change refused because the vault is someone else's to change, or a newer app's. */
class ReadOnlyVault(message: String) : Exception(message)

/**
 * An unlocked account as the app shows it: every vault's data in memory,
 * and the operations of the PC app's main window -- each change noted in
 * the vault's signed change log and written at once, a reader's vault and a
 * newer app's left alone, and Sync with its questions.
 *
 * Not thread-safe: the app calls it from one thread (or one at a time).
 */
class OpenAccount(val account: Account) {
    companion object {
        /** As many lines of a vault's change log as the PC's Activity shows. */
        const val ACTIVITY_SHOWN = 200
    }

    val data = linkedMapOf<String, Obj>()

    class VaultInfo(val id: String, val name: String, val role: String, val canWrite: Boolean,
                    val shared: Boolean, val personal: Boolean, val goneFromServer: Boolean,
                    val newerAppText: String)

    /** Read every vault from disk; returns the names of those that could not be read. */
    fun load(): List<String> {
        data.clear()
        val unreadable = mutableListOf<String>()
        for (id in account.vaultIds()) {
            val file = account.vaultFile(id)
            try {
                data[id] = if (file.hasFile) file.load() else Entries.emptyVault()
            } catch (e: Exception) {
                unreadable += file.name
            }
        }
        return unreadable
    }

    fun vaults(): List<VaultInfo> = account.vaultIds().filter { it in data }.map { id ->
        val file = account.vaultFile(id)
        val needs = Entries.neededVersion(data.getValue(id))
        VaultInfo(id, file.name, account.vaultRole(id), account.canWrite(id),
            id != account.personalId && account.isShared(id), file.personal,
            account.vaultRecord(id)["gone_from_server"]?.toString() == "true",
            if (account.needsNewerApp(id)) (if (needs.isNotEmpty()) "SecretSpace $needs or newer" else "a newer SecretSpace") else "")
    }

    // ------------------------------------------------------------------ reading
    /** vault.sort_accounts: by date (undated last) or by name, A-Z on ties. */
    fun sorted(entries: List<Obj>, order: String = "modified_desc"): List<Obj> {
        val nameKey = compareBy<Obj>({ Py.textNoOr(it, "site").lowercase() }, { Py.textNoOr(it, "username").lowercase() })
        val byName = entries.sortedWith(if (order == "name_desc") nameKey.reversed() else nameKey)
        if (order != "modified_desc" && order != "modified_asc") return byName
        val dated = byName.filter { Py.truthy(it["updated_at"]) }
            .sortedBy { Py.str(it["updated_at"]) }.let { if (order == "modified_desc") it.reversedStable() else it }
        return dated + byName.filter { !Py.truthy(it["updated_at"]) }
    }

    /** A descending stable sort: equal keys keep their A-Z order, as Python's sort(reverse=True) does. */
    private fun List<Obj>.reversedStable(): List<Obj> =
        withIndex().sortedWith(compareByDescending<IndexedValue<Obj>> { Py.str(it.value["updated_at"]) }
            .thenBy { it.index }).map { it.value }

    /** The entries of a vault the list shows: live (or archived), matching ``query``. */
    fun entries(vaultId: String, query: String = "", archived: Boolean = false,
                order: String = "modified_desc"): List<Obj> {
        val all = Entries.accountsOf(data.getValue(vaultId)).filter { Entries.isArchived(it) == archived }
        val q = query.trim().lowercase()
        val found = if (q.isEmpty()) all else all.filter { e ->
            listOf("site", "username", "url").any { Py.text(e, it).lowercase().contains(q) }
        }
        return if (archived) found.sortedByDescending { Py.text(it, "archived_at") } else sorted(found, order)
    }

    fun find(vaultId: String, entryId: String): Obj? = data[vaultId]?.let { Entries.findEntry(it, entryId) }

    // ------------------------------------------------------------------ changing
    /**
     * Change a vault: ``change`` edits its data, then what changed goes into
     * the signed change log and the vault is written. Nothing is written,
     * and [ReadOnlyVault] raised, for a vault this account may only read.
     */
    fun <T> change(vaultId: String, change: (Obj) -> T): T {
        val file = account.vaultFile(vaultId)
        if (!account.canWrite(vaultId)) throw ReadOnlyVault(
            if (account.needsNewerApp(vaultId)) "“${file.name}” was changed by a newer SecretSpace: update this one to change it."
            else "“${file.name}” is shared with you for reading only.",
        )
        val current = data.getValue(vaultId)
        val before = Py.deepCopyObj(current)
        val working = Py.deepCopyObj(current)
        val result = change(working)
        Changelog.record(working, before, account.username, account.keyset, file.hex, file.fmt)
        file.save(working)
        data[vaultId] = working
        return result
    }

    fun add(vaultId: String, site: String, username: String, password: String, notes: String = "",
            totp: String = "", url: String = ""): Obj =
        change(vaultId) { Entries.addAccount(it, site, username, password, notes, totp, url) }

    fun edit(vaultId: String, entryId: String, fields: Map<String, String?>): Boolean = change(vaultId) {
        Entries.updateEntry(Entries.findEntry(it, entryId) ?: throw NoSuchElementException("That entry is gone."), fields)
    }

    fun archive(vaultId: String, entryId: String) = change(vaultId) { Entries.archiveAccount(it, entryId) }
    fun restore(vaultId: String, entryId: String) = change(vaultId) { Entries.restoreAccount(it, entryId) }
    fun purge(vaultId: String, entryId: String) = change(vaultId) { Entries.purgeAccount(it, entryId) }
    fun revert(vaultId: String, entryId: String, rid: String) = change(vaultId) {
        Entries.revertEntry(Entries.findEntry(it, entryId) ?: return@change false, rid)
    }
    fun clearHistory(vaultId: String, entryId: String) = change(vaultId) {
        Entries.findEntry(it, entryId)?.let { e -> Entries.clearHistory(e) }
    }

    /** Move a live entry to another vault; the original goes once the copy has arrived. */
    fun move(fromId: String, entryId: String, toId: String): Obj? {
        if (!account.canWrite(toId)) throw ReadOnlyVault("“${account.vaultFile(toId).name}” is shared with you for reading only.")
        var moved: Obj? = null
        change(fromId) { source ->
            val target = Py.deepCopyObj(data.getValue(toId))
            moved = Entries.move(source, target, entryId, toId) ?: return@change
            change(toId) { t -> t.clear(); t.putAll(target) }
        }
        finishMoves()
        return moved
    }

    // ------------------------------------------------------------------ the vaults (the PC's Vaults window)
    /** A new, empty vault on this phone; the next Sync puts it on the server. */
    fun createVault(name: String): String {
        val made = account.createVault(name)
        data[made.hex] = made.load()
        return made.hex
    }

    /** The owner's new name for a vault; told to the server at the next Sync. */
    fun renameVault(vaultId: String, name: String) {
        if (account.vaultRole(vaultId) != AccountStore.ROLE_OWNER) {
            throw ReadOnlyVault("Only an owner of “${account.vaultFile(vaultId).name}” can rename it.")
        }
        account.renameVault(vaultId, name)
    }

    /** Delete it (or leave someone else's), on the server first. */
    fun deleteVault(vaultId: String) {
        AccountSync.deleteVault(account, vaultId)
        data.remove(vaultId)
    }

    /** A vault deleted elsewhere and kept here goes back on the server at the next Sync. */
    fun putVaultBack(vaultId: String) = AccountSync.putVaultBack(account, vaultId)

    // ------------------------------------------------------------------ sharing (the PC's Members window)
    /** Why the vault cannot be shared now, or "". */
    fun whyNotShare(vaultId: String) = AccountSync.whyNotShare(account, vaultId)

    fun members(vaultId: String): List<AccountSync.Member> =
        AccountSync.sharing(account) { AccountSync.listMembers(account, it, vaultId) }

    /** Someone's keys from the server, to compare their fingerprint before sharing or trusting them. */
    fun lookUp(username: String): AccountSync.ContactKeys =
        AccountSync.sharing(account) { AccountSync.lookupContact(account, it, username.trim()) }

    /** After the fingerprint was compared and matched. */
    fun share(vaultId: String, keys: AccountSync.ContactKeys, role: String) =
        AccountSync.sharing(account) { AccountSync.shareVault(account, it, vaultId, keys, role) }

    /** Members -> Check: the fingerprint matched, so this phone trusts these keys from now on. */
    fun trust(keys: AccountSync.ContactKeys) = AccountSync.trustContact(account, keys, true)

    fun setRole(vaultId: String, username: String, role: String) {
        AccountSync.sharing(account) { it.putMember(vaultId, username, role) }
    }

    /** A new key for the vault, taking ``remove`` out in the same step; the vault is synced first. */
    fun changeKey(vaultId: String, remove: Collection<String> = emptyList()) {
        data[vaultId] = AccountSync.sharing(account) {
            AccountSync.changeKey(account, it, vaultId, data.getValue(vaultId), remove)
        }
    }

    // ------------------------------------------------------------------ import and export (the PC's dialogs)
    class ImportPlan(val rows: List<Importers.Row>, val duplicates: Int, val dropped: List<String>) {
        val carried get() = Importers.importedFields(rows)
    }

    /**
     * What an import would add to the vault (dialogs._finish_import): rows
     * that carry something, a nameless one called "(unknown)", and none that
     * is already there or twice in the file (same name, username, password).
     */
    fun planImport(vaultId: String, parsed: Importers.Result): ImportPlan {
        val seen = Entries.accountsOf(data.getValue(vaultId))
            .map { Triple(Py.text(it, "site"), Py.text(it, "username"), Py.text(it, "password")) }.toMutableSet()
        val fresh = mutableListOf<Importers.Row>()
        var duplicates = 0
        for (row in parsed.rows) {
            if (row.site.isEmpty() && row.username.isEmpty() && row.password.isEmpty()) continue
            val named = if (row.site.isEmpty()) Importers.Row("(unknown)", row.username, row.password, row.notes,
                row.totp, row.url) else row
            if (!seen.add(Triple(named.site, named.username, named.password))) duplicates++ else fresh += named
        }
        return ImportPlan(fresh, duplicates, parsed.dropped)
    }

    /** Add the planned rows in one change (one line in the log for many); a non-web address is not kept. */
    fun importRows(vaultId: String, rows: List<Importers.Row>): Int = change(vaultId) { d ->
        for (r in rows) Entries.addAccount(d, r.site, r.username, r.password, r.notes, r.totp, Weblink.openable(r.url) ?: "")
        rows.size
    }

    // ------------------------------------------------------------------ activity (the PC's Activity window)
    class ActivityRow(val at: String, val by: String, val what: String, val state: String)

    /** The vault's change log, newest first (the last 200), each line's signature checked. */
    fun activity(vaultId: String): List<ActivityRow> {
        val hex = account.vaultFile(vaultId).hex
        return Changelog.linesOf(data[vaultId]).takeLast(ACTIVITY_SHOWN).reversed().map { line ->
            val by = Py.text(line, "by")
            ActivityRow(Py.text(line, "at"), by, Changelog.describe(line),
                Changelog.check(line, hex, account.signingKeyFor(by)))
        }
    }

    /** Fetch the keys of people this phone has not met, so their lines can be checked (not marked as compared). */
    fun fetchKeys(names: Collection<String>) = AccountSync.sharing(account) { server ->
        for (who in names) {
            val keys = try {
                AccountSync.lookupContact(account, server, who)
            } catch (e: SyncError) {
                continue                                // no such account any more
            }
            if (keys.status == "new") AccountSync.trustContact(account, keys, false)
        }
    }

    /** What the server noted about the vault, newest first: (when, "who did what · from where"). Owners only. */
    fun serverLog(vaultId: String): List<Pair<String, String>> =
        AccountSync.sharing(account) { it.vaultLog(vaultId) }.reversed().map { line ->
            val plain = Py.asObj(Py.native(line))!!
            val address = Py.text(plain, "address")
            Py.text(plain, "at") to ((if ("by" in plain) Py.str(plain["by"]) else "?") + " " +
                Changelog.describeServerLine(plain) + if (address.isNotEmpty()) "   ·   from $address" else "")
        }

    /** Where a vault lives, in words (the PC's vaults_ui._status); ``true`` when it is a warning. */
    fun vaultStatus(vaultId: String): Pair<String, Boolean> {
        if (vaultId == account.personalId) return "Your own vault. It comes with the account." to false
        val record = account.vaultRecord(vaultId)
        val role = account.vaultRole(vaultId)
        if (record.flag("gone_from_server")) {
            return (if (role != AccountStore.ROLE_OWNER) "No longer shared with you, or deleted. Kept on this " +
                "phone only, and no longer synced." else "Deleted on another device. Kept on this phone only, " +
                "and no longer synced.") to true
        }
        if (account.server == null) return "On this phone." to false
        if (record.flag("on_server")) {
            val others = (record.int("members") ?: 1) - 1
            if (role != AccountStore.ROLE_OWNER) {
                return "Shared with you — you are ${if (role == AccountStore.ROLE_EDITOR) "an editor" else "a reader"}." to false
            }
            if (others > 0) return "Shared with $others other ${if (others == 1) "person" else "people"}." to false
            return "On the server — your other devices get it when they sync." to false
        }
        return "On this phone until the next sync puts it on the server." to false
    }

    // ------------------------------------------------------------------ housekeeping
    /** vault.archive_purge_days: 0 = keep archived entries until deleted by hand. */
    fun purgeDays(vaultId: String): Int {
        val raw = data[vaultId]?.get("archive_purge_days")
        val days = when (raw) {
            is Long -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: 0
            is Double -> raw.toInt()
            is Boolean -> if (raw) 1 else 0
            else -> 0
        }
        return if (days in 1..3650) days else 0
    }

    fun setPurgeDays(vaultId: String, days: Int) = change(vaultId) {
        if (days > 0) it["archive_purge_days"] = days.toLong() else it.remove("archive_purge_days")
    }

    private val ARCHIVED_AT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

    /** Archived entries past each vault's keep-for limit go; returns how many. */
    fun autoPurge(now: Instant = Instant.now()): Int {
        var total = 0
        for (id in data.keys.toList()) {
            val days = purgeDays(id)
            if (days == 0 || !account.canWrite(id)) continue
            val cutoff = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
                .format(now.minus(days.toLong(), ChronoUnit.DAYS))
            val doomed = Entries.accountsOf(data.getValue(id)).filter {
                Entries.isArchived(it) && it["archived_at"] is String &&
                    ARCHIVED_AT.matches(it["archived_at"] as String) && (it["archived_at"] as String) < cutoff
            }.map { it["id"] as String }
            if (doomed.isEmpty()) continue
            change(id) { d -> doomed.forEach { Entries.purgeAccount(d, it) } }
            total += doomed.size
        }
        return total
    }

    /** moves.arrived_in + settle: originals whose copy is on the server (or on disk) go. */
    fun finishMoves(): List<String> {
        val synced = mutableMapOf<String, Set<Any?>>()
        fun arrived(vaultId: String?, entryId: String?): Boolean {
            if (vaultId == null || vaultId !in data) return false
            val ids = synced.getOrPut(vaultId) {
                val file = account.vaultFile(vaultId)
                val there = try {
                    if (account.server != null) file.loadBase() else if (file.hasFile) file.load() else null
                } catch (e: Exception) {
                    null
                }
                Entries.accountsOf(there ?: Entries.emptyVault()).map { it["id"] }.toSet()
            }
            return entryId in ids
        }
        val changed = mutableListOf<String>()
        for (id in data.keys.toList()) {
            if (!account.canWrite(id)) continue
            val probe = Py.deepCopyObj(data.getValue(id))
            if (Entries.settle(probe, ::arrived) > 0) {
                change(id) { d -> Entries.settle(d, ::arrived) }
                changed += id
            }
        }
        return changed
    }

    // ------------------------------------------------------------------ Sync
    class SyncReport {
        val changes = mutableListOf<String>()
        val readOnly = mutableListOf<String>()
        val tooNew = mutableListOf<String>()
        val leftForLater = mutableListOf<String>()
        var vaultList: AccountSync.VaultChanges? = null
    }

    /**
     * One press of Sync (the PC's account_sync_ui.run_sync): log in once,
     * make the list of vaults the same here and there, then sync each vault.
     * When one has conflicts, ``decide(vault name, result)`` is asked for a
     * choice per conflict id (Merge.KEEP_*), or null to leave that vault for
     * the next Sync. Runs on a worker thread; ``decide`` may wait for the user.
     */
    fun sync(decide: (String, Merge.Result) -> Map<String, String>?): SyncReport {
        val report = SyncReport()
        val server = AccountSync.loggedIn(account)
        try {
            report.vaultList = AccountSync.reconcileVaults(account, server)
            val queue = AccountSync.syncableVaults(account, server).toMutableList()
            load()
            var movesDone = false
            while (true) {
                if (queue.isEmpty() && !movesDone) {
                    movesDone = true
                    queue += finishMoves().filter { it in AccountSync.syncableVaults(account, server) }
                }
                val id = queue.removeFirstOrNull() ?: break
                syncOne(server, id, decide, report, retry = true)
            }
        } finally {
            server.logout()
        }
        return report
    }

    private fun syncOne(server: AccountServer, id: String, decide: (String, Merge.Result) -> Map<String, String>?,
                        report: SyncReport, retry: Boolean) {
        val session = VaultSync(account, server, id)
        val name = session.file.name
        try {
            val local = data.getValue(id)
            val result = session.prepare(local)
            if (!session.hasWork()) return
            val choices = if (result.conflicts.isEmpty()) null else decide(name, result) ?: run {
                report.leftForLater += name
                return
            }
            data[id] = session.commit(local, choices)
            if (result.pulled > 0 || result.conflicts.isNotEmpty()) report.changes += "$name: ${result.summary()}"
            if (!account.canWrite(id) && result.needsPush) report.readOnly += name
        } catch (e: Conflict409) {
            if (!retry) throw e
            syncOne(server, id, decide, report, retry = false)      // the server moved on: merge again
        } catch (e: NeedsNewerApp) {
            report.tooNew += name
        }
    }
}
