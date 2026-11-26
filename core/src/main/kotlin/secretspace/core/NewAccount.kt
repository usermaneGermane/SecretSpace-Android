package secretspace.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import secretspace.core.AccountCrypto as ac

/**
 * An account made on this phone, in memory until [createOn] puts it on a
 * server and writes it here (the PC's accounts.NewAccount followed by
 * account_sync.connect). The app shows the Emergency Kit and the recovery
 * phrase first; leaving half-way writes nothing, and a failed [createOn]
 * (a mistyped sign-up code, no network) can be tried again with the same
 * Secret Key and words the user already wrote down.
 */
class NewAccount private constructor(
    val username: String,
    private var password: String?,
    private val keyset: Map<String, String>,
    private val secret: ByteArray,
    private val loginKey: ByteArray,
    private val sealedKeyset: String,
    private val sealedRecovery: String,
    val secretKeyText: String,
    val recoveryPhrase: String,
) {
    /** Made once, so that trying again after a failure is the same account. */
    private val vaultId: ByteArray = ac.newVaultId()
    val fingerprint: String = ac.accountFingerprint(ac.publicKeyOf(keyset), ac.signingKeyOf(keyset))

    companion object {
        /** The words the user types back from the paper copy (dialogs.CHECK_WORDS). */
        val CHECK_WORDS = listOf(3, 7, 11)

        /** accounts.prepare_account: checks the name and makes the keys, writes nothing. */
        fun prepare(store: AccountStore, username: String, password: String,
                    passes: Int = ac.ARGON2_PASSES, memoryKib: Int = ac.ARGON2_MEMORY_KIB,
                    lanes: Int = ac.ARGON2_LANES): NewAccount {
            val name = Disk.validateUsername(username)
            if (password.isEmpty()) throw IllegalArgumentException("Master password is required.")
            if (store.accountExists(name)) throw IllegalStateException("There is already an account “$name”.")
            val secret = ac.newSecretKey()
            val salt = ac.randomBytes(16)
            val (auk, login) = ac.deriveAccountKeys(password, secret, name, salt, passes, memoryKib, lanes)
            val keyset = ac.newKeyset()
            val phrase = ac.newRecoveryPhrase()
            return NewAccount(name, password, keyset, secret, login,
                ac.sealKeyset(auk, keyset, salt, ac.KDF_ARGON2ID, passes, memoryKib, lanes),
                ac.sealRecovery(phrase, name, keyset), ac.formatSecretKey(secret), phrase)
        }
    }

    /** dialogs.phrase_mismatches: the word numbers in ``typed`` that are not the phrase's. */
    fun phraseMismatches(typed: Map<Int, String>): List<Int> {
        val words = recoveryPhrase.split(" ")
        return typed.toSortedMap().filter { (n, text) -> text.trim().lowercase() != words[n - 1] }.keys.toList()
    }

    /** The Emergency Kit as a text file: accounts.emergency_kit_text, with the server named. */
    fun emergencyKitText(serverUrl: String, created: String = Disk.utcNow()): String = listOf(
        "SecretSpace Emergency Kit",
        "=====================",
        "",
        "Account:      $username",
        "Secret Key:   $secretKeyText",
        "Server:       ${serverUrl.trimEnd('/')}",
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
     * Put the account on the server at ``url`` (account_sync.connect: the
     * server's token is the sign-up code), then write it on this phone with
     * an empty personal vault. If the server already has it -- an earlier
     * try got that far -- the login simply works. Nothing is written here
     * unless the server has the account.
     */
    fun createOn(store: AccountStore, url: String, caFile: String, signUpCode: String): Account {
        val pw = password ?: throw IllegalStateException("This account was already created.")
        val server = AccountServer(url, caFile)
        server.checkApi()
        if (store.accountExists(username)) throw IllegalStateException("There is already an account “$username”.")
        try {
            try {
                server.login(username, loginKey)
            } catch (e: LoginFailed) {
                if (signUpCode.isBlank()) throw LoginFailed("Enter the server's sign-up code to create the account there.")
                signUp(server, signUpCode.trim())
                server.login(username, loginKey)
            }
            if (server.me()["personal_vault"]?.let { (it as? JsonPrimitive)?.content } != vaultId.toHex()) {
                throw SyncError("The server's account “$username” has a different personal vault: it is " +
                    "another account with the same name. Nothing was changed.")
            }
            val account = write(store, pw, JsonObject(mapOf(
                "url" to JsonPrimitive(server.url), "ca_file" to JsonPrimitive(caFile))))
            password = null
            return account
        } finally {
            server.logout()
        }
    }

    private fun signUp(server: AccountServer, code: String) {
        val body = JsonObject(mapOf(
            "username" to JsonPrimitive(username),
            "login_key" to JsonPrimitive(ac.b64(loginKey)),
            "keyset" to JsonPrimitive(sealedKeyset),
            "recovery" to JsonPrimitive(sealedRecovery),
            "public_key" to JsonPrimitive(ac.b64(ac.publicKeyOf(keyset))),
            "signing_key" to JsonPrimitive(ac.b64(ac.signingKeyOf(keyset))),
            "recovery_proof" to JsonPrimitive(ac.b64(ac.recoveryProof(keyset))),
            "personal_vault" to JsonPrimitive(vaultId.toHex()),
        ))
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

    /** NewAccount.save, with the server remembered: account.json last, as it makes it an account. */
    private fun write(store: AccountStore, password: String, server: JsonObject): Account {
        val folder = store.accountDir(username)
        if (folder.exists()) throw IllegalStateException(
            "A folder for “$username” is already there ($folder) but holds no account. Move it away to use this name.")
        if (!folder.mkdirs()) throw IOException("Could not make $folder.")
        try {
            val info = JsonObject(mapOf(
                "format" to JsonPrimitive(AccountStore.ACCOUNT_FORMAT),
                "username" to JsonPrimitive(username),
                "mode" to JsonPrimitive(AccountStore.MODE_PERSONAL),
                "created_at" to JsonPrimitive(Disk.utcNow()),
                "public_key" to JsonPrimitive(ac.b64(ac.publicKeyOf(keyset))),
                "personal_vault" to JsonPrimitive(vaultId.toHex()),
                "secret_key" to store.secrets.protect(secret, username, password),
                "server" to server,
                "fingerprints_seen" to JsonObject(emptyMap()),
            ))
            Disk.writeText(File(folder, AccountStore.KEYSET_FILE), sealedKeyset)
            Disk.writeText(File(folder, AccountStore.RECOVERY_FILE), sealedRecovery)
            val account = Account(store, username, info, keyset, secret, loginKey)
            account.saveVault(Entries.emptyVault())
            store.writeInfo(username, info)
            return account
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
    }
}
