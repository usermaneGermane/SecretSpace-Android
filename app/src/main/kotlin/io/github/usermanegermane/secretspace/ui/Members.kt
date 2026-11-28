package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import secretspace.core.AccountStore
import secretspace.core.AccountSync

/** The PC's sharing_ui.ROLE_LABELS and ROLE_NOTES. */
private val ROLES = listOf(
    Triple(AccountStore.ROLE_EDITOR, "Editor", "change entries"),
    Triple(AccountStore.ROLE_READER, "Reader", "only read"),
    Triple(AccountStore.ROLE_OWNER, "Owner", "also manage the members"),
)

fun roleLabel(role: String) = ROLES.firstOrNull { it.first == role }?.second ?: role.ifEmpty { "Member" }
private fun a(word: String) = (if (word.take(1) in setOf("a", "e", "i", "o", "u")) "an " else "a ") + word

/** What a dialog on the Members screen is about. */
private sealed interface MemberAsk {
    data object Add : MemberAsk
    data class Fingerprint(val keys: AccountSync.ContactKeys, val role: String?) : MemberAsk   // role: sharing
    data class Remove(val username: String) : MemberAsk
    data object ChangeKey : MemberAsk
    data class Role(val username: String, val role: String) : MemberAsk
}

/**
 * The PC's Members window: who can open the vault and as what, your own
 * fingerprint to read out, and -- for owners -- adding a person after
 * comparing fingerprints, changing roles, removing someone (with a new key
 * in the same step) and changing the key.
 */
@Composable
fun MembersScreen(state: AppState, vaultId: String) {
    val open = state.open ?: return
    val context = LocalContext.current
    val name = open.account.vaultFile(vaultId).name
    val owner = open.account.vaultRole(vaultId) == AccountStore.ROLE_OWNER
    var members by remember(vaultId) { mutableStateOf<List<AccountSync.Member>?>(null) }
    var ask by remember { mutableStateOf<MemberAsk?>(null) }

    fun reload() = state.run("Asking the server…") {
        members = withContext(Dispatchers.IO) { open.members(vaultId) }
    }

    /** A step on the server, then the list again. */
    fun step(what: String, work: () -> Unit) = state.run(what) {
        members = withContext(Dispatchers.IO) { work(); open.members(vaultId) }
    }

    LaunchedEffect(vaultId) { reload() }
    Page("Members of “$name”", { state.screen = Screen.Vaults }) {
        Body {
            Muted(if (owner) "Everyone here can open this vault. Owners manage the members; editors change " +
                "entries; readers only read." else "You are ${a(roleLabel(open.account.vaultRole(vaultId)).lowercase())} " +
                "of this vault. Its owners manage the members.")
            Gap()
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Your fingerprint")
                        Text(open.account.fingerprint, style = Type.mono, color = Look.c.text)
                    }
                    QuietButton("Copy", { Clipboard.copy(context, "Fingerprint", open.account.fingerprint, false)
                        state.say("Fingerprint copied") })
                }
                Gap(Sp.xs)
                Muted("Read it to someone who is about to share a vault with you, so they know the key is yours.",
                    style = Type.caption)
            }
            Gap()
            val list = members
            if (list == null) Muted("Asking the server…")
            else {
                SectionLabel(if (list.size == 1) "1 member" else "${list.size} members")
                list.forEach { m ->
                    MemberCard(m, owner, check = {
                        state.run("Asking the server for ${m.username}'s keys…") {
                            val keys = withContext(Dispatchers.IO) { open.lookUp(m.username) }
                            ask = MemberAsk.Fingerprint(keys, null)
                        }
                    }, role = { ask = MemberAsk.Role(m.username, m.role) }, remove = { ask = MemberAsk.Remove(m.username) })
                    Gap(Sp.s)
                }
            }
            if (owner) {
                Gap()
                MainButton("Add a person", { ask = MemberAsk.Add }, Modifier.fillMaxWidth(), enabled = state.busy == null)
                Gap(Sp.s)
                QuietButton("Change key", { ask = MemberAsk.ChangeKey }, Modifier.fillMaxWidth(), enabled = state.busy == null)
                Gap(Sp.xs)
                Muted("Change key gives the vault a new key without removing anyone.", style = Type.caption)
            }
        }
    }

    /** The members who stay and whose keys were never compared here (the PC's ``unchecked``). */
    fun unchecked(leaving: String? = null): String {
        val names = members.orEmpty().filter { !it.me && !it.verified && it.username != leaving }.map { it.username }
        return if (names.isEmpty()) "" else "\n\nNot checked on this phone: ${names.joinToString(", ")}. The new key " +
            "goes to the keys the server gives for them. Compare fingerprints first (Check) if you can."
    }

    when (val q = ask) {
        null -> {}
        MemberAsk.Add -> AddPersonDialog(name, { ask = null }) { who, role ->
            ask = null
            state.run("Asking the server…") {
                val keys = withContext(Dispatchers.IO) { open.lookUp(who) }
                ask = MemberAsk.Fingerprint(keys, role)
            }
        }
        is MemberAsk.Fingerprint -> FingerprintDialog(q.keys, sharing = q.role != null, { ask = null }) {
            ask = null
            if (q.role != null) step("Sharing…") {
                open.share(vaultId, q.keys, q.role)
                state.say("“$name” is now shared with ${q.keys.username}")
            } else step("Saving…") { open.trust(q.keys) }
        }
        is MemberAsk.Role -> RoleDialog(q.username, q.role, { ask = null }) { role ->
            ask = null
            step("Changing what ${q.username} may do…") { open.setRole(vaultId, q.username, role) }
        }
        is MemberAsk.Remove -> ConfirmDialog("Remove ${q.username}", "Remove ${q.username} from “$name”?\n\nThe vault " +
            "gets a new key in the same step, so they can open nothing that is written from now on. What they have " +
            "already seen they may have kept: change those passwords if it matters.\n\nThe vault is synced first." +
            unchecked(q.username), "Remove", danger = true, { ask = null }) {
            ask = null
            step("Removing ${q.username} and changing the key…") { open.changeKey(vaultId, listOf(q.username)) }
        }
        MemberAsk.ChangeKey -> ConfirmDialog("Change the vault's key", "Give “$name” a new key?\n\nEvery member gets " +
            "it with their next sync. Worth doing after someone left the vault, or if a device that had it was lost. " +
            "The vault is synced first." + unchecked(), "Change key", danger = false, { ask = null }) {
            ask = null
            step("Changing the key…") { open.changeKey(vaultId) }
        }
    }
}

