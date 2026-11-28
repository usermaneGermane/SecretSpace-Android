package secretspace.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import secretspace.core.Disk.flag
import secretspace.core.Disk.int
import secretspace.core.Disk.long
import secretspace.core.Disk.obj
import secretspace.core.Disk.text
import secretspace.core.AccountCrypto as ac

/**
 * An account and its server: signing in, whose vault keys to trust, and
 * the account's list of vaults made the same here and there (the PC's
 * account_sync.py). Merging a vault's entries is stage 3.
 */
object AccountSync {

    // ------------------------------------------------------------------ whose key is this?
    /** json.dumps(..., ensure_ascii=True) of one string, as the PC writes it. */
    private fun pyString(text: String): String = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000c' -> append("\\f")
            c.code < 0x20 || c.code > 0x7e -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /**
     * What the signature on a wrapped key covers: which vault, which key
     * generation, for whom, the wrapped key itself, and who vouches.
     */
    fun keyMessage(vaultHex: String, generation: Long, member: String, wrappedKey: String, signer: String): ByteArray {
        val label = String(ac.wrapFormat(wrappedKey).label("vault key"), Charsets.US_ASCII)
        val digest = ac.sha256(wrappedKey.toByteArray(Charsets.US_ASCII)).toHex()
        return ("[" + listOf(pyString(label), pyString(vaultHex), generation.toString(), pyString(member),
            pyString(digest), pyString(signer)).joinToString(",") + "]").toByteArray(Charsets.US_ASCII)
    }

    fun signKey(account: Account, vaultHex: String, generation: Long, member: String, wrappedKey: String): String =
        ac.sign(account.keyset, keyMessage(vaultHex, generation, member, wrappedKey, account.username))

    /**
     * Take the vault key the server lists (``there``, one vault of /v2/me)
     * or raise [KeyNotTrusted]. Returns who signed it. For a vault this
     * device does not have yet (``heldFrom`` null) the signer may be anyone,
     * remembered on first sight; for one it has, the signer must be whoever
     * signed the key held now, or someone whose fingerprint was compared.
     */
    fun checkKey(account: Account, server: AccountServer, there: JsonObject, name: String,
                 heldFrom: String? = null): String {
        val signer = there.text("key_signer") ?: ""
        val sig = there.text("key_sig") ?: ""
        val wrapped = there.text("wrapped_key") ?: ""
        if (sig.isEmpty() || signer.isEmpty()) throw KeyNotTrusted(
            "The server's key for the vault “$name” has no signature (it was put there by an older " +
                "SecretSpace). Sync once from a device that already has this vault, or ask its owner to " +
                "change the vault's key (Members → Change key). Nothing was changed.",
        )
        val key: ByteArray = if (signer == account.username) {
            ac.signingKeyOf(account.keyset)
        } else {
            val seen = account.contact(signer)
            val newVault = heldFrom == null
            if (seen != null && (newVault || seen.verified || signer == heldFrom)) {
                seen.signingKey
            } else if (seen == null && newVault) {
                val keys = lookupContact(account, server, signer)
                trustContact(account, keys, false)
                keys.signingKey
            } else {
                throw KeyNotTrusted(
                    "The server offers a new key for the vault “$name”, signed by “$signer”, whose " +
                        "fingerprint was never checked on this phone. Nothing was changed. If $signer is an " +
                        "owner of this vault, compare fingerprints with them (Vaults → Members → Check), " +
                        "then sync again.",
                )
            }
        }
        val message = keyMessage(there.text("id") ?: "", there.long("key_generation") ?: 0L, account.username,
            wrapped, signer)
        if (!ac.verify(key, message, sig)) throw KeyNotTrusted(
            "The key the server offers for the vault “$name” is not signed by “$signer” as it claims. " +
                "Nothing was changed, and nothing of this vault was sent. If this keeps happening, do not " +
                "trust this server with that vault.",
        )
        return signer
    }

    class ContactKeys(
        val username: String, val publicKey: ByteArray, val signingKey: ByteArray,
        val fingerprint: String, val status: String, val verified: Boolean,
    )

