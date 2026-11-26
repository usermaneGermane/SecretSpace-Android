package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import kotlinx.coroutines.delay
import secretspace.core.Changelog
import secretspace.core.Passwords
import secretspace.core.Py
import secretspace.core.Times
import secretspace.core.Totp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(state: AppState, vaultId: String, entryId: String) {
    val open = state.open ?: return
    @Suppress("UNUSED_VARIABLE") val redraw = state.version
    val entry = open.find(vaultId, entryId) ?: run { state.screen = Screen.Vault; return }
    val canWrite = open.account.canWrite(vaultId)
    val archived = Py.text(entry, "archived_at").isNotEmpty()
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    fun copy(label: String, text: String, sensitive: Boolean) {
        if (text.isEmpty()) return state.say("Nothing to copy")
        Clipboard.copy(context, label, text, sensitive)
        state.say(if (sensitive) "$label copied · clears in ${Clipboard.CLEAR_MS / 1000}s" else "$label copied")
    }
    val site = Py.text(entry, "site")
    val back = { state.screen = if (archived) Screen.Archive(vaultId) else Screen.Vault }
    Page("", back, actions = {
        if (canWrite && !archived) QuietButton("Edit", { state.screen = Screen.Edit(vaultId, entryId) })
    }) {
        Body {
            // The header band in the tile's colour (the PC's details card).
            Row(Modifier.fillMaxWidth().clip(Radius.panel).background(Look.c.band(site)).padding(Sp.xl),
                verticalAlignment = Alignment.CenterVertically) {
                Tile(site, 64.dp)
                Spacer(Modifier.width(Sp.l))
                Column(Modifier.weight(1f)) {
                    Text(site.ifEmpty { "(no name)" }, style = Type.title, color = Look.c.text, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    val by = Py.text(entry, "updated_by")
                    val stamp = Py.text(entry, "updated_at")
                    Text((if (archived) "Archived · " else "") + (if (stamp.isEmpty()) "Changed before dates were kept"
                        else "Last changed ${Times.long(stamp)}") + (if (by.isNotEmpty()) " by $by" else ""),
                        style = Type.caption, color = Look.c.muted)
                }
            }
            Gap(Sp.m)
            val username = Py.text(entry, "username")
            ValueCard("Username", username.ifEmpty { "—" }) {
                QuietButton("Copy", { copy("Username", username, false) }, enabled = username.isNotEmpty())
            }
            val password = Py.text(entry, "password")
            ValueCard("Password", if (shown) password else "•".repeat(minOf(password.length, 12)).ifEmpty { "—" },
                style = if (shown) Type.mono else Type.leadBold) {
                QuietButton(if (shown) "Hide" else "Show", { shown = !shown }, enabled = password.isNotEmpty())
                Spacer(Modifier.width(Sp.s))
                MainButton("Copy", { copy("Password", password, true) }, enabled = password.isNotEmpty())
            }
            val (strength, fraction) = Passwords.strength(password)
            Meter(strength, fraction, Modifier.padding(horizontal = Sp.xs))
            Py.text(entry, "url").takeIf { it.isNotEmpty() }?.let { url ->
                ValueCard("Web address", url) { QuietButton("Copy", { copy("Web address", url, false) }) }
            }
            Py.text(entry, "totp").takeIf { it.isNotEmpty() }?.let { TotpCard(it) { code -> copy("2FA code", code, true) } }
            Py.text(entry, "notes").takeIf { it.isNotEmpty() }?.let { notes ->
                Gap(Sp.m)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Notes", Modifier.weight(1f))
                    LinkButton("Copy", { copy("Notes", notes, true) }, muted = true)
                }
                Text(notes, style = Type.body, color = Look.c.text)
            }
            Gap(Sp.l)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Sp.s), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                QuietButton("Earlier versions", { state.screen = Screen.History(vaultId, entryId) })
                if (canWrite && !archived) {
                    Box {
                        QuietButton("Move…", { moving = true })
                        DropdownMenu(expanded = moving, onDismissRequest = { moving = false }, containerColor = Look.c.card) {
                            val targets = open.vaults().filter { it.id != vaultId && it.canWrite }
                            if (targets.isEmpty()) DropdownMenuItem(text = { Muted("No other vault to move it to") },
                                onClick = { moving = false })
                            targets.forEach { v ->
                                DropdownMenuItem(leadingIcon = { Tile(v.name, 28.dp) }, text = { Text(v.name, style = Type.body) },
                                    onClick = {
                                        moving = false
                                        state.change("Moving…") { it.move(vaultId, entryId, v.id) }
                                        state.screen = Screen.Vault
                                    })
                            }
                        }
                    }
                    QuietButton("Archive", { confirmArchive = true })
                }
            }
        }
    }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false }, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Archive “$site”?", style = Type.heading) },
        text = { Text("It leaves the list and can be restored from the Archive.", style = Type.body, color = Look.c.muted) },
        confirmButton = { MainButton("Archive", {
            confirmArchive = false
            state.change { it.archive(vaultId, entryId) }
            state.screen = Screen.Vault
        }) },
        dismissButton = { QuietButton("Cancel", { confirmArchive = false }) },
    )
}

