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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stridecoach.running.core.data.health.HealthConnectSyncManager as HcMgr
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.tracking.AutoActivityDetector
import com.stridecoach.running.core.tracking.RunTrackingService as TrackSvc
import com.stridecoach.running.ui.components.KdeAppBackground
import com.stridecoach.running.ui.components.PassiveLocReading
import com.stridecoach.running.ui.components.StaticMapSnapshotter
import com.stridecoach.running.ui.components.rememberPassiveLocationReading
import com.stridecoach.running.ui.components.resolveMapStyleUri
import com.stridecoach.running.ui.screens.CoachScreen
import com.stridecoach.running.ui.screens.DataSettingsScreen
import com.stridecoach.running.ui.screens.HistoryAnalyticsScreen
import com.stridecoach.running.ui.screens.LiveRunScreen
import com.stridecoach.running.ui.screens.LoadTimeMachineScreen
import com.stridecoach.running.ui.screens.PaceZonesDetailScreen
import com.stridecoach.running.ui.screens.PersonalRecordsDetailScreen
import com.stridecoach.running.ui.screens.PlanBuilderScreen
import com.stridecoach.running.ui.screens.RoutePlannerScreen
import com.stridecoach.running.ui.screens.RunDetailMapScreen
import com.stridecoach.running.ui.screens.TrashScreen
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdeNavBg
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.StrideThemeState

enum class StatsDrillScreen {
    LOAD_HISTORY,
    PACE_ZONES,
    PERSONAL_RECORDS
}

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
    val topoBusy by vm.isFetchingTopo.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draw by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var drillScreen by rememberSaveable { mutableStateOf<StatsDrillScreen?>(null) }

    val io = ioHook(vm)
    val sb = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val resolvedMapStyle = resolveMapStyleUri(st.preferences.mapStyleMode, StrideThemeState.isLight)
    val passiveReading = rememberPassiveLocationReading(tab == 1 || draw)
    val passiveLoc = passiveReading?.latLon
    val fallbackMapCenter = remember(passiveLoc, st.runs.firstOrNull()?.id) {
        passiveLoc
            ?: st.runs.firstOrNull()?.let { EncodedPolylineCodec.decode(it.encodedPolyline).firstOrNull() }
            ?: LatLon(32.1133, 34.8044)
    }

    Perms(vm)
    LaunchedEffect(st.runs.size, resolvedMapStyle) {
        if (st.runs.isNotEmpty()) StaticMapSnapshotter.preloadAll(ctx, st.runs, resolvedMapStyle)
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
            val currentKindPace = remember(st.runs, run.activityKind, st.telemetry.currentBaselinePaceSec) {
                PaceZoneClassifier.computeCurrentBaselinePace(
                    st.runs,
                    run.activityKind,
                    st.telemetry.currentBaselinePaceSec
                )
            }
            RunDetailMapScreen(
                run = run,
                prefs = st.preferences,
                currentBaselinePaceSec = currentKindPace,
                snapPreview = snp,
                isSnapping = busy,
                isFetchingTopo = topoBusy,
                onToggleSnap = vm::toggleSnapForSelectedRun,
                onToggleSnapSection = vm::toggleSnapUnsnappedSection,
                onSnapAllGreenery = vm::setSnapAllGreenery,
                onFetchDemElevation = { vm.enrichSelectedRunElevation(silent = false) },
                onMoveToTrash = { vm.moveRunToTrash(run.id) },
                onRenameRun = { newName -> vm.renameRun(ctx, run.id, newName) },
                onBack = vm::clearSelectedRun,
                onTrimRange = vm::trimSelectedRunRange,
                onExportGpx = io.gpx
            )
            return@KdeAppBackground
        }
        if (draw) {
            BackHandler { draw = false }
            RoutePlannerScreen(
                refPaceSec = st.telemetry.currentBaselinePaceSec,
                prefs = st.preferences,
                userLocation = passiveLoc,
                fallbackCenter = fallbackMapCenter,
                onBack = { draw = false }
            ) { pts ->
                vm.setPlannedGuideRoute(pts)
                draw = false
                tab = 1
            }
            return@KdeAppBackground
        }
        if (showTrash) {
            BackHandler { showTrash = false }
            TrashScreen(
                trashedRuns = st.trashedRuns,
                trashedTemplates = st.trashedTemplates,
                onBack = { showTrash = false },
                onRestoreRun = vm::restoreRunFromTrash,
                onDeleteRunForever = vm::deleteRunForever,
                onRestoreTemplate = vm::restoreTemplateFromTrash,
                onDeleteTemplateForever = vm::deleteTemplateForever,
                onRestoreAll = vm::restoreAllFromTrash,
                onEmptyTrash = vm::emptyTrash
            )
            return@KdeAppBackground
        }
        val activeDrill = drillScreen
        if (activeDrill != null) {
            BackHandler { drillScreen = null }
            when (activeDrill) {
                StatsDrillScreen.LOAD_HISTORY -> LoadTimeMachineScreen(
                    runs = st.runs,
                    walks = st.walkLogs,
                    onBack = { drillScreen = null },
                    onSelectRun = vm::loadRunDetail
                )
                StatsDrillScreen.PACE_ZONES -> PaceZonesDetailScreen(
                    runs = st.runs,
                    baselinePaceSec = st.telemetry.currentBaselinePaceSec,
                    onBack = { drillScreen = null }
                )
                StatsDrillScreen.PERSONAL_RECORDS -> PersonalRecordsDetailScreen(
                    prHistories = st.telemetry.prHistories,
                    runs = st.runs,
                    onBack = { drillScreen = null },
                    onSelectRun = vm::loadRunDetail
                )
            }
            return@KdeAppBackground
        }

        Tabs(
            t = tab,
            vm = vm,
            st = st,
            io = io,
            sb = sb,
            passiveReading = passiveReading,
            fallbackMapCenter = fallbackMapCenter,
            onDraw = { draw = true },
            onOpenTrash = { showTrash = true },
            onOpenDrill = { drillScreen = it }
        ) { tab = it }
    }
}

