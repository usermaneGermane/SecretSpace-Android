package secretspace.core

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** The password generator and strength meter: the PC's password_generator.py. */
object Passwords {
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/|~"
    private val random = SecureRandom()

    fun generate(length: Int = 16, upper: Boolean = true, lower: Boolean = true, digits: Boolean = true,
                 symbols: Boolean = true): String {
        if (length < 6) throw IllegalArgumentException("Password length should be at least 6 characters.")
        var pools = listOfNotNull(UPPER.takeIf { upper }, LOWER.takeIf { lower }, DIGITS.takeIf { digits },
            SYMBOLS.takeIf { symbols })
        if (pools.isEmpty()) pools = listOf(UPPER, LOWER, DIGITS, SYMBOLS)
        val alphabet = pools.joinToString("")
        val chars = (pools.map { it[random.nextInt(it.length)] }.take(length) +
            List(length - min(length, pools.size)) { alphabet[random.nextInt(alphabet.length)] }).toMutableList()
        chars.shuffle(random)
        return chars.joinToString("")
    }

    private val COMMON = setOf(
        "password", "passwd", "pass", "secret", "letmein", "welcome", "admin",
        "administrator", "login", "master", "qwerty", "qwertyuiop", "azerty",
        "abc", "abcd", "abcdef", "abcdefg", "monkey", "dragon", "sunshine",
        "princess", "football", "baseball", "iloveyou", "trustno", "starwars",
        "superman", "batman", "shadow", "michael", "jennifer", "jordan",
        "hunter", "freedom", "whatever", "zaq", "asdf", "asdfgh", "qazwsx",
        "changeme", "default", "test", "guest", "root", "user", "temp",
    )
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "azertyuiop", "qwertzuiop", "1234567890")

    /** Python's str.strip(): whitespace by Unicode's (and Python's) idea of it. */
    private fun pyStrip(s: String) = s.trim { it.isWhitespace() || it in "\u001c\u001d\u001e\u001f\u0085" }

    private fun core(password: String): String {
        var c = pyStrip(password.lowercase())
        c = c.replace(Regex("^[^a-z]+|[^a-z0-9]+$"), "")
        c = c.replace(Regex("[0-9]+$"), "")
        for ((a, b) in listOf("@" to "a", "4" to "a", "3" to "e", "1" to "l", "0" to "o", "$" to "s", "5" to "s",
            "7" to "t", "!" to "i")) c = c.replace(a, b)
        return c
    }

    private fun repeatedUnit(p: List<Int>): List<Int> {
        val n = p.size
        for (size in 1..n / 2) {
            if (n % size == 0 && (0 until n).all { p[it] == p[it % size] }) return p.subList(0, size)
        }
        return p
    }

    private fun points(s: String): List<Int> = s.codePoints().toArray().toList()
    private fun text(p: List<Int>) = String(p.toIntArray(), 0, p.size)

    private fun isCommon(password: String): Boolean {
        for (candidate in setOf(core(password), core(text(repeatedUnit(points(password)))))) {
            if (candidate in COMMON) return true
            for (row in ROWS) {
                if (candidate.length >= 4 && (candidate in row || candidate in row.reversed())) return true
            }
        }
        return false
    }

    private fun isAlnum(cp: Int) = Character.isLetterOrDigit(cp)

    private fun effectiveLength(p: List<Int>): Double {
        val unit = repeatedUnit(p)
        if (unit.size != p.size) return effectiveLength(unit) + ln((p.size / unit.size).toDouble()) / ln(2.0)
        var total = 1.0
        for (i in 1 until p.size) {
            val prev = p[i - 1]
            val cur = p[i]
            total += when {
                cur == prev -> 0.15
                abs(cur - prev) == 1 && isAlnum(prev) && isAlnum(cur) -> 0.25
                else -> 1.0
            }
        }
        return total
    }

    /** (label, fraction 0..1) as the PC shows it: "", Weak, Fair, Good or Strong. */
    fun strength(password: String): Pair<String, Double> {
        if (password.isEmpty()) return "" to 0.0
        var pool = 0
        if (password.any { it in LOWER }) pool += 26
        if (password.any { it in UPPER }) pool += 26
        if (password.any { it in DIGITS }) pool += 10
        if (password.any { it in SYMBOLS }) pool += SYMBOLS.length
        if (password.any { it !in LOWER + UPPER + DIGITS + SYMBOLS }) pool += 32
        var entropy = effectiveLength(points(password)) * (ln(max(pool, 1).toDouble()) / ln(2.0))
        if (isCommon(password)) entropy = min(entropy, 20.0)
        val fraction = min(entropy / 120.0, 1.0)
        val label = when {
            entropy < 40 -> "Weak"
            entropy < 70 -> "Fair"
            entropy < 100 -> "Good"
            else -> "Strong"
        }
        return label to fraction
    }
}
