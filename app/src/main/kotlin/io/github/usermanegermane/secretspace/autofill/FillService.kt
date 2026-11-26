package io.github.usermanegermane.secretspace.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.widget.RemoteViews
import io.github.usermanegermane.secretspace.R

/**
 * SecretSpace as the phone's autofill service. On a login form it offers
 * one line, "Fill from SecretSpace"; nothing of the vault is read here or
 * handed to the other app until the user taps it, unlocks (fingerprint or
 * master password) and picks an entry in [FillActivity].
 */
class FillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val fields = Fields.of(structure)
        if (!fields.isLogin || fields.packageName == packageName) return callback.onSuccess(null)

        val intent = Intent(this, FillActivity::class.java)
            .putExtra(FillActivity.EXTRA_USERNAME, fields.username)
            .putExtra(FillActivity.EXTRA_PASSWORD, fields.password)
            .putExtra(FillActivity.EXTRA_DOMAIN, fields.webDomain)
            .putExtra(FillActivity.EXTRA_PACKAGE, fields.packageName)
        // Mutable: Android adds the form's structure to it when the user taps the line.
        val sender = PendingIntent.getActivity(this, request.id, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
        val line = RemoteViews(packageName, R.layout.autofill_line).apply {
            setTextViewText(R.id.autofill_text, "Fill from SecretSpace")
        }
        val dataset = Dataset.Builder(line).apply {
            for (id in fields.ids) setValue(id, null)
            setAuthentication(sender)
        }.build()
        callback.onSuccess(FillResponse.Builder().addDataset(dataset).build())
    }

    /** New logins are not saved from other apps (yet): add them in SecretSpace. */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()
}
