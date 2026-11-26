package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import secretspace.core.Disk.flag
import secretspace.core.Disk.int
import secretspace.core.Disk.obj
import secretspace.core.Disk.text
import secretspace.core.Disk.with
import secretspace.core.Disk.without
import java.io.File
import java.io.IOException
import secretspace.core.AccountCrypto as ac

/** This device does not hold the account's Secret Key: ask for the Emergency Kit. */
class SecretKeyNeeded(val username: String) :
    Exception("Enter the Secret Key from the Emergency Kit of “$username”.")

/** An account still in the format of SecretSpace 3. The phone never converts one. */
class NeedsConversion(val username: String) : Exception(
    "The account “$username” is still in the format of SecretSpace 3. Open it once with " +
        "SecretSpace on a PC, which converts it, then sign in here again. Nothing was changed.",
)

/**
 * The accounts on this device, in the PC's folder layout (accounts.py):
 *
 *     <home>/accounts/<username>/account.json, keyset.ssk, recovery.ssk,
 *                               vaults/<id>.ssv (+ .bak, + .base)
 *
 * A folder made here opens on the PC (given the PC is told this ``home``)
 * and one made there opens here, Secret Key storage permitting.
 */
class AccountStore(val home: File, val secrets: SecretStore = PasswordSecretStore()) {
    companion object {
        const val ACCOUNTS_DIR = "accounts"
        const val ACCOUNT_FILE = "account.json"
        const val KEYSET_FILE = "keyset.ssk"
        const val RECOVERY_FILE = "recovery.ssk"
        const val VAULTS_DIR = "vaults"
        const val VAULT_SUFFIX = ".ssv"
        const val ACCOUNT_FORMAT = 2
        const val OLD_ACCOUNT_FORMAT = 1
        const val MODE_PERSONAL = "personal"
        const val PERSONAL_NAME = "Personal"
        const val ROLE_OWNER = "owner"
        const val ROLE_EDITOR = "editor"
        const val ROLE_READER = "reader"
        val ROLES = listOf(ROLE_OWNER, ROLE_EDITOR, ROLE_READER)

        /** A vault name as it is stored, or IllegalArgumentException saying what is wrong. */
        fun cleanVaultName(name: String?): String {
            val clean = (name ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (clean.isEmpty()) throw IllegalArgumentException("Give the vault a name.")
            if (clean.codePointCount(0, clean.length) > ac.VAULT_NAME_CHARS ||
                clean.toByteArray(Charsets.UTF_8).size > ac.VAULT_NAME_BYTES
            ) throw IllegalArgumentException("A vault name can be up to ${ac.VAULT_NAME_CHARS} characters.")
            if (clean.any { it.code < 32 || it.code == 127 }) {
                throw IllegalArgumentException("A vault name cannot contain control characters.")
            }
            return clean
        }
    }

    val accountsDir: File get() = File(home, ACCOUNTS_DIR)
    fun accountDir(username: String) = File(accountsDir, Disk.validateUsername(username))
    internal fun path(username: String, name: String) = File(accountDir(username), name)

    fun listAccounts(): List<String> = (accountsDir.listFiles() ?: emptyArray())
        .filter { f -> runCatching { Disk.validateUsername(f.name) }.isSuccess && File(f, ACCOUNT_FILE).isFile }
        .map { it.name }.sortedBy { it.lowercase() }

    fun accountExists(username: String) = path(username, ACCOUNT_FILE).isFile

    internal fun readAnyInfo(username: String): JsonObject {
        val file = path(username, ACCOUNT_FILE)
        if (!file.isFile) throw NoSuchElementException("There is no account “$username” on this device.")
        val info = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("The account file of “$username” is damaged.")
        if (info.int("format") !in listOf(ACCOUNT_FORMAT, OLD_ACCOUNT_FORMAT)) {
            throw IllegalStateException("The account “$username” was made by a newer SecretSpace; update this one.")
        }
        return info
    }

    internal fun writeInfo(username: String, info: JsonObject) =
        Disk.writeText(path(username, ACCOUNT_FILE), Disk.prettyJson(info))

    /**
     * Open an account: [SecretKeyNeeded] when this device does not hold the
     * Secret Key and none was given, CryptoError("Wrong master password or
     * Secret Key.") for a wrong one. A Secret Key typed in is stored once it
     * has opened the account.
     */
    fun unlock(username: String, password: String, secretKeyText: String? = null): Account {
        var info = readAnyInfo(username)
        if (info.int("format") == OLD_ACCOUNT_FORMAT) throw NeedsConversion(username)
        val sealed = Disk.readText(path(username, KEYSET_FILE))
        val typed = secretKeyText?.takeIf { it.isNotBlank() }?.let { ac.parseSecretKey(it) }
        val secret = typed ?: try {
            secrets.unprotect(info["secret_key"], username, password)
        } catch (e: SecretKeyUnavailable) {
            throw SecretKeyNeeded(username)
        }
        val opened = ac.unlockAccount(username, password, secret, sealed)
        if (typed != null) {
            info = info.with("secret_key" to secrets.protect(typed, username, password))
            writeInfo(username, info)
        }
        return Account(this, username, info, opened.keyset, secret, opened.loginKey)
    }

    /**
     * The Secret Key this device keeps for an account, or [SecretKeyNeeded] --
     * also when it is sealed under a password that is no longer the account's.
     */
    fun storedSecret(username: String, password: String): ByteArray {
        val info = readAnyInfo(username)
        return try {
            secrets.unprotect(info["secret_key"], username, password)
        } catch (e: SecretKeyUnavailable) {
            throw SecretKeyNeeded(username)
        } catch (e: CryptoError) {
            throw SecretKeyNeeded(username)
        }
    }

    /**
     * Write what a server holds for an account onto this device (``me`` is
     * the server's /v2/me, its keyset already opened by the caller). An
     * account already here keeps its vault files; one with the same name and
     * another personal vault is someone else's and is left alone.
     */
    fun adoptFromServer(username: String, me: JsonObject, keyset: Map<String, String>, secret: ByteArray,
                        loginKey: ByteArray, password: String, server: JsonObject): Account {
        val vaultId = me.text("personal_vault") ?: throw IllegalStateException("The server named no personal vault.")
        val folder = accountDir(username)
        var info: JsonObject
        if (accountExists(username)) {
            info = readAnyInfo(username)
            if (info.text("personal_vault") != vaultId) throw IllegalStateException(
                "This device already has a different account called “$username”. Delete it first.",
            )
            if (info.int("format") == OLD_ACCOUNT_FORMAT) throw NeedsConversion(username)
        } else if (folder.exists()) {
            throw IllegalStateException(
                "A folder for “$username” is already there ($folder) but holds no account. Move it away to use this name.",
            )
        } else {
            if (!folder.mkdirs()) throw IOException("Could not make $folder.")
            info = JsonObject(mapOf(
                "format" to JsonPrimitive(ACCOUNT_FORMAT), "username" to JsonPrimitive(username),
                "mode" to JsonPrimitive(MODE_PERSONAL), "created_at" to JsonPrimitive(Disk.utcNow()),
                "personal_vault" to JsonPrimitive(vaultId), "fingerprints_seen" to JsonObject(emptyMap()),
                "vaults" to JsonObject(emptyMap()),
            ))
        }
        info = info.with(
            "public_key" to (me["public_key"] ?: JsonNull), "server" to server,
            "secret_key" to secrets.protect(secret, username, password),
        ).without("convert")
        Disk.writeText(File(folder, KEYSET_FILE), me.text("keyset") ?: "")
        Disk.writeText(File(folder, RECOVERY_FILE), me.text("recovery") ?: "")
        writeInfo(username, info)                       // last: it makes it an account
        return Account(this, username, info, keyset, secret, loginKey)
    }

    /** Remove the account's folder. The caller has checked the password. */
    fun deleteAccount(username: String) {
        val folder = accountDir(username)
        if (!File(folder, ACCOUNT_FILE).isFile) throw NoSuchElementException("There is no account “$username” on this device.")
        folder.deleteRecursively()
    }
}

/**
 * One vault of an unlocked account: id, name and key in memory, its files
 * on disk (accounts.VaultFile).
 */
class VaultFile internal constructor(
    folder: File,
    val id: ByteArray,
    private val key: ByteArray,
    val name: String,
    val personal: Boolean,
    val generation: Long = 0,
    val role: String = AccountStore.ROLE_OWNER,
    /** The format the vault has on the server; the file here is always the current one. */
    val fmt: Format = ac.NOW,
    private val tooNew: MutableMap<String, String> = mutableMapOf(),
) {
    val hex: String = id.toHex()
    val path = File(folder, hex + AccountStore.VAULT_SUFFIX)
    val basePath get() = File(path.path + Disk.BASE_SUFFIX)
    val hasFile get() = path.exists()
    val canWrite get() = role != AccountStore.ROLE_READER && hex !in tooNew

    private fun noteLevel(data: Obj) {
        if (Entries.tooNew(data)) tooNew[hex] = Entries.neededVersion(data) else tooNew.remove(hex)
    }

    /** ``data`` under this vault's key, as this device stores it (validated in place, as on the PC). */
    fun encrypt(data: Obj): String = ac.encryptVault(key, id, generation, Py.json(Entries.validateVault(data)))

    /** ... and as the server and the other members' apps expect it: in the vault's own format. */
    fun encryptForServer(data: Obj): String =
        ac.encryptVault(key, id, generation, Py.json(Entries.validateVault(data)), fmt)

    /** A blob of this vault, in either format (it must name this vault). */
    fun decrypt(blob: String): Obj = Entries.validateVault(Py.native(ac.decryptVault(key, blob, id)))

    /** The vault's data; falls back to .bak like the PC does. */
    fun load(): Obj {
        var first: Exception? = null
        for (file in listOf(path, File(path.path + Disk.BACKUP_SUFFIX))) {
            if (!file.exists()) continue
            try {
                return decrypt(Disk.readText(file)).also { noteLevel(it) }
            } catch (e: Exception) {
                if (e !is CryptoError && e !is IllegalArgumentException && e !is IOException) throw e
                if (first == null) first = e
            }
        }
        throw first ?: NoSuchElementException("The vault “$name” is missing on this device.")
    }

    fun save(data: Obj) {
        Disk.rotateBackup(path)
        Disk.writeText(path, encrypt(data))
        noteLevel(data)
    }

    /** The vault as last agreed with the server, or null. */
    fun loadBase(): Obj? = try {
        decrypt(Disk.readText(basePath))
    } catch (e: Exception) {
        null
    }

    fun saveBase(data: Obj, origin: Obj) {
        val copy: Obj = LinkedHashMap(data)
        copy[Disk.BASE_ORIGIN_KEY] = origin
        Disk.writeText(basePath, encrypt(copy))
    }

    fun sealedName(): String = ac.sealVaultName(key, id, name, fmt)
    fun openName(sealed: String): String = ac.openVaultName(key, id, sealed)
    internal val vaultKey: ByteArray get() = key

    fun removeFiles() {
        for (f in listOf(path, File(path.path + Disk.BACKUP_SUFFIX), basePath)) f.delete()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.fromHex(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdef" }) { "Not a vault id." }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** An unlocked account: its keys in memory, its files on disk (accounts.Account). */
class Account internal constructor(
    val store: AccountStore,
    val username: String,
    info: JsonObject,
    val keyset: Map<String, String>,
    private val secret: ByteArray,
    /** What logs this account in to its server; only ever in memory. */
    var loginKey: ByteArray?,
) {
    var info: JsonObject = info
        private set
    private val vaultKeys = mutableMapOf<String, ByteArray>()
    private val tooNew = mutableMapOf<String, String>()
    var serverVersion: String? = null

    val personalId: String get() = info.text("personal_vault")!!
    private val vaultsFolder get() = File(store.accountDir(username), AccountStore.VAULTS_DIR)
    private val personalKey = ac.unb64(keyset["personal_vault_key"], "vault key")

    val fingerprint: String get() = ac.accountFingerprint(ac.publicKeyOf(keyset), ac.signingKeyOf(keyset))
    val signingKey: String get() = ac.b64(ac.signingKeyOf(keyset))
    val secretKeyText: String get() = ac.formatSecretKey(secret)

    private fun writeInfo(fresh: JsonObject) {
        store.writeInfo(username, fresh)
        info = fresh
    }

    /** Settings that are this device's own (sync on unlock and the like). */
    fun setInfo(key: String, value: JsonElement) = writeInfo(info.with(key to value))

    // -- other people ------------------------------------------------------
    class Contact(val publicKey: ByteArray, val signingKey: ByteArray, val verified: Boolean)

    /** The keys this device has seen for another account, or null. */
    fun contact(name: String): Contact? {
        val found = info.obj("fingerprints_seen")?.obj(name) ?: return null
        return try {
            Contact(ac.unb64(found.text("public_key"), "key"), ac.unb64(found.text("signing_key"), "key"),
                found.flag("verified"))
        } catch (e: CryptoError) {
            null
        }
    }

    fun rememberContact(name: String, publicKey: ByteArray, signingKey: ByteArray, verified: Boolean) {
        val seen = (info.obj("fingerprints_seen") ?: JsonObject(emptyMap())).with(name to JsonObject(mapOf(
            "public_key" to JsonPrimitive(ac.b64(publicKey)), "signing_key" to JsonPrimitive(ac.b64(signingKey)),
            "verified" to JsonPrimitive(verified), "seen_at" to JsonPrimitive(Disk.utcNow()),
        )))
        writeInfo(info.with("fingerprints_seen" to seen))
    }

    fun signingKeyFor(name: String): ByteArray? =
        if (name == username) ac.signingKeyOf(keyset) else contact(name)?.signingKey

    // -- its vaults --------------------------------------------------------
    private fun extra(): JsonObject = info.obj("vaults") ?: JsonObject(emptyMap())

    /** The personal vault first, then the others by name. */
    fun vaultIds(): List<String> {
        val extra = extra()
        return listOf(personalId) + extra.keys.sortedWith(compareBy(
            { (extra[it] as? JsonObject)?.text("name")?.lowercase() ?: "" }, { it }))
    }

    fun vaultRecord(vaultId: String): JsonObject = extra().obj(vaultId) ?: JsonObject(emptyMap())

    fun vaultRole(vaultId: String?): String {
        if (vaultId == null || vaultId == personalId) return AccountStore.ROLE_OWNER
        return vaultRecord(vaultId).text("role")?.takeIf { it in AccountStore.ROLES } ?: AccountStore.ROLE_OWNER
    }

    fun needsNewerApp(vaultId: String?) = (vaultId ?: personalId) in tooNew
    fun canWrite(vaultId: String?) = vaultRole(vaultId) != AccountStore.ROLE_READER && !needsNewerApp(vaultId)

    fun vaultFormat(vaultId: String?): Format {
        if (vaultId == null || vaultId == personalId) return ac.NOW
        val wrapped = vaultRecord(vaultId).text("wrapped_key") ?: return ac.NOW
        return try { ac.wrapFormat(wrapped) } catch (e: CryptoError) { ac.NOW }
    }

    fun isShared(vaultId: String): Boolean =
        (vaultRecord(vaultId).int("members") ?: 1) > 1 || vaultRole(vaultId) != AccountStore.ROLE_OWNER

    fun vaultFile(vaultId: String? = null): VaultFile {
        if (vaultId == null || vaultId == personalId) {
            return VaultFile(vaultsFolder, personalId.fromHex(), personalKey, AccountStore.PERSONAL_NAME, true,
                tooNew = tooNew)
        }
        val record = extra().obj(vaultId) ?: throw NoSuchElementException("This account has no vault $vaultId.")
        val key = vaultKeys.getOrPut(vaultId) {
            ac.unwrapKey(keyset, record.text("wrapped_key") ?: "", vaultId.fromHex(), username)
        }
        return VaultFile(vaultsFolder, vaultId.fromHex(), key, record.text("name") ?: "", false,
            (record.int("key_generation") ?: 0).toLong(), vaultRole(vaultId), vaultFormat(vaultId), tooNew)
    }

    private fun nameTaken(name: String, exceptId: String? = null): Boolean {
        val wanted = name.lowercase()
        if (wanted == AccountStore.PERSONAL_NAME.lowercase()) return true
        return extra().any { (id, r) -> id != exceptId && (r as? JsonObject)?.text("name")?.lowercase() == wanted }
    }

    private fun writeVaults(extra: JsonObject) = writeInfo(info.with("vaults" to extra))

    /** A further vault, empty, made on this device; its key wrapped to this account. */
    fun createVault(name: String): VaultFile {
        val clean = AccountStore.cleanVaultName(name)
        if (nameTaken(clean)) throw IllegalArgumentException("There is already a vault called “$clean”.")
        val id = ac.newVaultId()
        val key = ac.newVaultKey()
        val wrapped = ac.wrapKey(ac.publicKeyOf(keyset), key, id, username)
        val made = VaultFile(vaultsFolder, id, key, clean, false)
        made.save(Entries.emptyVault())
        writeVaults(extra().with(made.hex to JsonObject(mapOf(
            "name" to JsonPrimitive(clean), "wrapped_key" to JsonPrimitive(wrapped),
            "created_at" to JsonPrimitive(Disk.utcNow()), "on_server" to JsonPrimitive(false),
            "key_signer" to JsonPrimitive(username),
        ))))
        vaultKeys[made.hex] = key
        return made
    }

    /**
     * A vault the server holds for this account that this device does not
     * have yet. Its key must open with this account's and its name with the
     * vault's key, or nothing is written.
     */
    fun adoptVault(vaultId: String, wrappedKey: String, sealedName: String, role: String = AccountStore.ROLE_OWNER,
                   keyGeneration: Int = 0, members: Int = 1): VaultFile {
        val raw = vaultId.fromHex()
        val key = ac.unwrapKey(keyset, wrappedKey, raw, username)
        val base = AccountStore.cleanVaultName(ac.openVaultName(key, raw, sealedName))
        var name = base
        var n = 2
        while (nameTaken(name, vaultId)) name = "$base (${n++})"
        writeVaults(extra().with(vaultId to JsonObject(mapOf(
            "name" to JsonPrimitive(name), "wrapped_key" to JsonPrimitive(wrappedKey),
            "created_at" to JsonPrimitive(Disk.utcNow()), "on_server" to JsonPrimitive(true),
            "role" to JsonPrimitive(if (role in AccountStore.ROLES) role else AccountStore.ROLE_OWNER),
            "key_generation" to JsonPrimitive(keyGeneration), "members" to JsonPrimitive(maxOf(1, members)),
        ))))
        vaultKeys[vaultId] = key
        return vaultFile(vaultId)
    }

    /** The vault has a new key: take it, and put this device's copy under it. */
    fun rekeyVault(vaultId: String, wrappedKey: String, generation: Int) {
        if (vaultId == personalId || vaultId !in extra()) throw IllegalArgumentException("That vault has no key to change.")
        val newKey = ac.unwrapKey(keyset, wrappedKey, vaultId.fromHex(), username)
        val old = vaultFile(vaultId)
        val data = if (old.hasFile) runCatching { old.load() }.getOrNull() else null
        val base = old.loadBase()
        writeVaults(extra().with(vaultId to vaultRecord(vaultId).with(
            "wrapped_key" to JsonPrimitive(wrappedKey), "key_generation" to JsonPrimitive(generation))))
        vaultKeys[vaultId] = newKey
        val new = vaultFile(vaultId)
        if (data != null) new.save(data) else old.removeFiles()
        File(new.path.path + Disk.BACKUP_SUFFIX).delete()
        new.basePath.delete()
        val origin = Py.asObj(base?.remove(Disk.BASE_ORIGIN_KEY))
        if (base != null && !origin.isNullOrEmpty()) new.saveBase(base, origin)
    }

    /** ``pending`` marks a name the server has not been told yet. */
    fun renameVault(vaultId: String, name: String, pending: Boolean = true) {
        val clean = AccountStore.cleanVaultName(name)
        if (vaultId == personalId || vaultId !in extra()) throw IllegalArgumentException("That vault cannot be renamed.")
        if (nameTaken(clean, vaultId)) throw IllegalArgumentException("There is already a vault called “$clean”.")
        val record = vaultRecord(vaultId)
        writeVaults(extra().with(vaultId to record.with("name" to JsonPrimitive(clean),
            "rename_pending" to JsonPrimitive(pending && record.flag("on_server")))))
    }

    fun markVault(vaultId: String, vararg flags: Pair<String, JsonElement>) {
        if (vaultId !in extra()) return
        writeVaults(extra().with(vaultId to vaultRecord(vaultId).with(*flags)))
    }

    fun deleteVault(vaultId: String) {
        if (vaultId == personalId || vaultId !in extra()) throw IllegalArgumentException("That vault cannot be deleted.")
        val target = vaultFile(vaultId)
        writeVaults(extra().without(vaultId))
        vaultKeys.remove(vaultId)
        target.removeFiles()
    }

    // -- the personal vault --------------------------------------------------
    fun loadVault() = vaultFile().load()
    fun saveVault(data: Obj) = vaultFile().save(data)
    val hasVaultFile get() = vaultFile().hasFile

    // -- the server ----------------------------------------------------------
    /** {"url", "ca_file"} of the server this account syncs with, or null. */
    val server: JsonObject? get() = info.obj("server")?.takeIf { !it.text("url").isNullOrEmpty() }

    /**
     * Remember the server (null forgets it); account.json only (the PC's
     * Account.set_server). The same server at a changed address keeps what
     * this device knows about its vaults there; another server, or none,
     * makes them this device's own again, to go on whichever comes next.
     */
    fun setServer(url: String?, caFile: String = "") {
        val before = server?.text("url")
        val after = url?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        var fresh = info.with("server" to (if (after == null) JsonNull else JsonObject(mapOf(
            "url" to JsonPrimitive(after), "ca_file" to JsonPrimitive(caFile)))))
        if (after != before && !Servers.same(after, before)) {
            val serverOnly = setOf("on_server", "gone_from_server", "rename_pending", "role", "key_generation", "members")
            fresh = fresh.with("vaults" to JsonObject(extra().mapValues { (_, record) ->
                JsonObject((record as? JsonObject ?: JsonObject(emptyMap())).filterKeys { it !in serverOnly })
            }))
        }
        writeInfo(fresh)
    }

    fun checkPassword(password: String): Boolean = try {
        ac.unlockAccount(username, password, secret, Disk.readText(store.path(username, AccountStore.KEYSET_FILE)))
        true
    } catch (e: Exception) {
        false
    }

    fun setRecovery(sealedRecovery: String) =
        Disk.writeText(store.path(username, AccountStore.RECOVERY_FILE), sealedRecovery)

    fun sealedFiles(): Map<String, String> = mapOf(
        "keyset" to Disk.readText(store.path(username, AccountStore.KEYSET_FILE)),
        "recovery" to Disk.readText(store.path(username, AccountStore.RECOVERY_FILE)),
    )
}
