package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Clipboard
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.NewAccount
import secretspace.core.Passwords

/** "Step 2 of 4" above a step's heading and what it is for. */
@Composable
private fun StepHead(step: Int, heading: String, text: String) {
    Text("Step $step of 4", style = Type.smallBold, color = Look.c.green)
    Gap(Sp.xs)
    Text(heading, style = Type.title, color = Look.c.text)
    Gap(Sp.xs)
    Muted(text, style = Type.body)
    Gap(Sp.l)
}

/**
 * A new account, in the PC's steps: the details (on this phone only, or
 * with the server it goes on), the Emergency Kit, the 12 words, then three
 * of them typed back. Nothing is written until the last step: then on the
 * server first, or on this phone only.
 */
@Composable
fun CreateScreen(state: AppState) {
    var onServer by remember { mutableStateOf(false) }
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
    Page("Create an account", { state.cancelCreate() }) {
        Body {
            StepHead(1, "Where it is kept", "Choose where the account lives, then its name and master password.")
            ChoiceCard(!onServer, "On this phone only",
                "No server needed. The phone holds the only copy: if it is lost or reset, the passwords are " +
                    "gone. You can put the account on a server later (Settings → Sync server).") { onServer = false }
            ChoiceCard(onServer, "On my SecretSpace server",
                "Kept on the server too and synced, so your PCs can sign in to it.") { onServer = true }
            Gap(Sp.m)
            if (onServer) {
                Card {
                    SectionLabel("Your server")
                    Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri,
                        supporting = "As on the PC, e.g. https://10.10.10.3:59872")
                    CertificatePicker(certificate) { state.away = true; pick.launch(arrayOf("*/*")) }
                    Field("Sign-up code", code, { code = it }, secret = true,
                        supporting = "The server's token: on the server, sudo secretspace-setup show-token")
                }
                Gap(Sp.m)
            }
            Card {
                SectionLabel("Your account")
                Field("Username", username, { username = it })
                Field("Master password", password, { password = it }, secret = true)
                val (label, fraction) = Passwords.strength(password)
                if (password.isNotEmpty()) Meter(label, fraction)
                Field("Master password again", again, { again = it }, secret = true, error = again.isNotEmpty() && again != password,
                    supporting = if (again.isNotEmpty() && again != password) "The two passwords differ." else
                        "Nobody can reset it for you: keep it in your head, or written down somewhere safe.")
            }
            Gap(Sp.l)
            MainButton("Next", { state.startCreate(url.takeIf { onServer }, certificate, code, username, password) },
                Modifier.fillMaxWidth(),
                enabled = state.busy == null && (!onServer || url.length > 8) && username.isNotBlank() &&
                    password.isNotEmpty() && password == again)
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
    Page("Create an account", { state.screen = Screen.Create }) {
        Body {
            StepHead(2, "Your Emergency Kit", "With your master password, this Secret Key opens your account on " +
                "another device. SecretSpace keeps a copy on this phone only.")
            Card {
                SectionLabel("Account")
                Text(made.username, style = Type.leadBold, color = Look.c.text)
                Gap(Sp.m)
                SectionLabel("Secret Key")
                // Breaks only between its groups, never inside one.
                Text(breakAtDashes(made.secretKeyText), style = Type.code, color = Look.c.text)
                Gap(Sp.m)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    QuietButton("Save Emergency Kit…", {
                        state.away = true
                        save.launch("SecretSpace Emergency Kit - ${made.username}.txt")
                    })
                    QuietButton("Copy", {
                        Clipboard.copy(context, "Secret Key", made.secretKeyText, sensitive = true)
                        state.say("Secret Key copied")
                    })
                }
            }
            Gap(Sp.m)
            Muted("Print the kit, or keep the file somewhere offline — not next to your master password. Without the " +
                "Secret Key, a stolen copy of your account cannot be opened by guessing the password.")
            Gap(Sp.s)
            CheckRow(saved, "I have saved my Emergency Kit", { saved = it; error = null })
            error?.let { Text(it, style = Type.small, color = Look.c.danger) }
            Gap(Sp.m)
            MainButton("Next", { if (saved) state.screen = Screen.CreateWords else error = "Save or print the kit, then tick the box." },
                Modifier.fillMaxWidth())
            Gap(Sp.xs)
            LinkButton("Cancel", { state.cancelCreate() }, muted = true)
        }
    }
}

@Composable
fun CreateWordsScreen(state: AppState) {
    val made = state.creating?.made ?: return
    Page("Create an account", { state.screen = Screen.CreateKit }) {
        Body {
            StepHead(3, "Your recovery phrase", "These 12 words open your account if you forget your master " +
                "password. Write them on paper, in order.")
            // Two columns, 1-6 down the left and 7-12 down the right: no word is ever cut in two.
            val words = made.recoveryPhrase.split(" ")
            Row(Modifier.fillMaxWidth().clip(Radius.card).background(Look.c.card).padding(Sp.l)) {
                listOf(0, 6).forEach { start ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                        for (i in start until start + 6) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = Type.caption, color = Look.c.muted, textAlign = TextAlign.End,
                                    modifier = Modifier.width(22.dp))
                                Spacer(Modifier.width(Sp.s))
                                Text(words[i], style = Type.mono, color = Look.c.text, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            Gap(Sp.m)
            Muted("Anyone with these words can open everything in this account. Keep them like a passport: not in " +
                "a file or a photo, and not with the Emergency Kit. SecretSpace does not show them again.")
            Gap(Sp.l)
            MainButton("I wrote them down", { state.screen = Screen.CreateCheck }, Modifier.fillMaxWidth())
            Gap(Sp.xs)
            LinkButton("Cancel", { state.cancelCreate() }, muted = true)
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
    Page("Create an account", { state.screen = Screen.CreateWords }) {
        Body {
            StepHead(4, "Check your phrase", "Type words ${words.dropLast(1).joinToString(", ")} and ${words.last()} " +
                "from your paper copy.")
            Card {
                for (n in words) {
                    Field("Word $n", typed.value.getValue(n), { v -> typed.value = typed.value + (n to v); error = null })
                }
                if (creating.url != null) Field("Sign-up code", code, { code = it; creating.code = it }, secret = true,
                    supporting = "The server's token, as in step 1")
            }
            error?.let { Gap(Sp.s); Text(it, style = Type.small, color = Look.c.danger) }
            Gap(Sp.l)
            MainButton("Create account", {
                val wrong = creating.made.phraseMismatches(typed.value)
                if (wrong.isNotEmpty()) {
                    error = "Word " + wrong.joinToString(", ") + (if (wrong.size == 1) " does" else " do") +
                        " not match. Look at your paper copy again."
                } else {
                    state.finishCreate(code)
                }
            }, Modifier.fillMaxWidth(), enabled = state.busy == null)
            Gap(Sp.xs)
            LinkButton("Cancel", { state.cancelCreate() }, muted = true)
        }
    }
}
