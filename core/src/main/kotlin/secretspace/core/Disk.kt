package secretspace.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What this app is, as the server is told (X-SecretSpace-Client). */
object AppVersion {
    const val PLATFORM = "android"
    const val VERSION = "0.8.0"
}

/** Files, names and JSON as the PC's vault.py and accounts.py have them. */
object Disk {
    const val BACKUP_SUFFIX = ".bak"
    const val BASE_SUFFIX = ".base"
    const val BASE_ORIGIN_KEY = "base_origin"


    private val USERNAME = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._ -]{0,62})$")
    private val RESERVED = setOf("con", "prn", "aux", "nul") +
        (1..9).map { "com$it" } + (1..9).map { "lpt$it" }

    fun utcNow(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC).format(Instant.now())

    /** The username if it is safe as a folder name (vault.validate_username). */
    fun validateUsername(username: String?): String {
        val name = (username ?: "").trim()
        if (name.isEmpty()) throw IllegalArgumentException("Username is required.")
        if (!USERNAME.matches(name)) throw IllegalArgumentException(
            "Usernames may only contain letters, digits, spaces, dots, hyphens and " +
                "underscores, must start with a letter or digit, and can be at most 63 characters.",
        )
        if (name.trimEnd('.', ' ').lowercase() in RESERVED) {
            throw IllegalArgumentException("'$name' is a reserved device name on Windows.")
        }
        return name
    }

    /**
     * Never a half-written file: the bytes go to a temporary file beside the
     * target, reach the disk, and only then replace it.
     */
    fun writeAtomic(target: File, payload: ByteArray) {
        val folder = target.absoluteFile.parentFile
        folder.mkdirs()
        val tmp = File.createTempFile(".tmp-", ".part", folder)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun writeText(target: File, text: String) = writeAtomic(target, text.toByteArray(Charsets.UTF_8))
    fun readText(file: File): String = file.readText(Charsets.UTF_8).trim()

    /** The current file becomes its .bak before it is overwritten. */
    fun rotateBackup(file: File) {
        val current = try { file.readBytes() } catch (e: java.io.IOException) { return }
        if (current.isEmpty()) return
        try {
            writeAtomic(File(file.path + BACKUP_SUFFIX), current)
        } catch (e: java.io.IOException) {
            // a failed backup must never block the real save
        }
    }

    // ------------------------------------------------------------------ JSON
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Keys sorted all the way down, like json.dumps(sort_keys=True). */
    fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }
            .associate { it.key to sorted(it.value) })
        is JsonArray -> JsonArray(element.map { sorted(it) })
        else -> element
    }

    fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), sorted(element))

    fun str(value: String?): JsonElement = JsonPrimitive(value)
    fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let {
        if (it.isString) it.content.toIntOrNull() else it.intOrNull ?: it.longOrNull?.toInt()
    }
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
        if (it.isString) it.content.toLongOrNull() else it.longOrNull
    }
    fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(this + pairs)
    fun JsonObject.without(key: String): JsonObject = JsonObject(this - key)

    /** Is version ``have`` below ``needed``? False for anything not a plain version. */
    fun older(have: String, needed: String?): Boolean {
        val plain = Regex("""(\d{1,4})\.(\d{1,4})\.(\d{1,4})""")
        val a = plain.find(have)?.takeIf { it.range.first == 0 } ?: return false
        val b = needed?.let { plain.matchEntire(it) } ?: return false
        val x = a.groupValues.drop(1).map { it.toInt() }
        val y = b.groupValues.drop(1).map { it.toInt() }
        for (i in 0..2) if (x[i] != y[i]) return x[i] < y[i]
        return false
    }
}
