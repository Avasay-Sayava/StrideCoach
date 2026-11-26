package com.stridecoach.running.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RunBlack = Color(0xFF080707)
val RunNavBg = Color(0xFF0F1210)
val RunCardBg = Color(0xFF161917)
val RunBorder = Color(0xFF2A2F2C)
val RunMuted = Color(0xFF8E9A93)
val RunGreen = Color(0xFF00F15E)
val PaceOnTarget = Color(0xFF00F15E)
val PaceTooFast = Color(0xFFF59E0B)
val PaceTooSlow = Color(0xFFEF4444)
val WalkLoadAccent = Color(0xFF38BDF8)

private val DarkPalette = darkColorScheme(
    primary = RunGreen, onPrimary = Color(0xFF04210E),
    secondary = WalkLoadAccent, onSecondary = Color(0xFF031D2B),
    tertiary = PaceTooFast, background = RunBlack,
    surface = RunCardBg, surfaceVariant = Color(0xFF202522),
    onBackground = Color(0xFFFAFAFA), onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = RunMuted, outline = RunBorder
)

@Composable
fun StrideCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkPalette, content = content)
}
