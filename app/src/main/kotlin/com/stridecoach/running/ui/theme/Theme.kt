package com.stridecoach.running.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PaceOnTarget = Color(0xFF10B981)
val PaceTooFast = Color(0xFFF59E0B)
val PaceTooSlow = Color(0xFFEF4444)
val WalkLoadAccent = Color(0xFF38BDF8)

private val StrideDarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF06281E),
    secondary = Color(0xFF38BDF8),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF1F5F9)
)

@Composable
fun StrideCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StrideDarkColors,
        content = content
    )
}
