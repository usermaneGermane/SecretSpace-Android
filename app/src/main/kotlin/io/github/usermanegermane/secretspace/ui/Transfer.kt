package io.github.usermanegermane.secretspace.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.usermanegermane.secretspace.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import secretspace.core.Importers
import secretspace.core.OpenAccount

private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/**
 * Import into a vault (the PC's Import → CSV or JSON): pick the file, see
 * what is new, what else comes with it and what cannot be stored, then add.
 */
@Composable
fun ImportFlow(state: AppState, vaultId: String, vaultName: String, done: () -> Unit) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf<Pair<String, OpenAccount.ImportPlan>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        state.away = false
        if (uri == null) return@rememberLauncherForActivityResult done()
        state.run("Reading the file…") {
            val open = state.open ?: return@run done()
            try {
                val (name, parsed) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "import.csv"
                    val bytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "That file cannot be read." }
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (out.size() <= MAX_IMPORT_BYTES) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    require(bytes.size <= MAX_IMPORT_BYTES) { "That file is too large for an export of passwords." }
                    name to Importers.parse(name, bytes.toString(Charsets.UTF_8))
                }
                val p = open.planImport(vaultId, parsed)
                when {
                    parsed.rows.none { it.site.isNotEmpty() || it.username.isNotEmpty() || it.password.isNotEmpty() } -> {
                        state.say("No accounts found in that file"); done()
                    }
                    p.rows.isEmpty() -> { state.say("Those entries are already in “$vaultName”"); done() }
                    else -> plan = name to p
                }
            } catch (e: Exception) {
                done()
                throw IllegalStateException("Could not read the file: ${e.message}")
            }
        }
    }
    LaunchedEffect(Unit) {
        state.away = true
        pick.launch(arrayOf("text/csv", "text/comma-separated-values", "application/json", "text/plain", "*/*"))
    }
    val (name, p) = plan ?: return
    val message = buildString {
        append("Found ${p.rows.size} new account(s) in “$name”.")
        if (p.duplicates > 0) append("\n${p.duplicates} duplicate(s) will be skipped.")
        if (p.carried.isNotEmpty()) append("\n\nAlso importing: " + p.carried.joinToString(", ") + ".")
        if (p.dropped.isNotEmpty()) append("\n\nThis file also contains " + p.dropped.joinToString(", ") + ", which " +
            "SecretSpace cannot store. Keep the original file until you have checked you still have them.")
        append("\n\nAdd them to “$vaultName”?")
    }
    ConfirmDialog("Import accounts", message, "Import", danger = false, { plan = null; done() }) {
        plan = null
        done()
        state.change("Importing…") { o ->
            val added = o.importRows(vaultId, p.rows)
            state.say("Imported $added account(s)")
        }
    }
}

/**
 * Export a vault in plain text (the PC's Export unencrypted): warned first;
 * CSV leaves the archive out (for another password manager), JSON keeps it.
 */
@Composable
fun ExportFlow(state: AppState, vaultId: String, vaultName: String, done: () -> Unit) {
    val context = LocalContext.current
    val open = state.open ?: return
    val base = "${open.account.username}-passwords"
    fun write(uri: Uri?, json: Boolean) {
        state.away = false
        if (uri == null) return done()
        done()
        state.run("Exporting…") {
            val data = open.data[vaultId] ?: return@run
            val (text, count) = if (json) Importers.exportJson(data) else Importers.exportCsv(data)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt").use { out ->
                    requireNotNull(out) { "That file cannot be written." }
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            }
            state.say("Exported $count account(s) in plain text. Delete the file once you are done with it.")
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { write(it, false) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it, true) }
    AlertDialog(
        onDismissRequest = done, containerColor = Look.c.card, shape = Radius.dialog,
        title = { Text("Export “$vaultName” unencrypted", style = Type.heading) },
        text = { Column {
            Text("This writes every password of “$vaultName” to a file in plain text, with no protection at all. " +
                "Anyone who opens that file — or recovers it from the phone later — can read all of them.\n\nOnly do " +
                "this to move into another password manager, and delete the file as soon as you are done.",
                style = Type.body, color = Look.c.muted)
            Gap(Sp.s)
            Muted("CSV: for another password manager or a browser; archived entries are left out. JSON: everything, " +
                "the archive included.", style = Type.caption)
        } },
        confirmButton = { DangerButton("Export CSV", { state.away = true; csv.launch("$base.csv") }) },
        dismissButton = { Column {
            QuietButton("Export JSON", { state.away = true; json.launch("$base.json") }, danger = true)
            Gap(Sp.xs)
            QuietButton("Cancel", done)
        } },
    )
}
