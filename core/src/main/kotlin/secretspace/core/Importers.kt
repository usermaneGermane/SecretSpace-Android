package secretspace.core

import kotlinx.serialization.json.Json

/**
 * An entry's web address (the PC's weblink.py): only http and https are
 * ever links. The address comes from the vault, and a shared vault can be
 * written by other people.
 */
object Weblink {
    const val MAX_CHARS = 2048
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

    /** (address to store, problem for the form or ""): weblink.clean. */
    fun clean(text: String?): Pair<String, String> {
        var value = (text ?: "").trim()
        if (value.isEmpty()) return "" to ""
        if (value.length > MAX_CHARS) return value to "the address is too long."
        if (value.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) return value to "a web address has no spaces."
        if ("://" !in value) {
            val scheme = if (":" in value) value.substringBefore(":").lowercase() else ""
            // "localhost:8080" and "10.0.0.1:443" are a host and a port, not a scheme.
            if (scheme.isNotEmpty() && !value.substringAfter(":").take(1).let { it.isNotEmpty() && it[0].isDigit() }) {
                return value to "only http:// and https:// addresses can be links."
            }
            value = "https://$value"
        }
        val scheme = value.substringBefore(":").takeIf { SCHEME.matches(it) }?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https") return value to "only http:// and https:// addresses can be links."
        if (hostname(value).isNullOrEmpty()) return value to "the address has no site name in it."
        return value to ""
    }

    /** The address if it is safe to hand to a browser, else null. */
    fun openable(address: String?): String? = clean(address).let { (v, problem) -> v.takeIf { it.isNotEmpty() && problem.isEmpty() } }

    /** urlsplit(value).hostname, for an address with a scheme. */
    private fun hostname(value: String): String? {
        val rest = value.substringAfter(":")
        if (!rest.startsWith("//")) return null
        val netloc = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        val host = netloc.substringAfterLast('@')
        val name = if (host.startsWith("[")) host.substringAfter("[").substringBefore("]")
            else host.substringBefore(":")
        return name.lowercase().ifEmpty { null }
    }
}

/**
 * Logins from another password manager or a browser (the PC's importers.py):
 * Chrome, Bitwarden, LastPass, KeePass and other CSV or JSON exports. What a
 * file holds that SecretSpace cannot store is named, not dropped quietly.
 */
object Importers {
    class Row(val site: String, val username: String, val password: String, val notes: String = "",
              val totp: String = "", val url: String = "") {
        val any get() = listOf(site, username, password, notes, totp, url).any { it.isNotEmpty() }
    }

    class Result(val rows: List<Row>, val dropped: List<String>)

    private val NAME_KEYS = listOf("name", "title", "site", "web/app", "webapp", "account", "grouping")
    private val URL_KEYS = listOf("url", "login_uri", "uri", "website", "hostname")
    private val SITE_KEYS = NAME_KEYS + URL_KEYS
    private val USER_KEYS = listOf("username", "user", "login_username", "login", "email", "e-mail", "user/email",
        "useremail", "user name")
    private val PASS_KEYS = listOf("password", "login_password", "pass", "pwd", "passwd", "passphrase")
    private val NOTE_KEYS = listOf("notes", "note", "comments", "comment", "extra", "description")
    private val TOTP_KEYS = listOf("totp", "login_totp", "otp", "otpauth", "authenticator", "two_factor", "2fa")
    private val LOSSY_KEYS = listOf("custom" to "custom fields", "fields" to "custom fields", "card" to "card details",
        "identity" to "identity details", "attachment" to "attachments", "history" to "password history")
    private val KNOWN = (SITE_KEYS + USER_KEYS + PASS_KEYS + NOTE_KEYS + TOTP_KEYS).toSet()

    /** Python's str() of a JSON value, as the PC applies it to a cell. */
    private fun cell(v: Any?): String = if (v is String) v else Py.str(v)

    private fun pick(row: Map<String, Any?>, keys: List<String>, exclude: List<String> = emptyList()): String {
        for (k in keys) if (k in row && cell(row[k]).trim().isNotEmpty()) return cell(row[k]).trim()
        for ((header, value) in row) {
            if (exclude.any { it in header }) continue
            if (keys.any { it in header } && cell(value).trim().isNotEmpty()) return cell(value).trim()
        }
        return ""
    }

