package com.stridecoach.running.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.stridecoach.running.core.data.health.HealthConnectSyncManager as HcMgr
import com.stridecoach.running.core.tracking.AutoActivityDetector
import com.stridecoach.running.core.tracking.RunTrackingService as TrackSvc
import com.stridecoach.running.ui.components.KdeAppBackground
import com.stridecoach.running.ui.components.StaticMapSnapshotter
import com.stridecoach.running.ui.screens.CoachScreen
import com.stridecoach.running.ui.screens.DataSettingsScreen
import com.stridecoach.running.ui.screens.HistoryAnalyticsScreen
import com.stridecoach.running.ui.screens.LiveRunScreen
import com.stridecoach.running.ui.screens.PlanBuilderScreen
import com.stridecoach.running.ui.screens.RoutePlannerScreen
import com.stridecoach.running.ui.screens.RunDetailMapScreen
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdeNavBg
import com.stridecoach.running.ui.theme.KdePurple

@Composable
fun AppShell(vm: StrideCoachViewModel, win: Window) {
    val st by vm.uiState.collectAsState()
    val lv by vm.liveTracking.collectAsState()
    LaunchedEffect(st.preferences.keepScreenAwake, lv.isActive) {
        if (st.preferences.keepScreenAwake && lv.isActive) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val sel by vm.selectedRunDetail.collectAsState()
    val snp by vm.snapPreview.collectAsState()
    val busy by vm.isSnapping.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draw by rememberSaveable { mutableStateOf(false) }
    val io = ioHook(vm)
    val sb = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    Perms(vm)
    LaunchedEffect(st.runs.size) {
        if (st.runs.isNotEmpty()) StaticMapSnapshotter.preloadAll(ctx, st.runs)
    }
    LaunchedEffect(st.statusBanner) {
        val m = st.statusBanner ?: return@LaunchedEffect
        sb.showSnackbar(m)
        vm.clearStatusBanner()
    }

    KdeAppBackground {
        val run = sel
        if (run != null) {
            BackHandler { vm.clearSelectedRun() }
            RunDetailMapScreen(
                run, st.activePlan?.targetPaceSecPerKm ?: 295, snp, busy,
                vm::snapSelectedRun, vm::applySnappedRun, vm::clearSnapPreview,
                vm::clearSelectedRun, vm::trimSelectedRunTail, io.gpx
            )
            return@KdeAppBackground
        }
        if (draw) {
            BackHandler { draw = false }
            RoutePlannerScreen(st.activePlan?.targetPaceSecPerKm ?: 295, { draw = false }) { pts ->
                vm.setPlannedGuideRoute(pts)
                draw = false
                tab = 1
            }
            return@KdeAppBackground
        }
        Tabs(tab, vm, st, io, sb, { draw = true }) { tab = it }
    }
}

@Composable
private fun Tabs(
    t: Int,
    vm: StrideCoachViewModel,
    st: CoachUiState,
    io: IoRef,
    sb: SnackbarHostState,
    onDraw: () -> Unit,
    onPick: (Int) -> Unit
) {
    val sA = rememberLazyListState()
    val sH = rememberLazyListState()
    val hc = hcHook(vm, t)
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(sb) },
        bottomBar = { NavBar(t, onPick) }
    ) { pad ->
        val mod = if (t == 1) Modifier.padding(bottom = pad.calculateBottomPadding()) else Modifier.padding(pad)
        Box(modifier = mod) { TabBody(t, vm, st, io, hc, sA, sH, onDraw, onPick) }
    }
}

private data class HcRef(val av: Boolean, val ok: Boolean, val ask: () -> Unit)

@Composable
private fun hcHook(vm: StrideCoachViewModel, t: Int): HcRef {
    val c = LocalContext.current
    var ok by remember { mutableStateOf(false) }
    val av = remember(c) { HcMgr.isAvailable(c) }
    LaunchedEffect(av, t) { if (av) ok = HcMgr.hasPermissions(c) }
    val q = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        val g = got.containsAll(HcMgr.requiredPermissions)
        ok = g
        vm.updatePreferences { it.copy(healthConnectEnabled = g) }
        if (g) vm.syncHealthConnect(c)
    }
    return HcRef(av, ok) { q.launch(HcMgr.requiredPermissions) }
}

@Composable
private fun Perms(vm: StrideCoachViewModel) {
    val c = LocalContext.current
    val st by vm.uiState.collectAsState()
    val r = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (st.preferences.autoDetectRunsEnabled) AutoActivityDetector.setEnabled(c, true)
    }
    LaunchedEffect(Unit) {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) l.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) l.add(Manifest.permission.ACTIVITY_RECOGNITION)
        r.launch(l.toTypedArray())
    }
}

