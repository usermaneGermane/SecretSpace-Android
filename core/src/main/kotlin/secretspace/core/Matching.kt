package secretspace.core

import java.net.URI
import java.util.Locale

/**
 * Which entries belong to the web site or app a login form is on, for
 * autofill. An entry's web address decides for a site: the same host, or
 * one a subdomain of the other (www.bank.example and bank.example). For an
 * app (no web site known) its package's own words are compared with the
 * entry's name. These are suggestions; the user picks the entry.
 */
object Matching {
    private val COMMON = setOf("com", "org", "net", "android", "app", "apps", "mobile", "client", "www", "io", "co",
        "de", "uk", "us", "eu", "google", "gms", "main", "release", "prod", "lite")

    /** The host of an entry's web address, lower case, without "www."; null when there is none. */
    fun hostOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text else "https://$text"
        val host = try {
            URI(withScheme).host
        } catch (e: Exception) {
            null
        } ?: return null
        return host.lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.").takeIf { it.isNotEmpty() }
    }

    fun sameSite(entryHost: String, domain: String): Boolean {
        val d = domain.lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.")
        return entryHost == d || d.endsWith(".$entryHost") || entryHost.endsWith(".$d")
    }

    private fun appWords(packageName: String) =
        packageName.lowercase(Locale.ROOT).split('.', '_', '-').filter { it.length >= 3 && it !in COMMON }

    fun matches(entry: Obj, webDomain: String?, packageName: String): Boolean {
        if (!webDomain.isNullOrEmpty()) {
            val host = hostOf(Py.text(entry, "url")) ?: return false
            return sameSite(host, webDomain)
        }
        val name = (Py.text(entry, "site") + " " + Py.text(entry, "url")).lowercase(Locale.ROOT)
        return appWords(packageName).any { name.contains(it) }
    }
}
