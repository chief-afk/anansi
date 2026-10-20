package com.chief.anansi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.chief.anansi.settings.SeedTheme

// Shared neutral surfaces, hand-tuned against a violet baseline
// (dark: hand-tuned tokens; light: M3 baseline with the designed surface #FEF7FF).
// Neutral surfaces are achromatic, so every seed reuses them.

private val DarkSurface = Color(0xFF131218)
private val DarkOnSurface = Color(0xFFE6E1E9)
private val DarkOnSurfaceVariant = Color(0xFFCAC4D4)
private val DarkSurfaceLowest = Color(0xFF0E0D12)
private val DarkSurfaceLow = Color(0xFF1B1A21)
private val DarkSurfaceContainer = Color(0xFF1F1E26)
private val DarkSurfaceHigh = Color(0xFF2A2932)
private val DarkSurfaceHighest = Color(0xFF35343E)
private val DarkSurfaceBright = Color(0xFF3B3944)
private val DarkOutline = Color(0xFF948F99)
private val DarkOutlineVariant = Color(0xFF49454F)
private val DarkError = Color(0xFFF2B8B5)
private val DarkOnError = Color(0xFF601410)
private val DarkErrorContainer = Color(0xFF8C1D18)
private val DarkOnErrorContainer = Color(0xFFF9DEDC)
private val DarkInverseSurface = Color(0xFFE6E1E9)
private val DarkInverseOnSurface = Color(0xFF313033)
private val Scrim = Color(0xFF000000)

private val LightSurface = Color(0xFFFEF7FF)
private val LightOnSurface = Color(0xFF1D1B20)
private val LightOnSurfaceVariant = Color(0xFF49454F)
private val LightSurfaceLowest = Color(0xFFFFFFFF)
private val LightSurfaceLow = Color(0xFFF7F2FA)
private val LightSurfaceContainer = Color(0xFFF3EDF7)
private val LightSurfaceHigh = Color(0xFFECE6F0)
private val LightSurfaceHighest = Color(0xFFE6E0E9)
private val LightSurfaceBright = Color(0xFFFEF7FF)
private val LightSurfaceDim = Color(0xFFDED8E1)
private val LightOutline = Color(0xFF79747E)
private val LightOutlineVariant = Color(0xFFCAC4D7)
private val LightError = Color(0xFFB3261E)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFF9DEDC)
private val LightOnErrorContainer = Color(0xFF410E0B)
private val LightInverseSurface = Color(0xFF322F35)
private val LightInverseOnSurface = Color(0xFFF5EFF7)

// Seed-varying roles. Violet carries the exact board/M3 baseline values; the
// other six seeds are static tonal derivations of the board's swatch anchors.

private fun violetLight() = seedScheme(
    dark = false,
    primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
    inversePrimary = Color(0xFFD0BCFF),
)

private fun violetDark() = seedScheme(
    dark = true,
    primary = Color(0xFFCFBCFF), onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
    inversePrimary = Color(0xFF6750A4),
)

private fun lavenderLight() = seedScheme(
    dark = false,
    primary = Color(0xFF503B71), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4D6FE), onPrimaryContainer = Color(0xFF120028),
    secondary = Color(0xFF4A4457), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1DAF0), onSecondaryContainer = Color(0xFF0D0816),
    tertiary = Color(0xFF66354D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFCEE3), onTertiaryContainer = Color(0xFF1E000F),
    inversePrimary = Color(0xFFC7B1F0),
)

private fun lavenderDark() = seedScheme(
    dark = true,
    primary = Color(0xFFC7B1F0), onPrimary = Color(0xFF1E0538),
    primaryContainer = Color(0xFF362054), onPrimaryContainer = Color(0xFFE4D6FE),
    secondary = Color(0xFFC1B9D0), onSecondary = Color(0xFF181322),
    secondaryContainer = Color(0xFF302A3C), onSecondaryContainer = Color(0xFFE1DAF0),
    tertiary = Color(0xFFE5AAC4), onTertiary = Color(0xFF2D021B),
    tertiaryContainer = Color(0xFF491B33), onTertiaryContainer = Color(0xFFFFCEE3),
    inversePrimary = Color(0xFF503B71),
)

private fun midnightDuskLight() = seedScheme(
    dark = false,
    primary = Color(0xFF4B3B78), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFD8FF), onPrimaryContainer = Color(0xFF10002C),
    secondary = Color(0xFF484459), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFDAF4), onSecondaryContainer = Color(0xFF0C0818),
    tertiary = Color(0xFF673353), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFECDE9), onTertiaryContainer = Color(0xFF1D0012),
    inversePrimary = Color(0xFFC1B1F9),
)

