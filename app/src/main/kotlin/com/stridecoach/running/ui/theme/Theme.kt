package com.stridecoach.running.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

val KdeBg = Color(0xFF121417)
val KdeBgDeep = Color(0xFF16191D)
val KdeNavBg = Color(0xFF16191D)
val KdeCardBg = Color(0xFF1E2126)
val KdeCardAlt = Color(0xFF252930)
val KdeBorder = Color(0xFF333840)
val KdeMuted = Color(0xFF9BA3AC)
val KdePurple = Color(0xFF926EE4)
val KdePurpleSoft = Color(0xFF6C53A6)
val KdeSky = Color(0xFF3DAEE9)
val KdeText = Color(0xFFFCFCFC)

val Zone1Green = Color(0xFF4ADE80)
val Zone2Mint = Color(0xFF86EFAC)
val Zone3Amber = Color(0xFFFBBF24)
val Zone4Coral = Color(0xFFF87171)
val Zone5Red = Color(0xFFEF4444)

val PaceOnTarget = Zone1Green
val PaceTooFast = Zone3Amber
val PaceTooSlow = Zone4Coral

val TabularNumberStyle = TextStyle(fontFeatureSettings = "tnum")

fun paceZoneColor(zone: Int): Color = when (zone) {
    1 -> Zone1Green
    2 -> Zone2Mint
    3 -> Zone3Amber
    4 -> Zone4Coral
    else -> Zone5Red
}

private val KdePalette = darkColorScheme(
    primary = KdePurple,
    onPrimary = Color(0xFFFFFFFF),
    secondary = KdeSky,
    onSecondary = Color(0xFF061B29),
    tertiary = Zone3Amber,
    background = KdeBg,
    surface = KdeCardBg,
    surfaceVariant = KdeCardAlt,
    onBackground = KdeText,
    onSurface = KdeText,
    onSurfaceVariant = KdeMuted,
    outline = KdeBorder
)

@Composable
fun StrideCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KdePalette, content = content)
}
