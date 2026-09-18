package io.github.zero6689.tailnetbyok.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Palette.
 *
 * Chosen to read as "network tooling" rather than "consumer app": a cool slate
 * base with one accent. The accent carries meaning rather than decoration —
 * [Accent] marks anything the app has verified (a running node, a passed check),
 * and [Warning] marks the states where something is legal but fragile, which in
 * this app means a bare MagicDNS name or a self-signed certificate.
 */
private val Slate900 = Color(0xFF0E1420)
private val Slate800 = Color(0xFF161D2C)
private val Slate700 = Color(0xFF1F2839)
private val Slate200 = Color(0xFFC7D0DE)
private val Slate100 = Color(0xFFE6EAF1)

internal val Accent = Color(0xFF3DDC97)
internal val AccentDark = Color(0xFF1FA271)
internal val Warning = Color(0xFFE8B339)
internal val Danger = Color(0xFFE0655F)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Slate900,
    primaryContainer = AccentDark,
    onPrimaryContainer = Slate100,
    secondary = Color(0xFF8FB4D9),
    onSecondary = Slate900,
    background = Slate900,
    onBackground = Slate100,
    surface = Slate800,
    onSurface = Slate100,
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate200,
    error = Danger,
    onError = Slate900,
)

private val LightScheme = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = Color(0xFF3C6E9F),
    onSecondary = Color.White,
    background = Color(0xFFF6F8FB),
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Color(0xFFE3E9F1),
    onSurfaceVariant = Color(0xFF44506A),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

@Composable
fun TailnetByokTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You. On by default because a network tool that clashes with the
     * user's wallpaper is a tool they open less often, and the palette above is
     * only a fallback for older devices.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