@Composable
private fun Tabs(
    t: Int,
    vm: StrideCoachViewModel,
    st: CoachUiState,
    io: IoRef,
    sb: SnackbarHostState,
    passiveReading: PassiveLocReading?,
    fallbackMapCenter: LatLon,
    onDraw: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDrill: (StatsDrillScreen) -> Unit,
    onPick: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val sA = rememberLazyListState()
    val sH = rememberLazyListState()
    var resumeTick by remember { mutableIntStateOf(0) }
    var battOk by remember { mutableStateOf(isBattOk(ctx)) }

    DisposableEffect(owner, ctx) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                battOk = isBattOk(ctx)
                resumeTick++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val battLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        battOk = isBattOk(ctx)
    }
    val hc = hcHook(vm, t, resumeTick)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(sb) },
        bottomBar = { NavBar(t, onPick) }
    ) { pad ->
        val mod = if (t == 1) Modifier.padding(bottom = pad.calculateBottomPadding()) else Modifier.padding(pad)
        Box(modifier = mod) {
            TabBody(
                t = t,
                vm = vm,
                st = st,
                io = io,
                hc = hc,
                battOk = battOk,
                passiveReading = passiveReading,
                fallbackMapCenter = fallbackMapCenter,
                sA = sA,
                sH = sH,
                onDraw = onDraw,
                onOpenTrash = onOpenTrash,
                onOpenDrill = onOpenDrill,
                onTab = onPick,
                onAskBattery = { askBatt(ctx) { intent -> battLauncher.launch(intent) } }
            )
        }
    }
}

private data class HcRef(val av: Boolean, val ok: Boolean, val pendingRoutes: Int, val ask: () -> Unit, val askRoute: () -> Unit)

