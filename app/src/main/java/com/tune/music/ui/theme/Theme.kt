package com.tune.music.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tune.music.R

/** Selawik is Microsoft's open-source metric-compatible stand-in for Segoe. */
val Segoe = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik_semilight, FontWeight.ExtraLight),
    Font(R.font.selawik_regular, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
    Font(R.font.selawik_bold, FontWeight.Bold),
)

/** Windows Phone 8 accent colours. */
val Accents = listOf(
    "tune" to Color(0xFFF09609),
    "lime" to Color(0xFFA4C400),
    "green" to Color(0xFF60A917),
    "emerald" to Color(0xFF008A00),
    "teal" to Color(0xFF00ABA9),
    "cyan" to Color(0xFF1BA1E2),
    "cobalt" to Color(0xFF0050EF),
    "indigo" to Color(0xFF6A00FF),
    "violet" to Color(0xFFAA00FF),
    "pink" to Color(0xFFF472D0),
    "magenta" to Color(0xFFD80073),
    "crimson" to Color(0xFFA20025),
    "red" to Color(0xFFE51400),
    "orange" to Color(0xFFFA6800),
    "amber" to Color(0xFFF0A30A),
    "yellow" to Color(0xFFE3C800),
    "brown" to Color(0xFF825A2C),
    "olive" to Color(0xFF6D8764),
    "steel" to Color(0xFF647687),
    "mauve" to Color(0xFF76608A),
    "taupe" to Color(0xFF87794E),
)

val DarkBackground = Color(0xFF2B2B2B)

@Immutable
data class MetroColors(
    val background: Color,
    val foreground: Color,
    val subtle: Color,
    val accent: Color,
    val chrome: Color,
    val menuBackground: Color,
    val menuForeground: Color,
    val disabled: Color,
    val boxBorder: Color,
    /** Text box fill when not focused. */
    val boxIdle: Color,
    /** Hairline between bars and content (light theme only). */
    val divider: Color,
    /** Page titles, pivot and panorama headers: accent or plain. */
    val title: Color,
    val dark: Boolean,
)

val LocalMetro = staticCompositionLocalOf {
    MetroColors(DarkBackground, Color.White, Color(0xFF999999), Accents[0].second, Color(0xFF393939),
        Color.White, Color.Black, Color(0xFF474747), Color(0xFFBFBFBF), Color(0xFFCCCCCC), Color.Transparent,
        Color.White, true)
}

object Metro {
    val colors: MetroColors @Composable get() = LocalMetro.current
}

fun metroText(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = Segoe, fontSize = size, fontWeight = weight, color = color, lineHeight = size * 1.15f)

object MetroType {
    /** Panorama title, e.g. "music". */
    val panorama = metroText(110.sp, FontWeight.Light)
    /** Pivot header items / page titles. */
    val pivot = metroText(48.sp, FontWeight.Light)
    /** Small all-caps app title above pivots. */
    val appTitle = metroText(15.sp, FontWeight.SemiBold)
    val extraLarge = metroText(36.sp, FontWeight.Light)
    val large = metroText(28.sp, FontWeight.ExtraLight)
    val medium = metroText(22.sp, FontWeight.ExtraLight)
    val normal = metroText(17.sp)
    val small = metroText(14.sp)
}

@Composable
fun TuneTheme(accent: Color, light: Boolean, accentTitles: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (light) MetroColors(
        background = Color.White,
        foreground = Color.Black,
        subtle = Color(0xFF666666),
        accent = accent,
        // Light theme is white all the way: bars, dialogs, menus, text boxes.
        chrome = Color.White,
        menuBackground = Color.White,
        menuForeground = Color.Black,
        disabled = Color(0xFFE6E6E6),
        boxBorder = Color(0xFF999999),
        boxIdle = Color.White,
        divider = Color(0xFFDDDDDD),
        title = if (accentTitles) accent else Color.Black,
        dark = false,
    ) else MetroColors(
        // A very dark grey rather than pure black: easier on the eyes on LCDs.
        background = DarkBackground,
        foreground = Color.White,
        subtle = Color(0xFF999999),
        accent = accent,
        chrome = Color(0xFF393939),
        menuBackground = Color.White,
        menuForeground = Color.Black,
        disabled = Color(0xFF474747),
        boxBorder = Color(0xFFBFBFBF),
        boxIdle = Color(0xFFCCCCCC),
        divider = Color.Transparent,
        title = if (accentTitles) accent else Color.White,
        dark = true,
    )
    val scheme = (if (light) lightColorScheme() else darkColorScheme()).copy(
        primary = accent, background = colors.background, surface = colors.background,
        onBackground = colors.foreground, onSurface = colors.foreground,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalMetro provides colors,
            LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.4f)),
        ) { content() }
    }
}
