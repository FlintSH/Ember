package dev.fl1nt.ember.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Flare's design tokens, copied from `DARK_PALETTE` and `LIGHT_PALETTE` in
 * Flare's `lib/customization/schema.ts` so Ember looks like the dashboard it
 * uploads to.
 */
@Immutable
data class FlarePalette(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
)

val FlareDark = FlarePalette(
    background = Color(0xFF020817),
    foreground = Color(0xFFF8FAFC),
    card = Color(0xFF020817),
    cardForeground = Color(0xFFF8FAFC),
    primary = Color(0xFFF8FAFC),
    primaryForeground = Color(0xFF0F172A),
    secondary = Color(0xFF1E293B),
    secondaryForeground = Color(0xFFF8FAFC),
    muted = Color(0xFF1E293B),
    mutedForeground = Color(0xFF94A3B8),
    accent = Color(0xFF1E293B),
    accentForeground = Color(0xFFF8FAFC),
    destructive = Color(0xFF991B1B),
    destructiveForeground = Color(0xFFF8FAFC),
    border = Color(0xFF1E293B),
    input = Color(0xFF1E293B),
    ring = Color(0xFFCBD5E1),
)

val FlareLight = FlarePalette(
    background = Color(0xFFF8FAFC),
    foreground = Color(0xFF0F172A),
    card = Color(0xFFFFFFFF),
    cardForeground = Color(0xFF0F172A),
    primary = Color(0xFF0F172A),
    primaryForeground = Color(0xFFF8FAFC),
    secondary = Color(0xFFE2E8F0),
    secondaryForeground = Color(0xFF0F172A),
    muted = Color(0xFFF1F5F9),
    mutedForeground = Color(0xFF475569),
    accent = Color(0xFFE2E8F0),
    accentForeground = Color(0xFF0F172A),
    destructive = Color(0xFFDC2626),
    destructiveForeground = Color(0xFFFFFFFF),
    border = Color(0xFFCBD5E1),
    input = Color(0xFFCBD5E1),
    ring = Color(0xFF64748B),
)

/** The flame gradient from Flare's icon. */
object Flame {
    val Amber = Color(0xFFFFC301)
    val Orange = Color(0xFFFF8A00)
    val Red = Color(0xFFFF2B01)
    val Glow = Color(0xFFFFD84D)

    /** Amber at the bottom left to red at the top right, like Flare's mark. */
    fun gradient(width: Float, height: Float): Brush =
        Brush.linearGradient(listOf(Amber, Red), start = Offset(0f, height), end = Offset(width, 0f))

    val horizontal: Brush = Brush.horizontalGradient(listOf(Amber, Orange, Red))
}

/** Colors Ember needs beyond Material's roles. */
@Immutable
data class EmberColors(
    val palette: FlarePalette,
    val isDark: Boolean,
    val success: Color,
    val warning: Color,
    /** The soft glow at the top of Flare's dashboard background. */
    val glow: Color,
)

fun emberColors(dark: Boolean) = EmberColors(
    palette = if (dark) FlareDark else FlareLight,
    isDark = dark,
    success = if (dark) Color(0xFF4ADE80) else Color(0xFF15803D),
    warning = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309),
    glow = if (dark) Color(0xFF14213D) else Color(0xFFE2E8F0),
)

/** Maps Flare's tokens onto Material 3 roles. */
fun FlarePalette.toColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = primaryForeground,
            primaryContainer = secondary,
            onPrimaryContainer = secondaryForeground,
            inversePrimary = primaryForeground,
            secondary = ring,
            onSecondary = primaryForeground,
            secondaryContainer = secondary,
            onSecondaryContainer = secondaryForeground,
            tertiary = Flame.Orange,
            onTertiary = background,
            tertiaryContainer = Color(0xFF3B1A06),
            onTertiaryContainer = Color(0xFFFFD9B8),
            background = background,
            onBackground = foreground,
            surface = card,
            onSurface = cardForeground,
            surfaceVariant = muted,
            onSurfaceVariant = mutedForeground,
            surfaceTint = Color.Transparent,
            inverseSurface = foreground,
            inverseOnSurface = primaryForeground,
            error = Color(0xFFF87171),
            onError = background,
            errorContainer = destructive,
            onErrorContainer = destructiveForeground,
            outline = border,
            outlineVariant = border,
            scrim = Color(0xFF000000),
            surfaceBright = Color(0xFF0F172A),
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = Color(0xFF050D1E),
            surfaceContainer = Color(0xFF081224),
            surfaceContainerHigh = Color(0xFF0C172B),
            surfaceContainerHighest = Color(0xFF111D33),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = primaryForeground,
            primaryContainer = secondary,
            onPrimaryContainer = secondaryForeground,
            inversePrimary = primaryForeground,
            secondary = ring,
            onSecondary = primaryForeground,
            secondaryContainer = secondary,
            onSecondaryContainer = secondaryForeground,
            tertiary = Color(0xFFC2410C),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFEDD5),
            onTertiaryContainer = Color(0xFF7C2D12),
            background = background,
            onBackground = foreground,
            surface = card,
            onSurface = cardForeground,
            surfaceVariant = muted,
            onSurfaceVariant = mutedForeground,
            surfaceTint = Color.Transparent,
            inverseSurface = primary,
            inverseOnSurface = primaryForeground,
            error = destructive,
            onError = destructiveForeground,
            errorContainer = Color(0xFFFEE2E2),
            onErrorContainer = Color(0xFF991B1B),
            outline = border,
            outlineVariant = Color(0xFFE2E8F0),
            scrim = Color(0xFF000000),
            surfaceBright = card,
            surfaceDim = Color(0xFFE2E8F0),
            surfaceContainerLowest = card,
            surfaceContainerLow = Color(0xFFF8FAFC),
            surfaceContainer = Color(0xFFF1F5F9),
            surfaceContainerHigh = Color(0xFFEDF1F6),
            surfaceContainerHighest = Color(0xFFE2E8F0),
        )
    }
