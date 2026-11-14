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

/** Fixed brand colours that keep their meaning regardless of the (possibly dynamic) scheme. */
object Brand {
    val Indigo = Color(0xFF4F5BD5)
    val Violet = Color(0xFF7C5CFC)
    val Teal = Color(0xFF12B5A6)
    val CallGreen = Color(0xFF1FBF6B)
    val HangUpRed = Color(0xFFEF4455)
    val Amber = Color(0xFFF5A524)

    /** Background gradient of the call screen. */
    val CallGradient = listOf(Color(0xFF1B1F4B), Color(0xFF232A6E), Color(0xFF0E3B4A))
    val IntercomGradient = listOf(Color(0xFF3A1B4B), Color(0xFF5B2A6E), Color(0xFF16304A))
}

private val LightColors = lightColorScheme(
    primary = Brand.Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF),
    onPrimaryContainer = Color(0xFF0C1466),
    secondary = Color(0xFF5B5F7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E4F5),
    onSecondaryContainer = Color(0xFF181B33),
    tertiary = Color(0xFF00897B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC5F3EC),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE3E4F0),
    onSurfaceVariant = Color(0xFF464756),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F2F9),
    surfaceContainer = Color(0xFFECEDF6),
    surfaceContainerHigh = Color(0xFFE6E7F1),
    surfaceContainerHighest = Color(0xFFE0E1EC),
    outline = Color(0xFF777889),
    outlineVariant = Color(0xFFC7C8D7),
    error = Color(0xFFBA1A2B),
    errorContainer = Color(0xFFFFDAD9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDC3FF),
    onPrimary = Color(0xFF1B258C),
    primaryContainer = Color(0xFF3540B4),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFC4C6E0),
    onSecondary = Color(0xFF2D3048),
    secondaryContainer = Color(0xFF3B3E57),
    onSecondaryContainer = Color(0xFFE2E4F5),
    tertiary = Color(0xFF5EDCCB),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFFC5F3EC),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE4E4EE),
    surface = Color(0xFF0F1116),
    onSurface = Color(0xFFE4E4EE),
    surfaceVariant = Color(0xFF3A3B49),
    onSurfaceVariant = Color(0xFFC7C8D7),
    surfaceContainerLowest = Color(0xFF0A0B0F),
    surfaceContainerLow = Color(0xFF16181F),
    surfaceContainer = Color(0xFF1A1C24),
    surfaceContainerHigh = Color(0xFF24262F),
    surfaceContainerHighest = Color(0xFF2F313B),
    outline = Color(0xFF9192A2),
    outlineVariant = Color(0xFF464756),
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
    dynamicColor: Boolean = true,
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
