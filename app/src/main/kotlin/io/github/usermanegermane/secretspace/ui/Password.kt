package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.kitText
import secretspace.core.Passwords
import secretspace.core.Disk.text

/** Settings: the master password and the recovery phrase (the PC's Settings → Account and Recovery phrase). */
@Composable
fun PasswordSettings(state: AppState) {
    val open = state.open ?: return
    var changing by remember { mutableStateOf(false) }
    val kit = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        state.away = false
        if (it != null) state.saveText(it, kitText(open.account.username, open.account.secretKeyText,
            open.account.server?.text("url")), "Emergency Kit saved — print it or keep it offline")
    }
    Card {
        SectionLabel("Master password")
        Muted(if (open.account.server != null) "A new one is changed on the server first; your other devices then " +
            "sign in again with it." else "The vaults stay as they are: only the lock on the account's keys changes.",
            style = Type.caption)
        Gap(Sp.s)
        QuietButton("Change master password…", { changing = true }, enabled = state.busy == null)
        Rule()
        SectionLabel("Emergency Kit")
        Muted("Your Secret Key again — with the master password it opens this account on another device.",
            style = Type.caption)
        Gap(Sp.s)
        QuietButton("Save kit…", {
            state.away = true
            kit.launch("SecretSpace Emergency Kit - ${open.account.username}.txt")
        })
        Rule()
        SectionLabel("Recovery phrase")
        Muted("12 words that open the account if the master password is forgotten. Lost them, or someone saw " +
            "them? Make new ones: the old words then open nothing.", style = Type.caption)
        Gap(Sp.s)
        QuietButton("New phrase…", { state.startNewPhrase() }, enabled = state.busy == null)
    }
    if (changing) ChangePasswordDialog(state) { changing = false }
}

@Composable
private fun ChangePasswordDialog(state: AppState, dismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val (label, fraction) = Passwords.strength(new)
    val problem = when {
        new.isEmpty() -> null
        again.isNotEmpty() && again != new -> "The two new passwords are not the same."
        new == current -> "That is the current one."
        else -> null
    }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Change master password", style = Type.heading) },
        text = { Column {
            Field("Current master password", current, { current = it }, secret = true)
            Field("New master password", new, { new = it }, secret = true)
            if (new.isNotEmpty()) Meter(label, fraction)
            Field("New master password again", again, { again = it }, secret = true)
            problem?.let { Text(it, style = Type.small, color = Look.c.danger) }
            Muted("Nobody can reset it for you. If you forget it, only the recovery phrase opens the account.",
                style = Type.caption)
        } },
        confirmButton = { MainButton("Change", { state.changePassword(current, new) { dismiss() } },
            enabled = state.busy == null && current.isNotEmpty() && new.isNotEmpty() && again == new && problem == null) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}

/** Settings → New phrase: the words first; saved (on the server first) only once they are written down. */
@Composable
fun NewPhraseScreen(state: AppState) {
    val fresh = state.newPhrase ?: return
    var written by remember { mutableStateOf(false) }
    Page("New recovery phrase", { state.dropNewPhrase() }) {
        Body {
            Muted("Write these 12 words on paper, in order. When you save them, the old words stop working" +
                (if (fresh.account.server != null) ", here and on the server." else "."))
            Gap()
            WordGrid(fresh.recoveryPhrase)
            Gap()
            Muted("Anyone with these words can open everything in this account. Keep them like a passport: not in a " +
                "file or a photo, and not with the Emergency Kit.")
            Gap(Sp.s)
            CheckRow(written, "I wrote them down", { written = it })
            Gap()
            MainButton("Save the new phrase", { state.saveNewPhrase() }, Modifier.fillMaxWidth(),
                enabled = written && state.busy == null)
            LinkButton("Cancel", { state.dropNewPhrase() }, muted = true)
        }
    }
}

/** Forgot your master password? Step 1: the account and its 12 words (and its server, when not on this phone). */
@Composable
fun RecoverScreen(state: AppState, username: String) {
    var name by remember { mutableStateOf(username) }
    var phrase by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var certificate by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        state.away = false
        if (it != null) certificate = it
    }
    val here = name.isNotBlank() && state.store.accountExists(name.trim())
    Page("Recover your account", { state.cancelRecovery() }) {
        Body {
            Muted("The 12-word recovery phrase you wrote down when the account was made opens it without the master " +
                "password. You then choose a new master password and get a new phrase and a new Emergency Kit.")
            Gap()
            Card {
                Field("Username", name, { name = it })
                Field("Recovery phrase", phrase, { phrase = it.lowercase() }, singleLine = false,
                    supporting = "The 12 words, in order, with spaces between them")
                if (!here) {
                    Gap(Sp.s)
                    Muted("This phone does not have this account: it comes from its server.", style = Type.caption)
                    Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri)
                    CertificatePicker(certificate) { state.away = true; pick.launch(arrayOf("*/*")) }
                }
            }
            Gap()
            MainButton("Next", { state.startRecovery(name, phrase, if (here) "" else url, certificate) },
                Modifier.fillMaxWidth(), enabled = state.busy == null && name.isNotBlank() && phrase.isNotBlank() &&
                    (here || url.length > 8))
            LinkButton("Cancel", { state.cancelRecovery() }, muted = true)
        }
    }
}