    /** Another account's keys from the server, set against what this device saw before. */
    fun lookupContact(account: Account, server: AccountServer, username: String): ContactKeys {
        val reply = server.publicKeys(username)
        val name = reply.text("username")?.takeIf { it.isNotEmpty() } ?: username
        val public = try { ac.unb64(reply.text("public_key"), "key") } catch (e: CryptoError) { null }
        val signing = try { ac.unb64(reply.text("signing_key") ?: "", "key") } catch (e: CryptoError) { null }
        if (public == null || signing == null || public.size != 32) {
            throw SyncError("The server sent keys this version cannot use.")
        }
        if (signing.size != 32) throw SyncError(
            "“$name” has not used a SecretSpace version that can share vaults yet. Ask them to update and sync once.",
        )
        if (name == account.username) throw SyncError("That is your own account.")
        val seen = account.contact(name)
        var status = "new"
        var verified = false
        if (seen != null) {
            val same = seen.publicKey.contentEquals(public) && seen.signingKey.contentEquals(signing)
            status = if (same) "known" else "changed"
            verified = same && seen.verified
        }
        return ContactKeys(name, public, signing, ac.accountFingerprint(public, signing), status, verified)
    }

    fun trustContact(account: Account, keys: ContactKeys, verified: Boolean) =
        account.rememberContact(keys.username, keys.publicKey, keys.signingKey, verified)

    // ------------------------------------------------------------------ sessions
    fun serverFor(account: Account): AccountServer {
        val found = account.server ?: throw SyncError(
            "This account is not on a sync server yet. Sign in from a server first.",
        )
        return AccountServer(found.text("url")!!, found.text("ca_file") ?: "")
    }

    /** A server session for an open account. */
    fun loggedIn(account: Account, server: AccountServer? = null): AccountServer {
        val session = server ?: serverFor(account)
        val login = account.loginKey ?: throw SyncError("Lock and unlock the account once, then sync.")
        session.checkApi()
        account.serverVersion = session.version
        try {
            session.login(account.username, login)
        } catch (e: LoginFailed) {
            throw LoginFailed(
                "The server did not accept this account's login. If the master password was changed on " +
                    "another device, sign in from the server again with the new password.",
            )
        }
        return session
    }

    // ------------------------------------------------------------------ an account made here goes on a server
    /** POST /v2/accounts with the server's token as the sign-up code; refusals in words. */
    internal fun signUp(server: AccountServer, code: String, username: String, body: JsonObject) {
        try {
            server.token = code
            server.request("POST", "/v2/accounts", body)
        } catch (e: Conflict409) {
            throw SyncError("The server already has an account called “$username” with another master " +
                "password or Secret Key — or it is someone else's. Nothing was changed.")
        } catch (e: LoginFailed) {
            throw LoginFailed("The server did not accept that sign-up code.")
        } finally {
            server.token = ""
        }
    }

    /** The logged-in account's personal vault must be this one, or it is another account of that name. */
    internal fun checkPersonalVault(server: AccountServer, username: String, vaultHex: String) {
        if (server.me().text("personal_vault") != vaultHex) throw SyncError(
            "The server's account “$username” has a different personal vault: it is another account " +
                "with the same name. Nothing was changed.",
        )
    }

    /**
     * Put ``account`` -- one on this phone only -- on the server at ``url``
     * and remember it (the PC's account_sync.connect). If the server has the
     * account already (put there from another device) the login simply
     * works; otherwise it is made there with the server's token as the
     * sign-up code. Returns "created" or "joined". Nothing is remembered
     * unless it worked; the next Sync puts the vaults there.
     */
    fun connect(account: Account, url: String, caFile: String, signUpCode: String): String {
        val login = account.loginKey ?: throw SyncError("Lock and unlock the account once, then connect.")
        val server = AccountServer(url.trim(), caFile)
        server.checkApi()
        var outcome = "joined"
        try {
            try {
                server.login(account.username, login)
            } catch (e: LoginFailed) {
                if (signUpCode.isBlank()) throw LoginFailed(
                    "The server has no such account yet. Enter the server's sign-up code to create it there.")
                val files = account.sealedFiles()
                signUp(server, signUpCode.trim(), account.username, JsonObject(mapOf(
                    "username" to JsonPrimitive(account.username),
                    "login_key" to JsonPrimitive(ac.b64(login)),
                    "keyset" to JsonPrimitive(files.getValue("keyset")),
                    "recovery" to JsonPrimitive(files.getValue("recovery")),
                    "public_key" to JsonPrimitive(ac.b64(ac.publicKeyOf(account.keyset))),
                    "signing_key" to JsonPrimitive(account.signingKey),
                    "recovery_proof" to JsonPrimitive(ac.b64(ac.recoveryProof(account.keyset))),
                    "personal_vault" to JsonPrimitive(account.personalId),
                )))
                server.login(account.username, login)
                outcome = "created"
            }
            checkPersonalVault(server, account.username, account.personalId)
        } finally {
            server.logout()
        }
        account.setServer(server.url, caFile)
        return outcome
    }