@Composable
private fun hcHook(vm: StrideCoachViewModel, t: Int, resumeTick: Int): HcRef {
    val c = LocalContext.current
    var ok by remember { mutableStateOf(false) }
    val av = remember(c) { HcMgr.isAvailable(c) }
    val pendingRoutes by vm.pendingHcRouteIds.collectAsState()
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var lastAutoPromptId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(av, t, resumeTick) {
        if (av) ok = HcMgr.hasPermissions(c)
    }
    val q = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        val g = got.containsAll(HcMgr.requiredPermissions)
        ok = g
        vm.updatePreferences { it.copy(healthConnectEnabled = g) }
        if (g) vm.syncHealthConnect(c)
    }
    val routeLauncher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        val sid = activeSessionId
        activeSessionId = null
        if (sid != null) vm.applyConsentedHealthRoute(c, sid, route)
    }
    LaunchedEffect(pendingRoutes) {
        val nextId = pendingRoutes.firstOrNull()
        if (nextId != null && nextId != lastAutoPromptId && activeSessionId == null) {
            lastAutoPromptId = nextId
            activeSessionId = nextId
            routeLauncher.launch(nextId)
        }
    }
    return HcRef(
        av = av,
        ok = ok,
        pendingRoutes = pendingRoutes.size,
        ask = { q.launch(HcMgr.requiredPermissions) },
        askRoute = {
            val nextId = pendingRoutes.firstOrNull()
            if (nextId != null) {
                activeSessionId = nextId
                routeLauncher.launch(nextId)
            }
        }
    )
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

private data class IoRef(
    val impB: () -> Unit,
    val expB: () -> Unit,
    val impT: () -> Unit,
    val gpx: () -> Unit,
    val pickFolder: () -> Unit,
    val restoreVault: () -> Unit
)

@Composable
private fun ioHook(vm: StrideCoachViewModel): IoRef {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val a = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importBackupFromUri(ctx, cr, u)
    }
    val b = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) vm.exportBackupToUri(cr, u)
    }
    val c = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importTimelineFromUri(ctx, cr, u)
    }
    val d = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { u ->
        if (u != null) vm.exportSelectedRunGpxToUri(cr, u)
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) vm.setSyncFolderUri(ctx, u)
    }
    val vault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.restoreVaultFromUri(cr, u)
    }
    return IoRef(
        impB = { a.launch(arrayOf("*/*")) },
        expB = { b.launch("running-tracker-export.backup") },
        impT = { c.launch(arrayOf("application/json", "*/*")) },
        gpx = { d.launch("stridecoach-run.gpx") },
        pickFolder = { tree.launch(null) },
        restoreVault = { vault.launch(arrayOf("application/json", "*/*")) }
    )
}