/** One value of an entry on its own card: the caption, the value, its buttons on the right. */
@Composable
private fun ValueCard(label: String, value: String, style: TextStyle = Type.leadBold, buttons: @Composable RowScope.() -> Unit) {
    Gap(Sp.s)
    Row(Modifier.fillMaxWidth().clip(Radius.card).background(Look.c.card).padding(Sp.l),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.smallBold, color = Look.c.muted)
            Gap(Sp.xs)
            Text(value, style = style, color = Look.c.text)
        }
        Spacer(Modifier.width(Sp.s))
        buttons()
    }
}

/** The 2FA code of the moment, and how long it stays. */
@Composable
private fun TotpCard(stored: String, onCopy: (String) -> Unit) {
    val params = remember(stored) { runCatching { Totp.parse(stored) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stored) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    params.onFailure { ValueCard("2FA", it.message ?: "Cannot make codes from this secret.", Type.body) {} }
    params.onSuccess { p ->
        val code = Totp.code(p, now / 1000.0)
        ValueCard("2FA code · ${Totp.secondsLeft(p, now / 1000.0)}s left", Totp.spaced(code), Type.code) {
            QuietButton("Copy", { onCopy(code) })
        }
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
    Page("Earlier versions", { state.screen = Screen.Entry(vaultId, entryId) }, actions = {
        if (canWrite && revisions.isNotEmpty()) QuietButton("Clear history", {
            state.change { it.clearHistory(vaultId, entryId) }
        }, danger = true)
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Sp.l, end = Sp.l, bottom = Sp.xxl),
            verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            item {
                Muted(if (revisions.isEmpty()) "No earlier versions." else "Newest first · the last 10 are kept",
                    Modifier.padding(vertical = Sp.xs))
            }
            items(revisions, key = { it["rid"] as String }) { rev ->
                val rid = rev["rid"] as String
                Card {
                    Text("Until ${Times.long(Py.text(rev, "replaced_at"))}" +
                        (Py.text(rev, "by").takeIf { it.isNotEmpty() }?.let { " · changed by $it" } ?: ""),
                        style = Type.smallBold, color = Look.c.text)
                    val changed = Py.asList(rev["changed"])?.joinToString(", ") ?: ""
                    if (changed.isNotEmpty()) Muted("Then changed: $changed", style = Type.caption)
                    Gap(Sp.s)
                    Text(listOf(Py.text(rev, "site"), Py.text(rev, "username")).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = Type.body, color = Look.c.text)
                    Text(if (shownRid == rid) Py.text(rev, "password") else "••••••••", style = Type.mono, color = Look.c.text)
                    Gap(Sp.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        QuietButton(if (shownRid == rid) "Hide" else "Show", { shownRid = if (shownRid == rid) null else rid })
                        if (canWrite) MainButton("Put back", {
                            state.change { it.revert(vaultId, entryId, rid) }
                            state.screen = Screen.Entry(vaultId, entryId)
                        })
                    }
                }
            }
            if (log.isNotEmpty()) item { SectionLabel("Changes", Modifier.padding(top = Sp.l)) }
            items(log, key = { "log-" + it["id"] }) { line ->
                val signer = open.account.signingKeyFor(line["by"] as String)
                val check = Changelog.check(line, open.account.vaultFile(vaultId).hex, signer)
                Row {
                    Text(Times.long(Py.text(line, "at")), style = Type.caption, color = Look.c.muted,
                        modifier = Modifier.width(140.dp))
                    Text("${line["by"]} ${line["action"]}" + (if (check == Changelog.BAD) "  ⚠ signature does not match" else ""),
                        style = Type.caption, color = if (check == Changelog.BAD) Look.c.danger else Look.c.text)
                }
            }
        }
    }
}