    // ------------------------------------------------------------------ the list of vaults
    class VaultChanges {
        val added = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        val gone = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val rekeyed = mutableListOf<String>()
        val roles = mutableListOf<Pair<String, String>>()
        var serverTooOld = false
        val any get() = added.isNotEmpty() || uploaded.isNotEmpty() || gone.isNotEmpty() || renamed.isNotEmpty() ||
            rekeyed.isNotEmpty() || roles.isNotEmpty() || serverTooOld
    }

    private fun yes(v: Boolean) = JsonPrimitive(v)

    /**
     * Make the list of vaults the same on this device and on the server
     * (``server`` logged in); contents are synced afterwards. A vault gone
     * from the server is kept here, marked: deleting someone's entries is
     * their decision, not a sync's.
     */
    fun reconcileVaults(account: Account, server: AccountServer): VaultChanges {
        val changes = VaultChanges()
        val extraHere = account.vaultIds().filter { it != account.personalId }
        val me = server.me()
        val listed = linkedMapOf<String, JsonObject>()
        for (v in (me["vaults"] as? JsonArray).orEmpty()) {
            val o = v as? JsonObject ?: continue
            val id = o.text("id") ?: continue
            if (o.text("kind") != "personal" && id != account.personalId) listed[id] = o
        }
        if (!server.hasVaults() || !server.hasSignedKeys()) {
            changes.serverTooOld = extraHere.isNotEmpty()
            return changes
        }
        if (server.hasSharing() && me.text("signing_key").isNullOrEmpty()) server.putSigningKey(account.signingKey)
        if (server.hasRecovery() && !me.flag("can_recover")) server.putRecoveryProof(ac.recoveryProof(account.keyset))
        val sealed = me.text("recovery")
        if (!sealed.isNullOrEmpty() && sealed != account.sealedFiles()["recovery"]) account.setRecovery(sealed)

        for (vaultId in extraHere) {
            var record = account.vaultRecord(vaultId)
            var file = account.vaultFile(vaultId)
            val there = listed[vaultId]
            if (there == null) {
                if (record.flag("on_server")) {
                    if (!record.flag("gone_from_server")) {
                        account.markVault(vaultId, "gone_from_server" to yes(true))
                        changes.gone += file.name
                    }
                    continue
                }
                val wrapped = record.text("wrapped_key")!!
                try {
                    server.createVault(vaultId, file.sealedName(), wrapped,
                        signKey(account, vaultId, file.generation, account.username, wrapped))
                } catch (e: Conflict409) {
                    throw SyncError("The server already has another vault with the id of “${file.name}”. Nothing was changed.")
                }
                account.markVault(vaultId, "on_server" to yes(true), "gone_from_server" to yes(false),
                    "key_signer" to JsonPrimitive(account.username))
                changes.uploaded += file.name
                continue
            }
            if (!record.flag("on_server") || record.flag("gone_from_server")) {
                account.markVault(vaultId, "on_server" to yes(true), "gone_from_server" to yes(false))
            }
            val generation = there.int("key_generation") ?: 0
            val held = record.int("key_generation") ?: 0
            if (generation < held) throw KeyNotTrusted(
                "The server offers an older key for the vault “${file.name}” than this phone holds. Nothing " +
                    "was changed, and nothing of this vault was sent.",
            )
            if (generation > held) {
                val signer = checkKey(account, server, there, file.name, record.text("key_signer") ?: "")
                try {
                    account.rekeyVault(vaultId, there.text("wrapped_key") ?: "", generation)
                } catch (e: Exception) {
                    if (e !is CryptoError && e !is IllegalArgumentException) throw e
                    throw SyncError("The new key of “${file.name}” cannot be opened by this account (${e.message}). " +
                        "Nothing was changed.")
                }
                account.markVault(vaultId, "key_signer" to JsonPrimitive(signer))
                changes.rekeyed += file.name
                file = account.vaultFile(vaultId)
            } else if ((there.text("wrapped_key") ?: "") != (record.text("wrapped_key") ?: "")) {
                throw KeyNotTrusted(
                    "The server's key for the vault “${file.name}” is not the one this phone holds, though the " +
                        "vault's key was not changed. Nothing was changed, and nothing of this vault was sent.",
                )
            } else if (there.text("key_sig").isNullOrEmpty()) {
                try {
                    server.putKeySig(vaultId, signKey(account, vaultId, held.toLong(), account.username,
                        record.text("wrapped_key")!!))
                } catch (e: SyncError) {
                    // signed meanwhile: nothing to add
                }
                if (record.text("key_signer").isNullOrEmpty()) {
                    account.markVault(vaultId, "key_signer" to JsonPrimitive(account.username))
                }
            }
            val role = there.text("role")?.takeIf { it in AccountStore.ROLES } ?: AccountStore.ROLE_OWNER
            if (role != account.vaultRole(vaultId)) {
                account.markVault(vaultId, "role" to JsonPrimitive(role))
                changes.roles += file.name to role
            }
            val count = there.int("members")?.takeIf { it > 0 } ?: 1
            if (count != (record.int("members")?.takeIf { it > 0 } ?: 1)) {
                account.markVault(vaultId, "members" to JsonPrimitive(count))
            }
            record = account.vaultRecord(vaultId)
            if (record.flag("rename_pending") && role != AccountStore.ROLE_OWNER) {
                account.markVault(vaultId, "rename_pending" to yes(false))
                record = account.vaultRecord(vaultId)
            }
            if (record.flag("rename_pending")) {
                server.renameVault(vaultId, file.sealedName())
                account.markVault(vaultId, "rename_pending" to yes(false))
                continue
            }
            val name = try {
                AccountStore.cleanVaultName(file.openName(there.text("name") ?: ""))
            } catch (e: Exception) {
                continue                                // an unreadable name changes nothing
            }
            if (name != file.name) {
                try {
                    account.renameVault(vaultId, name, pending = false)
                    changes.renamed += file.name to name
                } catch (e: IllegalArgumentException) {
                    // taken here: keep this device's name
                }
            }
        }

        for ((vaultId, there) in listed) {
            if (vaultId in extraHere) continue
            val signer = checkKey(account, server, there, "(new on the server)")
            val file = try {
                account.adoptVault(vaultId, there.text("wrapped_key") ?: "", there.text("name") ?: "",
                    there.text("role") ?: AccountStore.ROLE_OWNER, there.int("key_generation") ?: 0,
                    there.int("members") ?: 1)
            } catch (e: Exception) {
                if (e !is CryptoError && e !is IllegalArgumentException) throw e
                throw SyncError("The server lists a vault this account cannot open (${e.message}). Nothing was changed.")
            }
            account.markVault(vaultId, "key_signer" to JsonPrimitive(signer))
            changes.added += file.name
        }
        return changes
    }

