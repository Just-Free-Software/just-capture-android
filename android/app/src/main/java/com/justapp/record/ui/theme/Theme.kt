package com.justapp.record.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color.Black,
    secondary = Color(0xFFCCC2DC),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFAAAAAA)
)

@Composable
fun JustTheme(content: @Composable () -> Unit) {
    // The iOS app forces .dark; do the same regardless of system setting.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