@Composable
private fun NavBar(t: Int, onPick: (Int) -> Unit) {
    val list = listOf(
        "Coach" to Icons.Default.Home,
        "Live" to Icons.AutoMirrored.Filled.DirectionsRun,
        "Plans" to Icons.Default.CalendarMonth,
        "Stats" to Icons.Default.BarChart,
        "Settings" to Icons.Default.Settings
    )
    Surface(color = KdeNavBg, modifier = Modifier.fillMaxWidth()) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp
        ) {
            list.forEachIndexed { i, (lbl, ico) ->
                NavigationBarItem(
                    selected = t == i,
                    onClick = { onPick(i) },
                    icon = { Icon(ico, contentDescription = lbl) },
                    label = { Text(lbl, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
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
}

@Composable
private fun TabBody(
    t: Int,
    vm: StrideCoachViewModel,
    st: CoachUiState,
    io: IoRef,
    hc: HcRef,
    battOk: Boolean,
    passiveReading: PassiveLocReading?,
    fallbackMapCenter: LatLon,
    sA: LazyListState,
    sH: LazyListState,
    onDraw: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDrill: (StatsDrillScreen) -> Unit,
    onTab: (Int) -> Unit,
    onAskBattery: () -> Unit
) {
    val c = LocalContext.current
    val lv by vm.liveTracking.collectAsState()
    val gd by vm.plannedGuideRoute.collectAsState()
    when (t) {
        0 -> CoachScreen(st, { w, id -> vm.startWorkout(c, w, id); onTab(1) }, vm::applyWalkingDowngrade, vm::applyWalkingSwap, vm::logManualWalkToday)
        1 -> LiveRunScreen(
            liveState = lv,
            prefs = st.preferences,
            refPaceSec = st.telemetry.currentBaselinePaceSec,
            guidePts = gd,
            userLocation = passiveReading?.latLon,
            passiveSpeedKmh = passiveReading?.speedKmh ?: 0f,
            fallbackCenter = fallbackMapCenter,
            todayWorkoutLabel = st.todayDay?.workout?.title,
            onStartTodayWorkout = st.todayDay?.workout?.let { w -> { vm.startWorkout(c, w, st.todayDay?.id) } },
            onOpenPlanner = onDraw,
            onToggleWakeLock = { on -> vm.updatePreferences { it.copy(keepScreenAwake = on) } },
            onStartActivity = { kind -> vm.startWorkout(c, null, null, kind) },
            onPauseResume = { TrackSvc.sendAction(c, TrackSvc.ACTION_PAUSE_RESUME) },
            onNextStep = { TrackSvc.sendAction(c, TrackSvc.ACTION_NEXT_STEP) },
            onStopRun = { TrackSvc.sendAction(c, TrackSvc.ACTION_STOP) }
        )
        2 -> PlanBuilderScreen(
            state = st,
            onActivatePlan = vm::activatePlanConfig,
            onRecalibrate5k = vm::recalibrateActivePlanFrom5k,
            onSaveTemplate = vm::saveWorkoutTemplate,
            onTrashTemplate = vm::moveTemplateToTrash,
            onStartWorkout = { w -> vm.startWorkout(c, w, null); onTab(1) },
            onOpenRoutePlanner = onDraw
        )
        3 -> HistoryAnalyticsScreen(
            state = st,
            onSelectRun = vm::loadRunDetail,
            onTrashRun = vm::moveRunToTrash,
            onRenameRun = { id, newName -> vm.renameRun(c, id, newName) },
            onOpenTrash = onOpenTrash,
            onOpenLoadHistory = { onOpenDrill(StatsDrillScreen.LOAD_HISTORY) },
            onOpenPaceZones = { onOpenDrill(StatsDrillScreen.PACE_ZONES) },
            onOpenPersonalRecords = { onOpenDrill(StatsDrillScreen.PERSONAL_RECORDS) },
            analyticsScrollState = sA,
            historyScrollState = sH
        )
        4 -> DataSettingsScreen(
            prefs = st.preferences,
            busy = st.isImporting,
            summary = st.lastImportSummary,
            activityCounts = QuadCounts(st.telemetry.runCount, st.telemetry.walkCount, st.telemetry.cycleCount, st.telemetry.walkDaysCount),
            trashCount = st.trashedRuns.size + st.trashedTemplates.size,
            battOk = battOk,
            hcAvail = hc.av,
            hcOk = hc.ok,
            hcPendingRoutes = hc.pendingRoutes,
            onHcConnect = hc.ask,
            onHcGrantRoutes = hc.askRoute,
            onHcSync = { vm.syncHealthConnect(c) },
            onHealthAutoSync = { on -> vm.setHealthAutoSync(c, on) },
            onAutoDetect = { on -> vm.setAutoDetectRuns(c, on) },
            onAutoStart = { on -> vm.setAutoStartOnRunDetected(c, on) },
            onPickSyncFolder = io.pickFolder,
            onSyncFolderNow = { vm.syncFolderNow(c, forceAll = false) },
            onRestoreVaultFile = io.restoreVault,
            onOpenTrash = onOpenTrash,
            onUpdate = vm::updatePreferences,
            onImpBak = io.impB,
            onExpBak = io.expB,
            onImpTl = io.impT,
            onBattReq = onAskBattery
        )
    }
}

data class QuadCounts(val runs: Int, val walks: Int, val cycles: Int, val walkDays: Int)

private fun isBattOk(c: Context): Boolean {
    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(c.packageName)
}

@SuppressLint("BatteryLife")
private fun askBatt(c: Context, launch: (Intent) -> Unit) {
    val u = Uri.parse("package:${c.packageName}")
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u)
    val fb = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u)
    runCatching { launch(ask) }.onFailure { launch(fb) }
}
