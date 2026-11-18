package app.datame.movil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The colours of the web of Datame.
private val Light = lightColorScheme(
    primary = Color(0xFF1F6F54),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EDE3),
    onPrimaryContainer = Color(0xFF0D2B20),
    secondaryContainer = Color(0xFFE4F2EC),
    background = Color(0xFFF4F3EF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEAE3),
    onSurfaceVariant = Color(0xFF5E6660),
    outline = Color(0xFFD4D2CA),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFBE4E1),
    tertiaryContainer = Color(0xFFFBEED3),
    onTertiaryContainer = Color(0xFF6B4600),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5CC39D),
    onPrimary = Color(0xFF0D1F18),
    primaryContainer = Color(0xFF17352A),
    onPrimaryContainer = Color(0xFFA6E3CB),
    secondaryContainer = Color(0xFF17352A),
    background = Color(0xFF121513),
    surface = Color(0xFF1C201E),
    surfaceVariant = Color(0xFF262B28),
    onSurfaceVariant = Color(0xFFA2ABA5),
    outline = Color(0xFF2F3632),
    error = Color(0xFFF0958D),
    errorContainer = Color(0xFF3B1E1B),
    tertiaryContainer = Color(0xFF3D2F12),
    onTertiaryContainer = Color(0xFFF2D59A),
)

@Composable
fun DatameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