/** Step 2: the new master password. */
@Composable
fun RecoverPasswordScreen(state: AppState) {
    val opened = state.recovering ?: return
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Page("Recover your account", { state.cancelRecovery() }) {
        Body {
            Text("The words are right.", style = Type.leadBold, color = Look.c.text)
            Muted("Choose a new master password for “${opened.username}”." + if (opened.server != null)
                " The server is changed first; your other devices then sign in again with it." else "")
            Gap()
            Card {
                Field("New master password", new, { new = it }, secret = true)
                if (new.isNotEmpty()) Passwords.strength(new).let { (label, fraction) -> Meter(label, fraction) }
                Field("New master password again", again, { again = it }, secret = true)
                if (again.isNotEmpty() && again != new) Text("The two passwords are not the same.", style = Type.small,
                    color = Look.c.danger)
            }
            Gap()
            MainButton("Next", { state.prepareRecovery(new) }, Modifier.fillMaxWidth(),
                enabled = state.busy == null && new.isNotEmpty() && again == new)
            LinkButton("Cancel", { state.cancelRecovery() }, muted = true)
        }
    }
}

/** Step 3: the new Emergency Kit and phrase, then everything is written. */
@Composable
fun RecoverKitScreen(state: AppState) {
    val pending = state.recovered ?: return
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    val server = pending.opened.server?.text("url")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        state.away = false
        if (it != null) state.saveText(it, kitText(pending.username, pending.secretKeyText, server),
            "Emergency Kit saved — print it or keep it offline")
    }
    Page("Recover your account", { state.cancelRecovery() }) {
        Body {
            Muted("Nothing is changed yet. Keep these, then press Recover: the old Emergency Kit and the old words stop " +
                "working.")
            Gap()
            Card {
                SectionLabel("New Secret Key")
                Text(breakAtDashes(pending.secretKeyText), style = Type.code, color = Look.c.text)
                Gap(Sp.m)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    QuietButton("Save Emergency Kit…", {
                        state.away = true
                        save.launch("SecretSpace Emergency Kit - ${pending.username}.txt")
                    })
                    QuietButton("Copy", {
                        Clipboard.copy(context, "Secret Key", pending.secretKeyText, sensitive = true)
                        state.say("Secret Key copied")
                    })
                }
            }
            Gap()
            SectionLabel("New recovery phrase")
            WordGrid(pending.recoveryPhrase)
            Gap(Sp.s)
            CheckRow(saved, "I saved the new kit and wrote down the new words", { saved = it })
            Gap()
            MainButton("Recover", { state.finishRecovery() }, Modifier.fillMaxWidth(),
                enabled = saved && state.busy == null)
            LinkButton("Cancel", { state.cancelRecovery() }, muted = true)
        }
    }
}
