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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import io.github.usermanegermane.secretspace.ui.Avatar
import io.github.usermanegermane.secretspace.ui.Card
import io.github.usermanegermane.secretspace.ui.Gap
import io.github.usermanegermane.secretspace.ui.Look
import io.github.usermanegermane.secretspace.ui.MainButton
import io.github.usermanegermane.secretspace.ui.Muted
import io.github.usermanegermane.secretspace.ui.QuietButton
import io.github.usermanegermane.secretspace.ui.RowCard
import io.github.usermanegermane.secretspace.ui.Sp
import io.github.usermanegermane.secretspace.ui.Type

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
                Scaffold(containerColor = Look.c.bg) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(Sp.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(Sp.s))
                            Column {
                                Text("Fill from SecretSpace", style = Type.heading, color = Look.c.text)
                                Muted(domain ?: app, style = Type.caption)
                            }
                        }
                        Gap(Sp.l)
                        if (state.open == null) Unlock() else Pick(domain, app) { entry -> fill(entry, userId, passId) }
                        state.message?.let { Gap(Sp.s); Text(it, style = Type.small, color = Look.c.danger) }
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
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, 40.dp)
                Spacer(Modifier.width(Sp.m))
                Text(name, style = Type.leadBold, color = Look.c.text)
            }
            Gap(Sp.s)
            Field("Master password", password, { password = it }, secret = true)
            Gap(Sp.s)
            MainButton("Unlock $name", { state.unlock(name, password, null) { state.say("Open SecretSpace once to enter the Secret Key.") } },
                Modifier.fillMaxWidth(), enabled = state.busy == null && password.isNotEmpty())
            if (fingerprint) {
                Gap(Sp.s)
                QuietButton("Unlock with fingerprint", { withFingerprint() }, Modifier.fillMaxWidth(), enabled = state.busy == null)
            }
        }
        state.busy?.let { Gap(Sp.s); Muted(it) }
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
        Field("Search all entries", query, { query = it }, showLabel = false)
        if (shown.isEmpty()) Muted(if (query.isBlank()) "Nothing saved for this ${if (domain != null) "site" else "app"} " +
            "yet: search for the entry." else "No entry matches.", Modifier.padding(vertical = Sp.s), Type.body)
        val several = all.map { it.first }.distinct().size > 1
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            items(shown) { (vault, e) ->
                RowCard(Py.text(e, "site"), Py.text(e, "username") + (if (several) " · $vault" else ""), { pick(e) })
            }
        }
        Gap(Sp.s)
        QuietButton("Cancel", { finish() }, Modifier.fillMaxWidth())
    }
}