@Composable
private fun MemberCard(m: AccountSync.Member, owner: Boolean, check: () -> Unit, role: () -> Unit, remove: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.username, 40.dp)
            Spacer(Modifier.width(Sp.m))
            Column(Modifier.weight(1f)) {
                Text(m.username + if (m.me) "  (you)" else "", style = Type.bodyBold, color = Look.c.text)
                val detail = when {
                    m.me -> roleLabel(m.role)
                    m.verified -> roleLabel(m.role) + "  ·  fingerprint checked on this phone"
                    else -> roleLabel(m.role) + "  ·  fingerprint not checked on this phone"
                }
                Text(detail, style = Type.small, color = if (!m.me && !m.verified) Look.c.warn else Look.c.muted)
            }
        }
        if (!m.me && (!m.verified || owner)) Row(Modifier.fillMaxWidth()) {
            if (!m.verified) LinkButton("Check", check)
            if (owner) {
                LinkButton("Change role", role)
                LinkButton("Remove", remove, muted = true)
            }
        }
    }
}

@Composable
private fun AddPersonDialog(vaultName: String, dismiss: () -> Unit, go: (String, String) -> Unit) {
    var who by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(AccountStore.ROLE_EDITOR) }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Share “$vaultName”", style = Type.heading) },
        text = { Column {
            Muted("With someone who has an account on the same server. They get the vault with their next sync.")
            Field("Their username", who, { who = it }, placeholder = "e.g. user1")
            SectionLabel("They can")
            ROLES.forEach { (value, label, note) -> RadioRow(role == value, "$label — $note") { role = value } }
        } },
        confirmButton = { MainButton("Continue", { go(who.trim(), role) }, enabled = who.isNotBlank()) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}

/** The one check that keeps a dishonest server out: they read their fingerprint, and it must be this one. */
@Composable
private fun FingerprintDialog(keys: AccountSync.ContactKeys, sharing: Boolean, dismiss: () -> Unit, yes: () -> Unit) {
    val who = keys.username
    val changed = keys.status == "changed"
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Is this $who?", style = Type.heading) },
        text = { Column {
            Text("Ask $who to open Vaults → Members in their SecretSpace and read you the fingerprint shown at the " +
                "top — in person or on the phone, not by a message through this server.", style = Type.body,
                color = Look.c.muted)
            if (changed) {
                Gap(Sp.s)
                Text("Warning: the server now gives DIFFERENT keys for $who than this phone has seen before. That " +
                    "happens when an account was deleted and made again — or when someone is pretending to be $who. " +
                    "Go on only if $who confirms the fingerprint below.", style = Type.small, color = Look.c.danger)
            }
            Gap(Sp.m)
            SectionLabel("$who's fingerprint must be")
            Text(keys.fingerprint, style = Type.mono, color = Look.c.green)
            Gap(Sp.s)
            Muted(if (sharing) "If it is different, stop: the vault's key would go to someone else."
                else "If it is different, do not trust changes shown under this name.", style = Type.caption)
        } },
        confirmButton = { MainButton(if (sharing) "It matches — share" else "It matches", yes) },
        dismissButton = { QuietButton("It is different", dismiss) },
    )
}

@Composable
private fun RoleDialog(who: String, current: String, dismiss: () -> Unit, apply: (String) -> Unit) {
    var role by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("What $who may do", style = Type.heading) },
        text = { Column {
            ROLES.forEach { (value, label, note) -> RadioRow(role == value, "$label — $note") { role = value } }
        } },
        confirmButton = { MainButton("Save", { apply(role) }, enabled = role != current) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, action: String, danger: Boolean, dismiss: () -> Unit, yes: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text(title, style = Type.heading) },
        text = { Text(text, style = Type.body, color = Look.c.muted) },
        confirmButton = { if (danger) DangerButton(action, yes) else MainButton(action, yes) },
        dismissButton = { QuietButton("Cancel", dismiss) },
    )
}
