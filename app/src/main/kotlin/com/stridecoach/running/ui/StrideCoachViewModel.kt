package com.stridecoach.running.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stridecoach.running.core.data.adapter.GpxExporter
import com.stridecoach.running.core.data.adapter.ImportFileKind
import com.stridecoach.running.core.data.adapter.RunningTrackerBackupAdapter
import com.stridecoach.running.core.data.adapter.TimelineJsonImporter
import com.stridecoach.running.core.data.adapter.VaultAndTimelineSyncManager
import com.stridecoach.running.core.data.health.HealthConnectSyncManager
import com.stridecoach.running.core.data.health.HealthConnectSyncWorker
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.data.routing.ElevationDemService
import com.stridecoach.running.core.data.routing.OsmFootRouter
import com.stridecoach.running.core.domain.engine.DailyLoadBar
import com.stridecoach.running.core.domain.engine.EncodedPolylineCodec
import com.stridecoach.running.core.domain.engine.GeoMath
import com.stridecoach.running.core.domain.engine.LatLon
import com.stridecoach.running.core.domain.engine.MatchedPointSample
import com.stridecoach.running.core.domain.engine.PaceBaselineMode
import com.stridecoach.running.core.domain.engine.PaceZoneBucket
import com.stridecoach.running.core.domain.engine.PaceZoneClassifier
import com.stridecoach.running.core.domain.engine.ParkSafeSnapMerger
import com.stridecoach.running.core.domain.engine.PersonalRecord
import com.stridecoach.running.core.domain.engine.PersonalRecordHistory
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.SnapMergeResult
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.engine.UnsnappedSection
import com.stridecoach.running.core.domain.engine.WalkingLoadAlert
import com.stridecoach.running.core.domain.engine.WalkingLoadRegulator
import com.stridecoach.running.core.domain.engine.WeeklyLoadPoint
import com.stridecoach.running.core.domain.model.ActivityKind
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrackPoint
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
import com.stridecoach.running.core.tracking.AutoActivityDetector
import com.stridecoach.running.core.tracking.LiveTrackingState
import com.stridecoach.running.core.tracking.RunTrackingService
import com.stridecoach.running.core.tracking.TrackingSessionBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

@Immutable
data class TelemetrySummary(
    val totalRunKm: Double = 0.0,
    val totalDurationMillis: Long = 0L,
    val avgPaceSecPerKm: Int = 0,
    val avgCadenceSpm: Int = 0,
    val currentBaselinePaceSec: Int = 330,
    val runCount: Int = 0,
    val walkCount: Int = 0,
    val cycleCount: Int = 0,
    val otherCount: Int = 0,
    val walkDaysCount: Int = 0,
    val weeklyLoad: List<WeeklyLoadPoint> = emptyList(),
    val last7DaysLoad: List<DailyLoadBar> = emptyList(),
    val paceZones: List<PaceZoneBucket> = emptyList(),
    val weekdayWalkHeatmap: Map<DayOfWeek, Int> = emptyMap(),
    val personalRecords: List<PersonalRecord> = emptyList(),
    val prHistories: List<PersonalRecordHistory> = emptyList()
)

@Immutable
data class CoachUiState(
    val activePlan: TrainingPlanConfig? = null,
    val plannedDays: List<PlannedDay> = emptyList(),
    val todayDay: PlannedDay? = null,
    val tomorrowDay: PlannedDay? = null,
    val walkingAlert: WalkingLoadAlert? = null,
    val runs: List<RunRecord> = emptyList(),
    val walkLogs: List<WalkLog> = emptyList(),
    val templates: List<StructuredWorkout> = emptyList(),
    val trashedRuns: List<RunRecord> = emptyList(),
    val trashedTemplates: List<StructuredWorkout> = emptyList(),
    val telemetry: TelemetrySummary = TelemetrySummary(),
    val preferences: CoachPreferences = CoachPreferences(),
    val statusBanner: String? = null,
    val isImporting: Boolean = false,
    val lastImportSummary: String? = null
)

