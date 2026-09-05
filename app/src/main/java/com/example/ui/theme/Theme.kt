package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * ============================================================================
 * Smart-workspace theme
 * ============================================================================
 *
 * "Cybernetic workspace" identity: deep slate surfaces, cyan intelligence
 * accent, emerald health, amber warnings. Complete M3 roles + rounded
 * geometric shapes (16-28dp) so every card/dialog/field feels deliberate.
 */

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Slate950,
    primaryContainer = Slate850,
    onPrimaryContainer = CyanPrimary,
    secondary = AmberWarning,
    onSecondary = Slate950,
    secondaryContainer = Color(0xFF3A2E0A),
    onSecondaryContainer = AmberWarning,
    tertiary = EmeraldSuccess,
    onTertiary = Slate950,
    tertiaryContainer = Color(0xFF0A3426),
    onTertiaryContainer = EmeraldSuccess,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate850,
    onSurfaceVariant = Slate400,
    surfaceTint = CyanPrimary,
    inverseSurface = Slate200,
    inverseOnSurface = Slate900,
    outline = Slate700,
    outlineVariant = Color(0xFF334155),
    error = CrimsonError,
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFCA5A5),
    scrim = Color(0xEE000000)
)

private val LightColorScheme = lightColorScheme(
    primary = CyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF92400E),
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF065F46),
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate700,
    surfaceTint = CyanDark,
    inverseSurface = Slate900,
    inverseOnSurface = Slate50,
    outline = Slate400,
    outlineVariant = Color(0xFFCBD5E1),
    error = CrimsonError,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    scrim = Color(0x99000000)
)

private val WorkspaceShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use intentional custom cybernetic palette
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = WorkspaceShapes,
        content = content
    )
}
