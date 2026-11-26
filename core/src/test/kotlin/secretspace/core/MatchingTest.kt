package secretspace.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Autofill's suggestions: the entries of the site or app a login form is on. */
class MatchingTest {
    private fun entry(site: String, url: String = "") = linkedMapOf<String, Any?>("site" to site, "url" to url)

    @Test fun hostsAreReadAsTheBrowserShowsThem() {
        assertEquals("bank.example", Matching.hostOf("https://www.Bank.example/login?x=1"))
        assertEquals("bank.example", Matching.hostOf("bank.example/login"))
        assertEquals("10.10.10.3", Matching.hostOf("https://10.10.10.3:59872"))
        assertNull(Matching.hostOf(""))
        assertNull(Matching.hostOf("   "))
        assertNull(Matching.hostOf(null))
    }

    @Test fun aSiteMatchesItsOwnEntriesOnly() {
        val bank = entry("Bank", "https://bank.example")
        assertTrue(Matching.matches(bank, "bank.example", "com.android.chrome"))
        assertTrue(Matching.matches(bank, "www.bank.example", "com.android.chrome"))
        assertTrue(Matching.matches(bank, "login.bank.example", "com.android.chrome"))
        assertTrue(Matching.matches(entry("Bank", "https://login.bank.example"), "bank.example", ""))
        assertFalse(Matching.matches(bank, "bank.example.evil.test", "com.android.chrome"), "not a lookalike")
        assertFalse(Matching.matches(bank, "notbank.example", "com.android.chrome"))
        assertFalse(Matching.matches(entry("bank.example"), "bank.example", ""), "a site needs the web address")
    }

    @Test fun anAppMatchesByTheWordsOfItsPackage() {
        assertTrue(Matching.matches(entry("Netflix"), null, "com.netflix.mediaclient"))
        assertTrue(Matching.matches(entry("ProtonMail"), null, "ch.protonmail.android"))
        assertFalse(Matching.matches(entry("Bank"), null, "com.netflix.mediaclient"))
        assertFalse(Matching.matches(entry("Android"), null, "com.android.settings"), "common words do not count")
    }
}