private data class IoStatus(
    val banner: String? = null,
    val busy: Boolean = false,
    val summary: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class StrideCoachViewModel(
    private val repo: StrideCoachRepository,
    private val prefs: AppPreferencesStore
) : ViewModel() {
    private val gen = TrainingPlanGenerator()
    private val reg = WalkingLoadRegulator()
    private val trimmer = RunTailTrimmer()
    private val stats = RunAnalyticsEngine()
    private val backup = RunningTrackerBackupAdapter()
    private val timeline = TimelineJsonImporter()
    private val gpx = GpxExporter()

    private val snapCache = mutableMapOf<String, SnapMergeResult>()
    private val ioFlow = MutableStateFlow(IoStatus())
    private val selFlow = MutableStateFlow<RunRecord?>(null)
    val selectedRunDetail: StateFlow<RunRecord?> = selFlow.asStateFlow()
    private val snapFlow = MutableStateFlow<SnapMergeResult?>(null)
    val snapPreview: StateFlow<SnapMergeResult?> = snapFlow.asStateFlow()
    private val snappingFlow = MutableStateFlow(false)
    val isSnapping: StateFlow<Boolean> = snappingFlow.asStateFlow()
    private val topoBusyFlow = MutableStateFlow(false)
    val isFetchingTopo: StateFlow<Boolean> = topoBusyFlow.asStateFlow()
    private val guideFlow = MutableStateFlow<List<LatLon>>(emptyList())
    val plannedGuideRoute: StateFlow<List<LatLon>> = guideFlow.asStateFlow()
    val liveTracking: StateFlow<LiveTrackingState> = TrackingSessionBus.state

    private val daysFlow = repo.observeActivePlan().flatMapLatest { plan ->
        if (plan == null) flowOf(emptyList()) else repo.observePlanDays(plan.id)
    }

    private val telemetryFlow = combine(
        repo.observeRuns(),
        repo.observeWalkLogs(),
        repo.observeActivePlan(),
        prefs.preferences
    ) { runs, walks, plan, p ->
        computeTelemetry(runs, walks, plan?.targetPaceSecPerKm ?: 300, p.paceBaselineMode)
    }.flowOn(Dispatchers.Default)

    private val trashFlow = combine(
        repo.observeTrashedRuns(),
        repo.observeTrashedTemplates()
    ) { trRuns, trTpls -> trRuns to trTpls }

    val uiState: StateFlow<CoachUiState> = combine(
        repo.observeActivePlan(),
        daysFlow,
        repo.observeRuns(),
        repo.observeWalkLogs(),
        repo.observeWorkoutTemplates(),
        trashFlow,
        telemetryFlow,
        prefs.preferences,
        ioFlow
    ) { arr ->
        val plan = arr[0] as TrainingPlanConfig?
        @Suppress("UNCHECKED_CAST")
        val days = arr[1] as List<PlannedDay>
        @Suppress("UNCHECKED_CAST")
        val runs = arr[2] as List<RunRecord>
        @Suppress("UNCHECKED_CAST")
        val walks = arr[3] as List<WalkLog>
        @Suppress("UNCHECKED_CAST")
        val tpls = arr[4] as List<StructuredWorkout>
        @Suppress("UNCHECKED_CAST")
        val trashPair = arr[5] as Pair<List<RunRecord>, List<StructuredWorkout>>
        val tel = arr[6] as TelemetrySummary
        val p = arr[7] as CoachPreferences
        val io = arr[8] as IoStatus
        buildState(plan, days, runs, walks, tpls, trashPair.first, trashPair.second, tel, p, io)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachUiState())

    init {
        viewModelScope.launch {
            if (repo.observeActivePlan().first() == null) {
                activatePlanConfig(PlanPresets.telAviv10kPreset())
            }
        }
        viewModelScope.launch {
            TrackingSessionBus.completedRuns.collect { run ->
                val curPrefs = prefs.preferences.first()
                val finalized = if (curPrefs.autoTrimRunsEnabled) {
                    val sug = trimmer.suggestAutoTrim(run.trackPoints, run.activityKind)
                    if (sug != null) trimmer.trimRange(run, sug.startMs, sug.endMs) else run
                } else {
                    run
                }
                repo.saveRun(finalized)
                val trimTag = if (finalized.durationMillis < run.durationMillis) " (auto-trimmed idle ends)" else ""
                postBanner("Saved ${finalized.name}: ${"%.2f".format(finalized.km())} km$trimTag")
            }
        }
        viewModelScope.launch {
            prefs.preferences.collect { p ->
                TrackingSessionBus.voiceEnabled = p.voiceCoachEnabled
                TrackingSessionBus.hapticEnabled = p.hapticCuesEnabled
                TrackingSessionBus.autoPauseEnabled = p.autoPauseEnabled
                TrackingSessionBus.gyroEnabled = p.gyroTelemetryEnabled
                TrackingSessionBus.slopeAdjustedPaceEnabled = p.slopeAdjustedPaceEnabled
                TrackingSessionBus.widgetTapGuardEnabled = p.widgetTapGuardEnabled
            }
        }
    }

    fun activatePlanConfig(cfg: TrainingPlanConfig) {
        viewModelScope.launch {
            val days = gen.generate(cfg)
            repo.activatePlan(cfg, days)
            postBanner("Activated plan: ${cfg.name} (${days.size} days)")
        }
    }

    fun recalibrateActivePlanFrom5k(ms: Long) {
        val cur = uiState.value.activePlan ?: return
        activatePlanConfig(gen.recalibrateFrom5kBenchmark(cur, ms))
    }

    fun applyWalkingDowngrade() {
        val st = uiState.value
        val day = st.todayDay ?: return
        val alert = st.walkingAlert ?: return
        viewModelScope.launch {
            repo.updatePlannedDays(listOf(reg.applyDowngrade(day, alert)))
            postBanner("Today's workout downgraded to easy recovery run.")
        }
    }

    fun applyWalkingSwap() {
        val st = uiState.value
        val d0 = st.todayDay ?: return
        val d1 = st.tomorrowDay ?: return
        viewModelScope.launch {
            val (a, b) = reg.applySwapWithTomorrow(d0, d1)
            repo.updatePlannedDays(listOf(a, b))
            postBanner("Swapped today's workout with tomorrow's rest day.")
        }
    }

    fun logManualWalkToday(m: Int) {
        if (m <= 0) return
        viewModelScope.launch {
            val day = LocalDate.now().toEpochDay()
            repo.mergeWalkLogs(listOf(WalkLog(day, m, WalkSource.MANUAL, 0, System.currentTimeMillis())))
            postBanner("Logged ${"%.1f".format(m / 1000.0)} km walk for today.")
        }
    }

    fun saveWorkoutTemplate(w: StructuredWorkout) {
        viewModelScope.launch {
            repo.saveWorkoutTemplate(w)
            postBanner("Saved template: ${w.title}")
        }
    }

    fun moveTemplateToTrash(id: String) {
        viewModelScope.launch {
            repo.moveTemplateToTrash(id)
            postBanner("Moved workout template to Trash.")
        }
    }

    fun moveRunToTrash(id: String) {
        viewModelScope.launch {
            repo.moveRunToTrash(id)
            if (selFlow.value?.id == id) {
                selFlow.value = null
                snapFlow.value = null
            }
            postBanner("Moved activity to Trash (recoverable in Data -> Trash).")
        }
    }

    fun restoreRunFromTrash(id: String) {
        viewModelScope.launch {
            repo.restoreRun(id)
            postBanner("Restored activity from Trash.")
        }
    }

    fun deleteRunForever(id: String) {
        viewModelScope.launch {
            repo.deleteRunForever(id)
            postBanner("Permanently deleted activity.")
        }
    }

    fun restoreTemplateFromTrash(id: String) {
        viewModelScope.launch {
            repo.restoreTemplate(id)
            postBanner("Restored workout template.")
        }
    }

    fun deleteTemplateForever(id: String) {
        viewModelScope.launch {
            repo.deleteTemplateForever(id)
            postBanner("Permanently deleted workout template.")
        }
    }

    fun restoreAllFromTrash() {
        viewModelScope.launch {
            repo.restoreAllFromTrash()
            postBanner("Restored all items from Trash.")
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            repo.emptyTrash()
            postBanner("Emptied Trash permanently.")
        }
    }

    fun startWorkout(
        ctx: Context,
        w: StructuredWorkout?,
        dayId: String? = null,
        kind: ActivityKind = ActivityKind.RUNNING
    ) {
        TrackingSessionBus.pendingWorkout = w
        TrackingSessionBus.pendingPlanDayId = dayId
        TrackingSessionBus.pendingActivityKind = kind
        RunTrackingService.start(ctx)
    }

    fun loadRunDetail(id: String) {
        viewModelScope.launch {
            val detail = repo.getRunDetail(id)
            if (detail != null) {
                val rawPoly = detail.rawEncodedPolyline.ifBlank { detail.encodedPolyline }
                snapFlow.value = snapCache[snapKey(detail.id, rawPoly)]
                selFlow.value = detail
                val p = prefs.preferences.first()
                if (p.autoFetchDemElevation && detail.trackPoints.count { it.altitudeMeters != null } < 2 && detail.hasTrack()) {
                    enrichSelectedRunElevation(silent = true)
                }
            } else {
                snapFlow.value = null
                selFlow.value = null
            }
        }
    }

    fun enrichSelectedRunElevation(silent: Boolean = false) {
        val cur = selFlow.value ?: return
        if (topoBusyFlow.value) return
        val pts = cur.trackPoints.ifEmpty {
            EncodedPolylineCodec.synthesizeReplayTrack(
                cur.encodedPolyline, cur.startTimestampMillis, cur.durationMillis, cur.splits
            )
        }
        if (pts.size < 2) return
        viewModelScope.launch {
            topoBusyFlow.value = true
            runCatching { ElevationDemService.enrichRunElevation(cur, pts) }
                .onSuccess { enriched ->
                    if (enriched != null) {
                        repo.saveRun(enriched)
                        if (selFlow.value?.id == enriched.id) selFlow.value = enriched
                        if (!silent) postBanner("Loaded DEM elevation (+${enriched.elevGainM}m / -${enriched.elevLossM}m).")
                    } else if (!silent) {
                        postBanner("Elevation lookup unavailable for this route.")
                    }
                }
                .onFailure { if (!silent) postBanner("Elevation fetch failed: check connection.") }
            topoBusyFlow.value = false
        }
    }

    fun clearSelectedRun() {
        snapFlow.value = null
        selFlow.value = null
    }

    fun setPlannedGuideRoute(pts: List<LatLon>) {
        guideFlow.value = pts
    }

    fun toggleSnapForSelectedRun(enabled: Boolean) {
        val cur = selFlow.value ?: return
        if (snappingFlow.value) return
        val rawPoly = cur.rawEncodedPolyline.ifBlank { cur.encodedPolyline }
        if (!enabled) {
            viewModelScope.launch {
                val rawPts = EncodedPolylineCodec.synthesizeReplayTrack(
                    rawPoly, cur.startTimestampMillis, cur.durationMillis, cur.splits
                )
                val reverted = cur.copy(
                    encodedPolyline = rawPoly,
                    rawEncodedPolyline = rawPoly,
                    trackPoints = rawPts,
                    isSnapped = false
                )
                repo.saveRun(reverted)
                selFlow.value = reverted
                postBanner("Restored original GPS trace.")
            }
            return
        }

        val cachedRes = snapCache[snapKey(cur.id, rawPoly)]
        if (cachedRes != null) {
            viewModelScope.launch { applySnapMergeAndSave(cur, rawPoly, cachedRes, showBanner = true) }
            return
        }

        val rawPts = EncodedPolylineCodec.synthesizeReplayTrack(
            rawPoly, cur.startTimestampMillis, cur.durationMillis, cur.splits
        )
        if (rawPts.size < 3) {
            postBanner("Not enough GPS points to snap this route.")
            return
        }
        viewModelScope.launch {
            snappingFlow.value = true
            runCatching { OsmFootRouter.snapRunParkSafe(rawPts) }
                .onSuccess { res ->
                    snapCache[snapKey(cur.id, rawPoly)] = res
                    snapFlow.value = res
                    applySnapMergeAndSave(cur, rawPoly, res, showBanner = true)
                }
                .onFailure { postBanner("Street snapping failed: check connection.") }
            snappingFlow.value = false
        }
    }

    fun toggleSnapUnsnappedSection(sectionId: Int) {
        val cur = selFlow.value ?: return
        val curSnap = snapFlow.value ?: return
        val rawPoly = cur.rawEncodedPolyline.ifBlank { cur.encodedPolyline }
        val nextForced = if (sectionId in curSnap.forcedSnapSectionIds) {
            curSnap.forcedSnapSectionIds - sectionId
        } else {
            curSnap.forcedSnapSectionIds + sectionId
        }
        applySectionOverrides(cur, rawPoly, curSnap, nextForced)
    }

    fun setSnapAllGreenery(snapAll: Boolean) {
        val cur = selFlow.value ?: return
        val curSnap = snapFlow.value ?: return
        val rawPoly = cur.rawEncodedPolyline.ifBlank { cur.encodedPolyline }
        val nextForced = if (snapAll) curSnap.unsnappedSections.map { it.id }.toSet() else emptySet()
        applySectionOverrides(cur, rawPoly, curSnap, nextForced)
    }

    private fun applySectionOverrides(
        cur: RunRecord,
        rawPoly: String,
        curSnap: SnapMergeResult,
        nextForced: Set<Int>
    ) {
        viewModelScope.launch {
            snappingFlow.value = true
            val enriched = ensureStreetSamplesForForced(curSnap, nextForced)
            val recomputed = ParkSafeSnapMerger.recomputeWithOverrides(enriched, nextForced)
            snapCache[snapKey(cur.id, rawPoly)] = recomputed
            snapFlow.value = recomputed
            applySnapMergeAndSave(cur, rawPoly, recomputed, showBanner = false)
            snappingFlow.value = false
        }
    }

    private suspend fun ensureStreetSamplesForForced(
        base: SnapMergeResult,
        forcedIds: Set<Int>
    ): SnapMergeResult {
        val missingSections = base.unsnappedSections.filter { it.id in forcedIds && !it.canSnapToStreet }
        if (missingSections.isEmpty() || base.rawPoints.isEmpty()) return base
        val mutableSamples = base.samples.toMutableList()
        for (sec in missingSections) {
            fillSectionSamplesFromFootRouter(base.rawPoints, mutableSamples, sec)
        }
        return base.copy(samples = mutableSamples)
    }

    private suspend fun fillSectionSamplesFromFootRouter(
        raw: List<TrackPoint>,
        samples: MutableList<MatchedPointSample>,
        sec: UnsnappedSection
    ) {
        val sPt = raw[sec.startIdx.coerceIn(0, raw.lastIndex)]
        val ePt = raw[sec.endIdx.coerceIn(0, raw.lastIndex)]
        val routed = runCatching {
            OsmFootRouter.routeFootLeg(LatLon(sPt.latitude, sPt.longitude), LatLon(ePt.latitude, ePt.longitude))
        }.getOrNull() ?: return
        if (routed.size < 2) return
        val count = (sec.endIdx - sec.startIdx).coerceAtLeast(1)
        for (k in sec.startIdx..sec.endIdx) {
            val frac = (k - sec.startIdx).toDouble() / count
            val rIdx = (frac * routed.lastIndex).toInt().coerceIn(0, routed.lastIndex)
            val target = routed[rIdx]
            val drift = GeoMath.distanceMeters(raw[k].latitude, raw[k].longitude, target.latitude, target.longitude)
            samples[k] = MatchedPointSample(true, target.latitude, target.longitude, drift)
        }
    }

    private suspend fun applySnapMergeAndSave(
        cur: RunRecord,
        rawPoly: String,
        res: SnapMergeResult,
        showBanner: Boolean
    ) {
        val snappedPoly = EncodedPolylineCodec.encode(res.points.map { LatLon(it.latitude, it.longitude) })
        val updated = cur.copy(
            encodedPolyline = snappedPoly,
            rawEncodedPolyline = rawPoly,
            snappedEncodedPolyline = snappedPoly,
            isSnapped = true,
            snapStreetPct = res.snappedPct,
            snapGreeneryPct = res.greeneryPct,
            snapLoopsKept = res.circlesKept,
            trackPoints = res.points
        )
        repo.saveRun(updated)
        selFlow.value = updated
        if (showBanner) {
            val loopTxt = if (res.circlesKept > 0) " • ${res.circlesKept} stop loops kept" else ""
            postBanner("Snapped ${res.snappedPct}% streets • ${res.greeneryPct}% greenery kept$loopTxt.")
        }
    }

    fun trimSelectedRunRange(startMs: Long, endMs: Long) {
        val cur = selFlow.value ?: return
        viewModelScope.launch {
            val out = trimmer.trimRange(cur, startMs, endMs)
            snapCache.keys.removeAll { it.startsWith("${cur.id}_") }
            snapFlow.value = null
            repo.saveRun(out)
            selFlow.value = out
            postBanner("Trimmed route. Kept ${"%.2f".format(out.km())} km.")
        }
    }

    fun syncHealthConnect(ctx: Context) {
        runImportTask("Health Connect sync") {
            val res = withContext(Dispatchers.IO) {
                HealthConnectSyncManager.syncRecentDays(ctx, repo, days = 30L)
            }
            prefs.update { it.copy(lastHealthSyncMillis = System.currentTimeMillis()) }
            maybeWriteVault(ctx)
            "Synced ${res.syncedWalkDays} walk days • ${res.addedRuns} new activities from Health Connect."
        }
    }

    fun setHealthAutoSync(ctx: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefs.update { it.copy(healthAutoSyncEnabled = enabled) }
            val p = prefs.preferences.first()
            HealthConnectSyncWorker.setScheduled(ctx, enabled || p.folderAutoSyncEnabled)
            if (enabled && HealthConnectSyncManager.hasPermissions(ctx)) {
                syncHealthConnect(ctx)
            } else {
                postBanner(if (enabled) "Health Connect 6h auto-sync enabled." else "Health Connect auto-sync paused.")
            }
        }
    }

    fun setSyncFolderUri(ctx: Context, uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        }
        viewModelScope.launch {
            prefs.update { it.copy(syncFolderUri = uri.toString()) }
            syncFolderNow(ctx, forceAll = true)
        }
    }

    fun syncFolderNow(ctx: Context, forceAll: Boolean = false) {
        runImportTask("Folder & Vault sync") {
            val res = withContext(Dispatchers.IO) {
                VaultAndTimelineSyncManager.syncFolderNow(ctx, repo, prefs, forceAll = forceAll)
            }
            buildString {
                if (res.vaultRestored) append("Restored vault backup! ")
                if (res.importedTimelineFiles > 0) append("${res.importedTimelineFiles} Timeline files, ")
                if (res.importedBackupFiles > 0) append("${res.importedBackupFiles} backup files, ")
                append("${res.addedActivities} new activities, ${res.addedWalkDays} walk days")
                if (res.vaultSaved) append(" • Vault updated")
            }
        }
    }

    fun restoreVaultFromUri(cr: ContentResolver, uri: Uri) {
        runImportTask("Vault restore") {
            val count = withContext(Dispatchers.IO) {
                val s = cr.openInputStream(uri) ?: error("Cannot open vault file")
                s.use { VaultAndTimelineSyncManager.restoreFromVaultStream(it, repo) }
            }
            "Restored $count sessions & app state from StrideCoach vault."
        }
    }

    fun setAutoDetectRuns(ctx: Context, enabled: Boolean) {
        updatePreferences { it.copy(autoDetectRunsEnabled = enabled) }
        AutoActivityDetector.setEnabled(ctx, enabled)
    }

    fun setAutoStartOnRunDetected(ctx: Context, enabled: Boolean) {
        viewModelScope.launch {
            prefs.update { p ->
                p.copy(
                    autoStartOnRunDetected = enabled,
                    autoDetectRunsEnabled = if (enabled) true else p.autoDetectRunsEnabled
                )
            }
            AutoActivityDetector.setAutoStartEnabled(ctx, enabled)
            if (enabled) AutoActivityDetector.setEnabled(ctx, true)
            postBanner(
                if (enabled) "Auto-start enabled when running motion is detected."
                else "Auto-start disabled — notifications only."
            )
        }
    }

    fun importBackupFromUri(ctx: Context, cr: ContentResolver, uri: Uri) {
        runImportTask("Backup import") {
            val runs = withContext(Dispatchers.IO) { readBackup(cr, uri) }
            withContext(Dispatchers.IO) { repo.saveRuns(runs) }
            maybeWriteVault(ctx)
            val km = runs.sumOf { it.distanceMeters } / 1000.0
            "Imported ${runs.size} runs (${"%.1f".format(km)} km) from Running Tracker backup."
        }
    }

    fun importTimelineFromUri(ctx: Context, cr: ContentResolver, uri: Uri) {
        runImportTask("Timeline import") {
            val res = withContext(Dispatchers.IO) { readTimeline(cr, uri) }
            withContext(Dispatchers.IO) {
                repo.mergeWalkLogs(res.walkLogs)
                repo.saveRunsDeduplicated(res.activityRecords)
            }
            maybeWriteVault(ctx)
            val km = res.totalWalkMeters / 1000.0
            "Timeline: ${res.walkLogs.size} walk days (${"%.1f".format(km)} km), " +
                "${res.runCount} runs, ${res.cyclingCount} rides • filtered ${res.filteredGlitches} glitches."
        }
    }

    fun importAutoDetectedUri(cr: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val kind = withContext(Dispatchers.IO) { peekKind(cr, uri) }
            when (kind) {
                ImportFileKind.RUNNING_TRACKER_BACKUP -> runImportTask("Backup import") {
                    val runs = withContext(Dispatchers.IO) { readBackup(cr, uri) }
                    withContext(Dispatchers.IO) { repo.saveRuns(runs) }
                    "Imported ${runs.size} runs."
                }
                ImportFileKind.GOOGLE_TIMELINE -> runImportTask("Timeline import") {
                    val res = withContext(Dispatchers.IO) { readTimeline(cr, uri) }
                    withContext(Dispatchers.IO) {
                        repo.mergeWalkLogs(res.walkLogs)
                        repo.saveRunsDeduplicated(res.activityRecords)
                    }
                    "Timeline: ${res.walkLogs.size} walk days & ${res.activityRecords.size} activities."
                }
                ImportFileKind.UNKNOWN -> postBanner("Unrecognized file format. Choose a .backup or Timeline.json file.")
            }
        }
    }

    fun exportBackupToUri(cr: ContentResolver, uri: Uri) {
        runExportTask("Backup export") {
            val txt = backup.exportBackup(repo.getAllRunsWithSplits())
            writeUri(cr, uri, txt)
            "Exported Running Tracker .backup file."
        }
    }

    fun exportSelectedRunGpxToUri(cr: ContentResolver, uri: Uri) {
        val run = selFlow.value ?: return
        runExportTask("GPX export") {
            writeUri(cr, uri, gpx.exportRun(run))
            "Exported GPX track for ${run.name}."
        }
    }

    private suspend fun maybeWriteVault(ctx: Context) {
        val p = prefs.preferences.first()
        if (p.syncFolderUri.isNotBlank()) {
            withContext(Dispatchers.IO) {
                VaultAndTimelineSyncManager.writeVaultSnapshot(ctx, repo, p.syncFolderUri)
            }
        }
    }

    private fun readBackup(cr: ContentResolver, uri: Uri): List<RunRecord> {
        val s = cr.openInputStream(uri) ?: error("Cannot open selected file")
        return s.use { backup.importBackupStream(it) }
    }

    private suspend fun readTimeline(cr: ContentResolver, uri: Uri) = run {
        val existing = repo.getAllRunsWithSplits()
        val s = cr.openInputStream(uri) ?: error("Cannot open selected file")
        s.use { timeline.importTimelineStream(it, existing) }
    }

    private fun writeUri(cr: ContentResolver, uri: Uri, text: String) {
        val out = cr.openOutputStream(uri) ?: error("Cannot write destination file")
        out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun runExportTask(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess(::postBanner)
                .onFailure { err -> postBanner("$label failed: ${err.message}") }
        }
    }

    fun updatePreferences(fn: (CoachPreferences) -> CoachPreferences) {
        viewModelScope.launch { prefs.update(fn) }
    }

    fun clearStatusBanner() {
        ioFlow.value = ioFlow.value.copy(banner = null)
    }

    private fun runImportTask(label: String, block: suspend () -> String) {
        if (ioFlow.value.busy) return
        viewModelScope.launch {
            ioFlow.value = ioFlow.value.copy(busy = true)
            runCatching { block() }
                .onSuccess { msg -> ioFlow.value = IoStatus(banner = msg, busy = false, summary = msg) }
                .onFailure { err ->
                    val msg = "$label failed: ${err.message}"
                    ioFlow.value = ioFlow.value.copy(banner = msg, busy = false)
                }
        }
    }

    private fun peekKind(cr: ContentResolver, uri: Uri): ImportFileKind {
        val stream = cr.openInputStream(uri) ?: return ImportFileKind.UNKNOWN
        val buf = ByteArray(4096)
        val n = stream.use { it.read(buf) }
        if (n <= 0) return ImportFileKind.UNKNOWN
        return backup.detectImportKind(String(buf, 0, n, Charsets.UTF_8))
    }

    private fun postBanner(msg: String) {
        ioFlow.value = ioFlow.value.copy(banner = msg)
    }

    private fun snapKey(id: String, rawPoly: String): String = "${id}_${rawPoly.hashCode()}"

    private fun computeTelemetry(
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        planTargetPaceSec: Int,
        baselineModeStr: String
    ): TelemetrySummary {
        val runningSessions = runs.filter { it.activityKind == ActivityKind.RUNNING }
        val pool = if (runningSessions.isNotEmpty()) runningSessions else runs
        val runKm = pool.sumOf { it.distanceMeters } / 1000.0
        val totalMs = pool.sumOf { it.durationMillis }
        val paces = pool.map { it.paceSecondsPerKm }.filter { it > 0 }
        val avgPace = if (paces.isEmpty()) 0 else paces.average().toInt()
        val cads = pool.map { it.avgCadenceSpm }.filter { it > 0 }
        val avgCad = if (cads.isEmpty()) 0 else cads.average().toInt()

        val mode = runCatching { PaceBaselineMode.valueOf(baselineModeStr) }.getOrDefault(PaceBaselineMode.CURRENT_FITNESS)
        val basePace = when (mode) {
            PaceBaselineMode.CURRENT_FITNESS -> PaceZoneClassifier.computeCurrentBaselinePace(runs, ActivityKind.RUNNING, 330)
            PaceBaselineMode.PLAN_TARGET -> planTargetPaceSec.coerceIn(180, 600)
            PaceBaselineMode.RUN_AVERAGE -> avgPace.takeIf { it > 0 } ?: 330
        }
        val prHist = stats.computePersonalRecordHistories(pool)

        return TelemetrySummary(
            totalRunKm = runKm,
            totalDurationMillis = totalMs,
            avgPaceSecPerKm = avgPace,
            avgCadenceSpm = avgCad,
            currentBaselinePaceSec = basePace,
            runCount = runs.count { it.activityKind == ActivityKind.RUNNING },
            walkCount = runs.count { it.activityKind == ActivityKind.WALKING },
            cycleCount = runs.count { it.activityKind == ActivityKind.CYCLING },
            otherCount = runs.count { it.activityKind == ActivityKind.HIKING || it.activityKind == ActivityKind.OTHER },
            walkDaysCount = walks.size,
            weeklyLoad = stats.computeWeeklyCombinedLoad(pool, walks),
            last7DaysLoad = stats.computeLast7DaysLoad(pool, walks),
            paceZones = stats.computePaceZoneDistribution(pool, basePace),
            weekdayWalkHeatmap = stats.computeWeekdayWalkingHeatmap(walks),
            personalRecords = prHist.map { PersonalRecord(it.label, it.targetMeters, it.currentBest.durationMillis, it.currentBest.runId) },
            prHistories = prHist
        )
    }

    private fun buildState(
        plan: TrainingPlanConfig?,
        days: List<PlannedDay>,
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        tpls: List<StructuredWorkout>,
        trashedRuns: List<RunRecord>,
        trashedTpls: List<StructuredWorkout>,
        telemetry: TelemetrySummary,
        p: CoachPreferences,
        io: IoStatus
    ): CoachUiState {
        val now = LocalDate.now().toEpochDay()
        val d0 = days.firstOrNull { it.dateEpochDay == now } ?: days.firstOrNull()
        val d1 = d0?.let { t -> days.firstOrNull { it.dateEpochDay == t.dateEpochDay + 1 } }
        val w0 = walks.firstOrNull { it.dateEpochDay == now }
        val w1 = walks.firstOrNull { it.dateEpochDay == now - 1 }
        val alert = if (plan != null && d0 != null) reg.evaluate(d0, d1, w0, w1, plan.walkingLoadRule) else null
        return CoachUiState(
            plan, days, d0, d1, alert, runs, walks, tpls, trashedRuns, trashedTpls,
            telemetry, p, io.banner, io.busy, io.summary
        )
    }

    class Factory(
        private val repo: StrideCoachRepository,
        private val prefs: AppPreferencesStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(cls: Class<T>): T = StrideCoachViewModel(repo, prefs) as T
    }
}
