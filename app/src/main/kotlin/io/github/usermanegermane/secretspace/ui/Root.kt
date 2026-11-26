package io.github.usermanegermane.secretspace.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen

// The PC app's colours (theme.py): its green accent on a neutral background.
private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF0D7644), onPrimary = Color.White,
    background = Color(0xFFEDF0F4), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE6EAEF),
)
private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF56E39F), onPrimary = Color(0xFF05230F),
    background = Color(0xFF15161A), surface = Color(0xFF1F2127), surfaceVariant = Color(0xFF1A1C21),
)

@Composable
fun SecretSpaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

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
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val screen = state.screen) {
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
            }
            state.busy?.let {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- shared pieces
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, back: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    if (back != null) BackHandler(onBack = back)
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (back != null) IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
    )
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
          secret: Boolean = false, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text,
          supporting: String? = null, enabled: Boolean = true) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        enabled = enabled, modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard,
            autoCorrectEnabled = !secret && keyboard == KeyboardType.Text),
        trailingIcon = if (secret) ({
            androidx.compose.material3.TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") }
        }) else null,
        supportingText = supporting?.let { { Text(it) } },
    )
}
