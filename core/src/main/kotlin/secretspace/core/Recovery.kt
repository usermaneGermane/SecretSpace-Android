package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import secretspace.core.Disk.obj
import secretspace.core.Disk.text
import java.io.File
import secretspace.core.AccountCrypto as ac

/**
 * Recovering an account with its 12-word phrase, and making a new phrase
 * (the PC's recovery.py). The phrase opens a second copy of the account's
 * keyset: with it, the same keys go under a new master password and a new
 * phrase replaces the old one. The phone keeps its Secret Key sealed under
 * the master password, which is forgotten, so a recovery here always makes
 * a new Secret Key and a new Emergency Kit (as the PC does on Linux).
 *
 * An account on a server is changed there first: if the server cannot be
 * reached, nothing changes anywhere.
 */
object Recovery {
    /** The keyset, opened with the phrase. ``server``: {"url", "ca_file"} or null. */
    class Opened(val username: String, val keyset: Map<String, String>, val server: JsonObject?, val local: Boolean)

    /** Everything a recovery will write, made in memory and shown before [save] writes it. */
    class Pending internal constructor(
        val opened: Opened, val password: String, private val secret: ByteArray,
        val recoveryPhrase: String, private val sealedKeyset: String, private val loginKey: ByteArray,
        private val sealedRecovery: String,
    ) {
        val username get() = opened.username
        val secretKeyText: String = ac.formatSecretKey(secret)

        /** The server first, then this phone; returns the account, open. */
        fun save(store: AccountStore): Account {
            val server = opened.server
            if (server != null) {
                val url = server.text("url")!!
                val ca = server.text("ca_file") ?: ""
                val remote = AccountServer(url, ca)
                remote.checkApi()
                if (!remote.hasRecovery()) throw SyncError(
                    "This server cannot recover accounts yet: it needs SecretSpace Server 2.0.0 or newer. " +
                        "Nothing was changed.",
                )
                remote.recover(JsonObject(mapOf(
                    "username" to JsonPrimitive(username),
                    "recovery_proof" to JsonPrimitive(ac.b64(ac.recoveryProof(opened.keyset))),
                    "login_key" to JsonPrimitive(ac.b64(loginKey)),
                    "keyset" to JsonPrimitive(sealedKeyset),
                    "recovery" to JsonPrimitive(sealedRecovery),
                )))
                // The server holds the new keys; this phone takes them from there as a sign-in does.
                return try {
                    AccountSync.signIn(store, url, ca, username, password, secretKeyText).account
                } catch (e: Exception) {
                    throw SyncError("The account was recovered on the server, but this phone could not sign in " +
                        "afterwards (${e.message}). Use “Sign in from a server” with the new master password " +
                        "and the new Emergency Kit.")
                }
            }
            store.replaceKeys(username, sealedKeyset, sealedRecovery, secret, password)
            return store.unlock(username, password)
        }
    }

    /**
     * Step 1: open the keyset with the phrase. CryptoError for a phrase that
     * is mistyped or not this account's; SyncError for the server. An
     * account not on this phone comes from ``url``.
     */
    fun openWithPhrase(store: AccountStore, username: String, phrase: String, url: String = "",
                       caFile: String = ""): Opened {
        var name = username.trim()
        require(name.isNotEmpty()) { "Enter the account name." }
        ac.phraseToEntropy(phrase)                                 // typos first, offline
        val sealed: String
        val server: JsonObject?
        val local: Boolean
        if (store.accountExists(name)) {
            sealed = store.readRecoveryCopy(name)
            server = store.readAnyInfo(name).obj("server")?.takeIf { !it.text("url").isNullOrEmpty() }
            local = true
        } else {
            val address = url.trim().trimEnd('/')
            require(address.isNotEmpty()) { "This phone has no account “$name”. Enter the address of the server it is on." }
            require(address.startsWith("http://") || address.startsWith("https://")) {
                "The server address starts with http:// or https://."
            }
            val remote = AccountServer(address, caFile)
            remote.checkApi()
            if (!remote.hasRecovery()) throw SyncError(
                "This server cannot recover accounts yet: it needs SecretSpace Server 2.0.0 or newer.",
            )
            val reply = remote.recoveryCopy(name)
            name = reply.text("username")?.takeIf { it.isNotEmpty() } ?: name
            sealed = reply.text("recovery") ?: ""
            server = JsonObject(mapOf("url" to JsonPrimitive(address), "ca_file" to JsonPrimitive(caFile)))
            local = false
        }
        val keyset = try {
            ac.recoverAccount(name, phrase, sealed)
        } catch (e: CryptoError) {
            if (!(local && server != null)) throw e
            // The phrase may have been changed on another device: the server has the newest copy.
            val remote = AccountServer(server.text("url")!!, server.text("ca_file") ?: "")
            remote.checkApi()
            if (!remote.hasRecovery()) throw e
            ac.recoverAccount(name, phrase, remote.recoveryCopy(name).text("recovery") ?: "")
        }
        return Opened(name, keyset, server, local)
    }

