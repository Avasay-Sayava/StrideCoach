package com.stridecoach.running.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeBgDeep
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdeText

@Composable
fun KdeAppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val bgBrush = Brush.verticalGradient(listOf(KdeBgDeep, KdeBg, KdeBg))
    CompositionLocalProvider(LocalContentColor provides fg) {
        Box(modifier = modifier.fillMaxSize().background(bgBrush), content = content)
    }
}

@Composable
fun StrideCard(
    modifier: Modifier = Modifier,
    borderColor: Color = KdeBorder,
    bgColor: Color = KdeCardBg,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun KpiStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = KdeText
) {
    StrideCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = KdeMuted)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
