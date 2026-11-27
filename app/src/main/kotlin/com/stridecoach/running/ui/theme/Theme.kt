package com.stridecoach.running.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

enum class AccentOption(
    val label: String,
    val primary: Color,
    val secondary: Color,
    val soft: Color
) {
    KDE_PURPLE("KDE Purple", Color(0xFF926EE4), Color(0xFF3DAEE9), Color(0xFF6C53A6)),
    OCEAN_SKY("Ocean Sky", Color(0xFF3DAEE9), Color(0xFF4ADE80), Color(0xFF2878A8)),
    EMERALD("Emerald", Color(0xFF22C55E), Color(0xFF3DAEE9), Color(0xFF15803D)),
    AMBER_CORAL("Amber", Color(0xFFF59E0B), Color(0xFFF87171), Color(0xFFB45309)),
    CRIMSON("Crimson", Color(0xFFEF4444), Color(0xFF926EE4), Color(0xFFB91C1C))
}

data class StridePalette(
    val bg: Color,
    val bgDeep: Color,
    val navBg: Color,
    val cardBg: Color,
    val cardAlt: Color,
    val border: Color,
    val muted: Color,
    val purple: Color,
    val purpleSoft: Color,
    val sky: Color,
    val text: Color,
    val isLight: Boolean
)

private fun buildDarkPalette(acc: AccentOption) = StridePalette(
    bg = Color(0xFF121417),
    bgDeep = Color(0xFF16191D),
    navBg = Color(0xFF16191D),
    cardBg = Color(0xFF1E2126),
    cardAlt = Color(0xFF252930),
    border = Color(0xFF333840),
    muted = Color(0xFF9BA3AC),
    purple = acc.primary,
    purpleSoft = acc.soft,
    sky = acc.secondary,
    text = Color(0xFFFCFCFC),
    isLight = false
)

private fun buildLightPalette(acc: AccentOption) = StridePalette(
    bg = Color(0xFFF4F6FA),
    bgDeep = Color(0xFFEAEEF5),
    navBg = Color(0xFFFFFFFF),
    cardBg = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFEDF1F7),
    border = Color(0xFFD5DCE6),
    muted = Color(0xFF5A6472),
    purple = acc.primary,
    purpleSoft = acc.soft,
    sky = acc.secondary,
    text = Color(0xFF141820),
    isLight = true
)

object StrideThemeState {
    var palette by mutableStateOf(buildDarkPalette(AccentOption.KDE_PURPLE))
    var isLight by mutableStateOf(false)
}

val KdeBg: Color get() = StrideThemeState.palette.bg
val KdeBgDeep: Color get() = StrideThemeState.palette.bgDeep
val KdeNavBg: Color get() = StrideThemeState.palette.navBg
val KdeCardBg: Color get() = StrideThemeState.palette.cardBg
val KdeCardAlt: Color get() = StrideThemeState.palette.cardAlt
val KdeBorder: Color get() = StrideThemeState.palette.border
val KdeMuted: Color get() = StrideThemeState.palette.muted
val KdePurple: Color get() = StrideThemeState.palette.purple
val KdePurpleSoft: Color get() = StrideThemeState.palette.purpleSoft
val KdeSky: Color get() = StrideThemeState.palette.sky
val KdeText: Color get() = StrideThemeState.palette.text

val Zone1Sky = Color(0xFF3DAEE9)
val Zone2Green = Color(0xFF4ADE80)
val Zone3Purple = Color(0xFF926EE4)
val Zone4Amber = Color(0xFFFBBF24)
val Zone5Coral = Color(0xFFF87171)

val PaceOnTarget = Zone2Green
val PaceTooFast = Zone4Amber
val PaceTooSlow = Zone5Coral

val TabularNumberStyle = TextStyle(fontFeatureSettings = "tnum")

fun paceZoneColor(zone: Int): Color = when (zone) {
    1 -> Zone1Sky
    2 -> Zone2Green
    3 -> Zone3Purple
    4 -> Zone4Amber
    else -> Zone5Coral
}

@Composable
fun StrideCoachTheme(
    themeMode: String = "DARK",
    accentName: String = "KDE_PURPLE",
    content: @Composable () -> Unit
) {
    val sysDark = isSystemInDarkTheme()
    val useLight = when (themeMode) {
        "LIGHT" -> true
        "DARK" -> false
        else -> !sysDark
    }
    val acc = AccentOption.entries.firstOrNull { it.name == accentName } ?: AccentOption.KDE_PURPLE
    val pal = if (useLight) buildLightPalette(acc) else buildDarkPalette(acc)
    StrideThemeState.palette = pal
    StrideThemeState.isLight = useLight

    val scheme = if (useLight) {
        lightColorScheme(
            primary = pal.purple,
            onPrimary = Color.White,
            secondary = pal.sky,
            onSecondary = Color.White,
            tertiary = Zone4Amber,
            background = pal.bg,
            surface = pal.cardBg,
            surfaceVariant = pal.cardAlt,
            onBackground = pal.text,
            onSurface = pal.text,
            onSurfaceVariant = pal.muted,
            outline = pal.border
        )
    } else {
        darkColorScheme(
            primary = pal.purple,
            onPrimary = Color.White,
            secondary = pal.sky,
            onSecondary = Color(0xFF061B29),
            tertiary = Zone4Amber,
            background = pal.bg,
            surface = pal.cardBg,
            surfaceVariant = pal.cardAlt,
            onBackground = pal.text,
            onSurface = pal.text,
            onSurfaceVariant = pal.muted,
            outline = pal.border
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
