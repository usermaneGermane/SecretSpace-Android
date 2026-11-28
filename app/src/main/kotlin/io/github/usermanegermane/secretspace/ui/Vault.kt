package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.Obj
import secretspace.core.Py
import secretspace.core.Times

@Composable
fun VaultScreen(state: AppState) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val vaults = open.vaults()
    val current = vaults.firstOrNull { it.id == state.vaultId } ?: vaults.first()
    var query by remember { mutableStateOf("") }
    var pickVault by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val entries = open.entries(current.id, query)
    Column(Modifier.fillMaxSize().background(Look.c.bg)) {
        // The account, and what is done to all of it: as the PC's account card at the foot of its side bar.
        Row(Modifier.fillMaxWidth().padding(start = Sp.l, end = Sp.xs, top = Sp.s, bottom = Sp.s),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(open.account.username, 40.dp)
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(open.account.username, style = Type.bodyBold, color = Look.c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(if (open.account.server != null) "Synced with your server" else "On this phone only",
                    style = Type.caption, color = Look.c.muted, maxLines = 1)
            }
            if (open.account.server != null) IconButton(onClick = { state.sync() }, enabled = state.busy == null) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync", tint = Look.c.text)
            }
            IconButton(onClick = { state.lock() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock", tint = Look.c.text) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Look.c.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Look.c.card) {
                    DropdownMenuItem(text = { Text("Archive of “${current.name}”", style = Type.body) }, onClick = {
                        menu = false; state.screen = Screen.Archive(current.id)
                    })
                    DropdownMenuItem(text = { Text("Activity in “${current.name}”", style = Type.body) }, onClick = {
                        menu = false; state.screen = Screen.Activity(current.id)
                    })
                    DropdownMenuItem(text = { Text("Vaults", style = Type.body) }, onClick = {
                        menu = false; state.screen = Screen.Vaults
                    })
                    DropdownMenuItem(text = { Text("Settings", style = Type.body) }, onClick = {
                        menu = false; state.screen = Screen.Settings
                    })
                }
            }
        }
        // The vault: as a row of the PC's vault list, with a menu of the others.
        Box(Modifier.padding(horizontal = Sp.l)) {
            Row(Modifier.fillMaxWidth().clip(Radius.row).background(Look.c.card)
                .clickable(enabled = vaults.size > 1) { pickVault = true }.padding(Sp.m),
                verticalAlignment = Alignment.CenterVertically) {
                Tile(current.name, 32.dp)
                Spacer(Modifier.width(Sp.m))
                Text("Vault", style = Type.smallBold, color = Look.c.muted)
                Spacer(Modifier.width(Sp.s))
                Text(current.name + roleText(current.role, current.shared), style = Type.bodyBold, color = Look.c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (vaults.size > 1) Text("▾", style = Type.bodyBold, color = Look.c.muted)
            }
            DropdownMenu(expanded = pickVault, onDismissRequest = { pickVault = false }, containerColor = Look.c.card) {
                vaults.forEach { v ->
                    DropdownMenuItem(leadingIcon = { Tile(v.name, 28.dp) },
                        text = { Text(v.name + roleText(v.role, v.shared), style = Type.body) },
                        onClick = { pickVault = false; state.vaultId = v.id })
                }
            }
        }
        if (!current.canWrite) Muted(if (current.newerAppText.isNotEmpty()) "Read only: update to " +
            "${current.newerAppText} to change it." else "Shared with you for reading only.",
            Modifier.padding(horizontal = Sp.l, vertical = Sp.xs))
        if (current.goneFromServer) Muted("Deleted on the server by another device; kept here and not synced.",
            Modifier.padding(horizontal = Sp.l, vertical = Sp.xs), Type.small)
        // Search, and the one green button: add an entry (the PC's "+" beside its search).
        Row(Modifier.fillMaxWidth().padding(horizontal = Sp.l, vertical = Sp.s), verticalAlignment = Alignment.CenterVertically) {
            Field("Search", query, { query = it }, Modifier.weight(1f), showLabel = false)
            if (current.canWrite) {
                Spacer(Modifier.width(Sp.s))
                Box(Modifier.size(56.dp).clip(Radius.inner).background(Look.c.green)
                    .clickable { state.screen = Screen.Edit(current.id, null) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "New entry", tint = Look.c.onAccent)
                }
            }
        }
        Text(when (entries.size) { 1 -> "1 entry"; else -> "${entries.size} entries" },
            style = Type.smallBold, color = Look.c.muted, modifier = Modifier.padding(horizontal = Sp.l + Sp.xs, vertical = Sp.xs))
        if (entries.isEmpty()) {
            Text(if (query.isBlank()) "No entries yet." else "Nothing matches “$query”.", style = Type.heading,
                color = Look.c.muted, modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Sp.l, end = Sp.l, bottom = Sp.xxl),
            verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(entries, key = { it["id"] as String }) { e ->
                RowCard(Py.text(e, "site"), rowLine(e), { state.screen = Screen.Entry(current.id, e["id"] as String) },
                    side = Times.short(Py.text(e, "updated_at")))
            }
        }
    }
}

/** A row's quiet line (gui.py): the username, then "2FA" and "note" when it has them. */
fun rowLine(e: Obj): String = (listOf(Py.text(e, "username")).filter { it.isNotEmpty() } +
    listOf("2FA" to "totp", "note" to "notes").filter { Py.text(e, it.second).isNotEmpty() }.map { it.first })
    .joinToString(" · ")

fun roleText(role: String, shared: Boolean): String = when {
    role == "reader" -> " (read only)"
    role == "editor" -> " (shared, editor)"
    shared -> " (shared)"
    else -> ""
}
