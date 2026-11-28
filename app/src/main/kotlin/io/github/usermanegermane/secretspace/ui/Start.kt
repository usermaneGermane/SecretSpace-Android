package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Biometric
import io.github.usermanegermane.secretspace.R
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.AppVersion

/** The login card of the PC (gui.py's login screen): centred on the window, the logo, a title and a line. */
@Composable
private fun LoginCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Sp.l),
        verticalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxWidth().clip(Radius.dialog).background(Look.c.card).padding(Sp.xxl),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo), contentDescription = "SecretSpace", modifier = Modifier.size(48.dp))
            Gap(Sp.m)
            Text(title, style = Type.title, color = Look.c.text, textAlign = TextAlign.Center)
            Gap(Sp.xs)
            Text(subtitle, style = Type.small, color = Look.c.muted, textAlign = TextAlign.Center)
            Gap(Sp.xl)
            Column(Modifier.fillMaxWidth()) { content() }
            Gap(Sp.l)
            Text("v${AppVersion.VERSION}", style = Type.caption, color = Look.c.muted)
        }
    }
}

/** A phone with no account yet: make one, or bring one from your server. */
@Composable
fun WelcomeScreen(state: AppState) {
    LoginCard("Welcome to SecretSpace", "Your passwords, encrypted on this phone. Make an account, or sign in to " +
        "the one you use on your PC.") {
        MainButton("Create an account", { state.screen = Screen.Create }, Modifier.fillMaxWidth())
        Gap(Sp.s)
        QuietButton("Sign in from a server", { state.screen = Screen.SignIn }, Modifier.fillMaxWidth())
    }
}

@Composable
fun UnlockScreen(state: AppState, username: String) {
    var password by remember { mutableStateOf("") }
    var needsKit by remember { mutableStateOf(false) }
    var kit by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(username) }
    val accounts = state.store.listAccounts()
    val activity = LocalActivity.current as FragmentActivity
    val fingerprint = Biometric.canOpen(activity, chosen)
    fun withFingerprint() {
        state.away = true
        Biometric.unlock(activity, chosen, { pw -> state.away = false; state.unlockWithFingerprint(chosen, pw) },
            { why -> state.away = false; if (why != null) state.say(why) })
    }
    // Ask at once, as the PC does with Windows Hello; the password stays there to type instead.
    LaunchedEffect(chosen) { if (fingerprint && state.busy == null) withFingerprint() }
    LoginCard("Welcome back", if (accounts.size > 1) "Pick your account and enter its master password."
        else "Enter the master password of “$chosen”.") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Sp.m, Alignment.CenterHorizontally)) {
            accounts.forEach { name ->
                AccountChoice(name, name == chosen) { chosen = name; password = ""; needsKit = false }
            }
            AddAccount { state.screen = Screen.Welcome }
        }
        Gap(Sp.l)
        Field("Master password", password, { password = it }, secret = true)
        if (needsKit) Field("Secret Key (Emergency Kit)", kit, { kit = it.uppercase() }, mono = true,
            supporting = "This phone does not hold the Secret Key of this account any more.")
        Gap(Sp.m)
        MainButton("Unlock $chosen", { state.unlock(chosen, password, kit.ifBlank { null }) { needsKit = true } },
            Modifier.fillMaxWidth(), enabled = state.busy == null && password.isNotEmpty())
        if (fingerprint) {
            Gap(Sp.s)
            QuietButton("Unlock with fingerprint", { withFingerprint() }, Modifier.fillMaxWidth(), enabled = state.busy == null)
        }
        LinkButton("Forgot your master password?", { state.screen = Screen.Recover(chosen) }, muted = true)
        Gap(Sp.s)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LinkButton("Create an account", { state.screen = Screen.Create })
            LinkButton("Sign in from a server", { state.screen = Screen.SignIn })
        }
    }
}

/** An account on the login card: its initials and name, green-edged when it is the one chosen. */
@Composable
private fun AccountChoice(name: String, chosen: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(Radius.row).background(if (chosen) Look.c.selected else Color.Transparent)
        .border(1.5.dp, if (chosen) Look.c.green else Look.c.border, Radius.row)
        .clickable(onClick = onClick).padding(vertical = Sp.m, horizontal = Sp.s),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, 48.dp)
        Gap(Sp.s)
        Text(name, style = Type.smallBold, color = Look.c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddAccount(onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clip(Radius.row).border(1.5.dp, Look.c.border, Radius.row)
        .clickable(onClick = onClick).padding(vertical = Sp.m, horizontal = Sp.s),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Look.c.input), contentAlignment = Alignment.Center) {
            Text("+", style = Type.heading, color = Look.c.muted)
        }
        Gap(Sp.s)
        Text("Add account", style = Type.smallBold, color = Look.c.text, maxLines = 1)
    }
}

/** An account that already exists on your server comes onto this phone (the PC's "Sign in from a server"). */
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
    Page("Sign in from a server", { state.home() }) {
        Body {
            Muted("For an account you already use on another device. You need the server address, your username " +
                "and master password, and the Secret Key from your Emergency Kit once; after that the master " +
                "password alone opens it on this phone.")
            Gap(Sp.m)
            Card {
                Field("Server address", url, { url = it }, keyboard = KeyboardType.Uri,
                    supporting = "As on the PC, e.g. https://10.10.10.3:59872")
                CertificatePicker(certificate) { state.away = true; pick.launch(arrayOf("*/*")) }
                Field("Username", username, { username = it })
                Field("Master password", password, { password = it }, secret = true)
                Field("Secret Key (Emergency Kit)", kit, { kit = it.uppercase() }, mono = true,
                    supporting = "Like A4-ABCDEF-…; spaces and dashes do not matter")
            }
            Gap(Sp.l)
            MainButton("Sign in", { state.signIn(url, certificate, username, password, kit) {} }, Modifier.fillMaxWidth(),
                enabled = state.busy == null && url.length > 8 && username.isNotBlank() && password.isNotEmpty())
            LinkButton("Forgot your master password?", { state.screen = Screen.Recover("") }, muted = true)
        }
    }
}

/** The server's certificate file: a quiet button, and what it is for. */
@Composable
fun CertificatePicker(certificate: Uri?, onPick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Sp.s - 2.dp)) {
        Text("Certificate file", style = Type.smallBold, color = Look.c.muted, modifier = Modifier.padding(bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton(if (certificate == null) "Choose a file…" else "Chosen ✓", onPick)
            Spacer(Modifier.width(Sp.m))
            Muted("The server.crt file the PC uses.", Modifier.weight(1f), Type.caption)
        }
    }
}
