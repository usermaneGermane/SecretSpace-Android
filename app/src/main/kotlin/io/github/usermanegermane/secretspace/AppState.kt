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
import secretspace.core.AccountStore
import secretspace.core.AccountSync
import secretspace.core.Merge
import secretspace.core.NeedsConversion
import secretspace.core.OpenAccount
import secretspace.core.PasswordSecretStore
import secretspace.core.SecretKeyNeeded
import java.io.File

/** Where the app is. */
sealed interface Screen {
    data object SignIn : Screen
    data class Unlock(val username: String) : Screen
    data object Vault : Screen
    data class Entry(val vaultId: String, val entryId: String) : Screen
    data class Edit(val vaultId: String, val entryId: String?) : Screen
    data class History(val vaultId: String, val entryId: String) : Screen
    data class Archive(val vaultId: String) : Screen
    data object Settings : Screen
}

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
    var screen by mutableStateOf<Screen>(Screen.SignIn)
    var vaultId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var question by mutableStateOf<Question?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set

    /** When the app went to the background; it locks if it stays there too long. */
    private var leftAt = 0L
    /** A picker or the camera is in front: that is not leaving the app. */
    var away = false

    init {
        val accounts = store.listAccounts()
        screen = if (accounts.isEmpty()) Screen.SignIn else Screen.Unlock(accounts.first())
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
            opened(OpenAccount(signed.account))
        } catch (e: SecretKeyNeeded) {
            onNeedsKit()
            throw e
        } catch (e: NeedsConversion) {
            throw e
        }
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
        try {
            val account = withContext(Dispatchers.Default) { store.unlock(username, password, kit) }
            opened(OpenAccount(account))
        } catch (e: SecretKeyNeeded) {
            onNeedsKit()
            throw e
        }
    }

    private suspend fun opened(account: OpenAccount) {
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
    }

    fun lock() {
        question?.answer?.complete(null)
        question = null
        val name = open?.account?.username
        open = null
        vaultId = null
        screen = if (name != null) Screen.Unlock(name) else Screen.SignIn
        Clipboard.clearNow(context)
    }

    fun wentAway() {
        if (!away) leftAt = System.currentTimeMillis()
    }

    fun cameBack() {
        if (!away && open != null && leftAt != 0L && System.currentTimeMillis() - leftAt > LOCK_AFTER_MS) lock()
        leftAt = 0L
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
        run("Syncing…") {
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
            say(if (lines.isEmpty()) "Up to date" else lines.joinToString("\n"))
        }
    }

    fun answer(choices: Map<String, String>?) {
        question?.answer?.complete(choices)
    }

    companion object {
        const val LOCK_AFTER_MS = 60_000L
    }
}