    /**
     * Delete a vault for good (the PC's account_sync.delete_vault). On a
     * server it goes there first: if the server cannot be reached nothing
     * is deleted anywhere, so a vault never comes back after being deleted
     * here. Someone else's vault is left, not deleted: it stays for the others.
     */
    fun deleteVault(account: Account, vaultId: String) {
        val record = account.vaultRecord(vaultId)
        if (account.server != null && record.flag("on_server") && !record.flag("gone_from_server")) {
            val server = loggedIn(account)
            try {
                if (account.vaultRole(vaultId) == AccountStore.ROLE_OWNER) server.deleteVault(vaultId)
                else server.leaveVault(vaultId, account.username)
            } finally {
                server.logout()
            }
        }
        account.deleteVault(vaultId)
    }

    /**
     * A vault deleted on the server and kept here goes onto the server again
     * with the next sync, as this account's own with a first key (the PC's
     * put_vault_back).
     */
    fun putVaultBack(account: Account, vaultId: String) {
        account.markVault(vaultId, "on_server" to yes(false), "gone_from_server" to yes(false),
            "role" to JsonPrimitive(AccountStore.ROLE_OWNER), "key_generation" to JsonPrimitive(0),
            "members" to JsonPrimitive(1), "key_signer" to JsonPrimitive(account.username))
        account.vaultFile(vaultId).basePath.delete()      // agreed with a vault that is gone
    }

