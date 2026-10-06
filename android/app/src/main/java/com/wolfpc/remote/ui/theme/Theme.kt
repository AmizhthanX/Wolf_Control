package com.wolfpc.remote.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// A wolf-grey base with an amber accent. Amber rather than green because the
// primary control on this screen turns a machine on, and green reads as
// "everything is fine" rather than "this does something".
private val WolfAmber = Color(0xFFE8A33D)
private val WolfAmberDark = Color(0xFF8A5A10)
private val WolfSlate = Color(0xFF1C1F24)
private val WolfSlateLight = Color(0xFF2A2F36)
private val WolfDanger = Color(0xFFCF4B3B)

private val DarkScheme = darkColorScheme(
    primary = WolfAmber,
    onPrimary = Color(0xFF1A1206),
    primaryContainer = WolfAmberDark,
    onPrimaryContainer = Color(0xFFFFE6C2),
    secondary = Color(0xFF8FA3B8),
    background = WolfSlate,
    onBackground = Color(0xFFE6E8EB),
    surface = WolfSlateLight,
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF353B44),
    error = WolfDanger,
)

private val LightScheme = lightColorScheme(
    primary = WolfAmberDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0B2),
    onPrimaryContainer = Color(0xFF2B1B00),
    secondary = Color(0xFF4A5A6B),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF171A1D),
    surface = Color.White,
    onSurface = Color(0xFF171A1D),
    error = WolfDanger,
)

private val WolfTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, letterSpacing = 0.6.sp),
)

@Composable
fun WolfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = scheme, typography = WolfTypography, content = content)
}
