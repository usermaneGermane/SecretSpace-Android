package io.github.usermanegermane.secretspace.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.usermanegermane.secretspace.AppState
import io.github.usermanegermane.secretspace.Biometric
import io.github.usermanegermane.secretspace.R
import io.github.usermanegermane.secretspace.SecretSpaceApp
import io.github.usermanegermane.secretspace.ui.Field
import io.github.usermanegermane.secretspace.ui.SecretSpaceTheme
import secretspace.core.Matching
import secretspace.core.Obj
import secretspace.core.Py

/**
 * What "Fill from SecretSpace" opens: unlock if locked, then the entries
 * of this site or app first, a search for the rest; the one picked goes
 * back to Android, which fills it into the other app's form.
 */
class FillActivity : FragmentActivity() {
    companion object {
        const val EXTRA_USERNAME = "username_id"
        const val EXTRA_PASSWORD = "password_id"
        const val EXTRA_DOMAIN = "web_domain"
        const val EXTRA_PACKAGE = "package"
    }

    private val state: AppState get() = (application as SecretSpaceApp).state

    @Suppress("DEPRECATION")
    private fun id(name: String): AutofillId? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(name, AutofillId::class.java) else intent.getParcelableExtra(name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val userId = id(EXTRA_USERNAME)
        val passId = id(EXTRA_PASSWORD)
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        val app = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        setResult(Activity.RESULT_CANCELED)
        setContent {
            SecretSpaceTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        Text("Fill from SecretSpace", style = MaterialTheme.typography.titleLarge)
                        Text(domain ?: app, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        if (state.open == null) Unlock() else Pick(domain, app) { entry -> fill(entry, userId, passId) }
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        state.cameBack()          // locked if it was left for too long, as when the app comes back
    }

    override fun onStop() {
        super.onStop()
        state.wentAway()
    }

    private fun fill(entry: Obj, userId: AutofillId?, passId: AutofillId?) {
        val line = RemoteViews(packageName, R.layout.autofill_line).apply {
            setTextViewText(R.id.autofill_text, Py.text(entry, "site"))
        }
        val dataset = Dataset.Builder(line).apply {
            userId?.let { setValue(it, AutofillValue.forText(Py.text(entry, "username"))) }
            passId?.let { setValue(it, AutofillValue.forText(Py.text(entry, "password"))) }
        }.build()
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    @Composable
    private fun Unlock() {
        val accounts = state.store.listAccounts()
        if (accounts.isEmpty()) {
            Text("There is no account on this phone yet. Open SecretSpace and sign in first.")
            return
        }
        val name = accounts.first()
        var password by remember { mutableStateOf("") }
        val fingerprint = Biometric.canOpen(this, name)
        fun withFingerprint() = Biometric.unlock(this, name, { pw -> state.unlockWithFingerprint(name, pw) },
            { why -> if (why != null) state.say(why) })
        LaunchedEffect(name) { if (fingerprint) withFingerprint() }
        Text(name, style = MaterialTheme.typography.titleMedium)
        Field("Master password", password, { password = it }, secret = true)
        Button(onClick = { state.unlock(name, password, null) { state.say("Open SecretSpace once to enter the Secret Key.") } },
            enabled = state.busy == null && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        if (fingerprint) OutlinedButton(onClick = { withFingerprint() }, enabled = state.busy == null,
            modifier = Modifier.fillMaxWidth()) { Text("Unlock with fingerprint") }
        state.busy?.let { Text(it) }
    }

    @Composable
    private fun Pick(domain: String?, app: String, pick: (Obj) -> Unit) {
        val open = state.open ?: return
        @Suppress("UNUSED_VARIABLE") val redraw = state.version
        var query by remember { mutableStateOf("") }
        val all = open.vaults().flatMap { v -> open.entries(v.id).map { v.name to it } }
        val suggested = all.filter { Matching.matches(it.second, domain, app) }
        val shown = if (query.isBlank()) suggested else all.filter { (_, e) ->
            listOf("site", "username", "url").any { Py.text(e, it).contains(query.trim(), ignoreCase = true) }
        }
        Field("Search all entries", query, { query = it })
        if (shown.isEmpty()) Text(if (query.isBlank()) "Nothing saved for this ${if (domain != null) "site" else "app"} " +
            "yet: search for the entry." else "No entry matches.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(shown) { (vault, e) ->
                ListItem(headlineContent = { Text(Py.text(e, "site")) },
                    supportingContent = { Text(Py.text(e, "username") + (if (all.map { it.first }.distinct().size > 1) " · $vault" else "")) },
                    modifier = Modifier.clickable { pick(e) })
                HorizontalDivider()
            }
        }
        TextButton(onClick = { finish() }) { Text("Cancel") }
    }
}
