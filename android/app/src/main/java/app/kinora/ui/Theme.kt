package app.kinora.ui

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

// The Kinora violet, used unless the user turns on Material You wallpaper colors in Settings.
private val BrandDark = darkColorScheme(
    primary = Color(0xFFCBBDFF), onPrimary = Color(0xFF2D0A8F),
    primaryContainer = Color(0xFF47349F), onPrimaryContainer = Color(0xFFE6DEFF),
    secondaryContainer = Color(0xFF3E3A5C), onSecondaryContainer = Color(0xFFE3DFFF),
    background = Color(0xFF110F1A), surface = Color(0xFF110F1A),
    surfaceVariant = Color(0xFF47454F), onSurface = Color(0xFFE6E1EC), onSurfaceVariant = Color(0xFFC8C4D0),
    surfaceContainer = Color(0xFF1C1A26), surfaceContainerHigh = Color(0xFF272431), surfaceContainerHighest = Color(0xFF322F3C),
    surfaceContainerLow = Color(0xFF171520),
)
private val BrandLight = lightColorScheme(
    primary = Color(0xFF5B3FD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1A0063),
    secondaryContainer = Color(0xFFE3DFFA), onSecondaryContainer = Color(0xFF1B1830),
    background = Color(0xFFFDF8FF), surface = Color(0xFFFDF8FF),
    surfaceVariant = Color(0xFFE5E0EC), onSurface = Color(0xFF1C1B20), onSurfaceVariant = Color(0xFF48454E),
    surfaceContainer = Color(0xFFF1ECF4), surfaceContainerHigh = Color(0xFFEBE6EE), surfaceContainerHighest = Color(0xFFE6E0E9),
    surfaceContainerLow = Color(0xFFF7F2FA),
)

@Composable
fun TubeTheme(mode: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
