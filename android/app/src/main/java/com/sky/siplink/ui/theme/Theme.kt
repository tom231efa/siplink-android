package com.sky.siplink.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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

    /** The dark background, also of the always dark call screen, and the ink on white controls. */
    val CallSurface = Color(0xFF121417)

    /** The one accent of the dark scheme. */
    val Blue = Color(0xFF6AA5FF)
}

// Light: greys only. Colour is left to what it stands for (call, hang up, warning).
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

// Dark: a soft, slightly cool black (never pure black) with one blue accent.
private val DarkColors = darkColorScheme(
    primary = Brand.Blue,
    onPrimary = Color(0xFF06224A),
    primaryContainer = Color(0xFF1B3F78),
    onPrimaryContainer = Color(0xFFD7E6FF),
    inversePrimary = Brand.Blue,
    secondary = Color(0xFFA9B4C6),
    onSecondary = Color(0xFF1A2330),
    secondaryContainer = Color(0xFF2A3442),
    onSecondaryContainer = Color(0xFFDCE4F0),
    tertiary = Color(0xFF9DB7DE),
    onTertiary = Color(0xFF142235),
    tertiaryContainer = Color(0xFF26364C),
    onTertiaryContainer = Color(0xFFD9E6F8),
    background = Brand.CallSurface,
    onBackground = Color(0xFFE6E8EB),
    surface = Brand.CallSurface,
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF2B3037),
    onSurfaceVariant = Color(0xFFB4BAC4),
    // The snackbar: a raised dark bar with light text. A near-white bar glares on the soft black.
    inverseSurface = Color(0xFF30353D),
    inverseOnSurface = Color(0xFFE6E8EB),
    surfaceBright = Color(0xFF2B3037),
    surfaceDim = Brand.CallSurface,
    surfaceContainerLowest = Color(0xFF0D0F12),
    surfaceContainerLow = Color(0xFF171A1E),
    surfaceContainer = Color(0xFF1B1E23),
    surfaceContainerHigh = Color(0xFF22262C),
    surfaceContainerHighest = Color(0xFF2B3037),
    outline = Color(0xFF7D8590),
    outlineVariant = Color(0xFF3A4048),
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

/** The app's own scheme, light or [dark]. */
internal fun appColorScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/**
 * The scheme of the "dynamic colour" switch: the accent, the primary roles, comes from [system]
 * (the colours of the wallpaper), everything else from the app's [own] scheme. A page then has
 * the same tones as with the switch off: the system's own surfaces would give its body, top bar
 * and tab bar three different tones, a dead black in the dark, and turn neutral buttons lavender.
 * inversePrimary stays the app's own as well: it goes with the app's own inverseSurface.
 */
internal fun withSystemAccent(own: ColorScheme, system: ColorScheme): ColorScheme = own.copy(
    primary = system.primary,
    onPrimary = system.onPrimary,
    primaryContainer = system.primaryContainer,
    onPrimaryContainer = system.onPrimaryContainer,
)

@Composable
fun SipLinkTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val own = appColorScheme(dark)
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        withSystemAccent(own, if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    } else {
        own
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
