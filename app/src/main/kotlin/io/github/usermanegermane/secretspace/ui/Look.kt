package io.github.usermanegermane.secretspace.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.usermanegermane.secretspace.R

/** The colours of the mode in use, from [Palette]: a screen writes ``Look.c.muted``, never a colour of its own. */
class Colors(val dark: Boolean) {
    private fun c(pair: Pair2) = Color(0xFF000000 or pair.pick(dark))
    val bg = c(Palette.BG)
    val header = c(Palette.HEADER_BG)
    val card = c(Palette.CARD)
    val pressed = c(Palette.CARD_HOVER)
    val selected = c(Palette.SELECTED)
    val input = c(Palette.INPUT_BG)
    val border = c(Palette.BORDER)
    val text = c(Palette.TEXT)
    val muted = c(Palette.MUTED)
    val green = c(Palette.GREEN)
    val greenPressed = c(Palette.GREEN_HOVER)
    val choice = c(Palette.CHOICE)
    val danger = c(Palette.DANGER)
    val onAccent = c(Palette.ON_ACCENT)
    val onDanger = c(Palette.ON_DANGER)
    val warn = c(Palette.WARN)
    val good = c(Palette.GOOD)

    fun tile(name: String) = Palette.tileOf(name).let { Color(0xFF000000 or it.tile) to Color(0xFF000000 or it.letter) }
    fun band(name: String) = c(Palette.tileOf(name).band)

    /** A password's strength, by the name Passwords.strength gives it (theme.STRENGTH). */
    fun strength(label: String) = when (label) {
        "Weak" -> danger
        "Fair" -> warn
        "Good" -> good
        "Strong" -> green
        else -> muted
    }
}

private val LocalColors = staticCompositionLocalOf { Colors(false) }

/** The app's one typeface, Manrope (SIL Open Font License, assets/Manrope-OFL.txt), as on the PC. */
val Manrope = FontFamily(Font(R.font.manrope_regular, FontWeight.Normal), Font(R.font.manrope_bold, FontWeight.Bold))

/**
 * Type by role (theme.T), a step up from the PC's for a phone held in the
 * hand: a screen says ``Type.body``, never a size of its own.
 */
object Type {
    private fun s(size: Int, bold: Boolean = false, family: FontFamily = Manrope) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.4).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

    val title = s(26, bold = true)      // a screen's one title: Welcome back, an open entry's name
    val heading = s(20, bold = true)    // a bar's title, a card's heading
    val lead = s(16)                    // what is typed in a field, a value to read
    val leadBold = s(16, bold = true)
    val body = s(15)                    // text to read
    val bodyBold = s(15, bold = true)   // a row's name
    val label = s(14, bold = true)      // buttons
    val small = s(13)                   // a row's second line, notes
    val smallBold = s(13, bold = true)  // field captions, section labels
    val caption = s(12)                 // times, counts, the small print
    val code = s(22, bold = true, family = FontFamily.Monospace)   // a 2FA code, a Secret Key, a fingerprint
    val mono = s(16, bold = true, family = FontFamily.Monospace)   // the recovery words, a shown password
}

/** Space in steps of 4, corner radii by what is rounded (theme.SP and theme.R). */
object Sp {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

object Radius {
    val button = RoundedCornerShape(10.dp)
    val inner = RoundedCornerShape(12.dp)       // fields, tiles, panels inside a card
    val row = RoundedCornerShape(14.dp)         // a list row
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(20.dp)       // an open entry's header
    val dialog = RoundedCornerShape(24.dp)      // the unlock card
}

object Look {
    val c: Colors @Composable get() = LocalColors.current
}

private fun scheme(c: Colors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    // Every role Material reads is one of ours, so no default purple or pink is left anywhere.
    return base.copy(
        primary = c.green, onPrimary = c.onAccent, primaryContainer = c.selected, onPrimaryContainer = c.text,
        inversePrimary = c.green,
        secondary = c.green, onSecondary = c.onAccent, secondaryContainer = c.selected, onSecondaryContainer = c.text,
        tertiary = c.green, onTertiary = c.onAccent, tertiaryContainer = c.selected, onTertiaryContainer = c.text,
        background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.input, onSurfaceVariant = c.muted,
        surfaceTint = Color.Transparent,
        inverseSurface = c.text, inverseOnSurface = c.bg,
        error = c.danger, onError = c.onDanger, errorContainer = c.danger, onErrorContainer = c.onDanger,
        outline = c.border, outlineVariant = c.border, scrim = Color(0x99000000),
        surfaceBright = c.card, surfaceDim = c.bg,
        surfaceContainerLowest = c.bg, surfaceContainerLow = c.card, surfaceContainer = c.card,
        surfaceContainerHigh = c.card, surfaceContainerHighest = c.input,
    )
}

private val typography = Typography(
    displayLarge = Type.title, displayMedium = Type.title, displaySmall = Type.title,
    headlineLarge = Type.title, headlineMedium = Type.title, headlineSmall = Type.heading,
    titleLarge = Type.heading, titleMedium = Type.leadBold, titleSmall = Type.bodyBold,
    bodyLarge = Type.lead, bodyMedium = Type.body, bodySmall = Type.small,
    labelLarge = Type.label, labelMedium = Type.smallBold, labelSmall = Type.caption,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = Radius.button, medium = Radius.inner,
    large = Radius.card, extraLarge = Radius.dialog,
)

/** The PC's look (DESIGN-LOOK.md): light or dark as the phone is set, Manrope, one green. */
@Composable
fun SecretSpaceTheme(content: @Composable () -> Unit) {
    val colors = Colors(isSystemInDarkTheme())
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = typography, shapes = shapes, content = content)
    }
}
