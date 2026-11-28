package io.github.usermanegermane.secretspace

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import secretspace.core.AccountServer
import secretspace.core.Disk.text
import secretspace.core.AccountStore
import secretspace.core.AccountSync
import secretspace.core.Merge
import secretspace.core.NeedsConversion
import secretspace.core.NewAccount
import secretspace.core.OpenAccount
import secretspace.core.CryptoError
import secretspace.core.Lockout
import secretspace.core.PasswordSecretStore
import secretspace.core.Recovery
import secretspace.core.SecretKeyNeeded
import java.io.File

/** Where the app is. */
sealed interface Screen {
    data object Welcome : Screen
    data object SignIn : Screen
    data object Create : Screen
    data object CreateKit : Screen
    data object CreateWords : Screen
    data object CreateCheck : Screen
    data class Unlock(val username: String) : Screen
    data object Vault : Screen
    data class Entry(val vaultId: String, val entryId: String) : Screen
    data class Edit(val vaultId: String, val entryId: String?) : Screen
    data class History(val vaultId: String, val entryId: String) : Screen
    data class Archive(val vaultId: String) : Screen
    data object Settings : Screen
    data object Vaults : Screen
    data class Members(val vaultId: String) : Screen
    data class Activity(val vaultId: String) : Screen
    data object NewPhrase : Screen
    data class Recover(val username: String) : Screen
    data object RecoverPassword : Screen
    data object RecoverKit : Screen
    data class ServerLog(val vaultId: String) : Screen
    data object Connect : Screen
}

/** A new account on its way: made in memory, not on the server or this phone yet. No ``url``: phone only. */
class Creating(val url: String?, val caFile: String, var code: String, val made: NewAccount)

/** A sync stopped on a vault with conflicts, waiting for the user's choices. */
class Question(val vaultName: String, val result: Merge.Result, val answer: CompletableDeferred<Map<String, String>?>)

/**
 * The app's one state: what is open, which screen shows, what is running.
 * Compose reads the mutable fields; ``version`` changes whenever the open
 * account's data does, so lists that read it are drawn again.
 */
class AppState(private val context: Context) {
    val home = File(context.filesDir, "home")
    val store = AccountStore(home, PasswordSecretStore())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var open by mutableStateOf<OpenAccount?>(null)
        private set
    var screen by mutableStateOf<Screen>(Screen.Welcome)
    var vaultId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var question by mutableStateOf<Question?>(null)
        private set
    var creating by mutableStateOf<Creating?>(null)
        private set
    /** A recovery on its way: the keyset opened with the words, then the new keys made (not written yet). */
    var recovering by mutableStateOf<Recovery.Opened?>(null)
        private set
    var recovered by mutableStateOf<Recovery.Pending?>(null)
        private set
    /** A new phrase being shown, not saved yet. */
    var newPhrase by mutableStateOf<Recovery.NewPhrase?>(null)
        private set
    /** The delay after wrong master passwords (the PC's lockout). */
    val lockout = Lockout(File(home, "unlock_failures.json"))
    var version by mutableIntStateOf(0)
        private set

    /** When the app went to the background; it locks if it stays there too long. */
    private var leftAt = 0L
    /** A picker or the camera is in front: that is not leaving the app. */
    var away = false

    init {
        home()
        if (Prefs.checkUpdates) checkForUpdate(quiet = true)
    }

    /** The first screen: an account's unlock, or the welcome when this phone has none. */
    fun home() {
        val accounts = store.listAccounts()
        screen = if (accounts.isEmpty()) Screen.Welcome else Screen.Unlock(accounts.first())
    }

    fun changed() {
        version++
    }

    fun say(text: String) {
        message = text
    }

