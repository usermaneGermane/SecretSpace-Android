package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import kotlinx.coroutines.delay
import secretspace.core.Changelog
import secretspace.core.Py
import secretspace.core.Totp

@Composable
fun EntryScreen(state: AppState, vaultId: String, entryId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val entry = open.find(vaultId, entryId) ?: run { state.screen = Screen.Vault; return }
    val canWrite = open.account.canWrite(vaultId)
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    fun copy(label: String, text: String, sensitive: Boolean) {
        if (text.isEmpty()) return state.say("Nothing to copy")
        Clipboard.copy(context, label, text, sensitive)
        state.say(if (sensitive) "$label copied · clears in ${Clipboard.CLEAR_MS / 1000}s" else "$label copied")
    }
    val back = { state.screen = if (Py.text(entry, "archived_at").isNotEmpty()) Screen.Archive(vaultId) else Screen.Vault }
    Column(Modifier.fillMaxSize()) {
        Bar(Py.text(entry, "site").ifEmpty { "(no name)" }, back) {
            if (canWrite) TextButton(onClick = { state.screen = Screen.Edit(vaultId, entryId) }) { Text("Edit") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Line("Username", Py.text(entry, "username")) { copy("Username", Py.text(entry, "username"), false) }
            val password = Py.text(entry, "password")
            Line("Password", if (shown) password else "•".repeat(minOf(password.length, 12)), mono = shown) {
                copy("Password", password, true)
            }
            TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide password" else "Show password") }
            Py.text(entry, "url").takeIf { it.isNotEmpty() }?.let { url -> Line("Web address", url) { copy("Web address", url, false) } }
            Py.text(entry, "totp").takeIf { it.isNotEmpty() }?.let { TotpLine(it) { code -> copy("2FA code", code, true) } }
            Py.text(entry, "notes").takeIf { it.isNotEmpty() }?.let { Line("Notes", it) { copy("Notes", it, true) } }
            Spacer(Modifier.padding(8.dp))
            val by = Py.text(entry, "updated_by")
            Text("Changed " + Py.text(entry, "updated_at").ifEmpty { "(before dates were kept)" } +
                (if (by.isNotEmpty()) " by $by" else ""), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.padding(8.dp))
            Row {
                OutlinedButton(onClick = { state.screen = Screen.History(vaultId, entryId) }) { Text("Earlier versions") }
                Spacer(Modifier.width(8.dp))
                if (canWrite && Py.text(entry, "archived_at").isEmpty()) {
                    OutlinedButton(onClick = { moving = true }) { Text("Move…") }
                    DropdownMenu(expanded = moving, onDismissRequest = { moving = false }) {
                        open.vaults().filter { it.id != vaultId && it.canWrite }.forEach { v ->
                            DropdownMenuItem(text = { Text(v.name) }, onClick = {
                                moving = false
                                state.change("Moving…") { it.move(vaultId, entryId, v.id) }
                                state.screen = Screen.Vault
                            })
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { confirmArchive = true }) { Text("Archive") }
                }
            }
        }
    }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("Archive “${Py.text(entry, "site")}”?") },
        text = { Text("It leaves the list and can be restored from the Archive.") },
        confirmButton = { TextButton(onClick = {
            confirmArchive = false
            state.change { it.archive(vaultId, entryId) }
            state.screen = Screen.Vault
        }) { Text("Archive") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Line(label: String, value: String, mono: Boolean = false, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, fontFamily = if (mono) FontFamily.Monospace else null)
            }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

/** The 2FA code of the moment, and how long it stays. */
@Composable
private fun TotpLine(stored: String, onCopy: (String) -> Unit) {
    val params = remember(stored) { runCatching { Totp.parse(stored) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stored) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    params.onFailure { Line("2FA", it.message ?: "Cannot make codes from this secret.") {} }
    params.onSuccess { p ->
        val code = Totp.code(p, now / 1000.0)
        Line("2FA code · ${Totp.secondsLeft(p, now / 1000.0)}s", Totp.spaced(code), mono = true) { onCopy(code) }
    }
}

@Composable
fun HistoryScreen(state: AppState, vaultId: String, entryId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val entry = open.find(vaultId, entryId) ?: run { state.screen = Screen.Vault; return }
    val canWrite = open.account.canWrite(vaultId)
    val revisions = Py.asList(entry["history"])?.mapNotNull { Py.asObj(it) } ?: emptyList()
    var shownRid by remember { mutableStateOf<String?>(null) }
    val log = Changelog.linesOf(open.data[vaultId]).filter { it["entry"] == entryId }.reversed()
    Column(Modifier.fillMaxSize()) {
        Bar("Earlier versions", { state.screen = Screen.Entry(vaultId, entryId) }) {
            if (canWrite && revisions.isNotEmpty()) TextButton(onClick = {
                state.change { it.clearHistory(vaultId, entryId) }
            }) { Text("Clear") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (revisions.isEmpty()) item { Text("No earlier versions.", Modifier.padding(8.dp)) }
            items(revisions, key = { it["rid"] as String }) { rev ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        val changed = Py.asList(rev["changed"])?.joinToString(", ") ?: ""
                        Text("Until ${Py.text(rev, "replaced_at").take(19).replace('T', ' ')}" +
                            (Py.text(rev, "by").takeIf { it.isNotEmpty() }?.let { " · changed by $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium)
                        if (changed.isNotEmpty()) Text("Then changed: $changed", style = MaterialTheme.typography.bodySmall)
                        Text("${Py.text(rev, "site")} · ${Py.text(rev, "username")}")
                        Text(if (shownRid == rev["rid"]) Py.text(rev, "password") else "••••••••",
                            fontFamily = FontFamily.Monospace)
                        Row {
                            TextButton(onClick = { shownRid = if (shownRid == rev["rid"]) null else rev["rid"] as String }) {
                                Text(if (shownRid == rev["rid"]) "Hide" else "Show")
                            }
                            if (canWrite) TextButton(onClick = {
                                state.change { it.revert(vaultId, entryId, rev["rid"] as String) }
                                state.screen = Screen.Entry(vaultId, entryId)
                            }) { Text("Put back") }
                        }
                    }
                }
            }
            if (log.isNotEmpty()) item { Text("Changes", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall) }
            items(log, key = { "log-" + it["id"] }) { line ->
                val signer = open.account.signingKeyFor(line["by"] as String)
                val check = Changelog.check(line, open.account.vaultFile(vaultId).hex, signer)
                Text("${Py.text(line, "at").take(16).replace('T', ' ')} · ${line["by"]} ${line["action"]}" +
                    (if (check == Changelog.BAD) "  ⚠ signature does not match" else ""),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