private fun midnightDuskDark() = seedScheme(
    dark = true,
    primary = Color(0xFFC1B1F9), onPrimary = Color(0xFF1B043E),
    primaryContainer = Color(0xFF32205A), onPrimaryContainer = Color(0xFFDFD8FF),
    secondary = Color(0xFFBEBAD3), onSecondary = Color(0xFF171324),
    secondaryContainer = Color(0xFF2F2A3E), onSecondaryContainer = Color(0xFFDFDAF4),
    tertiary = Color(0xFFE6A8CC), onTertiary = Color(0xFF2E001F),
    tertiaryContainer = Color(0xFF4A1938), onTertiaryContainer = Color(0xFFFECDE9),
    inversePrimary = Color(0xFF4B3B78),
)

private fun strawberryLight() = seedScheme(
    dark = false,
    primary = Color(0xFF831335), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD0D6), onPrimaryContainer = Color(0xFF200005),
    secondary = Color(0xFF603C41), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFED1D6), onSecondaryContainer = Color(0xFF1C0206),
    tertiary = Color(0xFF6B3900), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD5B3), onTertiaryContainer = Color(0xFF170600),
    inversePrimary = Color(0xFFFF9DAC),
)

private fun strawberryDark() = seedScheme(
    dark = true,
    primary = Color(0xFFFF9DAC), onPrimary = Color(0xFF32000D),
    primaryContainer = Color(0xFF5B001F), onPrimaryContainer = Color(0xFFFFD0D6),
    secondary = Color(0xFFDCB1B6), onSecondary = Color(0xFF280A11),
    secondaryContainer = Color(0xFF432228), onSecondaryContainer = Color(0xFFFED1D6),
    tertiary = Color(0xFFF7AA68), onTertiary = Color(0xFF260F00),
    tertiaryContainer = Color(0xFF472400), onTertiaryContainer = Color(0xFFFFD5B3),
    inversePrimary = Color(0xFF831335),
)

private fun takoLight() = seedScheme(
    dark = false,
    primary = Color(0xFF4B3588), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDED9FF), onPrimaryContainer = Color(0xFF0F002D),
    secondary = Color(0xFF48435F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDED9FB), onSecondaryContainer = Color(0xFF0C071D),
    tertiary = Color(0xFF6E2A59), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFCDEB), onTertiaryContainer = Color(0xFF1D0013),
    inversePrimary = Color(0xFFBEB1FE),
)

private fun takoDark() = seedScheme(
    dark = true,
    primary = Color(0xFFBEB1FE), onPrimary = Color(0xFF1B0044),
    primaryContainer = Color(0xFF321669), onPrimaryContainer = Color(0xFFDED9FF),
    secondary = Color(0xFFBDB9DA), onSecondary = Color(0xFF171129),
    secondaryContainer = Color(0xFF2E2943), onSecondaryContainer = Color(0xFFDED9FB),
    tertiary = Color(0xFFF0A0D3), onTertiary = Color(0xFF2E0021),
    tertiaryContainer = Color(0xFF500C3E), onTertiaryContainer = Color(0xFFFFCDEB),
    inversePrimary = Color(0xFF4B3588),
)

private fun tealTurquoiseLight() = seedScheme(
    dark = false,
    primary = Color(0xFF04544B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8CF4E4), onPrimaryContainer = Color(0xFF000F0C),
    secondary = Color(0xFF324E49), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC5E6E0), onSecondaryContainer = Color(0xFF000F0C),
    tertiary = Color(0xFF034E6C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB9E6FE), onTertiaryContainer = Color(0xFF000D17),
    inversePrimary = Color(0xFF6AD3C3),
)

private fun tealTurquoiseDark() = seedScheme(
    dark = true,
    primary = Color(0xFF6AD3C3), onPrimary = Color(0xFF001B17),
    primaryContainer = Color(0xFF003630), onPrimaryContainer = Color(0xFF8CF4E4),
    secondary = Color(0xFFA6C6BF), onSecondary = Color(0xFF001B17),
    secondaryContainer = Color(0xFF17342F), onSecondaryContainer = Color(0xFFC5E6E0),
    tertiary = Color(0xFF84C8EC), onTertiary = Color(0xFF001926),
    tertiaryContainer = Color(0xFF003347), onTertiaryContainer = Color(0xFFB9E6FE),
    inversePrimary = Color(0xFF04544B),
)

