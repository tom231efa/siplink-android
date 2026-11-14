package com.sky.siplink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sky.siplink.core.model.ThemeMode

/** Fixed colours that keep their meaning regardless of the (possibly dynamic) scheme. */
object Brand {
    val CallGreen = Color(0xFF30A46C)
    val HangUpRed = Color(0xFFE5484D)
    val Amber = Color(0xFFF5A524)

    /** The call screen is always dark: its background, and the ink on its white controls. */
    val CallSurface = Color(0xFF121212)
}

// A neutral palette: greys only. Colour is left to what it stands for (call, hang up, warning).
private val LightColors = lightColorScheme(
    primary = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E7E7),
    onPrimaryContainer = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFFE0E0E0),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF1C1C1C),
    tertiary = Color(0xFF4A4A4A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFEFEF),
    onTertiaryContainer = Color(0xFF1C1C1C),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEBEBEB),
    onSurfaceVariant = Color(0xFF5C5C5C),
    inverseSurface = Color(0xFF2B2B2B),
    inverseOnSurface = Color(0xFFF2F2F2),
    surfaceBright = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFDADADA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFD6D6D6),
    error = Color(0xFFBA1A2B),
    errorContainer = Color(0xFFFFDAD9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0F0F0),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color(0xFFF0F0F0),
    inversePrimary = Color(0xFF1C1C1E),
    secondary = Color(0xFFC8C8C8),
    onSecondary = Color(0xFF1E1E1E),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF1E1E1E),
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color(0xFFEAEAEA),
    background = Color(0xFF101010),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFC2C2C2),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    surfaceBright = Color(0xFF333333),
    surfaceDim = Color(0xFF101010),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2D2D2D),
    outline = Color(0xFF8F8F8F),
    outlineVariant = Color(0xFF444444),
    error = Color(0xFFFFB3B3),
    errorContainer = Color(0xFF93000F),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun SipLinkTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
