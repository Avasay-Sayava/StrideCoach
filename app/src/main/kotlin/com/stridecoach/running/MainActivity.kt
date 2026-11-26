package com.stridecoach.running

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stridecoach.running.core.tracking.RunTrackingService
import com.stridecoach.running.ui.StrideCoachViewModel
import com.stridecoach.running.ui.components.KdeAppBackground
import com.stridecoach.running.ui.screens.CoachScreen
import com.stridecoach.running.ui.screens.DataSettingsScreen
import com.stridecoach.running.ui.screens.HistoryAnalyticsScreen
import com.stridecoach.running.ui.screens.LiveRunScreen
import com.stridecoach.running.ui.screens.PlanBuilderScreen
import com.stridecoach.running.ui.theme.KdeMuted
import com.stridecoach.running.ui.theme.KdeNavBg
import com.stridecoach.running.ui.theme.KdePurple
import com.stridecoach.running.ui.theme.StrideCoachTheme

class MainActivity : ComponentActivity() {
    private lateinit var vmRef: StrideCoachViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as StrideCoachApplication
        val fac = StrideCoachViewModel.Factory(app.repository, app.preferencesStore)
        vmRef = ViewModelProvider(this, fac)[StrideCoachViewModel::class.java]
        handleIncoming(intent)

        setContent {
            StrideCoachTheme {
                val vm: StrideCoachViewModel = viewModel(factory = fac)
                KeepAwakeEffect(vm, window)
                AppShell(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = extractUri(intent) ?: return
        vmRef.importAutoDetectedUri(contentResolver, uri)
    }

    private fun extractUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_VIEW) return intent.data
        if (intent.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

@Composable
private fun KeepAwakeEffect(vm: StrideCoachViewModel, win: android.view.Window) {
    val st by vm.uiState.collectAsState()
    val live by vm.liveTracking.collectAsState()
    LaunchedEffect(st.preferences.keepScreenAwake, live.isActive) {
        if (st.preferences.keepScreenAwake && live.isActive) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun AppShell(vm: StrideCoachViewModel) {
    val st by vm.uiState.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val host = remember { SnackbarHostState() }

    RequestPerms()
    LaunchedEffect(st.statusBanner) {
        val msg = st.statusBanner ?: return@LaunchedEffect
        host.showSnackbar(msg)
        vm.clearStatusBanner()
    }

    val io = rememberIoHooks(vm)
    KdeAppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(host) },
            bottomBar = { AppBottomBar(tab) { tab = it } }
        ) { pad ->
            Box(modifier = Modifier.padding(pad)) {
                TabBody(tab, vm, io) { tab = it }
            }
        }
    }
}

@Composable
private fun RequestPerms() {
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) list.add(Manifest.permission.ACTIVITY_RECOGNITION)
        req.launch(list.toTypedArray())
    }
}

private data class IoHooks(
    val impBak: () -> Unit,
    val expBak: () -> Unit,
    val impTl: () -> Unit,
    val expGpx: () -> Unit
)

@Composable
private fun rememberIoHooks(vm: StrideCoachViewModel): IoHooks {
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
    return IoHooks(
        impBak = { a.launch(arrayOf("*/*")) },
        expBak = { b.launch("running-tracker-export.backup") },
        impTl = { c.launch(arrayOf("application/json", "*/*")) },
        expGpx = { d.launch("stridecoach-run.gpx") }
    )
}

@Composable
private fun AppBottomBar(tab: Int, onPick: (Int) -> Unit) {
    val items = listOf(
        "Coach" to Icons.Default.Home,
        "Live" to Icons.AutoMirrored.Filled.DirectionsRun,
        "Plans" to Icons.Default.CalendarMonth,
        "History" to Icons.Default.History,
        "Data" to Icons.Default.Settings
    )
    NavigationBar(containerColor = KdeNavBg) {
        items.forEachIndexed { idx, (lbl, icon) ->
            NavigationBarItem(
                selected = tab == idx,
                onClick = { onPick(idx) },
                icon = { Icon(icon, contentDescription = lbl) },
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
private fun TabBody(tab: Int, vm: StrideCoachViewModel, io: IoHooks, onTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val st by vm.uiState.collectAsState()
    val live by vm.liveTracking.collectAsState()
    val sel by vm.selectedRunDetail.collectAsState()

    when (tab) {
        0 -> CoachScreen(
            st,
            onStartWorkout = { w, id -> vm.startWorkout(ctx, w, id); onTab(1) },
            onApplyDowngrade = vm::applyWalkingDowngrade,
            onApplySwap = vm::applyWalkingSwap,
            onLogManualWalk = vm::logManualWalkToday
        )
        1 -> LiveRunScreen(
            live,
            st.preferences.keepScreenAwake,
            onToggleWakeLock = { on -> vm.updatePreferences { it.copy(keepScreenAwake = on) } },
            onStartFreeRun = { vm.startWorkout(ctx, null, null) },
            onPauseResume = { RunTrackingService.sendAction(ctx, RunTrackingService.ACTION_PAUSE_RESUME) },
            onNextStep = { RunTrackingService.sendAction(ctx, RunTrackingService.ACTION_NEXT_STEP) },
            onStopRun = { RunTrackingService.sendAction(ctx, RunTrackingService.ACTION_STOP) }
        )
        2 -> PlanBuilderScreen(
            st,
            onActivatePlan = vm::activatePlanConfig,
            onRecalibrate5k = vm::recalibrateActivePlanFrom5k,
            onSaveTemplate = vm::saveWorkoutTemplate,
            onStartWorkout = { w -> vm.startWorkout(ctx, w, null); onTab(1) }
        )
        3 -> HistoryAnalyticsScreen(st, sel, vm::loadRunDetail, vm::trimSelectedRunTail, io.expGpx)
        4 -> DataSettingsScreen(
            st.preferences,
            st.isImporting,
            st.lastImportSummary,
            st.runs.size,
            st.walkLogs.size,
            isUnrestricted(ctx),
            vm::updatePreferences,
            io.impBak,
            io.expBak,
            io.impTl
        ) { openBatterySettings(ctx) }
    }
}

private fun isUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@SuppressLint("BatteryLife")
private fun openBatterySettings(ctx: Context) {
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
    runCatching { ctx.startActivity(ask) }.onFailure { ctx.startActivity(fallback) }
}
