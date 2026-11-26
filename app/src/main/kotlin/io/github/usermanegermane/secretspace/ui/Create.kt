package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.NewAccount
import secretspace.core.Passwords

/**
 * A new account, in the PC's steps: the details (with the server it goes
 * on), the Emergency Kit, the 12 words, then three of them typed back.
 * Nothing is written until the last step has put it on the server.
 */
@Composable
fun CreateScreen(state: AppState) {
    var url by remember { mutableStateOf("https://") }
    var certificate by remember { mutableStateOf<Uri?>(null) }
    var code by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        state.away = false
        if (it != null) certificate = it
    }
    Column(Modifier.fillMaxSize()) {
        Bar("New account", back = { state.cancelCreate() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Step 1 of 4 · The account is made on this phone and put on your SecretSpace server, " +
                "where your PCs can sign in to it.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri,
                supporting = "As on the PC, e.g. https://10.10.10.3:59872")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { state.away = true; pick.launch(arrayOf("*/*")) }) {
                    Text(if (certificate == null) "Server's certificate…" else "Certificate chosen ✓")
                }
                Spacer(Modifier.width(8.dp))
                Text("The server.crt file the PC uses.", style = MaterialTheme.typography.bodySmall)
            }
            Field("Sign-up code", code, { code = it }, secret = true,
                supporting = "The server's token: on the server, sudo secretspace-setup show-token")
            Field("Username", username, { username = it })
            Field("Master password", password, { password = it }, secret = true)
            val (label, fraction) = Passwords.strength(password)
            if (password.isNotEmpty()) {
                LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            Field("Master password again", again, { again = it }, secret = true,
                supporting = if (again.isNotEmpty() && again != password) "The two passwords differ." else
                    "Nobody can reset it for you: keep it in your head, or written down somewhere safe.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { state.startCreate(url, certificate, code, username, password) },
                enabled = state.busy == null && url.length > 8 && username.isNotBlank() && password.isNotEmpty() &&
                    password == again,
                modifier = Modifier.fillMaxWidth()) { Text("Next") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun CreateKitScreen(state: AppState) {
    val creating = state.creating ?: return
    val made = creating.made
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        state.away = false
        if (it != null) state.saveKit(it)
    }
    Column(Modifier.fillMaxSize()) {
        Bar("Your Emergency Kit", back = { state.screen = Screen.Create })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Step 2 of 4 · With your master password, this Secret Key opens your account on another " +
                "device. SecretSpace keeps a copy on this phone only.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("Account", style = MaterialTheme.typography.labelMedium)
            Text(made.username, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Secret Key", style = MaterialTheme.typography.labelMedium)
            Text(made.secretKeyText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    state.away = true
                    save.launch("SecretSpace Emergency Kit - ${made.username}.txt")
                }) { Text("Save Emergency Kit…") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    Clipboard.copy(context, "Secret Key", made.secretKeyText, sensitive = true)
                    state.say("Secret Key copied")
                }) { Text("Copy") }
            }
            Spacer(Modifier.height(12.dp))
            Text("Print the kit, or keep the file somewhere offline — not next to your master password. " +
                "Without the Secret Key, a stolen copy of your account cannot be opened by guessing the password.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().toggleable(
                value = saved, role = Role.Checkbox, onValueChange = { saved = it; error = null })) {
                Checkbox(checked = saved, onCheckedChange = null)
                Text("I have saved my Emergency Kit")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (saved) state.screen = Screen.CreateWords else error = "Save or print the kit, then tick the box." },
                modifier = Modifier.fillMaxWidth()) { Text("Next") }
            TextButton(onClick = { state.cancelCreate() }) { Text("Cancel") }
        }
    }
}

@Composable
fun CreateWordsScreen(state: AppState) {
    val made = state.creating?.made ?: return
    Column(Modifier.fillMaxSize()) {
        Bar("Your recovery phrase", back = { state.screen = Screen.CreateKit })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Step 3 of 4 · These 12 words open your account if you forget your master password. " +
                "Write them on paper, in order.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                made.recoveryPhrase.split(" ").chunked(3).forEachIndexed { row, words ->
                    Row(Modifier.fillMaxWidth()) {
                        words.forEachIndexed { col, word ->
                            Row(Modifier.weight(1f)) {
                                Text("${row * 3 + col + 1}".padStart(2), style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(22.dp))
                                Text(word, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Anyone with these words can open everything in this account. Keep them like a passport: " +
                "not in a file or a photo, and not with the Emergency Kit. SecretSpace does not show them again.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { state.screen = Screen.CreateCheck }, modifier = Modifier.fillMaxWidth()) {
                Text("I wrote them down")
            }
            TextButton(onClick = { state.cancelCreate() }) { Text("Cancel") }
        }
    }
}

@Composable
fun CreateCheckScreen(state: AppState) {
    val creating = state.creating ?: return
    val typed = remember { mutableStateOf(NewAccount.CHECK_WORDS.associateWith { "" }) }
    var code by remember { mutableStateOf(creating.code) }
    var error by remember { mutableStateOf<String?>(null) }
    val words = NewAccount.CHECK_WORDS
    Column(Modifier.fillMaxSize()) {
        Bar("Check your phrase", back = { state.screen = Screen.CreateWords })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Step 4 of 4 · Type words ${words.dropLast(1).joinToString(", ")} and ${words.last()} " +
                "from your paper copy.", style = MaterialTheme.typography.bodyMedium)
            for (n in words) {
                Field("Word $n", typed.value.getValue(n), { v -> typed.value = typed.value + (n to v); error = null })
            }
            Field("Sign-up code", code, { code = it; creating.code = it }, secret = true,
                supporting = "The server's token, as in step 1")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val wrong = creating.made.phraseMismatches(typed.value)
                if (wrong.isNotEmpty()) {
                    error = "Word " + wrong.joinToString(", ") + (if (wrong.size == 1) " does" else " do") +
                        " not match. Look at your paper copy again."
                } else {
                    state.finishCreate(code)
                }
            }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Create account") }
            TextButton(onClick = { state.cancelCreate() }) { Text("Cancel") }
        }
    }
}