    /** Step 2: the same keys under a new password, a new phrase and a new Secret Key; nothing written yet. */
    fun prepare(opened: Opened, newPassword: String, passes: Int = ac.ARGON2_PASSES,
                memoryKib: Int = ac.ARGON2_MEMORY_KIB, lanes: Int = ac.ARGON2_LANES): Pending {
        require(newPassword.isNotEmpty()) { "Enter a new master password." }
        val secret = ac.newSecretKey()
        val (sealed, login) = ac.resealWithNewPassword(opened.username, opened.keyset, newPassword, secret,
            passes, memoryKib, lanes)
        val phrase = ac.newRecoveryPhrase()
        return Pending(opened, newPassword, secret, phrase, sealed, login,
            ac.sealRecovery(phrase, opened.username, opened.keyset))
    }

    /** A new phrase for an open account: shown first, then [save] makes it the one (the PC's NewPhrase). */
    class NewPhrase(val account: Account) {
        val recoveryPhrase: String = ac.newRecoveryPhrase()

        fun save() {
            val sealed = ac.sealRecovery(recoveryPhrase, account.username, account.keyset)
            if (account.server != null) {
                val remote = AccountSync.loggedIn(account)
                try {
                    if (!remote.hasRecovery()) throw SyncError(
                        "The server cannot take a new recovery phrase yet: it needs SecretSpace Server 2.0.0 or " +
                            "newer. Nothing was changed.",
                    )
                    remote.putRecovery(sealed)
                } finally {
                    remote.logout()
                }
            }
            account.setRecovery(sealed)
        }
    }
}

/**
 * The Emergency Kit as a text file (accounts.emergency_kit_text), with the
 * server named -- or none, for an account on this phone only. For a new
 * account and for a recovery, which makes a new kit too.
 */
fun kitText(username: String, secretKeyText: String, serverUrl: String?, created: String = Disk.utcNow()): String = listOf(
    "SecretSpace Emergency Kit",
    "=====================",
    "",
    "Account:      $username",
    "Secret Key:   $secretKeyText",
    "Server:       " + (serverUrl?.trimEnd('/') ?: "(none - this account is on this phone only)"),
    "Master password: ______________________________",
    "",
    "Made: $created",
    "",
    "You need the Secret Key and your master password to open this",
    "account on another device, or on this one after it is reset.",
    "Print this page or keep the file somewhere safe and offline. Do not",
    "keep it in the same place as your master password.",
    "",
    "Your 12-word recovery phrase is NOT on this page, on purpose: it",
    "opens the account on its own. Keep it apart from this kit.",
    "",
).joinToString("\n")

/**
 * A delay after repeated wrong master passwords at the unlock screen (the
 * PC's lockout.py): a speed bump against someone holding the phone, not a
 * boundary -- the account files are what the key derivation protects.
 */
class Lockout(private val file: File, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    companion object {
        /** Wrong attempts allowed before any delay. */
        const val FREE_ATTEMPTS = 3
        /** Seconds to wait after the 4th, 5th, 6th ... consecutive failure. */
        val PENALTIES = listOf(5, 15, 60, 300)

        fun penaltyFor(failures: Int): Int =
            if (failures <= FREE_ATTEMPTS) 0 else PENALTIES[minOf(failures - FREE_ATTEMPTS, PENALTIES.size) - 1]
    }

    private fun load(): JsonObject = try {
        Json.parseToJsonElement(Disk.readText(file)) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

    private fun entry(username: String) = load()[username] as? JsonObject

    /** Seconds still to wait, or 0. */
    fun remaining(username: String): Int {
        val until = (entry(username)?.get("until") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return 0
        return maxOf(0, Math.round(until - clock()).toInt())
    }

    fun failures(username: String): Int =
        (entry(username)?.get("failures") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /** A wrong password: returns the seconds of waiting now in force. */
    fun recordFailure(username: String): Int {
        val failures = failures(username) + 1
        val wait = penaltyFor(failures)
        Disk.writeText(file, JsonObject(load() + (username to JsonObject(mapOf(
            "failures" to JsonPrimitive(failures), "until" to JsonPrimitive(clock() + wait))))).toString())
        return wait
    }

    fun reset(username: String) {
        val state = load()
        if (username in state) Disk.writeText(file, JsonObject(state - username).toString())
    }
}
