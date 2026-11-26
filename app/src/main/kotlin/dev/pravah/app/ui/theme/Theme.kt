package dev.pravah.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** State accents that live outside the Material scheme. */
@Immutable
class PravahColors(val charge: Color, val drain: Color, val idle: Color, val dim: Color, val hairline: Color)

private val LocalPravah = staticCompositionLocalOf {
    PravahColors(ChargeDark, DrainDark, IdleDark, InkTextDim, InkOutline)
}

val MaterialTheme.pravah: PravahColors
    @Composable @ReadOnlyComposable get() = LocalPravah.current

private fun dark(bg: Color, surface: Color) = darkColorScheme(
    primary = ChargeDark, onPrimary = Color(0xFF07240F),
    background = bg, onBackground = InkText,
    surface = bg, onSurface = InkText,
    surfaceVariant = surface, onSurfaceVariant = InkTextDim,
    surfaceContainer = surface, surfaceContainerHigh = surface,
    secondaryContainer = surface, onSecondaryContainer = InkText,
    outline = InkOutline, outlineVariant = InkOutline,
    error = Color(0xFFFF8A80),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = ChargeLight, onPrimary = Color.White,
    background = PaperBackground, onBackground = PaperText,
    surface = PaperBackground, onSurface = PaperText,
    surfaceVariant = PaperSurface, onSurfaceVariant = PaperTextDim,
    surfaceContainer = PaperSurface, surfaceContainerHigh = PaperSurface,
    secondaryContainer = PaperSurface, onSecondaryContainer = PaperText,
    outline = PaperOutline, outlineVariant = PaperOutline,
    error = Color(0xFFB3261E),
)

@Composable
fun PravahTheme(
    amoled: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = when {
        darkTheme && amoled -> dark(Amoled, Color(0xFF0C0D0D))
        darkTheme -> dark(InkBackground, InkSurface)
        else -> LightScheme
    }
    val extra = if (darkTheme) PravahColors(ChargeDark, DrainDark, IdleDark, InkTextDim, if (amoled) Color(0xFF222526) else InkOutline)
    else PravahColors(ChargeLight, DrainLight, IdleLight, PaperTextDim, PaperOutline)

    CompositionLocalProvider(LocalPravah provides extra) {
        MaterialTheme(colorScheme = scheme, typography = PravahTypography, content = content)
    }
}