    /** Run ``work`` off the main thread with a line saying what is happening; errors become the message. */
    fun run(what: String, work: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            } finally {
                busy = null
                changed()
            }
        }
    }

    // ------------------------------------------------------------------ getting in
    fun signIn(url: String, certificate: Uri?, username: String, password: String, kit: String,
               onNeedsKit: () -> Unit) = run("Signing in…") {
        val ca = withContext(Dispatchers.IO) { certificate?.let { keepCertificate(it, url) } ?: "" }
        try {
            val signed = withContext(Dispatchers.Default) {
                AccountSync.signIn(store, url.trim(), ca, username.trim(), password, kit.ifBlank { null })
            }
            opened(OpenAccount(signed.account), password)
        } catch (e: SecretKeyNeeded) {
            onNeedsKit()
            throw e
        } catch (e: NeedsConversion) {
            throw e
        }
    }

    // ------------------------------------------------------------------ a new account
    /**
     * Step 1: the server answers (if there is one) and the keys are made;
     * nothing is written yet. A null ``url`` is an account on this phone only.
     */
    fun startCreate(url: String?, certificate: Uri?, code: String, username: String, password: String) =
        run("Making the account's keys…") {
            val address = url?.trim()
            val ca = if (address == null) "" else
                withContext(Dispatchers.IO) { certificate?.let { keepCertificate(it, address) } ?: "" }
            val made = withContext(Dispatchers.Default) {
                if (address != null) AccountServer(address, ca).checkApi()   // a wrong address or certificate shows now
                NewAccount.prepare(store, username.trim(), password)
            }
            creating = Creating(address, ca, code, made)
            screen = Screen.CreateKit
        }

    fun saveKit(uri: Uri) {
        val c = creating ?: return
        run("Saving the Emergency Kit…") {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt").use { out ->
                    requireNotNull(out) { "That file cannot be written." }
                    out.write(c.made.emergencyKitText(c.url).toByteArray(Charsets.UTF_8))
                }
            }
            say("Emergency Kit saved — print it or keep it offline")
        }
    }

    /** The last step: on the server, then on this phone, then open, with a first Sync. Or this phone only. */
    fun finishCreate(code: String) {
        val c = creating ?: return
        c.code = code
        val url = c.url
        if (url == null) return run("Creating the account…") {
            val account = withContext(Dispatchers.IO) { c.made.createHere(store) }
            creating = null
            opened(OpenAccount(account), null)
            say("Account “${account.username}” created on this phone. To keep a copy on your server, " +
                "see Settings → Sync server.")
        }
        run("Creating the account…") {
            val account = withContext(Dispatchers.Default) { c.made.createOn(store, url, c.caFile, code) }
            creating = null
            val open = OpenAccount(account)
            opened(open, null)
            try {
                withContext(Dispatchers.IO) { open.sync { _, _ -> null } }
                say("Account “${account.username}” created and on the server")
            } catch (e: Exception) {
                say("Account “${account.username}” created. Sync did not run yet: ${e.message}")
            }
        }
    }

    fun cancelCreate() {
        creating = null
        home()
    }

    /** The server's certificate, copied into the app's own files (the account keeps its path). */
    private fun keepCertificate(uri: Uri, url: String): String {
        val folder = File(context.filesDir, "servers").apply { mkdirs() }
        val name = url.substringAfter("://").substringBefore('/').replace(Regex("[^A-Za-z0-9.-]"), "_")
        val target = File(folder, "$name.crt")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "That certificate file cannot be read." }
            target.outputStream().use { input.copyTo(it) }
        }
        return target.path
    }

    fun unlock(username: String, password: String, kit: String?, onNeedsKit: () -> Unit) = run("Unlocking…") {
        val wait = lockout.remaining(username)
        if (wait > 0) throw IllegalStateException("Too many wrong tries. Wait $wait seconds, then try again.")
        try {
            val account = withContext(Dispatchers.Default) { store.unlock(username, password, kit) }
            lockout.reset(username)
            opened(OpenAccount(account), password)
        } catch (e: SecretKeyNeeded) {
            onNeedsKit()
            throw e
        } catch (e: CryptoError) {
            val now = lockout.recordFailure(username)
            throw CryptoError((e.message ?: "Wrong master password or Secret Key.") +
                if (now > 0) " Wait $now seconds before the next try." else "")
        }
    }

    // ------------------------------------------------------------------ the master password and the phrase
    /** Settings -> Change master password: the server first, then this phone; the fingerprint keeps working. */
    fun changePassword(current: String, new: String, done: () -> Unit) {
        val account = open?.account ?: return
        run("Changing the master password…") {
            withContext(Dispatchers.Default) {
                if (!account.checkPassword(current)) throw IllegalArgumentException("That is not the current master password.")
                AccountSync.changePassword(account, new)
            }
            Biometric.remember(context, account.username, new)
            done()
            say(if (account.server != null) "Master password changed, here and on the server. Your other devices " +
                "sign in again with the new one." else "Master password changed")
        }
    }

    fun startNewPhrase() {
        val account = open?.account ?: return
        newPhrase = Recovery.NewPhrase(account)
        screen = Screen.NewPhrase
    }

    fun saveNewPhrase() {
        val fresh = newPhrase ?: return
        run("Saving the new phrase…") {
            withContext(Dispatchers.IO) { fresh.save() }
            newPhrase = null
            screen = Screen.Settings
            say("New recovery phrase saved. The old one opens nothing any more.")
        }
    }

    fun dropNewPhrase() {
        newPhrase = null
        screen = Screen.Settings
    }

    /** Forgot your master password? Step 1: the words open the account's keys (from the server if not here). */
    fun startRecovery(username: String, phrase: String, url: String, certificate: Uri?) = run("Checking the words…") {
        val address = url.trim().takeIf { it.length > 8 } ?: ""
        val ca = if (address.isEmpty()) "" else withContext(Dispatchers.IO) {
            certificate?.let { keepCertificate(it, address) } ?: ""
        }
        recovering = withContext(Dispatchers.Default) { Recovery.openWithPhrase(store, username, phrase, address, ca) }
        screen = Screen.RecoverPassword
    }

    /** Step 2: the new keys under the new password, a new phrase and kit; nothing written yet. */
    fun prepareRecovery(password: String) {
        val opened = recovering ?: return
        run("Making the new keys…") {
            recovered = withContext(Dispatchers.Default) { Recovery.prepare(opened, password) }
            screen = Screen.RecoverKit
        }
    }

    /** Step 3: written (the server first), and the account opens. */
    fun finishRecovery() {
        val pending = recovered ?: return
        run("Recovering the account…") {
            val account = withContext(Dispatchers.Default) { pending.save(store) }
            lockout.reset(account.username)
            recovering = null
            recovered = null
            opened(OpenAccount(account), pending.password)
            say("Account recovered. Use the new master password from now on" +
                if (account.server != null) "; your other devices sign in again with it and the new Emergency Kit." else ".")
        }
    }

    fun cancelRecovery() {
        recovering = null
        recovered = null
        home()
    }

    /** Write ``text`` to a file the user picked (an Emergency Kit). */
    fun saveText(uri: Uri, text: String, done: String) = run("Saving…") {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt").use { out ->
                requireNotNull(out) { "That file cannot be written." }
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        say(done)
    }

    /** The fingerprint gave the password back; a wrong one (changed on another device) is forgotten. */
    fun unlockWithFingerprint(username: String, password: String) = run("Unlocking…") {
        val account = try {
            withContext(Dispatchers.Default) { store.unlock(username, password) }
        } catch (e: Exception) {
            Biometric.forget(username)
            throw IllegalStateException("The master password was changed. Enter the new one.")
        }
        opened(OpenAccount(account), password)
    }

    private suspend fun opened(account: OpenAccount, password: String?) {
        if (password != null) Biometric.remember(context, account.account.username, password)
        val unreadable = withContext(Dispatchers.IO) {
            val bad = account.load()
            account.autoPurge()
            account.finishMoves()
            bad
        }
        open = account
        vaultId = account.account.personalId
        screen = Screen.Vault
        if (unreadable.isNotEmpty()) say("Could not read: " + unreadable.joinToString(", "))
        val newer = account.vaults().filter { it.newerAppText.isNotEmpty() }
        if (newer.isNotEmpty()) say("Read only here: “${newer.first().name}” — update to ${newer.first().newerAppText}")
        if (AccountSync.syncOnUnlock(account.account)) {
            // As on the PC: by itself after the unlock; questions wait until Sync is pressed.
            busy = "Syncing…"
            try {
                val report = withContext(Dispatchers.IO) { account.sync { _, _ -> null } }
                val waiting = report.leftForLater
                if (waiting.isNotEmpty()) say("Synced. Changes on both sides wait in " + waiting.joinToString(", ") +
                    ": press Sync to decide.")
            } catch (e: Exception) {
                say("Sync did not run: ${e.message}")
            }
        }
    }

    fun lock() {
        question?.answer?.complete(null)
        question = null
        newPhrase = null
        val name = open?.account?.username
        open = null
        vaultId = null
        if (name != null) screen = Screen.Unlock(name) else home()
        Clipboard.clearNow(context)
    }

    fun wentAway() {
        if (!away) leftAt = System.currentTimeMillis()
    }

    fun cameBack() {
        val gone = if (leftAt == 0L) 0L else System.currentTimeMillis() - leftAt
        if (!away && open != null && leftAt != 0L && gone >= Prefs.lockMinutes * 60_000L) lock()
        // A new account half made keeps longer (the words are being written down), but not for ever.
        if (!away && creating != null && gone > DROP_NEW_ACCOUNT_MS) cancelCreate()
        if (!away && (recovering != null || recovered != null) && gone > DROP_NEW_ACCOUNT_MS) cancelRecovery()
        if (!away && newPhrase != null && open == null) newPhrase = null
        leftAt = 0L
        // Back in front: whatever picker or prompt was open is closed now, so the next leaving counts.
        away = false
        changed()               // what other apps changed meanwhile (the autofill choice) is shown again
    }

    // ------------------------------------------------------------------ changing
    /** A change to the open account, on a worker thread; the lists are drawn again after. */
    fun change(what: String = "Saving…", work: (OpenAccount) -> Unit) {
        val account = open ?: return
        run(what) { withContext(Dispatchers.IO) { work(account) } }
    }

    fun sync() {
        val account = open ?: return
        if (account.account.server == null) return say("This account is not on a server.")
        run("Syncing…") { say(syncNow(account)) }
    }

    /** One Sync of every vault; what it did, in lines. Conflicts are asked in [question]. */
    private suspend fun syncNow(account: OpenAccount): String {
        val report = withContext(Dispatchers.IO) {
            account.sync { name, result ->
                val asked = Question(name, result, CompletableDeferred())
                runBlocking(Dispatchers.Main) { question = asked }
                try {
                    runBlocking { asked.answer.await() }
                } finally {
                    runBlocking(Dispatchers.Main) { if (question === asked) question = null }
                }
            }
        }
        val lines = report.changes + report.leftForLater.map { "$it: left for the next Sync" } +
            report.tooNew.map { "$it: changed by a newer SecretSpace, nothing changed" } +
            report.readOnly.map { "$it: read only, nothing sent" }
        return if (lines.isEmpty()) "Up to date" else lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ the sync server
    /**
     * Put the open account (on this phone only so far) on a server: made
     * there with the sign-up code, or joined if it is there already; then a
     * first Sync puts the vaults there.
     */
    fun connect(url: String, certificate: Uri?, code: String) {
        val account = open ?: return
        run("Connecting to the server…") {
            val address = url.trim()
            val kept = account.account.server?.text("ca_file") ?: ""
            val ca = withContext(Dispatchers.IO) { certificate?.let { keepCertificate(it, address) } ?: kept }
            val editing = account.account.server != null
            val outcome = withContext(Dispatchers.Default) {
                AccountSync.connect(account.account, address, ca, code)
            }
            screen = Screen.Vault
            busy = "Syncing…"
            val first = if (outcome == "created") "Account created on the server" else if (editing)
                "Sync settings changed" else "Connected to the server"
            try {
                say(first + ". " + syncNow(account))
            } catch (e: Exception) {
                say("$first. Sync did not run yet: ${e.message}")
            }
        }
    }

    /** This phone stops syncing the account; the server keeps its copy (the PC's forget_server). */
    fun disconnect() {
        val account = open ?: return
        run("Saving…") {
            withContext(Dispatchers.IO) { account.account.setServer(null) }
            say("This phone no longer syncs the account. The server's copy is kept.")
        }
    }

    // ------------------------------------------------------------------ updates (the PC's update_check)
    var newerRelease by mutableStateOf<secretspace.core.UpdateCheck.Release?>(null)
        private set

    /** Ask GitHub now; ``quiet`` (the check at start) says nothing when there is nothing new or no answer. */
    fun checkForUpdate(quiet: Boolean = false) {
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { secretspace.core.UpdateCheck.latest() }
                if (found != null && secretspace.core.UpdateCheck.isNewer(found.version)) {
                    newerRelease = found
                    say("SecretSpace ${found.version} is available (you have ${secretspace.core.AppVersion.VERSION}): " +
                        "Settings → Updates")
                } else if (!quiet) say("SecretSpace ${secretspace.core.AppVersion.VERSION} is the newest version")
            } catch (e: Exception) {
                if (!quiet) say("Could not check for a new version: ${e.message}")
            }
        }
    }

    fun answer(choices: Map<String, String>?) {
        question?.answer?.complete(choices)
    }

    companion object {
        /** state.vaultId for "All vaults". */
        const val ALL = "*all*"
        const val DROP_NEW_ACCOUNT_MS = 10 * 60_000L
    }
}