private data class IoRef(val impB: () -> Unit, val expB: () -> Unit, val impT: () -> Unit, val gpx: () -> Unit)

@Composable
private fun ioHook(vm: StrideCoachViewModel): IoRef {
    val cr = LocalContext.current.contentResolver
    val a = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importBackupFromUri(cr, u)
    }
    val b = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) vm.exportBackupToUri(cr, u)
    }
    val c = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importTimelineFromUri(cr, u)
    }
    val d = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { u ->
        if (u != null) vm.exportSelectedRunGpxToUri(cr, u)
    }
    return IoRef(
        impB = { a.launch(arrayOf("*/*")) },
        expB = { b.launch("running-tracker-export.backup") },
        impT = { c.launch(arrayOf("application/json", "*/*")) },
        gpx = { d.launch("stridecoach-run.gpx") }
    )
}

@Composable
private fun NavBar(t: Int, onPick: (Int) -> Unit) {
    val list = listOf(
        "Coach" to Icons.Default.Home,
        "Live" to Icons.AutoMirrored.Filled.DirectionsRun,
        "Plans" to Icons.Default.CalendarMonth,
        "Stats" to Icons.Default.BarChart,
        "Data" to Icons.Default.Settings
    )
    NavigationBar(containerColor = KdeNavBg) {
        list.forEachIndexed { i, (lbl, ico) ->
            NavigationBarItem(
                selected = t == i,
                onClick = { onPick(i) },
                icon = { Icon(ico, contentDescription = lbl) },
                label = { Text(lbl) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = KdePurple,
                    selectedTextColor = KdePurple,
                    indicatorColor = KdePurple.copy(alpha = 0.18f),
                    unselectedIconColor = KdeMuted,
                    unselectedTextColor = KdeMuted
                )
            )
        }
    }
}

@Composable
private fun TabBody(
    t: Int,
    vm: StrideCoachViewModel,
    st: CoachUiState,
    io: IoRef,
    hc: HcRef,
    sA: LazyListState,
    sH: LazyListState,
    onDraw: () -> Unit,
    onTab: (Int) -> Unit
) {
    val c = LocalContext.current
    val lv by vm.liveTracking.collectAsState()
    val gd by vm.plannedGuideRoute.collectAsState()
    when (t) {
        0 -> CoachScreen(st, { w, id -> vm.startWorkout(c, w, id); onTab(1) }, vm::applyWalkingDowngrade, vm::applyWalkingSwap, vm::logManualWalkToday)
        1 -> LiveRunScreen(
            lv, st.activePlan?.targetPaceSecPerKm ?: 295, gd, st.preferences.keepScreenAwake,
            st.todayDay?.workout?.title,
            st.todayDay?.workout?.let { w -> { vm.startWorkout(c, w, st.todayDay?.id) } },
            onDraw,
            { on -> vm.updatePreferences { it.copy(keepScreenAwake = on) } },
            { vm.startWorkout(c, null, null) },
            { TrackSvc.sendAction(c, TrackSvc.ACTION_PAUSE_RESUME) },
            { TrackSvc.sendAction(c, TrackSvc.ACTION_NEXT_STEP) },
            { TrackSvc.sendAction(c, TrackSvc.ACTION_STOP) }
        )
        2 -> PlanBuilderScreen(
            st, vm::activatePlanConfig, vm::recalibrateActivePlanFrom5k, vm::saveWorkoutTemplate,
            { w -> vm.startWorkout(c, w, null); onTab(1) }, onDraw
        )
        3 -> HistoryAnalyticsScreen(st, vm::loadRunDetail, sA, sH)
        4 -> DataSettingsScreen(
            st.preferences, st.isImporting, st.lastImportSummary, st.runs.size, st.walkLogs.size,
            isBattOk(c), hc.av, hc.ok, hc.ask, { vm.syncHealthConnect(c) },
            { on -> vm.setAutoDetectRuns(c, on) }, vm::updatePreferences, io.impB, io.expB, io.impT
        ) { askBatt(c) }
    }
}

private fun isBattOk(c: Context): Boolean {
    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(c.packageName)
}

@SuppressLint("BatteryLife")
private fun askBatt(c: Context) {
    val u = Uri.parse("package:${c.packageName}")
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u)
    val fb = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u)
    runCatching { c.startActivity(ask) }.onFailure { c.startActivity(fb) }
}