private fun yotsubaLight() = seedScheme(
    dark = false,
    primary = Color(0xFF7D2601), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFED3C5), onPrimaryContainer = Color(0xFF1E0100),
    secondary = Color(0xFF613D32), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD3C4), onSecondaryContainer = Color(0xFF1C0200),
    tertiary = Color(0xFF594503), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEDA7D), onTertiaryContainer = Color(0xFF120A00),
    inversePrimary = Color(0xFFFEA385),
)

private fun yotsubaDark() = seedScheme(
    dark = true,
    primary = Color(0xFFFEA385), onPrimary = Color(0xFF2E0600),
    primaryContainer = Color(0xFF541500), onPrimaryContainer = Color(0xFFFED3C5),
    secondary = Color(0xFFDDB3A5), onSecondary = Color(0xFF290B03),
    secondaryContainer = Color(0xFF452419), onSecondaryContainer = Color(0xFFFFD3C4),
    tertiary = Color(0xFFE0B94F), onTertiary = Color(0xFF1E1500),
    tertiaryContainer = Color(0xFF3B2B00), onTertiaryContainer = Color(0xFFFEDA7D),
    inversePrimary = Color(0xFF7D2601),
)

// Swatch anchors from the designed appearance screen.
fun SeedTheme.swatchColor(): Color = when (this) {
    SeedTheme.VIOLET -> Color(0xFF6750A4)
    SeedTheme.LAVENDER -> Color(0xFFB39DDB)
    SeedTheme.MIDNIGHT_DUSK -> Color(0xFF5B4B8A)
    SeedTheme.STRAWBERRY -> Color(0xFFC9586E)
    SeedTheme.TAKO -> Color(0xFF7E6BC4)
    SeedTheme.TEAL_TURQUOISE -> Color(0xFF00897B)
    SeedTheme.YOTSUBA -> Color(0xFFD2603A)
}

fun SeedTheme.colorScheme(darkTheme: Boolean): ColorScheme = when (this) {
    SeedTheme.VIOLET -> if (darkTheme) violetDark() else violetLight()
    SeedTheme.LAVENDER -> if (darkTheme) lavenderDark() else lavenderLight()
    SeedTheme.MIDNIGHT_DUSK -> if (darkTheme) midnightDuskDark() else midnightDuskLight()
    SeedTheme.STRAWBERRY -> if (darkTheme) strawberryDark() else strawberryLight()
    SeedTheme.TAKO -> if (darkTheme) takoDark() else takoLight()
    SeedTheme.TEAL_TURQUOISE -> if (darkTheme) tealTurquoiseDark() else tealTurquoiseLight()
    SeedTheme.YOTSUBA -> if (darkTheme) yotsubaDark() else yotsubaLight()
}

private fun seedScheme(
    dark: Boolean,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    onTertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    inversePrimary: Color,
): ColorScheme {
    val surface = if (dark) DarkSurface else LightSurface
    val onSurface = if (dark) DarkOnSurface else LightOnSurface
    val error = if (dark) DarkError else LightError
    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = if (dark) DarkSurfaceHigh else LightSurfaceContainer,
        onSurfaceVariant = if (dark) DarkOnSurfaceVariant else LightOnSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = if (dark) DarkInverseSurface else LightInverseSurface,
        inverseOnSurface = if (dark) DarkInverseOnSurface else LightInverseOnSurface,
        error = error,
        onError = if (dark) DarkOnError else LightOnError,
        errorContainer = if (dark) DarkErrorContainer else LightErrorContainer,
        onErrorContainer = if (dark) DarkOnErrorContainer else LightOnErrorContainer,
        outline = if (dark) DarkOutline else LightOutline,
        outlineVariant = if (dark) DarkOutlineVariant else LightOutlineVariant,
        scrim = Scrim,
        surfaceBright = if (dark) DarkSurfaceBright else LightSurfaceBright,
        surfaceDim = if (dark) DarkSurface else LightSurfaceDim,
        surfaceContainerLowest = if (dark) DarkSurfaceLowest else LightSurfaceLowest,
        surfaceContainerLow = if (dark) DarkSurfaceLow else LightSurfaceLow,
        surfaceContainer = if (dark) DarkSurfaceContainer else LightSurfaceContainer,
        surfaceContainerHigh = if (dark) DarkSurfaceHigh else LightSurfaceHigh,
        surfaceContainerHighest = if (dark) DarkSurfaceHighest else LightSurfaceHighest,
    )
}
