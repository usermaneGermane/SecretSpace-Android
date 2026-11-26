package io.github.usermanegermane.secretspace

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copying a password: marked sensitive (Android then hides it from the
 * clipboard preview and keyboard suggestions) and cleared again after
 * CLEAR_MS, as on the PC.
 */
object Clipboard {
    const val CLEAR_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                           else "android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager.setPrimaryClip(clip)
        pending?.let { handler.removeCallbacks(it) }
        if (sensitive) {
            val clear = Runnable { clearIf(context, text) }
            pending = clear
            handler.postDelayed(clear, CLEAR_MS)
        }
    }

    /** Clear the clipboard if it still holds what this app put there. */
    private fun clearIf(context: Context, text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val now = try { manager.primaryClip?.getItemAt(0)?.text?.toString() } catch (e: Exception) { null }
        if (now == null || now == text) clear(manager)
    }

    fun clearNow(context: Context) {
        pending?.let { handler.removeCallbacks(it); it.run() }
        pending = null
    }

    private fun clear(manager: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
        else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
