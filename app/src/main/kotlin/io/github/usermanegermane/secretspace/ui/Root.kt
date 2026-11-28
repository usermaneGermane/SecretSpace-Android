package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen

/** The screen the state names, the line saying what runs, messages, and a sync's question. */
@Composable
fun Root(state: AppState) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            state.message = null
        }
    }
    state.question?.let { ConflictDialog(state, it) }
    Scaffold(containerColor = Look.c.bg, snackbarHost = {
        SnackbarHost(snackbar) { data ->
            // A toast as on the PC: the card colour, the text colour, a quiet Dismiss.
            Snackbar(data, shape = Radius.inner, containerColor = Look.c.card, contentColor = Look.c.text,
                dismissActionContentColor = Look.c.muted, actionColor = Look.c.green)
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Look.c.bg)) {
            when (val screen = state.screen) {
                Screen.Welcome -> WelcomeScreen(state)
                Screen.SignIn -> SignInScreen(state)
                Screen.Create -> CreateScreen(state)
                Screen.CreateKit -> CreateKitScreen(state)
                Screen.CreateWords -> CreateWordsScreen(state)
                Screen.CreateCheck -> CreateCheckScreen(state)
                is Screen.Unlock -> UnlockScreen(state, screen.username)
                Screen.Vault -> VaultScreen(state)
                is Screen.Entry -> EntryScreen(state, screen.vaultId, screen.entryId)
                is Screen.Edit -> EditScreen(state, screen.vaultId, screen.entryId)
                is Screen.History -> HistoryScreen(state, screen.vaultId, screen.entryId)
                is Screen.Archive -> ArchiveScreen(state, screen.vaultId)
                Screen.Settings -> SettingsScreen(state)
                Screen.Vaults -> VaultsScreen(state)
                is Screen.Members -> MembersScreen(state, screen.vaultId)
                Screen.Connect -> ConnectScreen(state)
            }
            state.busy?.let {
                Column(Modifier.fillMaxWidth().background(Look.c.header)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Look.c.green, trackColor = Look.c.border)
                    Text(it, Modifier.padding(horizontal = Sp.l, vertical = Sp.s), style = Type.smallBold, color = Look.c.muted)
                }
            }
        }
    }
}