    private val TLD = Regex("\\.[a-z]{2,}")

    /** A URL-looking value as a tidy site name; plain names as they are. */
    fun cleanSite(value: String?): String {
        var v = (value ?: "").trim()
        val looksUrl = "://" in v || v.lowercase().startsWith("www.") || (" " !in v && TLD.containsMatchIn(v.lowercase()))
        if (looksUrl) {
            if ("://" in v) v = v.substringAfter("://")
            if (v.lowercase().startsWith("www.")) v = v.substring(4)
            v = v.substringBefore("/")
        }
        return v.ifEmpty { (value ?: "").trim() }
    }

    private fun toRow(row: Map<String, Any?>): Row {
        val url = pick(row, URL_KEYS, listOf("pass", "user", "email"))
        val name = pick(row, NAME_KEYS, listOf("pass", "user", "email", "url", "uri", "host"))
        return Row(cleanSite(name.ifEmpty { url }), pick(row, USER_KEYS, listOf("pass", "url", "uri", "totp", "note")),
            pick(row, PASS_KEYS, listOf("hint", "note", "totp", "reused", "weak")),
            pick(row, NOTE_KEYS, listOf("pass", "user", "totp")), pick(row, TOTP_KEYS, listOf("pass", "user", "note")), url)
    }

    private fun dropped(rows: List<Map<String, Any?>>): List<String> {
        val found = sortedSetOf<String>()
        for (row in rows) for ((header, value) in row) {
            if (value == null || cell(value).trim().isEmpty()) continue
            for ((fragment, label) in LOSSY_KEYS) if (fragment in header) found += label
        }
        return found.toList()
    }

    private fun fromDicts(rows: List<Map<String, Any?>>) = Result(rows.map { toRow(it) }.filter { it.any }, dropped(rows))

    /** What else the rows carry, for the question before importing. */
    fun importedFields(rows: List<Row>): List<String> = listOfNotNull(
        "TOTP / 2FA secrets".takeIf { rows.any { it.totp.isNotEmpty() } },
        "notes".takeIf { rows.any { it.notes.isNotEmpty() } },
    )

    /** A CSV or JSON export; ``name`` decides which (".json"). */
    fun parse(name: String, text: String): Result =
        if (name.lowercase().endsWith(".json")) parseJson(text) else parseCsv(text)

    fun parseCsv(raw: String): Result {
        val text = raw.removePrefix("﻿")
        val rows = Csv.read(text, Csv.sniff(text.take(8192))).filter { r -> r.any { it.trim().isNotEmpty() } }
        if (rows.isEmpty()) return Result(emptyList(), emptyList())
        val headers = rows[0].map { it.trim().lowercase() }
        if (headers.count { it in KNOWN } >= 2) {
            return fromDicts(rows.drop(1).map { r ->
                linkedMapOf<String, Any?>().apply { for (i in 0 until minOf(headers.size, r.size)) put(headers[i], r[i]) }
            })
        }
        // no header: site, username, password
        return Result(rows.map { r ->
            Row(if (r.isNotEmpty()) cleanSite(r[0]) else "", r.getOrNull(1)?.trim() ?: "", r.getOrNull(2)?.trim() ?: "")
        }, emptyList())
    }

    fun parseJson(text: String): Result {
        val data = Py.native(Json.parseToJsonElement(text.removePrefix("﻿")))
        @Suppress("UNCHECKED_CAST")
        val items: List<Any?> = when {
            data is Map<*, *> && "accounts" in data -> (data["accounts"] as? List<Any?>) ?: return Result(emptyList(), emptyList())
            data is List<*> -> data
            data is Map<*, *> -> listOf(data)
            else -> return Result(emptyList(), emptyList())
        }
        return fromDicts(items.filterIsInstance<Map<*, *>>().map { m ->
            linkedMapOf<String, Any?>().apply { for ((k, v) in m) put(cell(k).trim().lowercase(), v) }
        })
    }

