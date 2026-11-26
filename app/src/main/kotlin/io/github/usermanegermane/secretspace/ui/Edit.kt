package io.github.usermanegermane.secretspace.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.QrReader
import io.github.usermanegermane.secretspace.Screen
import secretspace.core.Passwords
import secretspace.core.Py
import secretspace.core.Totp
import java.util.concurrent.Executors

@Composable
fun EditScreen(state: AppState, vaultId: String, entryId: String?) {
    val open = state.open ?: return
    val entry = entryId?.let { open.find(vaultId, it) }
    var site by remember { mutableStateOf(Py.text(entry, "site")) }
    var username by remember { mutableStateOf(Py.text(entry, "username")) }
    var password by remember { mutableStateOf(Py.text(entry, "password")) }
    var url by remember { mutableStateOf(Py.text(entry, "url")) }
    var totp by remember { mutableStateOf(Py.text(entry, "totp")) }
    var notes by remember { mutableStateOf(Py.text(entry, "notes")) }
    var generating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val totpProblem = Totp.check(totp)
    val back = { state.screen = if (entryId == null) Screen.Vault else Screen.Entry(vaultId, entryId) }
    Page(if (entry == null) "New entry" else "Edit entry", back) {
        Body {
            Card {
                Field("Name", site, { site = it })
                Field("Username", username, { username = it })
                Field("Password", password, { password = it }, secret = true)
                val (label, fraction) = Passwords.strength(password)
                Meter(label, fraction)
                QuietButton("Generate a password…", { generating = true })
                Gap(Sp.s)
                Field("Web address", url, { url = it }, keyboard = KeyboardType.Uri)
            }
            Gap(Sp.m)
            Card {
                Field("2FA secret", totp, { totp = it }, error = totpProblem.isNotEmpty(), supporting = totpProblem.ifEmpty {
                    "The text under the site's QR code, or an otpauth:// link" })
                QuietButton("Scan the QR code", { scanning = true })
                Gap(Sp.s)
                Field("Notes", notes, { notes = it }, singleLine = false)
            }
            Gap(Sp.l)
            MainButton("Save", {
                val fields = mapOf("site" to site.trim(), "username" to username.trim(), "password" to password,
                    "url" to url.trim(), "totp" to totp.trim(), "notes" to notes)
                if (entryId == null) {
                    state.change { it.add(vaultId, fields.getValue("site"), fields.getValue("username"), password,
                        notes, fields.getValue("totp"), fields.getValue("url")) }
                    state.screen = Screen.Vault
                } else {
                    state.change { it.edit(vaultId, entryId, fields) }
                    state.screen = Screen.Entry(vaultId, entryId)
                }
            }, Modifier.fillMaxWidth(), enabled = site.isNotBlank() && totpProblem.isEmpty() && state.busy == null)
        }
    }
    if (generating) GeneratorDialog({ generating = false }) { password = it; generating = false }
    if (scanning) ScanDialog(state, { scanning = false }) { found ->
        scanning = false
        val problem = Totp.check(found)
        if (problem.isEmpty()) totp = found else state.say("That QR code is not a 2FA code: $problem")
    }
}

@Composable
private fun GeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var length by remember { mutableStateOf(20f) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var made by remember { mutableStateOf(Passwords.generate(20)) }
    fun again() { made = Passwords.generate(length.toInt(), upper, lower, digits, symbols) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Generate a password", style = Type.heading) },
        text = {
            Column {
                Text(made, style = Type.mono, color = Look.c.text, modifier = Modifier.fillMaxWidth()
                    .clip(Radius.inner).background(Look.c.input).padding(Sp.m))
                val (label, fraction) = Passwords.strength(made)
                Meter(label, fraction)
                Gap(Sp.s)
                Text("Length ${length.toInt()}", style = Type.smallBold, color = Look.c.muted)
                Slider(value = length, onValueChange = { length = it; again() }, valueRange = 8f..64f,
                    colors = SliderDefaults.colors(thumbColor = Look.c.green, activeTrackColor = Look.c.green,
                        inactiveTrackColor = Look.c.border))
                listOf(Triple("Capitals A-Z", upper) { v: Boolean -> upper = v },
                    Triple("Small letters a-z", lower) { v: Boolean -> lower = v },
                    Triple("Digits 0-9", digits) { v: Boolean -> digits = v },
                    Triple("Symbols !@#", symbols) { v: Boolean -> symbols = v })
                    .forEach { (name, on, set) -> CheckRow(on, name, { set(it); again() }) }
            }
        },
        confirmButton = { MainButton("Use it", { onUse(made) }) },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            QuietButton("Again", { again() }); QuietButton("Cancel", onDismiss) } },
    )
}

/** The camera, until it sees a QR code; ``onFound`` gets its text. Mobile only, as the owner asked. */
@Composable
private fun ScanDialog(state: AppState, onDismiss: () -> Unit, onFound: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state.away = false
        allowed = it
        if (!it) { state.say("The camera is needed to scan; you can type the secret instead."); onDismiss() }
    }
    DisposableEffect(Unit) {
        if (!allowed) { state.away = true; ask.launch(Manifest.permission.CAMERA) }
        onDispose { }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            if (allowed) CameraPreview(onFound) { problem -> state.say(problem); onDismiss() }
            MainButton("Cancel", onDismiss, Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

@Composable
private fun CameraPreview(onFound: (String) -> Unit, onFail: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val worker = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    val reader = remember { QrReader() }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        val view = PreviewView(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { image ->
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val text = reader.read(bytes, plane.rowStride, image.width, image.height)
                    if (text != null && !done) {
                        done = true
                        ContextCompat.getMainExecutor(ctx).execute {
                            provider.unbindAll()
                            onFound(text)
                        }
                    }
                } finally {
                    image.close()
                }
            }
            // the back camera, else the front one (a tablet may have only that)
            val camera = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
            if (camera == null) return@addListener onFail("This device has no camera to scan with; type the secret instead.")
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, camera, preview, analysis)
            } catch (e: Exception) {
                onFail("The camera could not be started (${e.message}); type the secret instead.")
            }
        }, ContextCompat.getMainExecutor(context))
        view
    })
}
