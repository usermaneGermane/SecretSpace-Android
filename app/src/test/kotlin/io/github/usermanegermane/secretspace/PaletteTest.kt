package io.github.usermanegermane.secretspace

import io.github.usermanegermane.secretspace.ui.Palette
import io.github.usermanegermane.secretspace.ui.Pair2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The PC's look rules (its tests/test_look.py), for the phone's copy of the palette. */
class PaletteTest {
    private val inks = mapOf("TEXT" to Palette.TEXT, "MUTED" to Palette.MUTED, "GREEN" to Palette.GREEN,
        "DANGER" to Palette.DANGER, "WARN" to Palette.WARN)
    private val surfaces = mapOf("BG" to Palette.BG, "HEADER_BG" to Palette.HEADER_BG, "CARD" to Palette.CARD,
        "CARD_HOVER" to Palette.CARD_HOVER, "SELECTED" to Palette.SELECTED, "INPUT_BG" to Palette.INPUT_BG) +
        Palette.TILES.mapIndexed { i, t -> "band$i" to t.band }

    private fun hex(c: Long) = "#%06X".format(c)

    @Test fun textIsReadableOnEverySurfaceInBothModes() {
        val bad = mutableListOf<String>()
        for ((ink, i) in inks) for ((ground, g) in surfaces) for (dark in listOf(false, true)) {
            val ratio = Palette.contrast(i.pick(dark), g.pick(dark))
            if (ratio < 4.5) bad += "$ink on $ground (${if (dark) "dark" else "light"}): %.2f".format(ratio)
        }
        assertEquals("WCAG AA is 4.5 to 1", emptyList<String>(), bad)
    }

    @Test fun textIsReadableOnItsFill() {
        val fills = listOf(Palette.ON_ACCENT to Palette.GREEN, Palette.ON_ACCENT to Palette.GREEN_HOVER,
            Palette.ON_DANGER to Palette.DANGER, Palette.ON_DANGER to Palette.DANGER_HOVER, Palette.TEXT to Palette.CHOICE)
        for ((ink, fill) in fills) for (dark in listOf(false, true)) {
            assertTrue("${hex(ink.pick(dark))} on ${hex(fill.pick(dark))}", Palette.contrast(ink.pick(dark), fill.pick(dark)) >= 4.5)
        }
    }

    @Test fun strengthColoursShowOnACard() {
        for (s in listOf(Palette.DANGER, Palette.WARN, Palette.GOOD, Palette.GREEN)) for (dark in listOf(false, true)) {
            assertTrue(Palette.contrast(s.pick(dark), Palette.CARD.pick(dark)) >= 3.0)
        }
    }

    @Test fun tileLettersAreReadableAndTheTileIsTheSameAsOnThePc() {
        for (t in Palette.TILES) assertTrue(Palette.contrast(t.tile, t.letter) >= 4.5)
        // theme.tile_colors: "GitHub" sums to 579 (579 % 6 = 3, pink), "Bank" to 380 (lavender), as the PC's screenshot
        assertEquals(Palette.TILES[3], Palette.tileOf("GitHub"))
        assertEquals(Palette.TILES[2], Palette.tileOf("Bank"))
        assertEquals(Palette.TILES[0], Palette.tileOf(""))
        assertEquals(Palette.TILES["é".codePointAt(0) % 6], Palette.tileOf("é"))
    }

    /** As the PC's test_no_colour_is_typed_outside_theme: a screen takes its colours from the palette. */
    @Test fun noScreenTypesAColourOfItsOwn() {
        val source = File("src/main/kotlin/io/github/usermanegermane/secretspace")
        assertTrue("run from the app module", source.isDirectory)
        val typed = Regex("""Color\(0x[0-9A-Fa-f]+|Color\.(Red|Green|Blue|Black|White|Gray|DarkGray|LightGray|Cyan|Magenta|Yellow)\b""")
        val found = source.walk().filter { it.isFile && it.extension == "kt" && it.name !in setOf("Look.kt", "Palette.kt") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { n, line -> typed.find(line)?.let { "${f.name}:${n + 1}: ${it.value}" } } }
            .toList()
        assertEquals(emptyList<String>(), found)
        assertTrue(typed.containsMatchIn("Color(0xFF123456)") && typed.containsMatchIn("tint = Color.White"))
        assertTrue(!typed.containsMatchIn("Color.Transparent"))
    }

    @Test fun pairsPickTheirMode() {
        val p = Pair2(0x111111, 0xEEEEEE)
        assertEquals(0x111111L, p.pick(false))
        assertEquals(0xEEEEEEL, p.pick(true))
    }
}
