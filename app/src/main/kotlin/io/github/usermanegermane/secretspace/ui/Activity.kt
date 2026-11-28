package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import secretspace.core.AccountStore
import secretspace.core.Changelog
import secretspace.core.Times

/**
 * The PC's Activity window: who changed what in a vault, newest first,
 * each line's signature checked against its author's key.
 */
@Composable
fun ActivityScreen(state: AppState, vaultId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val name = open.account.vaultFile(vaultId).name
    val rows = open.activity(vaultId)
    val unknown = rows.filter { it.state == Changelog.UNKNOWN }.map { it.by }.toSet()
    val owner = open.account.vaultRole(vaultId) == AccountStore.ROLE_OWNER
    Page("Activity in “$name”", { state.screen = Screen.Vault }) {
        Body {
            Muted("Who changed what, newest first. Values are never recorded, and neither is who looked at an " +
                "entry or copied a password.")
            Gap()
            if (rows.isEmpty()) Muted("Nothing yet. Changes made from this version on are listed here.", style = Type.body)
            rows.forEach { row ->
                Card(padding = Sp.m) {
                    Text(Times.long(row.at), style = Type.caption, color = Look.c.muted)
                    Text("${row.by} ${row.what}", style = Type.body, color = when (row.state) {
                        Changelog.BAD -> Look.c.danger
                        Changelog.UNKNOWN -> Look.c.warn
                        else -> Look.c.text
                    })
                    when (row.state) {
                        Changelog.BAD -> Text("⚠ The signature does not match ${row.by}'s key: this line may not be theirs.",
                            style = Type.small, color = Look.c.danger)
                        Changelog.UNKNOWN -> Text("Not checked: this phone has not seen ${row.by}'s key.",
                            style = Type.small, color = Look.c.warn)
                    }
                }
                Gap(Sp.xs)
            }
            if (unknown.isNotEmpty() && open.account.server != null) {
                Gap()
                QuietButton("Check signatures", {
                    state.change("Asking the server for their keys…") { it.fetchKeys(unknown) }
                }, Modifier.fillMaxWidth(), enabled = state.busy == null)
            }
            if (owner && open.whyNotShare(vaultId).isEmpty()) {
                Gap(Sp.s)
                QuietButton("Server log", { state.screen = Screen.ServerLog(vaultId) }, Modifier.fillMaxWidth())
                Muted("What the server noted: uploads, members, key changes (owners only).", style = Type.caption)
            }
        }
    }
}

/** The PC's Server log window: what the server noted about the vault, with the address of each request. */
@Composable
fun ServerLogScreen(state: AppState, vaultId: String) {
    val open = state.open ?: return
    val name = open.account.vaultFile(vaultId).name
    var lines by remember(vaultId) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(vaultId) {
        state.run("Asking the server…") { lines = withContext(Dispatchers.IO) { open.serverLog(vaultId) } }
    }
    Page("Server log of “$name”", { state.screen = Screen.Activity(vaultId) }) {
        Body {
            Muted("What the server noted about this vault, newest first, with the address each request came from. " +
                "The server cannot see inside the vault: which entry changed is in Activity.")
            Gap()
            val shown = lines
            if (shown == null) Muted("Asking the server…", style = Type.body)
            else shown.forEach { (at, text) ->
                Card(padding = Sp.m) {
                    Column {
                        Text(Times.long(at), style = Type.caption, color = Look.c.muted)
                        Text(text, style = Type.body, color = Look.c.text)
                    }
                }
                Gap(Sp.xs)
            }
        }
    }
}
