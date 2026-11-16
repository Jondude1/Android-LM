package com.jondude1.androidlm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF00201B),
    secondary = Color(0xFF93C5FD), tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0F1115), surface = Color(0xFF0F1115),
    surfaceVariant = Color(0xFF1A1E26), onSurfaceVariant = Color(0xFFB8C0CC),
    surfaceContainer = Color(0xFF161A21), primaryContainer = Color(0xFF0F3B36), onPrimaryContainer = Color(0xFFB8F5EA),
    errorContainer = Color(0xFF4A1D1D), onErrorContainer = Color(0xFFFFD9D9),
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F766E), secondary = Color(0xFF1D4ED8), tertiary = Color(0xFFB45309),
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF003731),
)

@Composable
fun AndroidLmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
