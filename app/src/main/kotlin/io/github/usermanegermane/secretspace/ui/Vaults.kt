package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.AccountStore
import secretspace.core.Entries
import secretspace.core.OpenAccount
import secretspace.core.Py

/** What a dialog on the Vaults screen is about. */
private sealed interface VaultAsk {
    data object New : VaultAsk
    data class Rename(val id: String, val name: String) : VaultAsk
    data class Delete(val id: String, val name: String) : VaultAsk
}

/**
 * The PC's Vaults window: every vault of the account with where it lives,
 * and making, renaming, deleting (or leaving) and putting back vaults.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultsScreen(state: AppState) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    var ask by remember { mutableStateOf<VaultAsk?>(null) }
    Page("Vaults", { state.screen = Screen.Vault }) {
        Body {
            Muted("Each vault has its own key. Entries are added to the vault picked above the list.")
            Gap()
            open.vaults().forEach { v ->
                val (status, warning) = open.vaultStatus(v.id)
                val owner = open.account.vaultRole(v.id) == AccountStore.ROLE_OWNER
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tile(v.name, 42.dp)
                        Spacer(Modifier.width(Sp.m))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = Type.bodyBold, color = Look.c.text, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(count(open, v.id), style = Type.caption, color = Look.c.muted)
                        }
                    }
                    Gap(Sp.xs)
                    Text(status, style = Type.small, color = if (warning) Look.c.warn else Look.c.muted)
                    FlowRow(Modifier.fillMaxWidth()) {
                        LinkButton("Open", { state.vaultId = v.id; state.screen = Screen.Vault })
                        LinkButton("Activity", { state.screen = Screen.Activity(v.id) })
                        if (open.whyNotShare(v.id).isEmpty()) LinkButton(if (owner) "Share" else "Members",
                            { state.screen = Screen.Members(v.id) })
                        if (!v.personal && owner && !warning) LinkButton("Rename", { ask = VaultAsk.Rename(v.id, v.name) })
                        if (warning) LinkButton("Put back", {
                            state.change("Saving…") { o ->
                                o.putVaultBack(v.id)
                                state.say(if (o.account.server != null) "“${v.name}” goes on the server again at " +
                                    "the next Sync" else "“${v.name}” is this phone's again")
                            }
                        })
                        if (!v.personal) LinkButton(if (leaves(open, v.id)) "Leave" else "Delete",
                            { ask = VaultAsk.Delete(v.id, v.name) }, muted = true)
                    }
                }
                Gap(Sp.s)
            }
            Gap()
            MainButton("New vault", { ask = VaultAsk.New }, Modifier.fillMaxWidth(), enabled = state.busy == null)
        }
    }
    when (val a = ask) {
        null -> {}
        VaultAsk.New -> NameDialog("New vault", "A name for it: Home, Work, a project.", "", "Make vault",
            { ask = null }) { name ->
            ask = null
            state.change("Saving…") { o ->
                val id = o.createVault(name)
                state.vaultId = id
            }
        }
        is VaultAsk.Rename -> NameDialog("Rename “${a.name}”", if (open.account.server != null) "The new name " +
            "reaches the server and the other members at the next Sync." else "", a.name, "Rename",
            { ask = null }) { name ->
            ask = null
            state.change("Saving…") { it.renameVault(a.id, name) }
        }
        is VaultAsk.Delete -> DeleteVaultDialog(state, open, a.id, a.name) { ask = null }
    }
}

private fun count(open: OpenAccount, vaultId: String): String {
    val live = Entries.accountsOf(open.data[vaultId] ?: return "").count { !Py.truthy(it["archived"]) }
    return if (live == 1) "1 entry" else "$live entries"
}

/** Someone else's vault on the server: "deleting" it is leaving it (the PC's _delete_vault). */
private fun leaves(open: OpenAccount, vaultId: String): Boolean {
    val record = open.account.vaultRecord(vaultId)
    return open.account.server != null && record["on_server"].toString() == "true" &&
        record["gone_from_server"].toString() != "true" && open.account.vaultRole(vaultId) != AccountStore.ROLE_OWNER
}

@Composable
private fun NameDialog(title: String, detail: String, initial: String, action: String, dismiss: () -> Unit,
                       apply: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text(title, style = Type.heading) },
        text = { Column {
            if (detail.isNotEmpty()) Muted(detail)
            Field("Name", name, { name = it })
        } },
        confirmButton = { MainButton(action, { apply(name) }, enabled = name.isNotBlank() && name.trim() != initial) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}

/** As on the PC: what goes, where, for whom; the master password confirms it. */
@Composable
private fun DeleteVaultDialog(state: AppState, open: OpenAccount, vaultId: String, name: String, dismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val record = open.account.vaultRecord(vaultId)
    val onServer = open.account.server != null && record["on_server"].toString() == "true" &&
        record["gone_from_server"].toString() != "true"
    val leaving = leaves(open, vaultId)
    val stored = Entries.accountsOf(open.data[vaultId] ?: Entries.emptyVault()).size
    val where = if (onServer) "on this phone and on the server, so it disappears from your other devices too" else "on this phone"
    val text = if (leaving) "You stop being a member of this vault, and its copy on this phone is removed. The vault " +
        "stays for the others; an owner can add you again."
    else "This deletes the vault and everything in it (${if (stored == 1) "1 entry" else "$stored entries"}, archive " +
        "included) $where" + (if (onServer && open.account.isShared(vaultId)) ", for every member" else "") +
        ". It cannot be undone here."
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text(if (leaving) "Leave “$name”?" else "Delete “$name”?", style = Type.heading) },
        text = { Column {
            Text(text, style = Type.body, color = Look.c.muted)
            Gap(Sp.s)
            Field("Master password", password, { password = it }, secret = true)
        } },
        confirmButton = { DangerButton(if (leaving) "Leave vault" else "Delete vault", {
            val typed = password
            dismiss()
            state.change(if (leaving) "Leaving…" else "Deleting…") { o ->
                if (!o.account.checkPassword(typed)) return@change state.say("That is not the master password. Nothing was deleted.")
                o.deleteVault(vaultId)
                if (state.vaultId == vaultId) state.vaultId = o.account.personalId
                state.say(if (leaving) "You left “$name”" else "Vault “$name” deleted")
            }
        }) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}
