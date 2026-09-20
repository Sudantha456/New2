package com.app.youtube.lite.ui.theme

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * The palette: YouTube's own colours, flattened to the few roles Material 3 actually uses.
 *
 * Two deliberate departures from YouTube's app:
 *  • the "surface" is a true near-black (#0F0F0F) rather than dark grey. On OLED panels that is
 *    the difference between the display drawing pixels and drawing nothing — measurably less
 *    power over a long video, which is half the point of this app;
 *  • accents are not tinted. YouTube red (#FF0033) stays saturated in every state so the
 *    play/progress affordances read instantly against black.
 */
private val Red = Color(0xFFFF0033)
private val RedDark = Color(0xFFCC0029)
private val RedLightContainer = Color(0xFF3D000C)

private val DarkColors = darkColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = RedDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFAAAAAA),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFF3EA6FF),
    onTertiary = Color(0xFF001B33),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF262626),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF272727),
    error = Color(0xFFFF5449),
    onError = Color(0xFF1A0000),
    scrim = Color(0xCC000000),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = RedDark,
)

private val LightColors = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1E5),
    onPrimaryContainer = Color(0xFF3D000C),
    secondary = Color(0xFF606060),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF065FD4),
    onTertiary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF606060),
    surfaceContainer = Color(0xFFF9F9F9),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFE9E9E9),
    outline = Color(0xFFC7C7C7),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * Typography: the platform's own font, tightened.
 *
 * No font files are shipped. Downloading and unpacking a webfont costs several MB of APK, a
 * few milliseconds of start-up and — on most devices — renders *worse* than the system font,
 * which is already hinted for the display's pixel density.
 *
 * `letterSpacing` is capped at 0 for the display styles: Material 3's default tracking is
 * designed for its own Roboto Flex metrics and looks loose in a dense video list.
 */
private val LiteTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * The app theme.
 *
 * Dynamic colour is intentionally **off** by default: it is a nice system feature, but it
 * repaints every accent in the app with a wallpaper-derived colour, which for a video client
 * means the play button and progress bar stop being red and stop being recognisable. It stays
 * available for anyone who wants it (it is a single flag), but the default identity is YouTube's.
 */
@Composable
fun LiteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                // Icon colour has to follow the scheme, not the system: a user on a light system
                // theme with the app forced dark would otherwise get unreadable status-bar icons.
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LiteTypography,
        content = content,
    )
}
