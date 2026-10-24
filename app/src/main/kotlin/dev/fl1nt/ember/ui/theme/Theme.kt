package dev.fl1nt.ember.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Flare's 0.75rem radius, with larger cards and sheets. */
val EmberShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val LocalEmberColors = staticCompositionLocalOf { emberColors(dark = true) }

object EmberTheme {
    val colors: EmberColors
        @Composable
        @ReadOnlyComposable
        get() = LocalEmberColors.current
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** Flare's look. Dark is the default, as it is for a new Flare instance. */
@Composable
fun EmberTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val colors = emberColors(dark)
    val scheme = colors.palette.toColorScheme(dark)
    CompositionLocalProvider(LocalEmberColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = EmberTypography, shapes = EmberShapes) {
            // Text outside a Surface inherits this, so screens drawn on the page color stay readable.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
