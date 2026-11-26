package com.stridecoach.running.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KdeBg = Color(0xFF141618)
val KdeBgDeep = Color(0xFF1A1822)
val KdeNavBg = Color(0xFF202326)
val KdeCardBg = Color(0xFF23262A)
val KdeCardAlt = Color(0xFF292C30)
val KdeBorder = Color(0xFF383D43)
val KdeMuted = Color(0xFF9BA3AC)
val KdePurple = Color(0xFF926EE4)
val KdePurpleSoft = Color(0xFF6C53A6)
val KdeSky = Color(0xFF3DAEE9)
val KdeText = Color(0xFFFCFCFC)
val PaceOnTarget = Color(0xFF4ADE80)
val PaceTooFast = Color(0xFFFBBF24)
val PaceTooSlow = Color(0xFFF87171)

private val KdePalette = darkColorScheme(
    primary = KdePurple, onPrimary = Color(0xFFFFFFFF),
    secondary = KdeSky, onSecondary = Color(0xFF061B29),
    tertiary = PaceTooFast, background = KdeBg,
    surface = KdeCardBg, surfaceVariant = KdeCardAlt,
    onBackground = KdeText, onSurface = KdeText,
    onSurfaceVariant = KdeMuted, outline = KdeBorder
)

@Composable
fun StrideCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KdePalette, content = content)
}