    /**
     * A new master password. On a server, the server learns it first: if it
     * cannot be reached nothing changes anywhere (account_sync.change_password).
     */
    fun changePassword(account: Account, newPassword: String) {
        if (newPassword.isEmpty()) throw IllegalArgumentException("Enter a new master password.")
        if (account.server == null) return account.changePassword(newPassword)
        val server = loggedIn(account)
        try {
            account.changePassword(newPassword) { sealed, login -> server.putKeyset(login, sealed) }
        } finally {
            server.logout()
        }
    }

    // ------------------------------------------------------------------ sharing (the PC's phase 4)
    /** Why a vault cannot be shared right now, or "" when it can (sharing_ui.can_share). */
    fun whyNotShare(account: Account, vaultId: String): String {
        if (vaultId == account.personalId) return "The Personal vault is yours alone. Make another vault to share."
        if (account.server == null) return "Put the account on a server first (Settings → Sync server)."
        val record = account.vaultRecord(vaultId)
        if (record.flag("gone_from_server")) return "This vault is no longer on the server."
        if (!record.flag("on_server")) return "Press Sync once, so the vault is on the server."
        return ""
    }

    /** A server session that can share, for ``work``; logged out after. */
    fun <T> sharing(account: Account, work: (AccountServer) -> T): T {
        val server = loggedIn(account)
        try {
            if (!server.hasSharing() || !server.hasSignedKeys()) throw SyncError(
                "This server cannot share vaults yet: it needs SecretSpace Server 2.0.0 or newer.",
            )
            return work(server)
        } finally {
            server.logout()
        }
    }

    /**
     * Give the account behind ``keys`` (looked up, and its fingerprint
     * confirmed by the user) the vault's key, wrapped so that only it can
     * open it. The server stores the wrapped copy, never the key itself.
     */
    fun shareVault(account: Account, server: AccountServer, vaultId: String, keys: ContactKeys, role: String) {
        if (account.vaultRole(vaultId) != AccountStore.ROLE_OWNER) throw SyncError("Only an owner of this vault can share it.")
        val file = account.vaultFile(vaultId)
        if (file.personal) throw SyncError("The personal vault cannot be shared; make another vault for that.")
        val wrapped = ac.wrapKey(keys.publicKey, file.vaultKey, file.id, keys.username, file.fmt)
        server.putMember(vaultId, keys.username, role, wrapped,
            signKey(account, vaultId, file.generation, keys.username, wrapped))
        trustContact(account, keys, true)
        val count = (server.members(vaultId)["members"] as? JsonArray)?.size ?: 1
        account.markVault(vaultId, "members" to JsonPrimitive(maxOf(1, count)))
    }

    class Member(val username: String, val role: String, val addedBy: String, val addedAt: String,
                 val me: Boolean, val verified: Boolean)

