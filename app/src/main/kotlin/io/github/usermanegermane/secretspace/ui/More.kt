package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Question
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.AppVersion
import secretspace.core.Disk.text
import secretspace.core.Merge
import secretspace.core.Obj
import secretspace.core.Py

@Composable
fun ArchiveScreen(state: AppState, vaultId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val canWrite = open.account.canWrite(vaultId)
    var doomed by remember { mutableStateOf<Obj?>(null) }
    val entries = open.entries(vaultId, archived = true)
    Column(Modifier.fillMaxSize()) {
        Bar("Archive", { state.screen = Screen.Vault })
        if (entries.isEmpty()) Text("Nothing archived.", Modifier.padding(16.dp))
        LazyColumn {
            items(entries, key = { it["id"] as String }) { e ->
                ListItem(
                    headlineContent = { Text(Py.text(e, "site").ifEmpty { "(no name)" }) },
                    supportingContent = { Text("Archived " + Py.text(e, "archived_at").take(10) +
                        (if (Py.asObj(e["moved"]) != null) " · moved to another vault" else "")) },
                    trailingContent = { if (canWrite) Row {
                        TextButton(onClick = { state.change { it.restore(vaultId, e["id"] as String) } }) { Text("Restore") }
                        TextButton(onClick = { doomed = e }) { Text("Delete") }
                    } },
                    modifier = Modifier.clickable { state.screen = Screen.Entry(vaultId, e["id"] as String) },
                )
                HorizontalDivider()
            }
        }
    }
    doomed?.let { e ->
        AlertDialog(
            onDismissRequest = { doomed = null },
            title = { Text("Delete “${Py.text(e, "site")}” for good?") },
            text = { Text("This cannot be undone, here or on your other devices after they sync.") },
            confirmButton = { TextButton(onClick = {
                doomed = null
                state.change { it.purge(vaultId, e["id"] as String) }
            }) { Text("Delete for good") } },
            dismissButton = { TextButton(onClick = { doomed = null }) { Text("Cancel") } },
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
    Column(Modifier.fillMaxSize()) {
        Bar("Settings", { state.screen = Screen.Vault })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Account", style = MaterialTheme.typography.titleSmall)
            Text(account.username)
            Text("Fingerprint: ${account.fingerprint}", style = MaterialTheme.typography.bodySmall)
            Text("Read it out to someone who shares a vault with you, so they can check it is you.",
                style = MaterialTheme.typography.bodySmall)
            Text("Server: " + (account.server?.text("url") ?: "none"), Modifier.padding(top = 8.dp))
            if (open.account.canWrite(vaultId)) {
                Text("Archive of “${account.vaultFile(vaultId).name}”", Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleSmall)
                val days = open.purgeDays(vaultId)
                listOf(0, 30, 90, 180, 365).forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { state.change { it.setPurgeDays(vaultId, choice) } }) {
                        RadioButton(selected = days == choice, onClick = { state.change { it.setPurgeDays(vaultId, choice) } })
                        Text(if (choice == 0) "Keep archived entries until I delete them" else "Delete them after $choice days")
                    }
                }
            }
            OutlinedButton(onClick = { removing = true }, Modifier.padding(top = 24.dp)) {
                Text("Remove this account from this phone")
            }
            Text("SecretSpace for Android ${AppVersion.VERSION}", Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Remove “${account.username}” from this phone?") },
        text = { Column {
            Text("The account stays on the server and your other devices. Changes not yet synced are lost.")
            Field("Master password", password, { password = it }, secret = true)
        } },
        confirmButton = { TextButton(onClick = {
            if (!account.checkPassword(password)) return@TextButton state.say("That is not the master password.")
            removing = false
            val name = account.username
            state.lock()
            state.store.deleteAccount(name)
            val left = state.store.listAccounts()
            state.screen = if (left.isEmpty()) Screen.SignIn else Screen.Unlock(left.first())
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
    )
}

/** A sync's question: for each entry changed on both sides, which version to keep. */
@Composable
fun ConflictDialog(state: AppState, question: Question) {
    val choices = remember(question) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("“${question.vaultName}”: ${question.result.conflicts.size} to decide") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(question.result.conflicts, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(c.site.ifEmpty { "(no name)" }, style = MaterialTheme.typography.titleSmall)
                            Text(when (c.kind) {
                                "archive" -> "Archived on one side, changed on the other."
                                "purge" -> "Deleted on one side, changed on the other."
                                "missing" -> "Here, but not on the server (an older or other copy there)."
                                else -> "Changed here and on another device."
                            }, style = MaterialTheme.typography.bodySmall)
                            if (c.local != null && c.remote != null) {
                                val differ = secretspace.core.Entries.changedFields(c.local!!, c.remote!!)
                                if (differ.isNotEmpty()) Text("Differs in: " + differ.joinToString(", ") {
                                    if (it == "site") "name" else if (it == "totp") "2FA" else if (it == "url") "web address" else it
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                            side("Here", c.local)
                            side("On the server", c.remote)
                            val chosen = choices[c.id] ?: Merge.KEEP_BOTH
                            listOf(Merge.KEEP_LOCAL to "Keep this phone's", Merge.KEEP_REMOTE to "Keep the server's",
                                Merge.KEEP_BOTH to "Keep both").forEach { (value, label) ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { choices[c.id] = value }) {
                                    RadioButton(selected = chosen == value, onClick = { choices[c.id] = value })
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            state.answer(question.result.conflicts.associate { it.id to (choices[it.id] ?: Merge.KEEP_BOTH) })
        }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { state.answer(null) }) { Text("Later") } },
    )
}

@Composable
private fun side(label: String, entry: Obj?) {
    Text("$label: " + if (entry == null) "deleted" else
        listOfNotNull(Py.text(entry, "username").takeIf { it.isNotEmpty() },
            Py.text(entry, "updated_at").takeIf { it.isNotEmpty() }?.let { "changed $it" },
            Py.text(entry, "updated_by").takeIf { it.isNotEmpty() }?.let { "by $it" },
            if (Py.text(entry, "archived_at").isNotEmpty()) "archived" else null).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall)
}
