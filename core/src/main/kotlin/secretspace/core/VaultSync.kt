package secretspace.core

import secretspace.core.Py.asObj
import java.net.URI
import secretspace.core.AccountCrypto as ac

/** What makes two addresses the same server (the PC's sync_client). */
object Servers {
    /** Host, port and path -- not the scheme: one port is one listening program. */
    fun identity(url: String?): Triple<String, Int?, String> {
        val text = (url ?: "").trim()
        val uri = try { URI(text) } catch (e: Exception) { return Triple("", null, "") }
        val scheme = (uri.scheme ?: "").lowercase()
        val host = (uri.host ?: "").lowercase().removePrefix("[").removeSuffix("]")
        val port = if (uri.port in 0..65535) uri.port else mapOf("http" to 80, "https" to 443)[scheme]
        return Triple(host, port, (uri.rawPath ?: "").trimEnd('/'))
    }

    fun same(a: String?, b: String?): Boolean =
        !a.isNullOrEmpty() && !b.isNullOrEmpty() && identity(a) == identity(b)

    /**
     * Whether ``base`` describes the copy now on the server at ``url``: agreed
     * with this same address, and the server not gone back to an earlier
     * revision. A base with no origin (from before origins) is trusted.
     */
    fun baseMatches(base: Map<String, Any?>?, url: String, revision: Int): Boolean {
        val origin = asObj(base?.get(Disk.BASE_ORIGIN_KEY))
        if (origin.isNullOrEmpty()) return true
        val there = origin["url"]
        if (Py.truthy(there) && !same(Py.str(there), url)) return false
        val agreed = when (val r = origin["revision"] ?: 0L) {
            is Long -> r
            is String -> r.trim().toLongOrNull() ?: return true
            is Boolean -> if (r) 1L else 0L
            is Double -> r.toLong()
            else -> return true
        }
        return revision >= agreed
    }
}

/**
 * One sync of one vault of an account, in two halves either side of the
 * user (the PC's account_sync.AccountSync): [prepare] pulls and merges,
 * [commit] folds in the user's choices, saves, pushes and records the base.
 */
class VaultSync(val account: Account, var server: AccountServer? = null, vaultId: String? = null) {
    val file: VaultFile = account.vaultFile(vaultId)
    var revision = 0
        private set
    var result: Merge.Result? = null
        private set
    var firstPush = false
        private set
    var baseIgnored = false
        private set
    private var fingerprint: String? = null
    private var remoteLog: List<Obj> = emptyList()
    private var logIn = false
    private var logOut = false
    private var reformat = false

    companion object {
        /** A cheap hash of a vault's entries, to notice edits made while a sync was open. */
        fun fingerprint(data: Map<String, Any?>?): String =
            Entries.sha256Hex(Py.dumps(data?.get("accounts") ?: mutableListOf<Any?>(), sortKeys = true))
    }

    fun prepare(local: Obj): Merge.Result {
        val session = AccountSync.loggedIn(account, server)
        server = session
        val stored = session.getVault(file.hex)
        revision = stored.revision
        fingerprint = fingerprint(local)
        val blob = stored.blob
        firstPush = blob == null
        val remote: Obj
        if (blob == null) {
            remote = Entries.emptyVault()
        } else {
            remote = try {
                file.decrypt(blob)
            } catch (e: Exception) {
                if (e !is CryptoError && e !is IllegalArgumentException) throw e
                throw SyncError("The vault on the server cannot be read: ${e.message}")
            }
            reformat = ac.vaultHeader(blob).format < file.fmt.number
        }
        baseIgnored = false
        var base: Obj? = null
        if (blob != null) {
            base = file.loadBase()
            if (base != null && !Servers.baseMatches(base, session.url, revision)) {
                base = null
                baseIgnored = true
            }
        }
        val merged = Merge.mergeVaults(base, local, remote)
        result = merged
        remoteLog = Changelog.linesOf(remote)
        val here = Changelog.linesOf(local).map { it["id"] }.toSet()
        val there = remoteLog.map { it["id"] }.toSet()
        val union = Changelog.merge(Changelog.linesOf(local), remoteLog).map { it["id"] }.toSet()
        logIn = union != here
        logOut = union != there && blob != null
        if (Entries.tooNew(remote) || Entries.tooNew(local)) {
            if (merged.needsPush || firstPush || logOut) throw NeedsNewerApp(
                "“${file.name}” was changed by a newer SecretSpace, and this phone has changes of its own in " +
                    "it. They stay on this phone: update SecretSpace, then sync again.",
            )
            reformat = false
        }
        return merged
    }

    fun hasWork(): Boolean {
        val r = result ?: return false
        return r.changed || baseIgnored || firstPush || logIn || ((logOut || reformat) && file.canWrite)
    }

    fun commit(local: Obj, choices: Map<String, String>? = null): Obj {
        val r = result ?: throw SyncError("Nothing prepared to commit.")
        if (fingerprint(local) != fingerprint) throw SyncError(
            "The vault changed while the sync was open. Press Sync again to merge the newer version.",
        )
        val merged = Entries.validateVault(Merge.mergedVault(local, Merge.resolve(r, choices), r.extra))
        val log = Changelog.merge(Changelog.linesOf(local), remoteLog)
        if (log.isNotEmpty() || Changelog.CHANGES_KEY in merged) merged[Changelog.CHANGES_KEY] = log.toMutableList<Any?>()
        // Saved here first, then pushed: if the network fails in between, this
        // phone holds the merge and the next sync pushes it.
        file.save(merged)
        if ((r.needsPush || firstPush || logOut || reformat) && file.canWrite) {
            revision = server!!.putVault(file.hex, file.encryptForServer(merged), revision)
        }
        file.saveBase(merged, baseOrigin(server!!.url, revision))
        return merged
    }

    fun close() {
        server?.logout()
    }
}
