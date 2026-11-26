package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.Py

@Composable
fun VaultScreen(state: AppState) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val vaults = open.vaults()
    val current = vaults.firstOrNull { it.id == state.vaultId } ?: vaults.first()
    var query by remember { mutableStateOf("") }
    var pickVault by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Bar(open.account.username, back = null) {
                if (open.account.server != null) IconButton(onClick = { state.sync() }, enabled = state.busy == null) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sync")
                }
                IconButton(onClick = { state.lock() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Archive of “${current.name}”") }, onClick = {
                        menu = false; state.screen = Screen.Archive(current.id)
                    })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; state.screen = Screen.Settings })
                }
            }
            Box {
                TextButton(onClick = { pickVault = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Vault: ${current.name}" + roleText(current.role, current.shared) + "  ▾")
                }
                DropdownMenu(expanded = pickVault, onDismissRequest = { pickVault = false }) {
                    vaults.forEach { v ->
                        DropdownMenuItem(text = { Text(v.name + roleText(v.role, v.shared)) }, onClick = {
                            pickVault = false; state.vaultId = v.id
                        })
                    }
                }
            }
            if (!current.canWrite) {
                Text(if (current.newerAppText.isNotEmpty()) "Read only: update to ${current.newerAppText} to change it."
                     else "Shared with you for reading only.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (current.goneFromServer) {
                Text("Deleted on the server by another device; kept here and not synced.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            Field("Search", query, { query = it }, Modifier.padding(horizontal = 12.dp))
            val entries = open.entries(current.id, query)
            if (entries.isEmpty()) {
                Text(if (query.isBlank()) "No entries yet." else "Nothing matches “$query”.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it["id"] as String }) { e ->
                    ListItem(
                        headlineContent = { Text(Py.text(e, "site").ifEmpty { "(no name)" }) },
                        supportingContent = { Text(Py.text(e, "username")) },
                        trailingContent = { if (Py.text(e, "totp").isNotEmpty()) Text("2FA") },
                        modifier = Modifier.clickable { state.screen = Screen.Entry(current.id, e["id"] as String) },
                    )
                    HorizontalDivider()
                }
            }
        }
        if (current.canWrite) {
            FloatingActionButton(onClick = { state.screen = Screen.Edit(current.id, null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New entry")
            }
        }
    }
}

fun roleText(role: String, shared: Boolean): String = when {
    role == "reader" -> " (read only)"
    role == "editor" -> " (shared, editor)"
    shared -> " (shared)"
    else -> ""
}
