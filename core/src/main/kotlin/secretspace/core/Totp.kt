package secretspace.core

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 2FA codes (TOTP, RFC 6238) from the secret stored with an entry: the PC's
 * totp.py. The secret is the plain base32 text a site shows under its QR
 * code, or an ``otpauth://totp/...`` link (what a QR code holds).
 */
object Totp {
    class Params(val key: ByteArray, val digits: Int, val period: Int, val algorithm: String)

    private val ALGORITHMS = mapOf("SHA1" to "HmacSHA1", "SHA256" to "HmacSHA256", "SHA512" to "HmacSHA512")
    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD = 30
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun pyRepr(c: Char): String = when {
        c == '\'' -> "\"'\""
        c == '\\' -> "'\\\\'"
        c.code in 0x20..0x7e -> "'$c'"
        printable(c) -> "'$c'"
        c.code < 0x100 -> "'\\x%02x'".format(c.code)
        else -> "'\\u%04x'".format(c.code)
    }

    /** Python's str.isprintable() for one character. */
    private fun printable(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false
        else -> true
    }

    private fun decodeSecret(text: String): ByteArray {
        val cleaned = text.filterNot { it.isWhitespace() }.replace("-", "").uppercase().trimEnd('=')
        if (cleaned.isEmpty()) throw IllegalArgumentException("The 2FA secret is empty.")
        val bad = cleaned.filter { it !in B32 }.toSortedSet()
        if (bad.isNotEmpty()) throw IllegalArgumentException(
            "A 2FA secret uses only the letters A-Z and the digits 2-7; this one also has " +
                bad.joinToString(" ") { pyRepr(it) } + ".",
        )
        // base32 of whole 5-byte groups; a length no encoder makes means characters are missing
        if ((cleaned.length % 8) in listOf(1, 3, 6)) throw IllegalArgumentException(
            "That 2FA secret is not complete: some characters seem to be missing.",
        )
        var buffer = 0L
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in cleaned) {
            buffer = (buffer shl 5) or B32.indexOf(c).toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        val key = out.toByteArray()
        if (key.size < 10) throw IllegalArgumentException(
            "That 2FA secret is too short to be a real one (fewer than 16 characters).",
        )
        return key
    }

    /** The code settings in a stored secret, or IllegalArgumentException saying why not. */
    fun parse(stored: String?): Params {
        val text = (stored ?: "").trim()
        if (!text.lowercase().startsWith("otpauth://")) {
            return Params(decodeSecret(text), DEFAULT_DIGITS, DEFAULT_PERIOD, "SHA1")
        }
        val rest = text.substring("otpauth://".length)
        val netloc = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (netloc.lowercase() != "totp") throw IllegalArgumentException(
            "Only time-based codes (otpauth://totp/...) are supported, not otpauth://$netloc.",
        )
        val queryText = rest.substringAfter('?', "").substringBefore('#')
        val query = mutableMapOf<String, String>()
        for (part in queryText.split('&', ';')) {
            if (!part.contains('=')) continue
            val (k, v) = part.split('=', limit = 2)
            val value = URLDecoder.decode(v, Charsets.UTF_8)
            if (value.isEmpty()) continue                 // parse_qs drops blank values
            query[URLDecoder.decode(k, Charsets.UTF_8).lowercase()] = value
        }
        val secret = query["secret"] ?: throw IllegalArgumentException("That otpauth:// link has no secret in it.")
        val key = decodeSecret(URLDecoder.decode(secret.replace("+", "%2B"), Charsets.UTF_8))
        val algorithm = (query["algorithm"] ?: "SHA1").uppercase().replace("-", "")
        if (algorithm !in ALGORITHMS) throw IllegalArgumentException(
            "The algorithm $algorithm is not supported (SHA1, SHA256 or SHA512).",
        )
        val digits = query["digits"]?.trim()?.toIntOrNull() ?: if ("digits" in query) notANumber() else DEFAULT_DIGITS
        val period = query["period"]?.trim()?.toIntOrNull() ?: if ("period" in query) notANumber() else DEFAULT_PERIOD
        if (digits !in 6..8) throw IllegalArgumentException("Codes must have 6 to 8 digits.")
        if (period !in 1..300) throw IllegalArgumentException("The code period must be 1 to 300 seconds.")
        return Params(key, digits, period, algorithm)
    }

    private fun notANumber(): Nothing =
        throw IllegalArgumentException("The digits or period in that otpauth:// link is not a number.")

    /** "" if codes can be made from ``stored`` (or it is empty), else why not. */
    fun check(stored: String?): String {
        if ((stored ?: "").isBlank()) return ""
        return try {
            parse(stored)
            ""
        } catch (e: IllegalArgumentException) {
            e.message ?: "That 2FA secret cannot be used."
        }
    }

    fun code(params: Params, at: Double = System.currentTimeMillis() / 1000.0): String {
        val counter = Math.floor(at / params.period).toLong()
        val mac = Mac.getInstance(ALGORITHMS.getValue(params.algorithm))
        mac.init(SecretKeySpec(params.key, mac.algorithm))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = h.last().toInt() and 0x0F
        val value = ByteBuffer.wrap(h, offset, 4).int and 0x7FFFFFFF
        var pow = 1L
        repeat(params.digits) { pow *= 10 }
        return (value % pow).toString().padStart(params.digits, '0')
    }

    fun secondsLeft(params: Params, at: Double = System.currentTimeMillis() / 1000.0): Int =
        params.period - (at % params.period).toInt()

    /** '123456' -> '123 456', '12345678' -> '1234 5678'. */
    fun spaced(digits: String): String {
        val half = if (digits.length != 6) (digits.length + 1) / 2 else 3
        return digits.take(half) + " " + digits.drop(half)
    }

    @Suppress("unused")
    private fun uri(text: String) = URI(text)
}
