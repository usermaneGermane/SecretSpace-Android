package io.github.usermanegermane.secretspace.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Biometric
import io.github.usermanegermane.secretspace.Question
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.AppVersion
import secretspace.core.Disk.text
import secretspace.core.Merge
import secretspace.core.Obj
import secretspace.core.Py
import secretspace.core.Times

@Composable
fun ArchiveScreen(state: AppState, vaultId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val canWrite = open.account.canWrite(vaultId)
    var doomed by remember { mutableStateOf<Obj?>(null) }
    val entries = open.entries(vaultId, archived = true)
    Page("Archive", { state.screen = Screen.Vault }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Sp.l, end = Sp.l, bottom = Sp.xxl),
            verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Muted(if (entries.isEmpty()) "Nothing archived." else "Archived entries of “${open.account.vaultFile(vaultId).name}”",
                    Modifier.padding(vertical = Sp.xs))
            }
            items(entries, key = { it["id"] as String }) { e ->
                val id = e["id"] as String
                Column(Modifier.fillMaxWidth()) {
                    RowCard(Py.text(e, "site"), "Archived " + Times.long(Py.text(e, "archived_at")) +
                        (if (Py.asObj(e["moved"]) != null) " · moved to another vault" else ""),
                        { state.screen = Screen.Entry(vaultId, id) })
                    if (canWrite) Row(Modifier.padding(top = Sp.xs, start = Sp.s), horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        QuietButton("Restore", { state.change { it.restore(vaultId, id) } })
                        QuietButton("Delete for good", { doomed = e }, danger = true)
                    }
                }
            }
        }
    }
    doomed?.let { e ->
        AlertDialog(
            onDismissRequest = { doomed = null }, containerColor = Look.c.card, shape = Radius.dialog,
            title = { Text("Delete “${Py.text(e, "site")}” for good?", style = Type.heading) },
            text = { Text("This cannot be undone, here or on your other devices after they sync.", style = Type.body,
                color = Look.c.muted) },
            confirmButton = { DangerButton("Delete for good", {
                doomed = null
                state.change { it.purge(vaultId, e["id"] as String) }
            }) },
            dismissButton = { QuietButton("Cancel", { doomed = null }) },
        )
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val account = open.account
    val vaultId = state.vaultId ?: account.personalId
    var removing by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    Page("Settings", { state.screen = Screen.Vault }) {
        Body {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account.username, 48.dp)
                    Spacer(Modifier.width(Sp.m))
                    Column {
                        Text(account.username, style = Type.leadBold, color = Look.c.text)
                        Muted(if (account.server != null) "Synced with your server" else "On this phone only", style = Type.caption)
                    }
                }
                Rule()
                SectionLabel("Fingerprint")
                Text(account.fingerprint, style = Type.mono, color = Look.c.text)
                Gap(Sp.xs)
                Muted("Read it out to someone who shares a vault with you, so they can check it is you.", style = Type.caption)
            }
            Gap(Sp.m)
            PasswordSettings(state)
            Gap(Sp.m)
            ServerSettings(state)
            Gap(Sp.m)
            SecuritySettings(state)
            if (account.canWrite(vaultId)) {
                Gap(Sp.m)
                Card {
                    SectionLabel("Archive of “${account.vaultFile(vaultId).name}”")
                    val days = open.purgeDays(vaultId)
                    listOf(0, 30, 90, 180, 365).forEach { choice ->
                        RadioRow(days == choice, if (choice == 0) "Keep archived entries until I delete them"
                            else "Delete them after $choice days") { state.change { it.setPurgeDays(vaultId, choice) } }
                    }
                }
            }
            Gap(Sp.l)
            QuietButton("Remove this account from this phone", { password = ""; removing = true }, danger = true)
            Gap(Sp.l)
            Muted("SecretSpace for Android ${AppVersion.VERSION}", style = Type.caption)
        }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false }, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Remove “${account.username}” from this phone?", style = Type.heading) },
        text = { Column {
            Text(if (account.server == null) "This account is on this phone only: removing it deletes all its " +
                "passwords for good." else "The account stays on the server and your other devices. Changes not " +
                "yet synced are lost.", style = Type.body, color = Look.c.muted)
            Gap(Sp.s)
            Field("Master password", password, { password = it }, secret = true)
        } },
        confirmButton = { DangerButton("Remove", {
            if (!account.checkPassword(password)) return@DangerButton state.say("That is not the master password.")
            removing = false
            val name = account.username
            Biometric.turnOff(context, name)
            state.lock()
            state.store.deleteAccount(name)
            state.home()
        }) },
        dismissButton = { QuietButton("Cancel", { removing = false }) },
    )
}

