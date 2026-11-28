package io.github.usermanegermane.secretspace

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * This phone's own settings (the PC's appconfig): light or dark, the list's
 * order, when the app locks in the background, and the update check. Not
 * secrets, and not part of any account.
 */
object Prefs {
    val APPEARANCES = listOf("system" to "As the phone is set", "light" to "Light", "dark" to "Dark")
    /** The PC's SORT_LABELS. */
    val SORT_ORDERS = listOf("modified_desc" to "Recently modified", "modified_asc" to "Oldest modified",
        "name_asc" to "Name A → Z", "name_desc" to "Name Z → A")
    /** Minutes in the background before the app locks (0: as soon as it leaves the screen). */
    val LOCK_CHOICES = listOf(0, 1, 5, 10, 30, 60)
    const val DEFAULT_LOCK_MINUTES = 1

    var appearance by mutableStateOf("system")
        private set
    var sortOrder by mutableStateOf("modified_desc")
        private set
    var lockMinutes by mutableIntStateOf(DEFAULT_LOCK_MINUTES)
        private set
    var checkUpdates by mutableStateOf(false)
        private set

    private fun store(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = store(context)
        appearance = p.getString("appearance", null)?.takeIf { v -> APPEARANCES.any { it.first == v } } ?: "system"
        sortOrder = p.getString("sort_order", null)?.takeIf { v -> SORT_ORDERS.any { it.first == v } } ?: "modified_desc"
        lockMinutes = p.getInt("lock_minutes", DEFAULT_LOCK_MINUTES).takeIf { it in LOCK_CHOICES } ?: DEFAULT_LOCK_MINUTES
        checkUpdates = p.getBoolean("check_updates", false)
    }

    fun setAppearance(context: Context, value: String) {
        appearance = value
        store(context).edit().putString("appearance", value).apply()
    }

    fun setSortOrder(context: Context, value: String) {
        sortOrder = value
        store(context).edit().putString("sort_order", value).apply()
    }

    fun setLockMinutes(context: Context, value: Int) {
        lockMinutes = value
        store(context).edit().putInt("lock_minutes", value).apply()
    }

    fun setCheckUpdates(context: Context, on: Boolean) {
        checkUpdates = on
        store(context).edit().putBoolean("check_updates", on).apply()
    }

    /** Dark or not, for the chosen appearance and what the phone is set to. */
    fun isDark(system: Boolean) = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> system
    }

    fun lockLabel(minutes: Int) = when (minutes) {
        0 -> "As soon as I leave the app"
        1 -> "After 1 minute away"
        else -> "After $minutes minutes away"
    }
}
