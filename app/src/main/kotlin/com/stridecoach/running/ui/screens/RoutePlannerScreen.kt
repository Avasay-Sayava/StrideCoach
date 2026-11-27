package com.stridecoach.running.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.data.routing.OsmFootRouter
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.model.formatDuration
import com.stridecoach.running.ui.components.FloatingMapIconButton
import com.stridecoach.running.ui.components.MapCameraMode
import com.stridecoach.running.ui.components.VectorRouteMap
import com.stridecoach.running.ui.theme.KdeBorder
import com.stridecoach.running.ui.theme.KdeCardBg
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.KdeSky
import com.stridecoach.running.ui.theme.KdeText
import com.stridecoach.running.ui.theme.PaceOnTarget
import com.stridecoach.running.ui.theme.TabularNumberStyle
import kotlinx.coroutines.launch

private data class PlannedLeg(val end: LatLon, val coords: List<LatLon>)

@Composable
fun RoutePlannerScreen(
    refPaceSec: Int,
    prefs: CoachPreferences,
    userLocation: LatLon?,
    fallbackCenter: LatLon = LatLon(32.1133, 34.8044),
    onBack: () -> Unit,
    onLaunchRoute: (List<LatLon>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val legs = remember { mutableStateListOf<PlannedLeg>() }
    var snapStreets by remember { mutableStateOf(true) }
    var camMode by remember { mutableStateOf(MapCameraMode.FREE_PAN) }
    var is3d by remember(prefs.defaultMap3dEnabled) { mutableStateOf(prefs.defaultMap3dEnabled) }
    var routing by remember { mutableStateOf(false) }
    var recenterTick by remember { mutableIntStateOf(0) }

    val fullPath = remember(legs.size) { flattenLegs(legs) }
    val waypoints = remember(legs.size) { legs.map { it.end } }
    val distM = remember(fullPath) { pathDistanceMeters(fullPath) }
    val estMs = ((distM / 1000.0) * refPaceSec * 1000.0).toLong()

    fun appendWaypoint(pt: LatLon) {
        val prev = legs.lastOrNull()?.end
        if (prev == null) {
            legs.add(PlannedLeg(pt, listOf(pt)))
            return
        }
        if (!snapStreets) {
            legs.add(PlannedLeg(pt, listOf(prev, pt)))
            return
        }
        scope.launch {
            routing = true
            val arc = OsmFootRouter.routeFootLeg(prev, pt)
            legs.add(PlannedLeg(arc.last(), arc))
            routing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VectorRouteMap(
            pts = emptyList(),
            guidePts = fullPath,
            plannerWaypoints = waypoints,
            userLocation = userLocation,
            interactive = true,
            showUserDot = prefs.showUserLocationDot,
            cameraMode = camMode,
            is3dMode = is3d,
            show3dBuildings = prefs.show3dBuildings,
            showTerrainHillshade = prefs.showTerrainHillshade,
            lockAutoFit = true,
            preloadTiles = prefs.preloadNearbyTiles,
            mapStyleMode = prefs.mapStyleMode,
            fallbackCenter = fallbackCenter,
            bottomPadDp = 190,
            recenterToken = recenterTick,
            onUserManualPan = { camMode = MapCameraMode.FREE_PAN },
            onMapTap = ::appendWaypoint,
            modifier = Modifier.fillMaxSize()
        )
        PlannerTopBar(
            distM = distM,
            estMs = estMs,
            hasPath = legs.size >= 2,
            camMode = camMode,
            is3d = is3d,
            onBack = onBack,
            onPickMode = { mode ->
                camMode = mode
                if (mode != MapCameraMode.FREE_PAN) recenterTick++
            },
            onToggle3d = { is3d = !is3d }
        ) {
            if (legs.isNotEmpty()) legs.removeAt(legs.lastIndex)
        }
        PlannerBottomDock(
            hasPoints = legs.size >= 2,
            snapStreets = snapStreets,
            routing = routing,
            onMode = { snapStreets = it },
            onLoop = { legs.firstOrNull()?.end?.let(::appendWaypoint) },
            onClear = { legs.clear() },
            onRun = { onLaunchRoute(fullPath) },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PlannerTopBar(
    distM: Double,
    estMs: Long,
    hasPath: Boolean,
    camMode: MapCameraMode,
    is3d: Boolean,
    onBack: () -> Unit,
    onPickMode: (MapCameraMode) -> Unit,
    onToggle3d: () -> Unit,
    onUndo: () -> Unit
) {
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = topPad, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingMapIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = KdeCardBg.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, KdeBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("%.2f km".format(distM / 1000.0), style = MaterialTheme.typography.titleMedium.merge(TabularNumberStyle), color = KdePurple, fontWeight = FontWeight.Bold)
                    Text("Est. ${formatDuration(estMs)}", style = MaterialTheme.typography.bodySmall.merge(TabularNumberStyle), color = KdeText)
                }
            }
            FloatingMapIconButton(Icons.AutoMirrored.Filled.Undo, "Undo", onUndo)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MapFocusControlBar(
                camMode = camMode,
                is3d = is3d,
                hasPath = hasPath,
                onPickMode = onPickMode,
                onToggle3d = onToggle3d
            )
        }
    }
}

@Composable
private fun PlannerBottomDock(
    hasPoints: Boolean,
    snapStreets: Boolean,
    routing: Boolean,
    onMode: (Boolean) -> Unit,
    onLoop: () -> Unit,
    onClear: () -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
    val cc = FilterChipDefaults.filterChipColors(selectedContainerColor = KdePurple.copy(alpha = 0.24f), selectedLabelColor = KdePurple)
    val gc = FilterChipDefaults.filterChipColors(selectedContainerColor = PaceOnTarget.copy(alpha = 0.22f), selectedLabelColor = PaceOnTarget)

    Surface(
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = navPad),
        shape = RoundedCornerShape(16.dp),
        color = KdeCardBg.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, KdeBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (routing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KdeSky)
            Text("Tap map to place route points (map holds steady as you draw)", style = MaterialTheme.typography.labelMedium, color = KdeMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = snapStreets, onClick = { onMode(true) }, colors = cc, label = { Text("Snap Streets", maxLines = 1, softWrap = false) })
                FilterChip(selected = !snapStreets, onClick = { onMode(false) }, colors = gc, label = { Text("Greenery / Freehand", maxLines = 1, softWrap = false) })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLoop, enabled = hasPoints, shape = RoundedCornerShape(999.dp)) { Text("Loop", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = onClear, enabled = hasPoints, shape = RoundedCornerShape(999.dp)) { Text("Clear", maxLines = 1, softWrap = false) }
                Button(onClick = onRun, enabled = hasPoints, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) {
                    Text("Run Route", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun flattenLegs(legs: List<PlannedLeg>): List<LatLon> {
    if (legs.isEmpty()) return emptyList()
    val out = mutableListOf<LatLon>()
    for (leg in legs) {
        leg.coords.forEach { pt ->
            if (out.isEmpty() || out.last() != pt) out.add(pt)
        }
    }
    return out
}

private fun pathDistanceMeters(pts: List<LatLon>): Double {
    var sum = 0.0
    for (i in 1 until pts.size) {
        sum += GeoMath.distanceMeters(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude)
    }
    return sum
}
