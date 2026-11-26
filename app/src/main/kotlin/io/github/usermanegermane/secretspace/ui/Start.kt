package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Screen

/** An account that already exists on your server comes onto this phone. */
@Composable
fun SignInScreen(state: AppState) {
    var url by remember { mutableStateOf("https://") }
    var certificate by remember { mutableStateOf<Uri?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var kit by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        state.away = false
        if (it != null) certificate = it
    }
    val accounts = state.store.listAccounts()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("SecretSpace", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("Sign in to an account on your SecretSpace server. You need the Secret Key from its " +
            "Emergency Kit once; after that the master password opens it on this phone.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri,
            supporting = "As on the PC, e.g. https://10.10.10.3:59872")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.away = true; pick.launch(arrayOf("*/*")) }) {
                Text(if (certificate == null) "Server's certificate…" else "Certificate chosen ✓")
            }
            Spacer(Modifier.padding(4.dp))
            Text("The server.crt file the PC uses (a self-signed server needs it).",
                style = MaterialTheme.typography.bodySmall)
        }
        Field("Username", username, { username = it })
        Field("Master password", password, { password = it }, secret = true)
        Field("Secret Key (Emergency Kit)", kit, { kit = it.uppercase() },
            supporting = "Like A4-ABCDEF-…; spaces and dashes do not matter")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { state.signIn(url, certificate, username, password, kit) {} },
            enabled = state.busy == null && url.length > 8 && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        if (accounts.isNotEmpty()) {
            TextButton(onClick = { state.screen = Screen.Unlock(accounts.first()) }) {
                Text("Back to the accounts on this phone")
            }
        }
    }
}

@Composable
fun UnlockScreen(state: AppState, username: String) {
    var password by remember { mutableStateOf("") }
    var needsKit by remember { mutableStateOf(false) }
    var kit by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(username) }
    val accounts = state.store.listAccounts()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("SecretSpace", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        if (accounts.size > 1) {
            Row { accounts.forEach { name ->
                TextButton(onClick = { chosen = name }) { Text(if (name == chosen) "• $name" else name) }
            } }
        } else {
            Text(chosen, style = MaterialTheme.typography.titleMedium)
        }
        Field("Master password", password, { password = it }, secret = true)
        if (needsKit) Field("Secret Key (Emergency Kit)", kit, { kit = it.uppercase() },
            supporting = "This phone does not hold the Secret Key of this account any more.")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { state.unlock(chosen, password, kit.ifBlank { null }) { needsKit = true } },
            enabled = state.busy == null && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Unlock")
        }
        TextButton(onClick = { state.screen = Screen.SignIn }) { Text("Sign in to another account") }
    }
}