/** A sync's question: for each entry changed on both sides, which version to keep. */
@Composable
fun ConflictDialog(state: AppState, question: Question) {
    val choices = remember(question) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = { }, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("“${question.vaultName}”: ${question.result.conflicts.size} to decide", style = Type.heading) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                items(question.result.conflicts, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().clip(Radius.row).background(Look.c.input).padding(Sp.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tile(c.site, 32.dp)
                            Spacer(Modifier.width(Sp.s))
                            Text(c.site.ifEmpty { "(no name)" }, style = Type.bodyBold, color = Look.c.text)
                        }
                        Gap(Sp.xs)
                        Muted(when (c.kind) {
                            "archive" -> "Archived on one side, changed on the other."
                            "purge" -> "Deleted on one side, changed on the other."
                            "missing" -> "Here, but not on the server (an older or other copy there)."
                            else -> "Changed here and on another device."
                        }, style = Type.caption)
                        if (c.local != null && c.remote != null) {
                            val differ = secretspace.core.Entries.changedFields(c.local!!, c.remote!!)
                            if (differ.isNotEmpty()) Muted("Differs in: " + differ.joinToString(", ") {
                                if (it == "site") "name" else if (it == "totp") "2FA" else if (it == "url") "web address" else it
                            }, style = Type.caption)
                        }
                        side("Here", c.local)
                        side("On the server", c.remote)
                        val chosen = choices[c.id] ?: Merge.KEEP_BOTH
                        listOf(Merge.KEEP_LOCAL to "Keep this phone's", Merge.KEEP_REMOTE to "Keep the server's",
                            Merge.KEEP_BOTH to "Keep both").forEach { (value, label) ->
                            RadioRow(chosen == value, label) { choices[c.id] = value }
                        }
                    }
                }
            }
        },
        confirmButton = { MainButton("Done", {
            state.answer(question.result.conflicts.associate { it.id to (choices[it.id] ?: Merge.KEEP_BOTH) })
        }) },
        dismissButton = { QuietButton("Later", { state.answer(null) }) },
    )
}

@Composable
private fun side(label: String, entry: Obj?) {
    Muted("$label: " + if (entry == null) "deleted" else
        listOfNotNull(Py.text(entry, "username").takeIf { it.isNotEmpty() },
            Py.text(entry, "updated_at").takeIf { it.isNotEmpty() }?.let { "changed ${Times.long(it)}" },
            Py.text(entry, "updated_by").takeIf { it.isNotEmpty() }?.let { "by $it" },
            if (Py.text(entry, "archived_at").isNotEmpty()) "archived" else null).joinToString(" · "),
        style = Type.caption)
}

/**
 * Settings -> Sync server: the server this account syncs with, or -- for
 * one on this phone only -- putting it on one (the PC's Connect & sync).
 */
@Composable
private fun ServerSettings(state: AppState) {
    val open = state.open ?: return
    val server = open.account.server
    var leaving by remember { mutableStateOf(false) }
    Card {
        SectionLabel("Sync server")
        if (server == null) {
            Text("On this phone only", style = Type.bodyBold, color = Look.c.text)
            Muted("If the phone is lost, broken or reset, its passwords are gone. Put the account on your " +
                "SecretSpace server to keep a copy there and use it on your PCs.")
            Gap(Sp.m)
            QuietButton("Connect to a server…", { state.screen = Screen.Connect })
        } else {
            Text(server.text("url") ?: "", style = Type.bodyBold, color = Look.c.text)
            Muted("Changes go both ways each time you sync.")
            Gap(Sp.m)
            QuietButton("Stop syncing on this phone", { leaving = true })
        }
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false }, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Stop syncing on this phone?", style = Type.heading) },
        text = { Text("The entries stay on this phone and the server keeps its copy, but changes no longer go " +
            "between them. You can connect again later.", style = Type.body, color = Look.c.muted) },
        confirmButton = { MainButton("Stop syncing", { leaving = false; state.disconnect() }) },
        dismissButton = { QuietButton("Cancel", { leaving = false }) },
    )
}

