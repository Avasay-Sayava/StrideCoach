package com.stridecoach.running.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stridecoach.running.core.data.adapter.GpxExporter
import com.stridecoach.running.core.data.adapter.ImportFileKind
import com.stridecoach.running.core.data.adapter.RunningTrackerBackupAdapter
import com.stridecoach.running.core.data.adapter.TimelineJsonImporter
import com.stridecoach.running.core.data.repository.AppPreferencesStore
import com.stridecoach.running.core.data.repository.CoachPreferences
import com.stridecoach.running.core.data.repository.StrideCoachRepository
import com.stridecoach.running.core.domain.engine.PersonalRecord
import com.stridecoach.running.core.domain.engine.PlanPresets
import com.stridecoach.running.core.domain.engine.RunAnalyticsEngine
import com.stridecoach.running.core.domain.engine.RunTailTrimmer
import com.stridecoach.running.core.domain.engine.TrainingPlanGenerator
import com.stridecoach.running.core.domain.engine.WalkingLoadAlert
import com.stridecoach.running.core.domain.engine.WalkingLoadRegulator
import com.stridecoach.running.core.domain.engine.WeeklyLoadPoint
import com.stridecoach.running.core.domain.model.PlannedDay
import com.stridecoach.running.core.domain.model.RunRecord
import com.stridecoach.running.core.domain.model.StructuredWorkout
import com.stridecoach.running.core.domain.model.TrainingPlanConfig
import com.stridecoach.running.core.domain.model.WalkLog
import com.stridecoach.running.core.domain.model.WalkSource
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

data class CoachUiState(
    val activePlan: TrainingPlanConfig? = null,
    val plannedDays: List<PlannedDay> = emptyList(),
    val todayDay: PlannedDay? = null,
    val tomorrowDay: PlannedDay? = null,
    val walkingAlert: WalkingLoadAlert? = null,
    val runs: List<RunRecord> = emptyList(),
    val walkLogs: List<WalkLog> = emptyList(),
    val templates: List<StructuredWorkout> = emptyList(),
    val weeklyLoad: List<WeeklyLoadPoint> = emptyList(),
    val weekdayWalkHeatmap: Map<DayOfWeek, Int> = emptyMap(),
    val personalRecords: List<PersonalRecord> = emptyList(),
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

    private val ioFlow = MutableStateFlow(IoStatus())
    private val selFlow = MutableStateFlow<RunRecord?>(null)
    val selectedRunDetail: StateFlow<RunRecord?> = selFlow.asStateFlow()
    val liveTracking: StateFlow<LiveTrackingState> = TrackingSessionBus.state

    private val daysFlow = repo.observeActivePlan().flatMapLatest { plan ->
        if (plan == null) flowOf(emptyList()) else repo.observePlanDays(plan.id)
    }

    val uiState: StateFlow<CoachUiState> = combine(
        repo.observeActivePlan(),
        daysFlow,
        repo.observeRuns(),
        repo.observeWalkLogs(),
        repo.observeWorkoutTemplates(),
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
        val p = arr[5] as CoachPreferences
        val io = arr[6] as IoStatus
        buildState(plan, days, runs, walks, tpls, p, io)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachUiState())

    init {
        viewModelScope.launch {
            if (repo.observeActivePlan().first() == null) {
                activatePlanConfig(PlanPresets.telAviv10kPreset())
            }
        }
        viewModelScope.launch {
            TrackingSessionBus.completedRuns.collect { run ->
                repo.saveRun(run)
                postBanner("Saved run: ${run.name} (${"%.2f".format(run.km())} km)")
            }
        }
        viewModelScope.launch {
            prefs.preferences.collect { p ->
                TrackingSessionBus.voiceEnabled = p.voiceCoachEnabled
                TrackingSessionBus.hapticEnabled = p.hapticCuesEnabled
                TrackingSessionBus.autoPauseEnabled = p.autoPauseEnabled
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
            repo.saveWalkLogs(listOf(WalkLog(day, m, WalkSource.MANUAL, 0, System.currentTimeMillis())))
            postBanner("Logged ${"%.1f".format(m / 1000.0)} km walk for today.")
        }
    }

    fun saveWorkoutTemplate(w: StructuredWorkout) {
        viewModelScope.launch {
            repo.saveWorkoutTemplate(w)
            postBanner("Saved template: ${w.title}")
        }
    }

    fun startWorkout(ctx: Context, w: StructuredWorkout?, dayId: String? = null) {
        TrackingSessionBus.pendingWorkout = w
        TrackingSessionBus.pendingPlanDayId = dayId
        RunTrackingService.start(ctx)
    }

    fun loadRunDetail(id: String) {
        viewModelScope.launch { selFlow.value = repo.getRunDetail(id) }
    }

    fun trimSelectedRunTail(keepMs: Long) {
        val cur = selFlow.value ?: return
        viewModelScope.launch {
            val out = trimmer.trimToElapsedMillis(cur, keepMs)
            repo.saveRun(out)
            selFlow.value = out
            postBanner("Trimmed tail. New distance: ${"%.2f".format(out.km())} km")
        }
    }

    fun importBackupFromUri(cr: ContentResolver, uri: Uri) {
        runImportTask("Backup import") {
            val runs = withContext(Dispatchers.IO) { readBackup(cr, uri) }
            withContext(Dispatchers.IO) { repo.saveRuns(runs) }
            val km = runs.sumOf { it.distanceMeters } / 1000.0
            "Imported ${runs.size} runs (${"%.1f".format(km)} km) from Running Tracker backup."
        }
    }

    fun importTimelineFromUri(cr: ContentResolver, uri: Uri) {
        runImportTask("Timeline import") {
            val res = withContext(Dispatchers.IO) { readTimeline(cr, uri) }
            withContext(Dispatchers.IO) { repo.saveWalkLogs(res.walkLogs) }
            val km = res.totalWalkMeters / 1000.0
            "Imported ${res.walkLogs.size} walk days (${"%.1f".format(km)} km) • " +
                "filtered ${res.filteredGlitches} glitches, ${res.deduplicatedOverlaps} overlaps."
        }
    }

    fun importAutoDetectedUri(cr: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val kind = withContext(Dispatchers.IO) { peekKind(cr, uri) }
            when (kind) {
                ImportFileKind.RUNNING_TRACKER_BACKUP -> importBackupFromUri(cr, uri)
                ImportFileKind.GOOGLE_TIMELINE -> importTimelineFromUri(cr, uri)
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

    private fun buildState(
        plan: TrainingPlanConfig?,
        days: List<PlannedDay>,
        runs: List<RunRecord>,
        walks: List<WalkLog>,
        tpls: List<StructuredWorkout>,
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
            plan, days, d0, d1, alert, runs, walks, tpls,
            stats.computeWeeklyCombinedLoad(runs, walks),
            stats.computeWeekdayWalkingHeatmap(walks),
            stats.computePersonalRecords(runs),
            p, io.banner, io.busy, io.summary
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