    // ------------------------------------------------------------------ export
    /**
     * The vault in plain text as the PC's vault.export_csv writes it: no
     * archived entries, a header row, Python's csv dialect (CRLF lines).
     */
    fun exportCsv(data: Obj): Pair<String, Int> {
        val active = Entries.accountsOf(Entries.validateVault(Py.deepCopyObj(data))).filter { !Entries.isArchived(it) }
        val out = StringBuilder()
        Csv.writeRow(listOf("name", "url", "username", "password", "notes", "totp"), out)
        for (a in active) Csv.writeRow(listOf("site", "url", "username", "password", "notes", "totp")
            .map { Py.textNoOr(a, it) }, out)
        return out.toString() to active.size
    }

    /** vault.export_json: every entry, the archive included, json.dumps(indent=2) as Python writes it. */
    fun exportJson(data: Obj): Pair<String, Int> {
        val accounts = Entries.accountsOf(Entries.validateVault(Py.deepCopyObj(data)))
        return PyJson.indent(linkedMapOf<String, Any?>("accounts" to accounts)) to accounts.size
    }
}

/** Python's csv module, excel dialect, as far as importing and exporting need it. */
object Csv {
    class Dialect(val delimiter: Char)

    /**
     * csv.Sniffer().sniff(sample, delimiters=",;\t"), or the excel dialect
     * when it cannot tell: the delimiter that appears equally often on most
     * lines (counted outside quotes), preferring "," then ";" then tab.
     */
    fun sniff(sample: String): Dialect {
        val lines = sample.split("\n").map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Dialect(',')
        var best: Char? = null
        var bestScore = 0.0
        for (d in listOf(',', ';', '\t')) {
            val counts = lines.map { line -> countOutsideQuotes(line, d) }
            val mode = counts.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key })) ?: continue
            if (mode.key == 0) continue
            val score = mode.value.toDouble() / lines.size
            if (score > bestScore) { best = d; bestScore = score }
        }
        return Dialect(best ?: ',')
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var inQuotes = false
        var n = 0
        for (c in line) {
            if (c == '"') inQuotes = !inQuotes
            else if (c == d && !inQuotes) n++
        }
        return n
    }

    /** csv.reader: quoted fields may hold the delimiter, doubled quotes and line breaks. */
    fun read(text: String, dialect: Dialect): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var quotedField = false
        var i = 0
        var started = false
        fun endField() { row.add(field.toString()); field.clear(); quotedField = false }
        fun endRow() { endField(); rows.add(row); row = mutableListOf(); started = false }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when {
                c == '"' && field.isEmpty() && !quotedField -> { inQuotes = true; quotedField = true; started = true }
                c == dialect.delimiter -> { endField(); started = true }
                c == '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                c == '\n' -> endRow()
                else -> { field.append(c); started = true }
            }
            i++
        }
        if (started || field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** csv.writer (QUOTE_MINIMAL, CRLF): quote a field holding the delimiter, a quote or a line break. */
    fun writeRow(cells: List<String>, out: StringBuilder) {
        cells.forEachIndexed { i, cell ->
            if (i > 0) out.append(',')
            if (cell.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"')
            } else out.append(cell)
        }
        out.append("\r\n")
    }
}

/** json.dumps(value, indent=2) with Python's defaults (ensure_ascii, ", " / ": " separators). */
object PyJson {
    fun indent(value: Any?): String = StringBuilder().also { write(value, 0, it) }.toString()

    private fun write(value: Any?, level: Int, out: StringBuilder) {
        val pad = "  ".repeat(level + 1)
        when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(",\n")
                    out.append(pad); ascii(k as String, out); out.append(": "); write(v, level + 1, out)
                }
                out.append("\n").append("  ".repeat(level)).append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) { out.append("[]"); return }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(",\n")
                    out.append(pad); write(v, level + 1, out)
                }
                out.append("\n").append("  ".repeat(level)).append("]")
            }
            is String -> ascii(value, out)
            else -> out.append(Py.dumps(value))
        }
    }

    private fun ascii(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 || c.code > 0x7e -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }
}

