package com.stridecoach.running.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.ui.theme.KdeBg
import com.stridecoach.running.ui.theme.KdeBgDeep
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceTooSlow
import com.stridecoach.running.ui.theme.TabularNumberStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun TelemetryStatStrip(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = KdeCardBg,
        border = BorderStroke(1.dp, KdeBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { (value, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = KdeText,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KdeMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
fun TopTelemetryMapHud(
    speedText: String?,
    timeText: String,
    paceText: String,
    distText: String,
    modifier: Modifier = Modifier
) {
    val entries = buildList {
        if (speedText != null) add(speedText to "Speed")
        add(timeText to "Time")
        add(paceText to "Pace")
        add(distText to "Dist")
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = KdeCardBg.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, KdeBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEachIndexed { idx, (value, label) ->
                val accent = when (idx) {
                    0 -> if (speedText != null) KdeSky else KdeText
                    entries.lastIndex -> KdePurple
                    else -> KdeText
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge.merge(TabularNumberStyle),
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = KdeMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
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
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun FloatingMapIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = KdeText
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = KdeCardBg.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, KdeBorder),
        modifier = Modifier.size(44.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun HoldToFinishButton(
    enabled: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prog by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) PaceTooSlow.copy(alpha = 0.16f) else KdeCardBg,
        border = BorderStroke(1.dp, if (enabled) PaceTooSlow.copy(alpha = 0.6f) else KdeBorder),
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    val job = scope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val steps = 30
                        for (i in 1..steps) {
                            delay(50L)
                            prog = i / steps.toFloat()
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFinished()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    prog = 0f
                }
            )
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                CircularProgressIndicator(
                    progress = { if (prog > 0f) prog else 1f },
                    strokeWidth = 2.5.dp,
                    color = if (prog > 0f) PaceTooSlow else PaceTooSlow.copy(alpha = 0.35f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = if (prog > 0f) "Keep Holding…" else "Hold to Finish",
                color = if (enabled) PaceTooSlow else KdeMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

data class PassiveLocReading(
    val latLon: LatLon,
    val speedKmh: Float = 0f,
    val accMeters: Float? = null
)

@Composable
fun rememberPassiveUserLocation(enabled: Boolean): LatLon? =
    rememberPassiveLocationReading(enabled)?.latLon

@SuppressLint("MissingPermission")
@Composable
fun rememberPassiveLocationReading(enabled: Boolean): PassiveLocReading? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<PassiveLocReading?>(null) }

    DisposableEffect(ctx, enabled) {
        if (!enabled || !hasLocPermission(ctx)) {
            loc = null
            return@DisposableEffect onDispose {}
        }
        val mgr = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (mgr == null) return@DisposableEffect onDispose {}

        val lastGps = runCatching { mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val lastNet = runCatching { mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val init = listOfNotNull(lastGps, lastNet).maxByOrNull { it.time }
        if (init != null) {
            val spd = if (init.hasSpeed()) (init.speed * 3.6f).coerceAtLeast(0f) else 0f
            loc = PassiveLocReading(LatLon(init.latitude, init.longitude), spd, init.accuracy)
        }

        val listener = LocationListener { next: Location ->
            val spd = if (next.hasSpeed()) (next.speed * 3.6f).coerceAtLeast(0f) else 0f
            loc = PassiveLocReading(LatLon(next.latitude, next.longitude), spd, next.accuracy)
        }
        runCatching {
            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4_000L, 5f, listener, Looper.getMainLooper())
            } else if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 6_000L, 10f, listener, Looper.getMainLooper())
            }
        }
        onDispose { runCatching { mgr.removeUpdates(listener) } }
    }
    return loc
}

private fun hasLocPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
