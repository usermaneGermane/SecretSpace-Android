package io.github.usermanegermane.secretspace.ui

/**
 * The PC app's colours (theme.py, DESIGN-LOOK.md "direction C"), the same
 * values, so the phone and the desktop look like one product. Every colour
 * is a (light, dark) pair of 0xRRGGBB; the screens take them from [Look]
 * only, never typing one of their own. PaletteTest checks the contrast the
 * PC's test_look.py checks.
 */
class Pair2(val light: Long, val dark: Long) {
    fun pick(dark: Boolean) = if (dark) this.dark else light
}

object Palette {
    val BG = Pair2(0xEDF0F4, 0x15161A)              // the window itself
    val HEADER_BG = Pair2(0xE6EAEF, 0x1A1C21)       // bars
    val CARD = Pair2(0xFFFFFF, 0x1F2127)            // cards and panels on top of BG
    val CARD_HOVER = Pair2(0xE9EDF2, 0x2A2D36)      // a card being pressed
    val SELECTED = Pair2(0xDDF1E6, 0x1F3128)        // the chosen account, row or vault
    val INPUT_BG = Pair2(0xF4F6F9, 0x191B20)        // fields, panels inside a card
    val BORDER = Pair2(0xDCE1E8, 0x2E313A)
    val TEXT = Pair2(0x16181D, 0xF0F1F4)
    val MUTED = Pair2(0x586170, 0xA2A8B3)
    val GREEN = Pair2(0x0D7644, 0x56E39F)           // the one main action of a screen, and what is chosen
    val GREEN_HOVER = Pair2(0x0B6239, 0x3FCB87)
    val CHOICE = Pair2(0xB6E3C9, 0x24603F)
    val DANGER = Pair2(0xBC342E, 0xF2766E)
    val DANGER_HOVER = Pair2(0x9E2A24, 0xFF938A)
    val ON_ACCENT = Pair2(0xFFFFFF, 0x05230F)
    val ON_DANGER = Pair2(0xFFFFFF, 0x2A0603)
    val WARN = Pair2(0x8A5300, 0xF2B955)
    val GOOD = Pair2(0x1F8A55, 0x67C090)            // a "Good" password, between Fair and Strong

    /** Per-site tiles: (tile, letter, header band), as theme.TILE_PALETTE. */
    class Tile(val tile: Long, val letter: Long, val band: Pair2)

    val TILES = listOf(
        Tile(0xD5F2DD, 0x0E4023, Pair2(0xE2F6E8, 0x1D3527)),
        Tile(0xFFE0C2, 0x5C2E05, Pair2(0xFFEBD8, 0x3D2C1B)),
        Tile(0xE7DBFF, 0x35206B, Pair2(0xEFE7FF, 0x2B2547)),
        Tile(0xFFD9E0, 0x641328, Pair2(0xFFE6EA, 0x3F2228)),
        Tile(0xCFE3FF, 0x12305E, Pair2(0xDCE9FF, 0x22324D)),
        Tile(0xD6EEF2, 0x0C3F49, Pair2(0xE3F4F7, 0x1C3338)),
    )

    /** theme.tile_colors: the sum of the name's code points picks the tile, so a site has the same one everywhere. */
    fun tileOf(name: String): Tile =
        if (name.isEmpty()) TILES[0] else TILES[name.codePoints().sum() % TILES.size]

    /** WCAG contrast of two colours, 1 to 21. */
    fun contrast(a: Long, b: Long): Double {
        fun channel(c: Long, shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        fun luminance(c: Long) = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
