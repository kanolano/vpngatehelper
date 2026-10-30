package com.tb.vpngate.ui

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

// Deep green ground with a bright mint accent, taken from the launcher icon so the app and its
// icon read as the same thing. Green also carries the "tunnel up" signal without needing a label.
private val Mint = Color(0xFF5EE3A1)
private val MintDark = Color(0xFF0B3D2E)
private val Amber = Color(0xFFE3B65E)
private val Crimson = Color(0xFFE35E6B)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00371F),
    primaryContainer = Color(0xFF13513C),
    onPrimaryContainer = Mint,
    secondary = Color(0xFF9ECFBA),
    background = Color(0xFF0D1412),
    onBackground = Color(0xFFE2E8E5),
    surface = Color(0xFF131C19),
    onSurface = Color(0xFFE2E8E5),
    surfaceVariant = Color(0xFF1E2A26),
    onSurfaceVariant = Color(0xFFB4C3BC),
    error = Crimson,
    tertiary = Amber,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF13694A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F0D3),
    onPrimaryContainer = MintDark,
    secondary = Color(0xFF4C6358),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF171D1A),
    surface = Color.White,
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF414944),
    error = Color(0xFFB3261E),
    tertiary = Color(0xFF7A5900),
)

@Composable
fun VpnGateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You, where available; the brand palette is the fallback. */
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
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Semantic colours for connection state, which must not be remapped by Material You. */
object StateColors {
    val connected = Mint
    val connecting = Amber
    val failed = Crimson
}