/**
 * Put an account that is on this phone only on a server: the address, its
 * certificate and the sign-up code -- or no code, if the account is on that
 * server already.
 */
@Composable
fun ConnectScreen(state: AppState) {
    val open = state.open ?: return
    var url by remember { mutableStateOf("https://") }
    var certificate by remember { mutableStateOf<Uri?>(null) }
    var code by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        state.away = false
        if (it != null) certificate = it
    }
    Page("Connect to a server", { state.screen = Screen.Settings }) {
        Body {
            Muted("“${open.account.username}” is made on your SecretSpace server with this Secret Key and master " +
                "password, then synced. Your PCs can then sign in to it.", style = Type.body)
            Gap(Sp.m)
            Card {
                Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri,
                    supporting = "As on the PC, e.g. https://10.10.10.3:59872")
                CertificatePicker(certificate) { state.away = true; pick.launch(arrayOf("*/*")) }
                Field("Sign-up code", code, { code = it }, secret = true,
                    supporting = "The server's token: on the server, sudo secretspace-setup show-token. " +
                        "Leave it empty if the account is on that server already.")
            }
            Gap(Sp.l)
            MainButton("Connect & sync", { state.connect(url, certificate, code) }, Modifier.fillMaxWidth(),
                enabled = state.busy == null && url.length > 8)
        }
    }
}

/** Settings -> Security: unlock with fingerprint (as Windows Hello on the PC), and autofill. */
@Composable
private fun SecuritySettings(state: AppState) {
    val open = state.open ?: return
    val username = open.account.username
    val activity = LocalContext.current as FragmentActivity
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    var on by remember(username) { mutableStateOf(Biometric.isOn(activity, username)) }
    var asking by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val why = Biometric.whyNot(activity)
    Card {
        SectionLabel("Security")
        SwitchRow("Unlock with fingerprint", why ?: ((if (Biometric.pinAllowed) "Your fingerprint, face or the phone's " +
            "PIN opens this account again after a lock — so can anyone who knows the phone's PIN." else "Your " +
            "fingerprint opens this account again after a lock.") + " The master password is still needed each " +
            "time SecretSpace starts."), on, on || why == null) { want ->
            if (!want) {
                Biometric.turnOff(activity, username)
                on = false
            } else {
                password = ""
                asking = true
            }
        }
        Rule()
        val autofill = activity.getSystemService(AutofillManager::class.java)
        val ours = autofill?.hasEnabledAutofillServices() == true
        Row(Modifier.padding(vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Autofill", style = Type.bodyBold, color = Look.c.text)
                Muted(when {
                    autofill == null || !autofill.isAutofillSupported -> "This phone does not offer autofill to apps."
                    ours -> "On: SecretSpace offers your logins in apps and browsers that support Android's autofill."
                    else -> "Let SecretSpace fill in logins in other apps and browsers: choose it as the autofill service."
                })
            }
            if (autofill?.isAutofillSupported == true) {
                Spacer(Modifier.width(Sp.m))
                QuietButton(if (ours) "Change" else "Turn on", {
                    // Android's own settings: that is leaving the app, so it locks if left there.
                    try {
                        activity.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                            Uri.parse("package:" + activity.packageName)))
                    } catch (e: Exception) {
                        state.say("Open Android's settings → Passwords & accounts → Autofill service, and choose SecretSpace.")
                    }
                })
            }
        }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false }, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Unlock with fingerprint", style = Type.heading) },
        text = { Column {
            Text("Enter the master password of “$username” to turn it on.", style = Type.body, color = Look.c.muted)
            Gap(Sp.s)
            Field("Master password", password, { password = it }, secret = true)
        } },
        confirmButton = { MainButton("Turn on", {
            if (!open.account.checkPassword(password)) return@MainButton state.say("That is not the master password.")
            asking = false
            state.away = true
            Biometric.turnOn(activity, username, password, {
                state.away = false
                on = true
                state.say("Unlock with fingerprint is on")
            }, { error ->
                state.away = false
                on = false
                if (error != null) state.say(error)
            })
        }) },
        dismissButton = { QuietButton("Cancel", { asking = false }) },
    )
}
