package secretspace.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 2FA codes and the password tools, against the PC's own output (tools/make_vectors.py). */
class ToolsTest {
    private val v = Py.asObj(Py.native(Json.parseToJsonElement(
        javaClass.getResource("/vectors.json")!!.readText(Charsets.UTF_8))))!!

    private fun list(key: String) = Py.asList(v[key])!!.map { Py.asObj(it)!! }

    private fun number(x: Any?): Double = when (x) {
        is Long -> x.toDouble()
        is Double -> x
        else -> error("not a number: $x")
    }

    @Test fun codesAreThePcs() {
        for (c in list("totp")) {
            val p = Totp.parse(c["stored"] as String)
            assertEquals(c["key"], AccountCrypto.b64(p.key), c["stored"] as String)
            assertEquals(c["digits"], p.digits.toLong())
            assertEquals(c["period"], p.period.toLong())
            assertEquals(c["algorithm"], p.algorithm)
            for (row in Py.asList(c["codes"])!!) {
                val (at, code, left) = Py.asList(row)!!
                assertEquals(code, Totp.code(p, number(at)), "${c["stored"]} at $at")
                assertEquals(left, Totp.secondsLeft(p, number(at)).toLong())
            }
        }
    }

    @Test fun aSecretThatCannotBeUsedIsExplainedAsOnThePc() {
        for (c in list("totp_errors")) assertEquals(c["error"], Totp.check(c["stored"] as String), c["stored"] as String)
        for ((digits, spaced) in Py.asObj(v["spaced"])!!) assertEquals(spaced, Totp.spaced(digits))
    }

    @Test fun theStrengthMeterSaysWhatThePcSays() {
        for (row in Py.asList(v["strength"])!!) {
            val (pw, label, fraction) = Py.asList(row)!!
            val (l, f) = Passwords.strength(pw as String)
            assertEquals(label, l, pw)
            assertEquals(number(fraction), f, 1e-9, pw)
        }
    }

    @Test fun generatedPasswordsHaveEveryKindAsked() {
        repeat(200) {
            val pw = Passwords.generate(12)
            assertEquals(12, pw.length)
            assertTrue(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() } && pw.any { it.isDigit() } &&
                pw.any { !it.isLetterOrDigit() }, pw)
        }
        val digitsOnly = Passwords.generate(8, upper = false, lower = false, symbols = false)
        assertTrue(digitsOnly.all { it.isDigit() })
        assertEquals(6, Passwords.generate(6, upper = false, lower = false, digits = false, symbols = false).length)
        assertFailsWith<IllegalArgumentException> { Passwords.generate(5) }
        assertEquals(100, (1..100).map { Passwords.generate(16) }.toSet().size)
    }
}
