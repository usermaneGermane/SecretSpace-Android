package secretspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * "Is there a newer SecretSpace?" -- asked of GitHub only when the user
 * wants (the PC's update_check.py). One HTTPS request for this app's
 * releases; it sends nothing about the user or their vaults. Nothing is
 * downloaded: a newer version's page opens in the browser.
 *
 * Unlike the PC it reads the list of releases, not "latest": the Android
 * app's versions before 1.0 are pre-releases, which "latest" leaves out.
 */
object UpdateCheck {
    const val REPO = "usermaneGermane/SecretSpace-Android"
    const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val PAGE = "https://github.com/$REPO/releases"
    private val VERSION = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?")
    private val STAGES = mapOf("alpha" to 0, "beta" to 1, "rc" to 2)

    class Release(val version: String, val page: String)

    /** update_check.version_key: a test version comes before its release (4.2.0-rc.1 < 4.2.0). */
    fun versionKey(text: String): List<Int>? {
        val m = VERSION.matchEntire(text.trim()) ?: return null
        val (major, minor, patch, stage, number) = m.destructured
        val pre = if (stage.isNotEmpty()) listOf(STAGES.getValue(stage), number.toInt()) else listOf(3, 0)
        return listOf(major.toInt(), minor.toInt(), patch.toInt()) + pre
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    fun isNewer(tag: String, current: String = AppVersion.VERSION): Boolean {
        val new = versionKey(tag) ?: return false
        val have = versionKey(current) ?: return false
        return compare(new, have) > 0
    }

    /** The newest published release in GitHub's answer (drafts left out), or null. */
    fun newest(answer: String): Release? {
        val list = Json.parseToJsonElement(answer) as? JsonArray ?: throw IOException("GitHub's answer has no releases in it")
        return list.mapNotNull { it as? JsonObject }
            .filter { (it["draft"] as? JsonPrimitive)?.booleanOrNull != true }
            .mapNotNull { r ->
                val tag = (r["tag_name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val key = versionKey(tag) ?: return@mapNotNull null
                val page = (r["html_url"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("https://github.com/") } ?: PAGE
                Triple(key, tag.trim().removePrefix("v"), page)
            }
            .maxWithOrNull { a, b -> compare(a.first, b.first) }
            ?.let { Release(it.second, it.third) }
    }

    /** Ask GitHub; IOException with a readable message when it cannot be asked. */
    fun latest(timeoutMs: Int = 8000): Release? {
        val c = URI(API_URL).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SecretSpace-update-check")
        try {
            val status = try { c.responseCode } catch (e: IOException) { throw IOException("could not reach GitHub (${e.message})") }
            if (status != 200) throw IOException("GitHub answered $status")
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size > 2_000_000) throw IOException("GitHub's answer is too large")
            return newest(bytes.toString(Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }
}