    /** The vault's members as the server lists them, with "me" and "fingerprint checked on this phone". */
    fun listMembers(account: Account, server: AccountServer, vaultId: String): List<Member> {
        val found = (server.members(vaultId)["members"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { m ->
            val name = m.text("username") ?: ""
            Member(name, m.text("role") ?: "", m.text("added_by") ?: "", m.text("added_at") ?: "",
                name == account.username, account.contact(name)?.verified == true)
        }
        account.markVault(vaultId, "members" to JsonPrimitive(maxOf(1, found.size)))
        return found
    }

    /** Has every member (or every one of ``names``) an account in the current format? */
    fun membersConverted(listing: JsonObject, names: Collection<String>? = null): Boolean {
        val members = (listing["members"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { names == null || (it.text("username") ?: "") in names }
        return members.isNotEmpty() && members.all { ac.formatOf(it.int("format")) == ac.NOW }
    }

    /**
     * Give a vault a new key, taking ``remove`` out in the same step (what
     * Remove does), so a removed member can open nothing written afterwards.
     * The vault is synced first, so what goes under the new key is what
     * everybody agrees on; a sync that needs the user's decision stops this
     * with nothing changed. Returns the vault's data as it now is here
     * (the PC's account_sync.change_key).
     */
    fun changeKey(account: Account, server: AccountServer, vaultId: String, local: Obj,
                  remove: Collection<String> = emptyList()): Obj {
        if (account.vaultRole(vaultId) != AccountStore.ROLE_OWNER) throw SyncError("Only an owner of this vault can do that.")
        val sync = VaultSync(account, server, vaultId)
        val result = sync.prepare(local)
        if (result.conflicts.isNotEmpty()) throw SyncError(
            "This vault has changes here and on the server that need your decision. Press Sync first, then try again.",
        )
        val merged = sync.commit(local)
        if (Entries.tooNew(merged)) throw SyncError(
            "This vault was changed by a newer SecretSpace. Update SecretSpace, then try again.",
        )
        val file = sync.file
        val listing = server.members(vaultId)
        if ((listing.long("key_generation") ?: 0L) != file.generation) throw SyncError(
            "This vault's key was changed on another device. Press Sync first, then try again.",
        )
        val names = (listing["members"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("username") }
        val missing = remove.filter { it !in names }
        if (missing.isNotEmpty()) throw SyncError("Not a member any more: " + missing.joinToString(", ") + ".")
        val staying = names.filter { it !in remove }
        // The new key in the current format once every member who stays has converted; else the old one.
        val fmt = if (membersConverted(listing, staying)) ac.NOW else ac.OLD
        val newKey = ac.newVaultKey()
        val generation = file.generation + 1
        val wrapped = linkedMapOf<String, String>()
        for (name in staying) {
            val public = if (name == account.username) ac.publicKeyOf(account.keyset) else {
                val keys = lookupContact(account, server, name)
                if (keys.status == "changed") throw KeyChanged(
                    "The server now gives different keys for “$name” than this phone has seen before. Nothing " +
                        "was changed. Check with $name (Members → Check), or remove them too.",
                )
                if (keys.status == "new") trustContact(account, keys, false)
                keys.publicKey
            }
            wrapped[name] = ac.wrapKey(public, newKey, file.id, name, fmt)
        }
        val sigs = wrapped.mapValues { e -> JsonPrimitive(signKey(account, vaultId, generation, e.key, e.value)) }
        val body = JsonObject(mapOf(
            "generation" to JsonPrimitive(generation),
            "remove" to JsonArray(remove.map { JsonPrimitive(it) }),
            "wrapped_keys" to JsonObject(wrapped.mapValues { JsonPrimitive(it.value) }),
            "key_sigs" to JsonObject(sigs),
            "name" to JsonPrimitive(ac.sealVaultName(newKey, file.id, file.name, fmt)),
            "blob" to JsonPrimitive(ac.encryptVault(newKey, file.id, generation,
                Py.json(Entries.validateVault(Py.deepCopyObj(merged))), fmt)),
            "revision" to JsonPrimitive(sync.revision),
        ))
        val view = server.rotate(vaultId, body)
        // The server has it; now this phone. (Interrupted here, the next sync takes the key from the server.)
        account.rekeyVault(vaultId, wrapped.getValue(account.username), generation.toInt())
        account.markVault(vaultId, "key_signer" to JsonPrimitive(account.username))
        account.vaultFile(vaultId).saveBase(merged, baseOrigin(server.url, view.int("revision") ?: (sync.revision + 1)))
        account.markVault(vaultId, "members" to JsonPrimitive(staying.size))
        return merged
    }

    /** The vaults a sync covers: the personal one, and every other on the server. */
    fun syncableVaults(account: Account, server: AccountServer): List<String> {
        if (!server.hasVaults() || !server.hasSignedKeys()) return listOf(account.personalId)
        return account.vaultIds().filter {
            it == account.personalId || account.vaultRecord(it).let { r -> r.flag("on_server") && !r.flag("gone_from_server") }
        }
    }

    // ------------------------------------------------------------------ signing in
    class SignedIn(val account: Account, val personal: Obj?, val changes: VaultChanges)

    /**
     * Bring an account from the server onto this device: a new phone, or
     * this one after the master password was changed on another device.
     * ``secretKeyText`` comes from the Emergency Kit; it may be left out when
     * this device already keeps the account's Secret Key. An account no
     * device has converted from SecretSpace 3 yet is refused
     * ([NeedsConversion]): the phone leaves that to a PC.
     */
    fun signIn(store: AccountStore, url: String, caFile: String, username: String, password: String,
               secretKeyText: String? = null): SignedIn {
        val server = AccountServer(url, caFile)
        server.checkApi()
        val pre = server.prelogin(username)
        val name = pre.text("username")?.takeIf { it.isNotEmpty() } ?: username

        val secret = when {
            !secretKeyText.isNullOrBlank() -> ac.parseSecretKey(secretKeyText)
            store.accountExists(name) -> store.storedSecret(name, password)
            else -> throw SecretKeyNeeded(name)
        }
        val kdf = pre.obj("kdf") ?: throw SyncError("The server sent sign-in details this version cannot use.")
        val salt: ByteArray
        val cost: List<Int>
        try {
            salt = ac.unb64(kdf.text("salt"), "salt")
            cost = listOf("passes", "memory_kib", "lanes").map { kdf.int(it)!! }
        } catch (e: Exception) {
            throw SyncError("The server sent sign-in details this version cannot use.")
        }
        val (auk, login) = ac.deriveAccountKeys(password, secret, name, salt, cost[0], cost[1], cost[2])
        try {
            server.login(name, login)
        } catch (e: LoginFailed) {
            val (_, oldLogin) = ac.deriveAccountKeys(password, secret, name, salt, cost[0], cost[1], cost[2], ac.OLD)
            try {
                server.login(name, oldLogin)
            } catch (e2: LoginFailed) {
                throw LoginFailed("Wrong username, master password or Secret Key.")
            }
            server.logout()
            throw NeedsConversion(name)
        }
        try {
            val me = server.me()
            val keyset = try {
                ac.openKeyset(auk, me.text("keyset") ?: "")
            } catch (e: CryptoError) {
                throw SyncError("The server's copy of the account does not open with these details. Nothing was changed.")
            }
            val hadIt = store.accountExists(name)
            val account = store.adoptFromServer(name, me, keyset, secret, login, password, JsonObject(mapOf(
                "url" to JsonPrimitive(url.trimEnd('/')), "ca_file" to JsonPrimitive(caFile),
            )))
            var data: Obj? = null
            if (!hadIt || !account.hasVaultFile) {
                val stored = server.getVault(me.text("personal_vault")!!)
                val file = account.vaultFile()
                data = stored.blob?.let { file.decrypt(it) } ?: Entries.emptyVault()
                file.save(data)
                if (stored.blob != null) file.saveBase(data, baseOrigin(server.url, stored.revision))
            }
            // The account's other vaults come with it, so a new phone opens with everything.
            val changes = reconcileVaults(account, server)
            for (vaultId in syncableVaults(account, server)) {
                val file = account.vaultFile(vaultId)
                if (file.personal || file.hasFile) continue
                val stored = server.getVault(vaultId)
                val fetched = stored.blob?.let { file.decrypt(it) } ?: Entries.emptyVault()
                file.save(fetched)
                if (stored.blob != null) file.saveBase(fetched, baseOrigin(server.url, stored.revision))
            }
            return SignedIn(account, data, changes)
        } finally {
            server.logout()
        }
    }
}
